"""Training (SPEC 6.2) and nutrition (SPEC 6.3) tables and routes.

`init_schema(db)` is called from `main.init_db`; `install(app, h)` from `main.create_app`, where `h`
is the main module (its auth, scope, audit and event helpers are reused, never copied).
"""

import csv
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import sqlite3
from typing import Annotated, Literal

from fastapi import Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import coaching as co
from . import training_nutrition as tn
from .weight_trend import daily_trend
from .input_bounds import Offset, SQLITE_MAX, SqlitePath

FOODS_CSV = Path(__file__).with_name("data") / "foods.csv"
MEALS = ("breakfast", "lunch", "dinner", "snack")
KINDS = ("strength", "plyometric", "mobility", "conditioning")
LOG_DAYS_BACK = 30

SCHEMA = """
    CREATE TABLE IF NOT EXISTS exercises (
        id INTEGER PRIMARY KEY,
        club_id INTEGER NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN ('strength', 'plyometric', 'mobility', 'conditioning')),
        lower_body INTEGER NOT NULL DEFAULT 0 CHECK(lower_body IN (0, 1)),
        UNIQUE(club_id, name)
    );
    CREATE TABLE IF NOT EXISTS plans (
        id INTEGER PRIMARY KEY,
        club_id INTEGER NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
        title TEXT NOT NULL,
        group_id INTEGER REFERENCES groups(id) ON DELETE CASCADE,
        athlete_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
        starts_on TEXT NOT NULL,
        weeks INTEGER NOT NULL CHECK(weeks BETWEEN 1 AND 52),
        notes TEXT NOT NULL DEFAULT '',
        created_by INTEGER REFERENCES users(id)
    );
    CREATE TABLE IF NOT EXISTS plan_items (
        id INTEGER PRIMARY KEY,
        plan_id INTEGER NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
        weekday INTEGER NOT NULL CHECK(weekday BETWEEN 0 AND 6),
        exercise_id INTEGER NOT NULL REFERENCES exercises(id),
        sets INTEGER NOT NULL CHECK(sets BETWEEN 1 AND 20),
        reps INTEGER NOT NULL CHECK(reps BETWEEN 1 AND 100),
        reps_max INTEGER NOT NULL CHECK(reps_max BETWEEN 1 AND 100),
        load_kg REAL CHECK(load_kg BETWEEN 0 AND 1000),
        rpe_target REAL CHECK(rpe_target BETWEEN 0 AND 10),
        position INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS plan_items_plan ON plan_items(plan_id, position);
    CREATE TABLE IF NOT EXISTS set_logs (
        id INTEGER PRIMARY KEY,
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day TEXT NOT NULL,
        exercise_id INTEGER NOT NULL REFERENCES exercises(id),
        set_no INTEGER NOT NULL CHECK(set_no BETWEEN 1 AND 50),
        reps INTEGER NOT NULL CHECK(reps BETWEEN 1 AND 200),
        load_kg REAL CHECK(load_kg BETWEEN 0 AND 1000),
        rpe REAL CHECK(rpe BETWEEN 0 AND 10),
        plan_id INTEGER REFERENCES plans(id) ON DELETE SET NULL,
        client_id TEXT,
        created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS set_logs_athlete ON set_logs(athlete_id, day);
    CREATE UNIQUE INDEX IF NOT EXISTS set_logs_client ON set_logs(athlete_id, client_id) WHERE client_id IS NOT NULL;
    CREATE TABLE IF NOT EXISTS foods (
        id INTEGER PRIMARY KEY,
        fdc_id INTEGER,
        club_id INTEGER REFERENCES clubs(id) ON DELETE CASCADE,
        owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
        name_bg TEXT NOT NULL,
        name_en TEXT NOT NULL DEFAULT '',
        kcal REAL NOT NULL, protein_g REAL NOT NULL, carbs_g REAL NOT NULL,
        fat_g REAL NOT NULL, fibre_g REAL NOT NULL DEFAULT 0,
        source TEXT NOT NULL DEFAULT 'custom'
    );
    CREATE UNIQUE INDEX IF NOT EXISTS foods_fdc ON foods(fdc_id) WHERE fdc_id IS NOT NULL;
    CREATE TABLE IF NOT EXISTS food_logs (
        id INTEGER PRIMARY KEY,
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day TEXT NOT NULL,
        meal TEXT NOT NULL CHECK(meal IN ('breakfast', 'lunch', 'dinner', 'snack')),
        food_id INTEGER NOT NULL REFERENCES foods(id),
        grams REAL NOT NULL CHECK(grams > 0 AND grams <= 5000),
        client_id TEXT,
        created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS food_logs_athlete ON food_logs(athlete_id, day);
    CREATE UNIQUE INDEX IF NOT EXISTS food_logs_client ON food_logs(athlete_id, client_id) WHERE client_id IS NOT NULL;
    CREATE TABLE IF NOT EXISTS weights (
        id INTEGER PRIMARY KEY,
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day TEXT NOT NULL,
        kg REAL NOT NULL CHECK(kg BETWEEN 20 AND 400),
        UNIQUE(athlete_id, day)
    );
    CREATE TABLE IF NOT EXISTS nutrition_targets (
        athlete_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        kcal INTEGER NOT NULL, protein_g INTEGER, carbs_g INTEGER, fat_g INTEGER,
        set_by INTEGER REFERENCES users(id),
        updated_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS nutrition_day_complete (
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day TEXT NOT NULL,
        diary_fingerprint TEXT NOT NULL,
        confirmed_at TEXT NOT NULL,
        PRIMARY KEY (athlete_id, day)
    );
    CREATE TABLE IF NOT EXISTS coaching_programs (
        athlete_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        effective_date TEXT NOT NULL,
        seed_kcal REAL NOT NULL,
        mode TEXT NOT NULL CHECK(mode IN ('updating', 'holding')),
        goal TEXT NOT NULL CHECK(goal IN ('maintain', 'lose', 'gain')),
        rate_kg_week REAL NOT NULL,
        protein_g REAL NOT NULL,
        fat_g REAL NOT NULL,
        applied INTEGER NOT NULL DEFAULT 0 CHECK(applied IN (0, 1)),
        set_by INTEGER REFERENCES users(id),
        updated_at TEXT NOT NULL
    );
"""


def init_schema(db):
    """Idempotent: tables, the under_18 column on older databases and the bundled food list."""
    db.executescript(SCHEMA)
    columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
    if "under_18" not in columns:
        db.execute("ALTER TABLE users ADD COLUMN under_18 INTEGER NOT NULL DEFAULT 0 CHECK(under_18 IN (0, 1))")
    load_foods(db)


def read_foods_csv(path=FOODS_CSV):
    with open(path, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(line for line in handle if not line.startswith("#")))


def load_foods(db):
    db.executemany(
        "INSERT OR IGNORE INTO foods(fdc_id, name_bg, name_en, kcal, protein_g, carbs_g, fat_g, fibre_g, source) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [(int(r["fdc_id"]), r["name_bg"], r["name_en"], float(r["kcal"]), float(r["protein_g"]),
          float(r["carbs_g"]), float(r["fat_g"]), float(r["fibre_g"]), r["source"]) for r in read_foods_csv()])


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


def iso_day(value):
    try:
        return datetime.strptime(value, "%Y-%m-%d").date().isoformat()
    except ValueError:
        raise ValueError("day must be YYYY-MM-DD") from None


def stripped(value):
    if not value.strip():
        raise ValueError("Must not be blank")
    return value.strip()


class ExerciseBody(StrictModel):
    name: str = Field(min_length=1, max_length=100)
    kind: Literal["strength", "plyometric", "mobility", "conditioning"]
    lower_body: bool = False
    club_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    _name = field_validator("name")(stripped)


class PlanItem(StrictModel):
    weekday: int = Field(ge=0, le=6)
    exercise_id: int = Field(gt=0, le=SQLITE_MAX)
    sets: int = Field(ge=1, le=20)
    reps: int = Field(ge=1, le=100)
    reps_max: int | None = Field(default=None, ge=1, le=100)
    load_kg: float | None = Field(default=None, ge=0, le=1000)
    rpe_target: float | None = Field(default=None, ge=0, le=10)

    @model_validator(mode="after")
    def range_order(self):
        if self.reps_max is not None and self.reps_max < self.reps:
            raise ValueError("reps_max must not be below reps")
        return self


class PlanBody(StrictModel):
    title: str = Field(min_length=1, max_length=100)
    group_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    athlete_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    starts_on: str = Field(min_length=10, max_length=10)
    weeks: int = Field(default=4, ge=1, le=52)
    notes: str = Field(default="", max_length=1000)
    items: list[PlanItem] = Field(max_length=60)
    _title = field_validator("title")(stripped)
    _starts_on = field_validator("starts_on")(iso_day)

    @model_validator(mode="after")
    def one_target(self):
        if self.group_id is not None and self.athlete_id is not None:
            raise ValueError("Assign a plan to a group or to one athlete, not both")
        return self


class SetLog(StrictModel):
    day: str = Field(min_length=10, max_length=10)
    exercise_id: int = Field(gt=0, le=SQLITE_MAX)
    set_no: int = Field(ge=1, le=50)
    reps: int = Field(ge=1, le=200)
    load_kg: float | None = Field(default=None, ge=0, le=1000)
    rpe: float | None = Field(default=None, ge=0, le=10)
    plan_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    client_id: str | None = Field(default=None, min_length=1, max_length=128)
    _day = field_validator("day")(iso_day)


class CustomFood(StrictModel):
    name_bg: str = Field(min_length=1, max_length=100)
    name_en: str = Field(default="", max_length=200)
    kcal: float = Field(ge=0, le=1000)
    protein_g: float = Field(ge=0, le=100)
    carbs_g: float = Field(ge=0, le=100)
    fat_g: float = Field(ge=0, le=100)
    fibre_g: float = Field(default=0, ge=0, le=100)
    club_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    _name = field_validator("name_bg")(stripped)


class FoodLog(StrictModel):
    day: str = Field(min_length=10, max_length=10)
    meal: Literal["breakfast", "lunch", "dinner", "snack"]
    food_id: int = Field(gt=0, le=SQLITE_MAX)
    grams: float = Field(gt=0, le=5000)
    client_id: str | None = Field(default=None, min_length=1, max_length=128)
    _day = field_validator("day")(iso_day)


class Weight(StrictModel):
    kg: float = Field(ge=20, le=400)


class Target(StrictModel):
    kcal: int = Field(ge=1, le=10000)
    protein_g: int | None = Field(default=None, ge=0, le=1000)
    carbs_g: int | None = Field(default=None, ge=0, le=2000)
    fat_g: int | None = Field(default=None, ge=0, le=1000)


class Program(StrictModel):
    goal: Literal["maintain", "lose", "gain"]
    rate_kg_week: float = Field(ge=0, le=2)
    protein_g: float = Field(ge=0, le=500)
    fat_g: float = Field(ge=0, le=500)

    @model_validator(mode="after")
    def rate_matches_goal(self):
        if (self.goal == "maintain") != (self.rate_kg_week == 0):
            raise ValueError("Maintain uses rate 0; lose and gain need a positive rate up to 2 kg/week")
        return self


class Profile(StrictModel):
    under_18: bool


def utc_today():
    return datetime.now(timezone.utc).date()


def check_log_day(day):
    """Logs may be sent late from the offline queue: 30 days back, one day ahead (UTC)."""
    parsed = datetime.strptime(day, "%Y-%m-%d").date()
    today = utc_today()
    if not today - timedelta(days=LOG_DAYS_BACK) <= parsed <= today + timedelta(days=1):
        raise HTTPException(422, f"day must be from {LOG_DAYS_BACK} days before today through tomorrow (UTC)")


def food_macros(food, grams):
    factor = grams / 100
    return {key: round(food[column] * factor, 1)
            for key, column in (("kcal", "kcal"), ("protein_g", "protein_g"), ("carbs_g", "carbs_g"),
                                ("fat_g", "fat_g"), ("fibre_g", "fibre_g"))}


def install(app, h):
    current_user, get_db, require_role, audit = h.current_user, h.get_db, h.require_role, h.audit

    def need_consent(user, what):
        if not user["consent_recorded"]:
            raise HTTPException(403, f"Consent must be recorded by a coach or admin before recording {what}")

    def publish(db, user, kind, row_id, at, emit=None):
        (emit or app.state.events.publish)(h.athlete_viewers(db, user), {
            "type": kind, "id": row_id, "athlete_id": user["id"], "club_id": user["club_id"], "at": at})

    def staff_club(db, user, club_id):
        """Club a staff member writes into: coaches only their own, admins any existing club."""
        club_id = club_id if club_id is not None else user["club_id"]
        if user["role"] == "coach" and club_id != user["club_id"]:
            raise HTTPException(403, "Coaches can write in their own club only")
        if db.execute("SELECT 1 FROM clubs WHERE id = ?", (club_id,)).fetchone() is None:
            raise HTTPException(422, "Unknown club")
        return club_id

    def target_athlete(db, user, athlete_id):
        """Athlete whose data is read: athletes may omit the id, staff must name an athlete in scope."""
        if athlete_id is None:
            if user["role"] != "athlete":
                raise HTTPException(422, "athlete_id is required")
            athlete_id = user["id"]
        return h.athlete_access(db, user, athlete_id)

    def exercise_in_club(db, club_id, exercise_id):
        row = db.execute("SELECT * FROM exercises WHERE id = ?", (exercise_id,)).fetchone()
        if row is None or row["club_id"] != club_id:
            raise HTTPException(422, "Unknown exercise")
        return row

    def plan_visible(db, user, plan):
        if user["role"] == "admin":
            return True
        if plan["club_id"] != user["club_id"]:
            return False
        if user["role"] == "coach":
            return True
        if plan["athlete_id"] is not None:
            return plan["athlete_id"] == user["id"]
        if plan["group_id"] is not None:
            return db.execute("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
                              (plan["group_id"], user["id"])).fetchone() is not None
        return True

    def scoped_plan(db, user, plan_id):
        row = db.execute("SELECT * FROM plans WHERE id = ?", (plan_id,)).fetchone()
        if row is None or not plan_visible(db, user, row):
            raise HTTPException(404, "Plan not found")
        return row

    def plan_json(db, plan):
        items = [dict(r) for r in db.execute(
            "SELECT plan_items.id, weekday, exercise_id, exercises.name AS exercise, sets, reps, reps_max, "
            "load_kg, rpe_target FROM plan_items JOIN exercises ON exercises.id = plan_items.exercise_id "
            "WHERE plan_id = ? ORDER BY position", (plan["id"],))]
        return {**dict(plan), "items": items}

    def write_items(db, plan_id, items):
        for position, item in enumerate(items):
            db.execute(
                "INSERT INTO plan_items(plan_id, weekday, exercise_id, sets, reps, reps_max, load_kg, "
                "rpe_target, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (plan_id, item.weekday, item.exercise_id, item.sets, item.reps,
                 item.reps_max if item.reps_max is not None else item.reps, item.load_kg, item.rpe_target, position))

    def plan_athlete(db, athlete_id, club_id):
        athlete = db.execute("SELECT role, club_id, consent_recorded FROM users WHERE id = ?", (athlete_id,)).fetchone()
        if athlete is None or athlete["role"] != "athlete" or athlete["club_id"] != club_id:
            raise HTTPException(422, "Athlete must belong to the plan's club")
        need_consent(athlete, "an individual training plan")

    def validate_plan(db, body, club_id):
        if body.group_id is not None:
            group = db.execute("SELECT club_id FROM groups WHERE id = ?", (body.group_id,)).fetchone()
            if group is None or group["club_id"] != club_id:
                raise HTTPException(422, "Group must belong to the plan's club")
        if body.athlete_id is not None:
            plan_athlete(db, body.athlete_id, club_id)
        for item in body.items:
            exercise_in_club(db, club_id, item.exercise_id)

    # --- profile ---

    @app.patch("/api/users/{target_id}/profile")
    def set_profile(target_id: SqlitePath, body: Profile, user=Depends(current_user), db=Depends(get_db)):
        target = h.managed_user(db, user, target_id)
        if target["role"] != "athlete":
            raise HTTPException(422, "The profile applies to athletes only")
        db.execute("UPDATE users SET under_18 = ? WHERE id = ?", (body.under_18, target_id))
        audit(db, user["id"], "profile_changed", target_id, {"under_18": body.under_18})
        db.commit()
        return {"id": target_id, "under_18": body.under_18}

    # --- exercises ---

    @app.get("/api/exercises")
    def list_exercises(club_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                       user=Depends(current_user), db=Depends(get_db)):
        if user["role"] != "admin":
            club_id = user["club_id"]
        return [{**dict(r), "lower_body": bool(r["lower_body"])} for r in db.execute(
            "SELECT * FROM exercises WHERE (? IS NULL OR club_id = ?) ORDER BY name, id", (club_id, club_id))]

    @app.post("/api/exercises", status_code=201)
    def add_exercise(body: ExerciseBody, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        club_id = staff_club(db, user, body.club_id)
        try:
            cursor = db.execute("INSERT INTO exercises(club_id, name, kind, lower_body) VALUES (?, ?, ?, ?)",
                                (club_id, body.name, body.kind, body.lower_body))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "An exercise with this name already exists in the club") from None
        audit(db, user["id"], "exercise_created", cursor.lastrowid)
        db.commit()
        return {"id": cursor.lastrowid, "club_id": club_id, "name": body.name, "kind": body.kind,
                "lower_body": body.lower_body}

    @app.delete("/api/exercises/{exercise_id}", status_code=204)
    def delete_exercise(exercise_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        row = db.execute("SELECT club_id FROM exercises WHERE id = ?", (exercise_id,)).fetchone()
        if row is None or (user["role"] == "coach" and row["club_id"] != user["club_id"]):
            raise HTTPException(404, "Exercise not found")
        try:
            db.execute("DELETE FROM exercises WHERE id = ?", (exercise_id,))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "The exercise is used by a plan or by logged sets") from None
        audit(db, user["id"], "exercise_deleted", exercise_id)
        db.commit()

    # --- plans ---

    @app.get("/api/plans")
    def list_plans(club_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                   user=Depends(current_user), db=Depends(get_db)):
        if user["role"] != "admin":
            club_id = user["club_id"]
        rows = db.execute("SELECT * FROM plans WHERE (? IS NULL OR club_id = ?) ORDER BY starts_on DESC, id DESC",
                          (club_id, club_id)).fetchall()
        return [plan_json(db, row) for row in rows if plan_visible(db, user, row)]

    @app.post("/api/plans", status_code=201, dependencies=[Depends(current_user)])
    def add_plan(body: PlanBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return create_plan(body, user, db, emit)

    def create_plan(body: PlanBody, user, db, emit):
        require_role(user, "admin", "coach")
        club_id = user["club_id"]
        validate_plan(db, body, club_id)
        if emit is None:
            return
        cursor = db.execute(
            "INSERT INTO plans(club_id, title, group_id, athlete_id, starts_on, weeks, notes, created_by) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (club_id, body.title, body.group_id, body.athlete_id, body.starts_on, body.weeks, body.notes, user["id"]))
        write_items(db, cursor.lastrowid, body.items)
        audit(db, user["id"], "plan_created", cursor.lastrowid)
        return plan_json(db, db.execute("SELECT * FROM plans WHERE id = ?", (cursor.lastrowid,)).fetchone())

    @app.get("/api/plans/{plan_id}")
    def get_plan(plan_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        return plan_json(db, scoped_plan(db, user, plan_id))

    @app.put("/api/plans/{plan_id}", dependencies=[Depends(current_user)])
    def replace_plan(plan_id: SqlitePath, body: PlanBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return edit_plan(plan_id, body, user, db, emit)

    def edit_plan(plan_id: int, body: PlanBody, user, db, emit):
        require_role(user, "admin", "coach")
        plan = scoped_plan(db, user, plan_id)
        if plan["athlete_id"] is not None and plan["athlete_id"] != body.athlete_id:
            plan_athlete(db, plan["athlete_id"], plan["club_id"])
        validate_plan(db, body, plan["club_id"])
        if emit is None:
            return
        db.execute("UPDATE plans SET title = ?, group_id = ?, athlete_id = ?, starts_on = ?, weeks = ?, notes = ? "
                   "WHERE id = ?", (body.title, body.group_id, body.athlete_id, body.starts_on, body.weeks,
                                    body.notes, plan_id))
        db.execute("DELETE FROM plan_items WHERE plan_id = ?", (plan_id,))
        write_items(db, plan_id, body.items)
        audit(db, user["id"], "plan_changed", plan_id)
        return plan_json(db, db.execute("SELECT * FROM plans WHERE id = ?", (plan_id,)).fetchone())

    @app.delete("/api/plans/{plan_id}", status_code=204)
    def delete_plan(plan_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        scoped_plan(db, user, plan_id)
        db.execute("DELETE FROM plans WHERE id = ?", (plan_id,))
        audit(db, user["id"], "plan_deleted", plan_id)
        db.commit()

    # --- set logs ---

    def set_row(row):
        return {key: row[key] for key in ("id", "athlete_id", "day", "exercise_id", "set_no", "reps", "load_kg",
                                          "rpe", "plan_id", "client_id", "created_at")}

    @app.post("/api/set-logs", status_code=201, dependencies=[Depends(current_user)])
    def add_set_log(body: SetLog, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return log_set(body, user, db, emit)

    def log_set(body: SetLog, user, db, emit):
        require_role(user, "athlete")
        need_consent(user, "training")
        if emit is not None and body.client_id is not None:
            previous = db.execute("SELECT * FROM set_logs WHERE athlete_id = ? AND client_id = ?",
                                  (user["id"], body.client_id)).fetchone()
            if previous is not None:
                return JSONResponse(status_code=200, content=set_row(previous))
        check_log_day(body.day)
        exercise_in_club(db, user["club_id"], body.exercise_id)
        if body.plan_id is not None:
            plan = db.execute("SELECT * FROM plans WHERE id = ?", (body.plan_id,)).fetchone()
            if plan is None or not plan_visible(db, user, plan):
                raise HTTPException(422, "Plan not found")
        if emit is None:
            return
        created_at = h.now_iso()
        cursor = db.execute(
            "INSERT INTO set_logs(athlete_id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, "
            "client_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user["id"], body.day, body.exercise_id, body.set_no, body.reps, body.load_kg, body.rpe,
             body.plan_id, body.client_id, created_at))
        audit(db, user["id"], "set_logged", cursor.lastrowid)
        publish(db, user, "set", cursor.lastrowid, created_at, emit)
        return JSONResponse(status_code=201, content=set_row(
            db.execute("SELECT * FROM set_logs WHERE id = ?", (cursor.lastrowid,)).fetchone()))

    @app.get("/api/set-logs")
    def list_set_logs(athlete_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                      from_: Annotated[str | None, Query(alias="from")] = None,
                      to: Annotated[str | None, Query()] = None,
                      limit: Annotated[int, Query(ge=1, le=500)] = 100,
                      offset: Offset = 0,
                      user=Depends(current_user), db=Depends(get_db)):
        athlete = target_athlete(db, user, athlete_id)
        return [set_row(r) for r in db.execute(
            "SELECT * FROM set_logs WHERE athlete_id = ? AND (? IS NULL OR day >= ?) AND (? IS NULL OR day <= ?) "
            "ORDER BY day DESC, id DESC LIMIT ? OFFSET ?",
            (athlete["id"], from_, from_, to, to, limit, offset))]

    @app.delete("/api/set-logs/{log_id}", status_code=204)
    def delete_set_log(log_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if db.execute("DELETE FROM set_logs WHERE id = ? AND athlete_id = ?", (log_id, user["id"])).rowcount == 0:
            raise HTTPException(404, "Set not found")
        audit(db, user["id"], "set_deleted", log_id)
        db.commit()

    # --- training summary: weekly volume and progression suggestions ---

    @app.get("/api/athletes/{athlete_id}/training")
    def training_summary(athlete_id: SqlitePath, weeks: Annotated[int, Query(ge=1, le=26)] = 4,
                         user=Depends(current_user), db=Depends(get_db)):
        athlete = h.athlete_access(db, user, athlete_id)
        today = utc_today()
        first = (today - timedelta(weeks=weeks, days=7)).isoformat()
        sets = [(r["day"], r["reps"], r["load_kg"]) for r in db.execute(
            "SELECT day, reps, load_kg FROM set_logs WHERE athlete_id = ? AND day >= ?", (athlete_id, first))]
        loads = [(r["day"], r["rpe"], r["training_minutes"]) for r in db.execute(
            "SELECT day, rpe, training_minutes FROM checkins WHERE athlete_id = ? AND day >= ?",
            (athlete_id, first))]
        suggestions = []
        seen = set()
        plans = [p for p in db.execute("SELECT * FROM plans WHERE club_id = ? ORDER BY starts_on DESC, id DESC",
                                       (athlete["club_id"],)) if plan_visible(db, athlete, p)]
        for plan in plans:
            for item in db.execute(
                    "SELECT plan_items.*, exercises.name, exercises.lower_body FROM plan_items "
                    "JOIN exercises ON exercises.id = plan_items.exercise_id WHERE plan_id = ? "
                    "ORDER BY weekday, position", (plan["id"],)):
                if item["exercise_id"] in seen:
                    continue
                seen.add(item["exercise_id"])
                days = {}
                for log in db.execute(
                        "SELECT day, reps, load_kg, rpe FROM set_logs WHERE athlete_id = ? AND exercise_id = ? "
                        "ORDER BY day, set_no, id", (athlete_id, item["exercise_id"])):
                    days.setdefault(log["day"], []).append(
                        {"reps": log["reps"], "load_kg": log["load_kg"], "rpe": log["rpe"]})
                sessions = [{"day": day, "sets": rows} for day, rows in days.items()]
                planned = {"sets": item["sets"], "reps": item["reps"], "reps_max": item["reps_max"],
                           "rpe_target": item["rpe_target"]}
                suggestions.append({
                    "exercise_id": item["exercise_id"], "exercise": item["name"], "plan_id": plan["id"],
                    "planned": planned,
                    **tn.progression(planned, sessions, 5.0 if item["lower_body"] else 2.5)})
        return {"athlete_id": athlete_id, "weekly_volume": tn.weekly_volume(sets, loads, weeks, today.isoformat()),
                "progression": suggestions,
                "note": "Suggestions only, nothing is applied automatically. Discuss them with your coach."}

    # --- foods ---

    def visible_food_sql(user):
        if user["role"] == "admin":
            return "owner_id IS NULL", ()
        if user["role"] == "coach":
            return "owner_id IS NULL AND (club_id IS NULL OR club_id = ?)", (user["club_id"],)
        return "(club_id IS NULL AND owner_id IS NULL) OR (club_id = ? AND owner_id IS NULL) OR owner_id = ?", (
            user["club_id"], user["id"])

    def food_row(row):
        return {**dict(row)}

    @app.get("/api/foods")
    def list_foods(q: Annotated[str | None, Query(max_length=100)] = None,
                   limit: Annotated[int, Query(ge=1, le=500)] = 50,
                   offset: Offset = 0,
                   user=Depends(current_user), db=Depends(get_db)):
        clause, params = visible_food_sql(user)
        like = None
        if q:
            escaped = q.casefold().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            like = f"%{escaped}%"
        return [food_row(r) for r in db.execute(
            f"SELECT * FROM foods WHERE ({clause}) AND (? IS NULL OR casefold(name_bg) LIKE ? ESCAPE '\\' "
            "OR casefold(name_en) LIKE ? ESCAPE '\\') ORDER BY name_bg, id LIMIT ? OFFSET ?",
            (*params, like, like, like, limit, offset))]

    @app.post("/api/foods", status_code=201)
    def add_food(body: CustomFood, user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete":
            need_consent(user, "foods")
            club_id, owner_id = user["club_id"], user["id"]
            if body.club_id is not None:
                raise HTTPException(422, "club_id applies to staff only")
        else:
            club_id, owner_id = staff_club(db, user, body.club_id), None
        cursor = db.execute(
            "INSERT INTO foods(club_id, owner_id, name_bg, name_en, kcal, protein_g, carbs_g, fat_g, fibre_g) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (club_id, owner_id, body.name_bg, body.name_en, body.kcal, body.protein_g, body.carbs_g,
             body.fat_g, body.fibre_g))
        audit(db, user["id"], "food_created", cursor.lastrowid)
        db.commit()
        return food_row(db.execute("SELECT * FROM foods WHERE id = ?", (cursor.lastrowid,)).fetchone())

    @app.delete("/api/foods/{food_id}", status_code=204)
    def delete_food(food_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        row = db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone()
        mine = row is not None and row["club_id"] is not None and (
            row["owner_id"] == user["id"] if user["role"] == "athlete"
            else row["owner_id"] is None and (user["role"] == "admin" or row["club_id"] == user["club_id"]))
        if not mine:
            raise HTTPException(404, "Food not found")
        try:
            db.execute("DELETE FROM foods WHERE id = ?", (food_id,))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "The food is used in logged meals") from None
        audit(db, user["id"], "food_deleted", food_id)
        db.commit()

    # --- food logs ---

    def food_log_rows(db, athlete_id, where="", params=(), tail="", tail_params=()):
        rows = db.execute(
            "SELECT food_logs.*, foods.name_bg, foods.name_en, foods.kcal AS f_kcal, foods.protein_g AS f_protein_g, "
            "foods.carbs_g AS f_carbs_g, foods.fat_g AS f_fat_g, foods.fibre_g AS f_fibre_g "
            "FROM food_logs JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? "
            + where + " ORDER BY food_logs.day DESC, food_logs.id DESC" + tail, (athlete_id, *params, *tail_params))
        result = []
        for r in rows:
            per100 = {"kcal": r["f_kcal"], "protein_g": r["f_protein_g"], "carbs_g": r["f_carbs_g"],
                      "fat_g": r["f_fat_g"], "fibre_g": r["f_fibre_g"]}
            result.append({"id": r["id"], "athlete_id": r["athlete_id"], "day": r["day"], "meal": r["meal"],
                           "food_id": r["food_id"], "name_bg": r["name_bg"], "name_en": r["name_en"],
                           "grams": r["grams"], "client_id": r["client_id"], "created_at": r["created_at"],
                           **food_macros(per100, r["grams"])})
        return result

    @app.post("/api/food-logs", status_code=201, dependencies=[Depends(current_user)])
    def add_food_log(body: FoodLog, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return log_food(body, user, db, emit)

    def log_food(body: FoodLog, user, db, emit):
        require_role(user, "athlete")
        need_consent(user, "meals")
        if emit is not None and body.client_id is not None:
            previous = db.execute("SELECT id FROM food_logs WHERE athlete_id = ? AND client_id = ?",
                                  (user["id"], body.client_id)).fetchone()
            if previous is not None:
                return JSONResponse(status_code=200, content=food_log_rows(
                    db, user["id"], "AND food_logs.id = ?", (previous["id"],))[0])
        check_log_day(body.day)
        clause, params = visible_food_sql(user)
        if db.execute(f"SELECT 1 FROM foods WHERE id = ? AND ({clause})", (body.food_id, *params)).fetchone() is None:
            raise HTTPException(422, "Unknown food")
        if emit is None:
            return
        created_at = h.now_iso()
        cursor = db.execute(
            "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (user["id"], body.day, body.meal, body.food_id, body.grams, body.client_id, created_at))
        audit(db, user["id"], "food_logged", cursor.lastrowid)
        publish(db, user, "food", cursor.lastrowid, created_at, emit)
        return JSONResponse(status_code=201, content=food_log_rows(
            db, user["id"], "AND food_logs.id = ?", (cursor.lastrowid,))[0])

    @app.get("/api/food-logs")
    def list_food_logs(athlete_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                       from_: Annotated[str | None, Query(alias="from")] = None,
                       to: Annotated[str | None, Query()] = None,
                       limit: Annotated[int, Query(ge=1, le=500)] = 100,
                       offset: Offset = 0,
                       user=Depends(current_user), db=Depends(get_db)):
        athlete = target_athlete(db, user, athlete_id)
        return food_log_rows(db, athlete["id"], "AND (? IS NULL OR food_logs.day >= ?) AND (? IS NULL OR food_logs.day <= ?)",
                             (from_, from_, to, to), " LIMIT ? OFFSET ?", (limit, offset))

    @app.delete("/api/food-logs/{log_id}", status_code=204)
    def delete_food_log(log_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if db.execute("DELETE FROM food_logs WHERE id = ? AND athlete_id = ?", (log_id, user["id"])).rowcount == 0:
            raise HTTPException(404, "Meal entry not found")
        audit(db, user["id"], "food_deleted_log", log_id)
        db.commit()

    # --- weights and the trend ---

    @app.put("/api/me/weights/{day}", status_code=201, dependencies=[Depends(current_user)])
    def put_weight(day: str, body: Weight, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return save_weight(day, body, user, db, emit)

    def save_weight(day: str, body: Weight, user, db, emit):
        require_role(user, "athlete")
        need_consent(user, "weights")
        try:
            day = iso_day(day)
        except ValueError:
            raise HTTPException(422, "day must be YYYY-MM-DD") from None
        check_log_day(day)
        if emit is None:
            return
        existing = db.execute("SELECT id FROM weights WHERE athlete_id = ? AND day = ?", (user["id"], day)).fetchone()
        db.execute("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?) "
                   "ON CONFLICT(athlete_id, day) DO UPDATE SET kg = excluded.kg", (user["id"], day, body.kg))
        row_id = db.execute("SELECT id FROM weights WHERE athlete_id = ? AND day = ?", (user["id"], day)).fetchone()[0]
        audit(db, user["id"], "weight_logged", row_id, {"replaced": existing is not None})
        publish(db, user, "weight", row_id, h.now_iso(), emit)
        return JSONResponse(status_code=200 if existing else 201,
                            content={"athlete_id": user["id"], "day": day, "kg": body.kg})

    @app.delete("/api/me/weights/{day}", status_code=204)
    def delete_weight(day: str, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if db.execute("DELETE FROM weights WHERE athlete_id = ? AND day = ?", (user["id"], day)).rowcount == 0:
            raise HTTPException(404, "Weight not found")
        audit(db, user["id"], "weight_deleted", day)
        db.commit()

    def weight_series(db, athlete_id, end=None):
        query = "SELECT day, kg FROM weights WHERE athlete_id = ? AND (? IS NULL OR day <= ?) ORDER BY day"
        rows = [(r["day"], r["kg"]) for r in db.execute(query, (athlete_id, end, end))]
        return rows, daily_trend(rows)

    @app.get("/api/athletes/{athlete_id}/weights")
    def athlete_weights(athlete_id: SqlitePath, from_: Annotated[str | None, Query(alias="from")] = None,
                        to: Annotated[str | None, Query()] = None,
                        user=Depends(current_user), db=Depends(get_db)):
        h.athlete_access(db, user, athlete_id)
        rows, trend = weight_series(db, athlete_id)
        by_day = {t["day"]: t for t in trend}
        inside = lambda day: (from_ is None or day >= from_) and (to is None or day <= to)
        return {"athlete_id": athlete_id,
                "weights": [{"day": day, "kg": kg, "trend_kg": by_day.get(day, {}).get("trend_kg")}
                            for day, kg in rows if inside(day)],
                "trend": [t for t in trend if inside(t["day"])],
                "method": "exponential trend smoothing over daily bodyweight"}

    # --- energy balance, targets, daily summary ---

    def estimate_for(db, athlete_id, end):
        since = (end - timedelta(days=tn.WINDOW_DAYS - 1)).isoformat()
        food = {}
        for r in db.execute(
                "SELECT food_logs.day, SUM(food_logs.grams * foods.kcal / 100) AS kcal FROM food_logs "
                "JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? AND food_logs.day >= ? "
                "AND food_logs.day <= ? GROUP BY food_logs.day", (athlete_id, since, end.isoformat())):
            food[r["day"]] = r["kcal"]
        rows, trend = weight_series(db, athlete_id, end.isoformat())
        return tn.energy_balance(food, {t["day"]: t["trend_kg"] for t in trend}, [d for d, _ in rows], end)

    def rounded(estimate):
        for key in ("mean_intake_kcal", "expenditure_kcal"):
            if estimate.get(key) is not None:
                estimate[key] = round(estimate[key])
        if estimate.get("trend_change_kg") is not None:
            estimate["trend_change_kg"] = round(estimate["trend_change_kg"], 2)
        return estimate

    @app.get("/api/athletes/{athlete_id}/nutrition/estimate")
    def nutrition_estimate(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        h.athlete_access(db, user, athlete_id)
        return {"athlete_id": athlete_id, **rounded(estimate_for(db, athlete_id, utc_today())),
                "note": "An estimate from your own logs, not a measurement. Numbers to discuss with your coach."}

    def target_json(db, athlete):
        row = db.execute("SELECT * FROM nutrition_targets WHERE athlete_id = ?", (athlete["id"],)).fetchone()
        estimate = rounded(estimate_for(db, athlete["id"], utc_today()))
        weights, trend = weight_series(db, athlete["id"])
        kg = trend[-1]["trend_kg"] if trend else (weights[-1][1] if weights else None)
        return {"athlete_id": athlete["id"], "under_18": bool(athlete["under_18"]),
                "target": None if row is None else {k: row[k] for k in (
                    "kcal", "protein_g", "carbs_g", "fat_g", "set_by", "updated_at")},
                "estimate_kcal": estimate.get("expenditure_kcal"),
                "protein_range_g": None if kg is None else {
                    "min": tn.protein_range(kg)[0], "max": tn.protein_range(kg)[1], "basis_kg": round(kg, 1)},
                "note": "Numbers to discuss with your coach. The protein range is shown, not prescribed."}

    @app.get("/api/athletes/{athlete_id}/nutrition/target")
    def get_target(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        return target_json(db, h.athlete_access(db, user, athlete_id))

    @app.put("/api/athletes/{athlete_id}/nutrition/target", dependencies=[Depends(current_user)])
    def put_target(athlete_id: SqlitePath, body: Target, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return save_target(athlete_id, body, user, db, emit)

    def save_target(athlete_id: int, body: Target, user, db, emit):
        athlete = h.athlete_access(db, user, athlete_id)
        need_consent(athlete, "a nutrition target")
        estimate = rounded(estimate_for(db, athlete_id, utc_today())).get("expenditure_kcal")
        problem = tn.check_target(body.kcal, estimate, bool(athlete["under_18"]), user["role"] != "athlete")
        if problem:
            raise HTTPException(422, problem)
        if emit is None:
            return
        now = h.now_iso()
        db.execute(
            "INSERT INTO nutrition_targets(athlete_id, kcal, protein_g, carbs_g, fat_g, set_by, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(athlete_id) DO UPDATE SET kcal = excluded.kcal, "
            "protein_g = excluded.protein_g, carbs_g = excluded.carbs_g, fat_g = excluded.fat_g, "
            "set_by = excluded.set_by, updated_at = excluded.updated_at",
            (athlete_id, body.kcal, body.protein_g, body.carbs_g, body.fat_g, user["id"], now))
        audit(db, user["id"], "target_set", athlete_id, {"kcal": body.kcal})
        return target_json(db, athlete)

    @app.get("/api/athletes/{athlete_id}/nutrition/summary")
    def nutrition_summary(athlete_id: SqlitePath, day: Annotated[str | None, Query()] = None,
                          user=Depends(current_user), db=Depends(get_db)):
        athlete = h.athlete_access(db, user, athlete_id)
        try:
            day = iso_day(day) if day else utc_today().isoformat()
        except ValueError:
            raise HTTPException(422, "day must be YYYY-MM-DD") from None
        requested_day = date.fromisoformat(day)
        first = (requested_day - timedelta(days=min(6, requested_day.toordinal() - 1))).isoformat()
        keys = ("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")
        totals = {key: 0.0 for key in keys}
        by_meal = {meal: 0.0 for meal in MEALS}
        weekly = {}
        for r in food_log_rows(db, athlete_id, "AND food_logs.day >= ? AND food_logs.day <= ?", (first, day)):
            bucket = weekly.setdefault(r["day"], {key: 0.0 for key in keys})
            for key in keys:
                bucket[key] += r[key]
            if r["day"] == day:
                for key in keys:
                    totals[key] += r[key]
                by_meal[r["meal"]] += r["kcal"]
        target = db.execute("SELECT kcal, protein_g, carbs_g, fat_g FROM nutrition_targets WHERE athlete_id = ?",
                            (athlete_id,)).fetchone()
        days = len(weekly)
        return {"athlete_id": athlete_id, "day": day,
                "totals": {key: round(value, 1) for key, value in totals.items()},
                "by_meal_kcal": {meal: round(value, 1) for meal, value in by_meal.items()},
                "target": None if target is None else dict(target),
                "remaining": None if target is None else {
                    key: None if target[key] is None else round(target[key] - totals[key], 1)
                    for key in ("kcal", "protein_g", "carbs_g", "fat_g")},
                "weekly_average": {"days_logged": days, "window": [first, day], **{
                    key: round(sum(w[key] for w in weekly.values()) / days, 1) if days else None for key in keys}}}

    # --- adaptive expenditure and the weekly coaching program (SPEC 6.3) ---

    def publish_athlete(db, athlete, kind, row_id, at):
        app.state.events.publish(h.athlete_viewers(db, athlete), {
            "type": kind, "id": row_id, "athlete_id": athlete["id"], "club_id": athlete["club_id"], "at": at})

    def diary_fingerprint(db, athlete_id, day):
        """Stable hash of a day's diary, so a day confirmed complete is noticed when it is later edited."""
        rows = [[r["id"], r["meal"], r["food_id"], r["grams"]] for r in db.execute(
            "SELECT id, meal, food_id, grams FROM food_logs WHERE athlete_id = ? AND day = ? ORDER BY id",
            (athlete_id, day))]
        return hashlib.blake2b(json.dumps(rows, separators=(",", ":")).encode("utf-8")).hexdigest()

    def day_calories(db, athlete_id, day):
        return db.execute(
            "SELECT COALESCE(SUM(food_logs.grams * foods.kcal / 100), 0.0) AS kcal FROM food_logs "
            "JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? AND food_logs.day = ?",
            (athlete_id, day)).fetchone()["kcal"]

    def coaching_snaps(db, athlete_id):
        """Explicitly confirmed diary days: complete unless edited since confirmation (then not counted)."""
        snaps = {}
        for r in db.execute("SELECT day, diary_fingerprint FROM nutrition_day_complete WHERE athlete_id = ?",
                            (athlete_id,)):
            snaps[r["day"]] = {"status": "complete", "calories": day_calories(db, athlete_id, r["day"]),
                               "changed": diary_fingerprint(db, athlete_id, r["day"]) != r["diary_fingerprint"]}
        return snaps

    def program_row(db, athlete_id):
        return db.execute("SELECT * FROM coaching_programs WHERE athlete_id = ?", (athlete_id,)).fetchone()

    def expenditure_series(db, athlete, program):
        weights = {day: kg for day, kg in weight_series(db, athlete["id"])[0]}
        return co.compute(program["effective_date"], program["seed_kcal"], program["mode"],
                          coaching_snaps(db, athlete["id"]), weights, utc_today().isoformat())

    def recommended(expenditure, program, today):
        return co.program_targets(expenditure, program["goal"], program["rate_kg_week"],
                                  program["protein_g"], program["fat_g"], today)

    def program_view(db, athlete):
        program = program_row(db, athlete["id"])
        if program is None:
            return {"athlete_id": athlete["id"], "program": None, "under_18": bool(athlete["under_18"]),
                    "reason": "No coaching program yet. Set a goal and weekly rate to start.",
                    "note": "Recommended targets always pass through the under-18 and 1500 kcal guardrails."}
        series = expenditure_series(db, athlete, program)
        rec = recommended(series["expenditure"], program, utc_today().isoformat())
        target = {"calories": round(rec["calories"]), "protein_g": round(rec["protein"]),
                  "carbs_g": round(rec["carbs"]), "fat_g": round(rec["fat"])}
        guardrail = tn.check_target(target["calories"], round(rec["expenditure_kcal"]),
                                    bool(athlete["under_18"]), False)
        return {"athlete_id": athlete["id"], "under_18": bool(athlete["under_18"]),
                "program": {"goal": rec["goal"], "rate_kg_week": rec["rate_kg_week"], "mode": rec["mode"],
                            "effective_date": rec["effective_date"], "expires_on": rec["expires_on"],
                            "expenditure_kcal": round(rec["expenditure_kcal"]), "recommended_target": target},
                "applied": bool(program["applied"]),
                "guardrail": guardrail,
                "note": "Recommended targets still pass through the under-18 and 1500 kcal guardrails."}

    @app.get("/api/athletes/{athlete_id}/nutrition/expenditure")
    def nutrition_expenditure(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        athlete = h.athlete_access(db, user, athlete_id)
        program = program_row(db, athlete_id)
        if program is None:
            return {"athlete_id": athlete_id, "expenditure": None, "history": [], "method": co.WINDOW_DAYS,
                    "reason": "No coaching program yet.", "window_days": co.WINDOW_DAYS,
                    "note": "Adaptive expenditure: 14-day energy balance over complete diary days and the weight trend."}
        series = expenditure_series(db, athlete, program)
        return {"athlete_id": athlete_id, "window_days": co.WINDOW_DAYS, **series,
                "note": "Adaptive expenditure: 14-day energy balance over complete diary days and the weight trend."}

    @app.get("/api/athletes/{athlete_id}/nutrition/program")
    def get_program(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        return program_view(db, h.athlete_access(db, user, athlete_id))

    @app.put("/api/athletes/{athlete_id}/nutrition/program", dependencies=[Depends(current_user)])
    def set_program(athlete_id: SqlitePath, body: Program, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return save_program(athlete_id, body, user, db, emit)

    def save_program(athlete_id: int, body: Program, user, db, emit, *, require_applicable=False):
        athlete = h.athlete_access(db, user, athlete_id)
        need_consent(athlete, "a coaching program")
        existing = program_row(db, athlete_id)
        if existing is None:
            seed = estimate_for(db, athlete_id, utc_today())
            if not seed["available"]:
                raise HTTPException(422, "Not enough logged days yet to start an adaptive program")
            effective_date, seed_kcal, mode = utc_today().isoformat(), seed["expenditure_kcal"], "updating"
        else:
            effective_date, seed_kcal, mode = existing["effective_date"], existing["seed_kcal"], existing["mode"]
        proposed = {"effective_date": effective_date, "seed_kcal": seed_kcal, "mode": mode, "goal": body.goal,
                    "rate_kg_week": body.rate_kg_week, "protein_g": body.protein_g, "fat_g": body.fat_g}
        series = expenditure_series(db, athlete, proposed)
        try:
            rec = recommended(series["expenditure"], proposed, utc_today().isoformat())
        except ValueError as error:
            raise HTTPException(422, str(error)) from None
        target = {"calories": round(rec["calories"]), "protein_g": round(rec["protein"]),
                  "carbs_g": round(rec["carbs"]), "fat_g": round(rec["fat"])}
        # The recommended deficit still passes the under-18 and 1500 kcal guardrails before it is applied.
        guardrail = tn.check_target(target["calories"], round(rec["expenditure_kcal"]),
                                    bool(athlete["under_18"]), user["role"] != "athlete")
        if guardrail is not None and require_applicable:
            raise HTTPException(422, guardrail)
        if emit is None:
            return
        now = h.now_iso()
        applied = guardrail is None
        db.execute(
            "INSERT INTO coaching_programs(athlete_id, effective_date, seed_kcal, mode, goal, rate_kg_week, "
            "protein_g, fat_g, applied, set_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(athlete_id) DO UPDATE SET goal = excluded.goal, rate_kg_week = excluded.rate_kg_week, "
            "protein_g = excluded.protein_g, fat_g = excluded.fat_g, applied = excluded.applied, "
            "set_by = excluded.set_by, updated_at = excluded.updated_at",
            (athlete_id, effective_date, seed_kcal, mode, body.goal, body.rate_kg_week, body.protein_g,
             body.fat_g, int(applied), user["id"], now))
        if applied:
            db.execute(
                "INSERT INTO nutrition_targets(athlete_id, kcal, protein_g, carbs_g, fat_g, set_by, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(athlete_id) DO UPDATE SET kcal = excluded.kcal, "
                "protein_g = excluded.protein_g, carbs_g = excluded.carbs_g, fat_g = excluded.fat_g, "
                "set_by = excluded.set_by, updated_at = excluded.updated_at",
                (athlete_id, target["calories"], target["protein_g"], target["carbs_g"], target["fat_g"],
                 user["id"], now))
        audit(db, user["id"], "coaching_program_set", athlete_id,
              {"goal": body.goal, "rate_kg_week": body.rate_kg_week, "applied": applied})
        publish(db, athlete, "coaching", athlete_id, now, emit)
        return program_view(db, athlete)

    @app.put("/api/me/nutrition/days/{day}/complete", dependencies=[Depends(current_user)])
    def mark_day_complete(day: str, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            require_role(user, "athlete")
            need_consent(user, "a complete diary day")
            try:
                day = iso_day(day)
            except ValueError:
                raise HTTPException(422, "day must be YYYY-MM-DD") from None
            check_log_day(day)
            now = h.now_iso()
            fingerprint = diary_fingerprint(db, user["id"], day)
            db.execute("INSERT INTO nutrition_day_complete(athlete_id, day, diary_fingerprint, confirmed_at) "
                       "VALUES (?, ?, ?, ?) ON CONFLICT(athlete_id, day) DO UPDATE SET "
                       "diary_fingerprint = excluded.diary_fingerprint, confirmed_at = excluded.confirmed_at",
                       (user["id"], day, fingerprint, now))
            audit(db, user["id"], "nutrition_day_completed", day)
            publish(db, user, "nutrition_day", user["id"], now, emit)
            return {"athlete_id": user["id"], "day": day, "status": "complete",
                    "calories": round(day_calories(db, user["id"], day), 1)}

    @app.delete("/api/me/nutrition/days/{day}/complete", status_code=204)
    def unmark_day_complete(day: str, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if db.execute("DELETE FROM nutrition_day_complete WHERE athlete_id = ? AND day = ?",
                      (user["id"], day)).rowcount == 0:
            raise HTTPException(404, "Day not confirmed")
        audit(db, user["id"], "nutrition_day_uncompleted", day)
        db.commit()

    # --- CSV exports for staff ---

    def csv_response(db, user, athlete, kind, fields, rows):
        output = io.StringIO(newline="")
        writer = csv.writer(output)
        writer.writerow(fields)
        for row in rows:
            writer.writerow([h.csv_cell(row.get(field)) for field in fields])
        audit(db, user["id"], "csv_export", athlete["id"], {"kind": kind})
        db.commit()
        return Response(output.getvalue(), media_type="text/csv", headers={
            "Content-Disposition": f'attachment; filename="athlete-{athlete["id"]}-{kind}.csv"'})

    @app.get("/api/athletes/{athlete_id}/training.csv")
    def training_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows = [{**dict(r), "athlete_name": athlete["name"]} for r in db.execute(
            "SELECT set_logs.*, exercises.name AS exercise FROM set_logs JOIN exercises ON exercises.id = "
            "set_logs.exercise_id WHERE athlete_id = ? ORDER BY day, set_no, set_logs.id", (athlete_id,))]
        return csv_response(db, user, athlete, "training", [
            "id", "athlete_id", "athlete_name", "day", "exercise", "set_no", "reps", "load_kg", "rpe", "plan_id"], rows)

    @app.get("/api/athletes/{athlete_id}/nutrition.csv")
    def nutrition_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows = [{**r, "athlete_name": athlete["name"]} for r in reversed(food_log_rows(db, athlete_id))]
        return csv_response(db, user, athlete, "nutrition", [
            "id", "athlete_id", "athlete_name", "day", "meal", "name_bg", "name_en", "grams", "kcal",
            "protein_g", "carbs_g", "fat_g", "fibre_g"], rows)

    @app.get("/api/athletes/{athlete_id}/weights.csv")
    def weights_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows, trend = weight_series(db, athlete_id)
        by_day = {t["day"]: t["trend_kg"] for t in trend}
        return csv_response(db, user, athlete, "weights", ["athlete_id", "athlete_name", "day", "kg", "trend_kg"], [
            {"athlete_id": athlete_id, "athlete_name": athlete["name"], "day": day, "kg": kg,
             "trend_kg": by_day.get(day)} for day, kg in rows])

    return {operation.__name__: operation for operation in (
        list_plans, get_plan, list_exercises, list_set_logs, training_summary, list_foods,
        list_food_logs, athlete_weights, nutrition_estimate, get_target, nutrition_summary,
        nutrition_expenditure, get_program, create_plan, edit_plan, log_set, log_food,
        save_weight, save_target, save_program,
    )}


def export_data(db, athlete_id):
    """The athlete's training and nutrition data for the JSON export."""
    sets = [dict(r) for r in db.execute(
        "SELECT id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, client_id, created_at FROM set_logs "
        "WHERE athlete_id = ? ORDER BY day, set_no, id", (athlete_id,))]
    foods = [dict(r) for r in db.execute(
        "SELECT id, name_bg, name_en, kcal, protein_g, carbs_g, fat_g, fibre_g FROM foods "
        "WHERE owner_id = ? ORDER BY id", (athlete_id,))]
    meals = [dict(r) for r in db.execute(
        "SELECT food_logs.id, day, meal, food_id, foods.name_bg, foods.name_en, grams, client_id, food_logs.created_at "
        "FROM food_logs JOIN foods ON foods.id = food_logs.food_id WHERE athlete_id = ? "
        "ORDER BY day, food_logs.id", (athlete_id,))]
    weights = [dict(r) for r in db.execute("SELECT day, kg FROM weights WHERE athlete_id = ? ORDER BY day", (athlete_id,))]
    target = db.execute("SELECT kcal, protein_g, carbs_g, fat_g, set_by, updated_at FROM nutrition_targets "
                        "WHERE athlete_id = ?", (athlete_id,)).fetchone()
    program = db.execute("SELECT effective_date, seed_kcal, mode, goal, rate_kg_week, protein_g, fat_g, "
                         "applied, set_by, updated_at FROM coaching_programs WHERE athlete_id = ?",
                         (athlete_id,)).fetchone()
    complete_days = [dict(r) for r in db.execute(
        "SELECT day, diary_fingerprint, confirmed_at FROM nutrition_day_complete WHERE athlete_id = ? ORDER BY day",
        (athlete_id,))]
    return {"set_logs": sets, "custom_foods": foods, "food_logs": meals, "weights": weights,
            "nutrition_target": None if target is None else dict(target),
            "coaching_program": None if program is None else {**dict(program), "applied": bool(program["applied"])},
            "complete_diary_days": complete_days}


def delete_data(db, athlete_id):
    for table in ("set_logs", "food_logs", "weights", "nutrition_targets", "coaching_programs",
                  "nutrition_day_complete"):
        db.execute(f"DELETE FROM {table} WHERE athlete_id = ?", (athlete_id,))
    db.execute("DELETE FROM foods WHERE owner_id = ?", (athlete_id,))
