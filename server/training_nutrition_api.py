"""Training (SPEC 6.2) and nutrition (SPEC 6.3) tables and routes.

`init_schema(db)` is called from `main.init_db`; `install(app, h)` from `main.create_app`, where `h`
is the main module (its auth, scope, audit and event helpers are reused, never copied).
"""

import csv
from datetime import date, datetime, timedelta, timezone
from functools import lru_cache
import hashlib
from itertools import groupby
import json
from pathlib import Path
import re
import sqlite3
from typing import Annotated, Literal

from fastapi import Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import coaching as co
from . import food_sources as fs
from . import training_nutrition as tn
from .weight_trend import daily_trend
from .input_bounds import Offset, SQLITE_MAX, SqlitePath

FOODS_CSV = Path(__file__).with_name("data") / "foods.csv"
MEALS = ("breakfast", "lunch", "dinner", "snack")
KINDS = ("strength", "plyometric", "mobility", "conditioning")
LOG_DAYS_BACK = 30
# kcal of one diary row: a food entry from its food, a quick add as typed (food_logs LEFT JOIN foods).
LOG_KCAL = "COALESCE(food_logs.grams * foods.kcal / 100, food_logs.kcal)"

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
    CREATE TABLE IF NOT EXISTS workout_templates (
        id INTEGER PRIMARY KEY,
        owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        club_id INTEGER REFERENCES clubs(id) ON DELETE CASCADE,
        title TEXT NOT NULL,
        items TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS workouts (
        id INTEGER PRIMARY KEY,
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        started_at TEXT NOT NULL,
        ended_at TEXT,
        title TEXT NOT NULL,
        template_id INTEGER REFERENCES workout_templates(id) ON DELETE SET NULL,
        notes TEXT NOT NULL DEFAULT '',
        client_id TEXT
    );
    CREATE INDEX IF NOT EXISTS workouts_athlete ON workouts(athlete_id, started_at);
    CREATE UNIQUE INDEX IF NOT EXISTS workouts_client ON workouts(athlete_id, client_id) WHERE client_id IS NOT NULL;
    CREATE TABLE IF NOT EXISTS foods (
        id INTEGER PRIMARY KEY,
        fdc_id INTEGER,
        club_id INTEGER REFERENCES clubs(id) ON DELETE CASCADE,
        owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
        name_bg TEXT NOT NULL,
        name_en TEXT NOT NULL DEFAULT '',
        kcal REAL NOT NULL, protein_g REAL NOT NULL, carbs_g REAL NOT NULL,
        fat_g REAL NOT NULL, fibre_g REAL NOT NULL DEFAULT 0,
        source TEXT NOT NULL DEFAULT 'custom',
        servings TEXT, brand TEXT NOT NULL DEFAULT '', barcode TEXT, source_url TEXT NOT NULL DEFAULT '',
        catalog_id TEXT, nutrients TEXT, created_by INTEGER REFERENCES users(id) ON DELETE SET NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS foods_fdc ON foods(fdc_id) WHERE fdc_id IS NOT NULL;
    {food_logs}
    {food_log_indexes}
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
    CREATE TABLE IF NOT EXISTS recipes (
        id INTEGER PRIMARY KEY,
        owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
        club_id INTEGER NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        servings_count INTEGER NOT NULL CHECK(servings_count BETWEEN 1 AND 100),
        food_id INTEGER NOT NULL REFERENCES foods(id)
    );
    CREATE TABLE IF NOT EXISTS recipe_items (
        recipe_id INTEGER NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
        position INTEGER NOT NULL,
        food_id INTEGER NOT NULL REFERENCES foods(id),
        grams REAL NOT NULL CHECK(grams > 0 AND grams <= 5000),
        PRIMARY KEY (recipe_id, position)
    );
"""
# Version 2 (SPEC 7.1): food_id is null for a quick add, which carries its own label, kcal and macros instead;
# grams stays the stored truth for food entries, serving_label and quantity record how it was entered.
FOOD_LOGS = """CREATE TABLE IF NOT EXISTS {name} (
        id INTEGER PRIMARY KEY,
        athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day TEXT NOT NULL,
        meal TEXT NOT NULL CHECK(meal IN ('breakfast', 'lunch', 'dinner', 'snack')),
        food_id INTEGER REFERENCES foods(id),
        grams REAL CHECK(grams > 0 AND grams <= 5000),
        client_id TEXT,
        created_at TEXT NOT NULL,
        serving_label TEXT,
        quantity REAL,
        label TEXT,
        kcal REAL, protein_g REAL, carbs_g REAL, fat_g REAL,
        CHECK((food_id IS NOT NULL AND grams IS NOT NULL) OR (food_id IS NULL AND label IS NOT NULL AND kcal IS NOT NULL))
    );"""
FOOD_LOG_INDEXES = """CREATE INDEX IF NOT EXISTS food_logs_athlete ON food_logs(athlete_id, day);
    CREATE UNIQUE INDEX IF NOT EXISTS food_logs_client ON food_logs(athlete_id, client_id) WHERE client_id IS NOT NULL;"""
SCHEMA = SCHEMA.format(food_logs=FOOD_LOGS.format(name="food_logs"), food_log_indexes=FOOD_LOG_INDEXES)
OLD_LOG_COLUMNS = "id, athlete_id, day, meal, food_id, grams, client_id, created_at"
NEW_COLUMNS = {
    "set_logs": {"workout_id": "INTEGER REFERENCES workouts(id) ON DELETE SET NULL"},
    "foods": {"servings": "TEXT", "brand": "TEXT NOT NULL DEFAULT ''", "barcode": "TEXT",
              "source_url": "TEXT NOT NULL DEFAULT ''", "catalog_id": "TEXT", "nutrients": "TEXT",
              "created_by": "INTEGER REFERENCES users(id) ON DELETE SET NULL"},
    "coaching_programs": {"target_weight_kg": "REAL", "end_date": "TEXT"},
}
# Recipe portions carry a count and unit; the client builds the label in its language (SPEC 7.4). The label is a
# language-neutral key that log entries store. Version 2 stored Bulgarian text, migrated in place below.
RECIPE_UNITS = {"1 порция": {"label": "serving", "count": 1, "unit": "serving"},
                "цялата рецепта": {"label": "recipe", "count": 1, "unit": "recipe"}}


def recipe_servings(total, servings_count):
    return [{"label": "serving", "count": 1, "unit": "serving", "grams": round(total / servings_count, 1)},
            {"label": "recipe", "count": 1, "unit": "recipe", "grams": round(total, 1)}]


def init_schema(db):
    """Idempotent: tables, the under_18 column on older databases and the bundled food list."""
    db.executescript(SCHEMA)
    columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
    if "under_18" not in columns:
        db.execute("ALTER TABLE users ADD COLUMN under_18 INTEGER NOT NULL DEFAULT 0 CHECK(under_18 IN (0, 1))")
    for table, additions in NEW_COLUMNS.items():
        columns = {row["name"] for row in db.execute(f"PRAGMA table_info({table})")}
        for name, declaration in additions.items():
            if name not in columns:
                db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {declaration}")
    if "label" not in {row["name"] for row in db.execute("PRAGMA table_info(food_logs)")}:
        # SQLite cannot drop NOT NULL in place: rebuild food_logs once, keeping every row and id.
        db.executescript(f"""BEGIN; {FOOD_LOGS.format(name="food_logs_v2")}
            INSERT INTO food_logs_v2({OLD_LOG_COLUMNS}) SELECT {OLD_LOG_COLUMNS} FROM food_logs;
            DROP TABLE food_logs; ALTER TABLE food_logs_v2 RENAME TO food_logs; {FOOD_LOG_INDEXES} COMMIT;""")
    db.execute("CREATE INDEX IF NOT EXISTS set_logs_workout ON set_logs(workout_id) WHERE workout_id IS NOT NULL")
    # Club and personal foods from before 7.4: the creator from the audit log (food_created, food_scanned).
    db.execute("UPDATE foods SET created_by = (SELECT audit.actor_id FROM audit JOIN users ON users.id = audit.actor_id "
               "WHERE audit.action IN ('food_created', 'food_scanned') AND audit.target = CAST(foods.id AS TEXT) "
               "ORDER BY audit.id LIMIT 1) WHERE created_by IS NULL AND club_id IS NOT NULL AND source != 'recipe'")
    for row in db.execute("SELECT id, servings FROM foods WHERE source = 'recipe' AND servings NOT LIKE '%\"unit\"%'").fetchall():
        servings = [{**s, **RECIPE_UNITS.get(s["label"], {})} for s in json.loads(row["servings"])]
        db.execute("UPDATE foods SET servings = ? WHERE id = ?", (json.dumps(servings), row["id"]))
    db.execute("UPDATE food_logs SET serving_label = CASE serving_label WHEN '1 порция' THEN 'serving' ELSE 'recipe' END "
               "WHERE serving_label IN ('1 порция', 'цялата рецепта') "
               "AND food_id IN (SELECT id FROM foods WHERE source = 'recipe')")
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS foods_catalog ON foods(catalog_id) WHERE catalog_id IS NOT NULL")
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS foods_barcode ON foods(IFNULL(club_id, 0), IFNULL(owner_id, 0), barcode) "
               "WHERE barcode IS NOT NULL")
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
    # Bundled names can be corrected in foods.csv; existing databases pick the new Bulgarian name up at startup.
    db.executemany("UPDATE foods SET name_bg = ? WHERE fdc_id = ? AND name_bg IS NOT ?",
                   [(r["name_bg"], int(r["fdc_id"]), r["name_bg"]) for r in read_foods_csv()])
    # USDA portions and micronutrients for the bundled foods come from the catalogue, matched by FDC id, once.
    missing = [row[0] for row in db.execute("SELECT fdc_id FROM foods WHERE fdc_id IS NOT NULL AND servings IS NULL")]
    if missing:
        db.executemany("UPDATE foods SET servings = ?, nutrients = ? WHERE fdc_id = ?", [
            (json.dumps(food["servings"]), json.dumps(food["nutrients"]), fdc_id)
            for fdc_id, food in fs.catalog_by_fdc(missing).items()])
        db.execute("UPDATE foods SET servings = '[]' WHERE fdc_id IS NOT NULL AND servings IS NULL")


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
    # A queued set names its workout by the workout's client_id: the server id is unknown while offline.
    workout_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    workout_client_id: str | None = Field(default=None, min_length=1, max_length=128)
    _day = field_validator("day")(iso_day)

    @model_validator(mode="after")
    def one_workout_reference(self):
        if self.workout_id is not None and self.workout_client_id is not None:
            raise ValueError("Send workout_id or workout_client_id, not both")
        return self


def utc_timestamp(value):
    """ISO 8601 with a timezone and a year from 2000 to 2100, normalised to UTC."""
    if value is None:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("Timestamps must be ISO 8601") from None
    if parsed.utcoffset() is None or not 2000 <= parsed.year <= 2100:
        raise ValueError("Timestamps need a timezone and a year from 2000 to 2100")
    return parsed.astimezone(timezone.utc).isoformat()


class WorkoutBody(StrictModel):
    started_at: str = Field(max_length=40)
    ended_at: str | None = Field(default=None, max_length=40)
    title: str = Field(min_length=1, max_length=100)
    template_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    notes: str = Field(default="", max_length=1000)
    client_id: str | None = Field(default=None, min_length=1, max_length=128)
    _title = field_validator("title")(stripped)
    _times = field_validator("started_at", "ended_at")(utc_timestamp)

    @model_validator(mode="after")
    def time_order(self):
        if self.ended_at is not None and not timedelta(0) <= (
                datetime.fromisoformat(self.ended_at) - datetime.fromisoformat(self.started_at)) <= timedelta(hours=24):
            raise ValueError("ended_at must be after started_at and at most 24 hours later")
        return self


class TemplateItem(StrictModel):
    exercise_id: int = Field(gt=0, le=SQLITE_MAX)
    sets: int = Field(ge=1, le=20)
    reps: int = Field(ge=1, le=200)
    load_kg: float | None = Field(default=None, ge=0, le=1000)
    rest_s: int | None = Field(default=None, ge=0, le=900)
    # Consecutive items with the same number form a superset.
    superset: int | None = Field(default=None, ge=1, le=20)


class TemplateBody(StrictModel):
    title: str = Field(min_length=1, max_length=100)
    items: list[TemplateItem] = Field(min_length=1, max_length=30)
    club_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    _title = field_validator("title")(stripped)


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


CatalogId = Annotated[str, Field(max_length=40, pattern=fs.CATALOG_ID.pattern)]
Barcode = Annotated[str, Field(pattern=r"^[0-9]{8,14}$")]


class Serving(StrictModel):
    label: str = Field(min_length=1, max_length=60)
    grams: float = Field(gt=0, le=5000)
    _label = field_validator("label")(stripped)


class FoodFields(StrictModel):
    brand: str = Field(default="", max_length=100)
    barcode: Barcode | None = None
    servings: list[Serving] = Field(default=[], max_length=30)


class FoodV2(CustomFood, FoodFields):
    pass


class FoodPatch(FoodFields):
    """Correct a food (a scanned one, say): only the fields sent change."""
    name_bg: str | None = Field(default=None, min_length=1, max_length=100)
    name_en: str | None = Field(default=None, max_length=200)
    kcal: float | None = Field(default=None, ge=0, le=1000)
    protein_g: float | None = Field(default=None, ge=0, le=100)
    carbs_g: float | None = Field(default=None, ge=0, le=100)
    fat_g: float | None = Field(default=None, ge=0, le=100)
    fibre_g: float | None = Field(default=None, ge=0, le=100)
    brand: str | None = Field(default=None, max_length=100)
    servings: list[Serving] | None = Field(default=None, max_length=30)
    _name = field_validator("name_bg")(lambda value: value if value is None else stripped(value))


class QuickValues(StrictModel):
    label: str | None = Field(default=None, min_length=1, max_length=100)
    kcal: float | None = Field(default=None, ge=0, le=10000)
    protein_g: float | None = Field(default=None, ge=0, le=1000)
    carbs_g: float | None = Field(default=None, ge=0, le=1000)
    fat_g: float | None = Field(default=None, ge=0, le=1000)
    serving_label: str | None = Field(default=None, min_length=1, max_length=60)
    quantity: float | None = Field(default=None, gt=0, le=100)


class FoodEntry(FoodLog, QuickValues):
    """A diary entry (SPEC 7.1): a food by id, a catalogue food (materialised once), or a quick add with no food."""
    food_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    catalog_id: CatalogId | None = None
    grams: float | None = Field(default=None, gt=0, le=5000)

    @model_validator(mode="after")
    def one_source(self):
        quick = self.label is not None or self.kcal is not None or any(
            v is not None for v in (self.protein_g, self.carbs_g, self.fat_g))
        if quick:
            if self.label is None or self.kcal is None or self.food_id is not None or self.catalog_id is not None \
                    or self.grams is not None or self.serving_label is not None or self.quantity is not None:
                raise ValueError("A quick add needs label and kcal (macros optional) and no food, grams or serving")
        elif (self.food_id is None) == (self.catalog_id is None):
            raise ValueError("Give food_id or catalog_id, or a quick add with label and kcal")
        elif self.grams is None and self.serving_label is None:
            raise ValueError("Give grams or a serving_label (with quantity)")
        return self


class FoodLogPatch(QuickValues):
    day: str | None = Field(default=None, min_length=10, max_length=10)
    meal: Literal["breakfast", "lunch", "dinner", "snack"] | None = None
    grams: float | None = Field(default=None, gt=0, le=5000)
    _day = field_validator("day")(lambda value: value if value is None else iso_day(value))


class FoodLogCopy(StrictModel):
    from_day: str = Field(min_length=10, max_length=10)
    to_day: str = Field(min_length=10, max_length=10)
    from_meal: Literal["breakfast", "lunch", "dinner", "snack"] | None = None
    to_meal: Literal["breakfast", "lunch", "dinner", "snack"] | None = None
    client_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,100}$")
    _days = field_validator("from_day", "to_day")(iso_day)


class FoodLogBatch(StrictModel):
    entries: list[FoodEntry] = Field(min_length=1, max_length=50)


class RecipeItem(StrictModel):
    food_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    catalog_id: CatalogId | None = None
    grams: float = Field(gt=0, le=5000)

    @model_validator(mode="after")
    def one_food(self):
        if (self.food_id is None) == (self.catalog_id is None):
            raise ValueError("Give food_id or catalog_id")
        return self


class RecipeBody(StrictModel):
    name: str = Field(min_length=1, max_length=100)
    servings_count: int = Field(ge=1, le=100)
    items: list[RecipeItem] = Field(min_length=1, max_length=50)
    club_id: int | None = Field(default=None, gt=0, le=SQLITE_MAX)
    _name = field_validator("name")(stripped)


class GoalBody(StrictModel):
    phase: Literal["maintain", "lose", "gain"]
    target_weight_kg: float | None = Field(default=None, ge=20, le=400)
    end_date: str | None = Field(default=None, min_length=10, max_length=10)
    protein_g: float = Field(ge=0, le=500)
    fat_g: float = Field(ge=0, le=500)
    _end = field_validator("end_date")(lambda value: value if value is None else iso_day(value))

    @model_validator(mode="after")
    def target_matches_phase(self):
        if (self.phase == "maintain") != (self.target_weight_kg is None and self.end_date is None) or \
                (self.target_weight_kg is None) != (self.end_date is None):
            raise ValueError("Lose and gain need target_weight_kg and end_date; maintain takes neither")
        return self


class AiParse(StrictModel):
    text: str = Field(min_length=1, max_length=1000)
    day: str | None = Field(default=None, min_length=10, max_length=10)
    meal: Literal["breakfast", "lunch", "dinner", "snack"] = "snack"
    _text = field_validator("text")(stripped)
    _day = field_validator("day")(lambda value: value if value is None else iso_day(value))


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
    under_18: bool | None = None
    leaderboard_allowed: bool | None = None  # coach's confirmation for an under-18 athlete (SPEC 8)

    @model_validator(mode="after")
    def one_field(self):
        if self.under_18 is None and self.leaderboard_allowed is None:
            raise ValueError("Send under_18 or leaderboard_allowed")
        return self


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
        if body.under_18 is not None:
            db.execute("UPDATE users SET under_18 = ? WHERE id = ?", (body.under_18, target_id))
            audit(db, user["id"], "profile_changed", target_id, {"under_18": body.under_18})
        if body.leaderboard_allowed is not None:
            db.execute("UPDATE users SET leaderboard_allowed = ? WHERE id = ?", (body.leaderboard_allowed, target_id))
            audit(db, user["id"], "leaderboard_allowed_changed", target_id,
                  {"leaderboard_allowed": body.leaderboard_allowed})
        db.commit()
        return {"id": target_id, **body.model_dump(exclude_none=True)}

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
                                          "rpe", "plan_id", "workout_id", "client_id", "created_at")}

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
        workout_id = None
        if body.workout_id is not None or body.workout_client_id is not None:
            column, key = ("id", body.workout_id) if body.workout_id is not None else ("client_id", body.workout_client_id)
            workout = db.execute(f"SELECT id FROM workouts WHERE athlete_id = ? AND {column} = ?", (user["id"], key)).fetchone()
            if workout is None:
                raise HTTPException(422, "Workout not found")
            workout_id = workout["id"]
        if emit is None:
            return
        created_at = h.now_iso()
        cursor = db.execute(
            "INSERT INTO set_logs(athlete_id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, "
            "client_id, created_at, workout_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user["id"], body.day, body.exercise_id, body.set_no, body.reps, body.load_kg, body.rpe,
             body.plan_id, body.client_id, created_at, workout_id))
        audit(db, user["id"], "set_logged", cursor.lastrowid)
        publish(db, user, "set", cursor.lastrowid, created_at, emit)
        h.publish_record(db, user, "set_log", cursor.lastrowid, created_at, emit)
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

    # --- workout templates and workouts (SPEC 9, wave 1) ---

    def template_sql(user):
        """Athletes see their own and their club's shared templates; staff see shared ones only."""
        if user["role"] == "admin":
            return "club_id IS NOT NULL", ()
        if user["role"] == "coach":
            return "club_id = ?", (user["club_id"],)
        return "owner_id = ? OR club_id = ?", (user["id"], user["club_id"])

    def template_json(db, row):
        items = json.loads(row["items"])
        names = {r["id"]: r["name"] for r in db.execute(
            f"SELECT id, name FROM exercises WHERE id IN ({','.join('?' * len(items))})",
            [item["exercise_id"] for item in items])}
        return {**dict(row), "items": [{**item, "exercise": names.get(item["exercise_id"])} for item in items]}

    def scoped_template(db, user, template_id, write=False):
        clause, params = template_sql(user)
        row = db.execute(f"SELECT * FROM workout_templates WHERE id = ? AND ({clause})",
                         (template_id, *params)).fetchone()
        if row is None or (write and user["role"] == "athlete" and row["club_id"] is not None):
            raise HTTPException(404, "Template not found")
        return row

    def template_values(db, user, body, club_id=None):
        """Athlete templates stay private (club_id NULL); staff templates are shared with one club."""
        if user["role"] == "athlete":
            need_consent(user, "workout templates")
            if body.club_id is not None:
                raise HTTPException(422, "club_id applies to staff only")
            exercise_club = user["club_id"]
        else:
            if club_id is not None and body.club_id not in (None, club_id):
                raise HTTPException(422, "A template cannot move to another club")
            club_id = exercise_club = club_id or staff_club(db, user, body.club_id)
        for item in body.items:
            exercise_in_club(db, exercise_club, item.exercise_id)
        return club_id, json.dumps([item.model_dump() for item in body.items])

    @app.get("/api/workout-templates")
    def list_templates(user=Depends(current_user), db=Depends(get_db)):
        clause, params = template_sql(user)
        return [template_json(db, r) for r in db.execute(
            f"SELECT * FROM workout_templates WHERE {clause} ORDER BY title, id", params)]

    @app.post("/api/workout-templates", status_code=201, dependencies=[Depends(current_user)])
    def add_template(body: TemplateBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            club_id, items = template_values(db, user, body)
            cursor = db.execute("INSERT INTO workout_templates(owner_id, club_id, title, items) VALUES (?, ?, ?, ?)",
                                (user["id"], club_id, body.title, items))
            audit(db, user["id"], "workout_template_created", cursor.lastrowid)
            return template_json(db, db.execute("SELECT * FROM workout_templates WHERE id = ?",
                                                (cursor.lastrowid,)).fetchone())

    @app.put("/api/workout-templates/{template_id}", dependencies=[Depends(current_user)])
    def replace_template(template_id: SqlitePath, body: TemplateBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            row = scoped_template(db, user, template_id, write=True)
            _, items = template_values(db, user, body, row["club_id"])
            db.execute("UPDATE workout_templates SET title = ?, items = ? WHERE id = ?", (body.title, items, template_id))
            audit(db, user["id"], "workout_template_changed", template_id)
            return template_json(db, db.execute("SELECT * FROM workout_templates WHERE id = ?", (template_id,)).fetchone())

    @app.delete("/api/workout-templates/{template_id}", status_code=204)
    def delete_template(template_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        scoped_template(db, user, template_id, write=True)
        db.execute("DELETE FROM workout_templates WHERE id = ?", (template_id,))
        audit(db, user["id"], "workout_template_deleted", template_id)
        db.commit()

    WORKOUTS = ("SELECT workouts.*, COUNT(set_logs.id) AS sets, COUNT(DISTINCT set_logs.exercise_id) AS exercises, "
                "ROUND(COALESCE(SUM(set_logs.reps * set_logs.load_kg), 0), 1) AS volume_kg "
                "FROM workouts LEFT JOIN set_logs ON set_logs.workout_id = workouts.id ")

    def workout_row(row):
        workout = dict(row)
        workout["duration_s"] = None if row["ended_at"] is None else round(
            (datetime.fromisoformat(row["ended_at"]) - datetime.fromisoformat(row["started_at"])).total_seconds())
        return workout

    def stored_workout(db, workout_id):
        return workout_row(db.execute(WORKOUTS + "WHERE workouts.id = ? GROUP BY workouts.id", (workout_id,)).fetchone())

    @app.post("/api/workouts", status_code=201, dependencies=[Depends(current_user)])
    def add_workout(body: WorkoutBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            require_role(user, "athlete")
            need_consent(user, "training")
            if body.client_id is not None:
                previous = db.execute("SELECT id FROM workouts WHERE athlete_id = ? AND client_id = ?",
                                      (user["id"], body.client_id)).fetchone()
                if previous is not None:
                    return JSONResponse(status_code=200, content=stored_workout(db, previous["id"]))
            check_log_day(body.started_at[:10])
            if body.template_id is not None and db.execute(
                    f"SELECT 1 FROM workout_templates WHERE id = ? AND ({template_sql(user)[0]})",
                    (body.template_id, *template_sql(user)[1])).fetchone() is None:
                raise HTTPException(422, "Template not found")
            cursor = db.execute(
                "INSERT INTO workouts(athlete_id, started_at, ended_at, title, template_id, notes, client_id) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)", (user["id"], body.started_at, body.ended_at, body.title,
                                                 body.template_id, body.notes, body.client_id))
            audit(db, user["id"], "workout_logged", cursor.lastrowid)
            publish(db, user, "workout", cursor.lastrowid, h.now_iso(), emit)
            return JSONResponse(status_code=201, content=stored_workout(db, cursor.lastrowid))

    @app.get("/api/workouts")
    def list_workouts(athlete_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                      from_: Annotated[str | None, Query(alias="from")] = None,
                      to: Annotated[str | None, Query()] = None,
                      limit: Annotated[int, Query(ge=1, le=500)] = 100,
                      offset: Offset = 0,
                      user=Depends(current_user), db=Depends(get_db)):
        athlete = target_athlete(db, user, athlete_id)
        return [workout_row(r) for r in db.execute(
            WORKOUTS + "WHERE workouts.athlete_id = ? AND (? IS NULL OR substr(started_at, 1, 10) >= ?) "
            "AND (? IS NULL OR substr(started_at, 1, 10) <= ?) GROUP BY workouts.id "
            "ORDER BY started_at DESC, workouts.id DESC LIMIT ? OFFSET ?",
            (athlete["id"], from_, from_, to, to, limit, offset))]

    def scoped_workout(db, user, workout_id):
        row = db.execute("SELECT athlete_id FROM workouts WHERE id = ?", (workout_id,)).fetchone()
        if row is None or (user["role"] == "athlete" and row["athlete_id"] != user["id"]):
            raise HTTPException(404, "Workout not found")
        h.athlete_access(db, user, row["athlete_id"])
        return row

    @app.get("/api/workouts/{workout_id}")
    def get_workout(workout_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        scoped_workout(db, user, workout_id)
        sets = [{**set_row(r), "exercise": r["name"]} for r in db.execute(
            "SELECT set_logs.*, exercises.name FROM set_logs JOIN exercises ON exercises.id = set_logs.exercise_id "
            "WHERE workout_id = ? ORDER BY set_logs.id", (workout_id,))]
        return {**stored_workout(db, workout_id), "set_logs": sets}

    @app.delete("/api/workouts/{workout_id}", status_code=204)
    def delete_workout(workout_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        """The workout goes; its logged sets stay as training history without a workout."""
        require_role(user, "athlete")
        scoped_workout(db, user, workout_id)
        db.execute("DELETE FROM workouts WHERE id = ?", (workout_id,))
        audit(db, user["id"], "workout_deleted", workout_id)
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
        # Session-RPE load from every training session of the day (SPEC 9); check-in pairs are sessions too.
        loads = [(r["day"], r["rpe"], r["minutes"]) for r in db.execute(
            "SELECT day, rpe, minutes FROM training_sessions WHERE athlete_id = ? AND day >= ?",
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
        """Foods (and recipes: same owner/club columns) a user may see and use."""
        if user["role"] == "admin":
            return "owner_id IS NULL", ()
        if user["role"] == "coach":
            return "owner_id IS NULL AND (club_id IS NULL OR club_id = ?)", (user["club_id"],)
        return "(club_id IS NULL AND owner_id IS NULL) OR (club_id = ? AND owner_id IS NULL) OR owner_id = ?", (
            user["club_id"], user["id"])

    def writable(row, user):
        """Own foods and recipes for athletes; club ones for their coach or any admin; never system foods."""
        return row is not None and row["club_id"] is not None and (
            row["owner_id"] == user["id"] if user["role"] == "athlete"
            else row["owner_id"] is None and (user["role"] == "admin" or row["club_id"] == user["club_id"]))

    def servings_of(food):
        """"100 g" first, then the stored portions (USDA, label serving size or recipe portions)."""
        stored = food["servings"]
        stored = json.loads(stored or "[]") if not isinstance(stored, list) else stored
        return [{"label": "100 g", "grams": 100.0}, *(s for s in stored if s["label"] != "100 g")]

    def food_row(row):
        food = dict(row)
        if "servings" in food:
            food["servings"] = servings_of(food)
            food["nutrients"] = json.loads(food["nutrients"] or "{}")
        return food

    def scoped_food(db, user, food_id):
        row = db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone()
        if not writable(row, user):
            raise HTTPException(404, "Food not found")
        return row

    def correctable_food(db, user, food_id):
        """PATCH scope (SPEC 7.4): as for delete, and also the athlete who scanned or created a club food until
        another athlete has logged it. Other users who can see the club food get 403."""
        row = db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone()
        if writable(row, user):
            return row
        clause, params = visible_food_sql(user)
        if row is None or row["club_id"] is None or not db.execute(
                f"SELECT 1 FROM foods WHERE id = ? AND ({clause})", (food_id, *params)).fetchone():
            raise HTTPException(404, "Food not found")
        if user["role"] == "athlete" and row["owner_id"] is None and row["created_by"] == user["id"] and not db.execute(
                "SELECT 1 FROM food_logs WHERE food_id = ? AND athlete_id != ? LIMIT 1", (food_id, user["id"])).fetchone():
            return row
        raise HTTPException(403, "Only staff, or the athlete who added this food before others logged it, can correct it")

    def insert_food(db, club_id, owner_id, food, created_by=None):
        try:
            cursor = db.execute(
                "INSERT INTO foods(club_id, owner_id, name_bg, name_en, kcal, protein_g, carbs_g, fat_g, fibre_g, "
                "brand, barcode, servings, nutrients, source, source_url, catalog_id, created_by) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (club_id, owner_id, food["name_bg"], food.get("name_en", ""), food["kcal"], food["protein_g"],
                 food["carbs_g"], food["fat_g"], food.get("fibre_g", 0), food.get("brand", ""), food.get("barcode"),
                 json.dumps(food.get("servings", [])), json.dumps(food.get("nutrients", {})),
                 food.get("source", "custom"), food.get("source_url", ""), food.get("catalog_id"), created_by))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "A food with this barcode already exists here") from None
        return cursor.lastrowid

    def resolve_food(db, user, food_id, catalog_id, write):
        """A visible food for an entry or recipe item. A catalogue food is materialised once as a system food
        (idempotent by catalog_id) when writing; a validation-only pass returns the catalogue record."""
        if catalog_id is not None:
            row = db.execute("SELECT * FROM foods WHERE catalog_id = ?", (catalog_id,)).fetchone()
            if row is not None:
                return row
            food = fs.catalog_get(catalog_id)
            if food is None:
                raise HTTPException(422, "Unknown catalogue food")
            if not write:
                return food
            return db.execute("SELECT * FROM foods WHERE id = ?", (insert_food(db, None, None, food),)).fetchone()
        clause, params = visible_food_sql(user)
        row = db.execute(f"SELECT * FROM foods WHERE id = ? AND ({clause})", (food_id, *params)).fetchone()
        if row is None:
            raise HTTPException(422, "Unknown food")
        return row

    @app.get("/api/foods")
    def list_foods(q: Annotated[str | None, Query(max_length=100)] = None,
                   limit: Annotated[int, Query(ge=1, le=500)] = 50,
                   offset: Offset = 0,
                   user=Depends(current_user), db=Depends(get_db)):
        """Existing foods first, then (for a search with room left on the page) catalogue foods not yet here.

        A search matches every query word against the start of a word in either name, ranks foods named by the query
        first, and when nothing matches it searches the closest known spelling of each unknown word (SPEC 10.4).
        """
        clause, params = visible_food_sql(user)
        if not q:
            return [food_row(r) for r in db.execute(f"SELECT * FROM foods WHERE ({clause}) ORDER BY name_bg, id "
                                                    "LIMIT ? OFFSET ?", (*params, limit, offset))]
        visible = db.execute(f"SELECT * FROM foods WHERE ({clause}) ORDER BY name_bg, id", params).fetchall()
        found, hits = {}, {}

        def search(query):
            tokens = fs.tokens_of(query)[:10]
            if tokens:
                for *_, r in sorted((fs.name_rank([r["name_bg"], r["name_en"]], tokens), index, r)
                                    for index, r in enumerate(visible) if fs.matches([r["name_bg"], r["name_en"]], tokens)):
                    found.setdefault(r["id"], r)
                for hit in fs.catalog_search(query, 50):
                    hits.setdefault(hit["catalog_id"], hit)

        search(q)
        if not found and not hits and (fixed := fs.correct(q)):
            search(fixed)
        found = [food_row(r) for r in list(found.values())[offset:offset + limit]]
        if len(found) < limit and hits:
            hits = list(hits.values())[:limit - len(found)]
            here = {r[0] for r in db.execute(
                f"SELECT catalog_id FROM foods WHERE catalog_id IN ({','.join('?' * len(hits))})",
                [h["catalog_id"] for h in hits])} | {r[0] for r in db.execute(
                f"SELECT 'fdc:' || fdc_id FROM foods WHERE fdc_id IN ({','.join('?' * len(hits))})",
                [h["fdc_id"] for h in hits])}
            found += [{**h, "servings": servings_of(h)} for h in hits
                      if h["catalog_id"] not in here and f'fdc:{h["fdc_id"]}' not in here]
        return found

    @app.get("/api/foods/recent")
    def recent_foods(meal: Literal["breakfast", "lunch", "dinner", "snack"] | None = None,
                     athlete_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                     user=Depends(current_user), db=Depends(get_db)):
        """Up to 20 foods for one-tap logging: the 10 most recent, then the most frequent (last 90 days)."""
        athlete = target_athlete(db, user, athlete_id)
        since = (utc_today() - timedelta(days=90)).isoformat()
        usage = ("SELECT foods.*, COUNT(*) AS uses, MAX(food_logs.day || food_logs.created_at) AS last_used "
                 "FROM food_logs JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? "
                 "AND food_logs.day >= ? AND (? IS NULL OR food_logs.meal = ?) GROUP BY foods.id ")
        args = (athlete["id"], since, meal, meal)
        rows = {r["id"]: r for r in db.execute(usage + "ORDER BY last_used DESC, foods.id DESC LIMIT 10", args)}
        for r in db.execute(usage + "ORDER BY uses DESC, last_used DESC, foods.id DESC LIMIT 20", args):
            if len(rows) < 20:
                rows.setdefault(r["id"], r)
        result = []
        for r in rows.values():
            last = db.execute("SELECT grams, serving_label, quantity FROM food_logs WHERE athlete_id = ? AND food_id = ? "
                              "ORDER BY day DESC, id DESC LIMIT 1", (athlete["id"], r["id"])).fetchone()
            food = food_row(r)
            del food["last_used"]
            result.append({**food, "last_grams": last["grams"], "last_serving_label": last["serving_label"],
                           "last_quantity": last["quantity"]})
        return result

    @app.get("/api/foods/barcode/{code}")
    def barcode_food(code: str, request: Request, user=Depends(current_user), db=Depends(get_db)):
        """A local food with this barcode first; else Open Food Facts, saved as a club food (data under the ODbL)."""
        if not re.fullmatch(r"[0-9]{8,14}", code):
            raise HTTPException(422, "A barcode has 8 to 14 digits")
        clause, params = visible_food_sql(user)
        local = f"SELECT * FROM foods WHERE barcode = ? AND ({clause}) ORDER BY owner_id IS NULL, club_id IS NULL, id"
        if (row := db.execute(local, (code, *params)).fetchone()) is not None:
            return food_row(row)
        if user["role"] == "athlete":
            need_consent(user, "foods")
        try:
            food = fs.open_food_facts(code)  # outside the write transaction: no lock held during the network call
        except fs.Unavailable:
            raise HTTPException(503, "The product database cannot be reached; nothing was saved. "
                                     "Try again or enter the food manually.") from None
        if food is None:
            raise HTTPException(404, "Barcode not found. Create the food with this barcode.")
        with h.authorized_write(db, request) as (user, _):
            if (row := db.execute(local, (code, *params)).fetchone()) is not None:
                return food_row(row)
            food_id = insert_food(db, user["club_id"], None, food, user["id"])
            audit(db, user["id"], "food_scanned", food_id)
            return JSONResponse(status_code=201, content=food_row(
                db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone()))

    @app.get("/api/nutrient-definitions")
    def nutrient_definitions(user=Depends(current_user)):
        return fs.DEFINITIONS

    @app.post("/api/foods", status_code=201, dependencies=[Depends(current_user)])
    def add_food(body: FoodV2, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, _):
            if user["role"] == "athlete":
                need_consent(user, "foods")
                club_id, owner_id = user["club_id"], user["id"]
                if body.club_id is not None:
                    raise HTTPException(422, "club_id applies to staff only")
            else:
                club_id, owner_id = staff_club(db, user, body.club_id), None
            food_id = insert_food(db, club_id, owner_id, body.model_dump(), user["id"])
            audit(db, user["id"], "food_created", food_id)
            return food_row(db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone())

    @app.patch("/api/foods/{food_id}", dependencies=[Depends(current_user)])
    def update_food(food_id: SqlitePath, body: FoodPatch, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, _):
            row = correctable_food(db, user, food_id)
            if user["role"] == "athlete":
                need_consent(user, "foods")
            if row["source"] == "recipe":
                raise HTTPException(422, "Edit the recipe instead")
            changes = body.model_dump(include=body.model_fields_set)
            if any(value is None for key, value in changes.items() if key not in ("barcode", "servings")):
                raise HTTPException(422, "Only barcode and servings can be cleared")
            if "servings" in changes:
                changes["servings"] = json.dumps(changes["servings"] or [])
            if changes:
                try:
                    db.execute(f"UPDATE foods SET {', '.join(f'{k} = ?' for k in changes)} WHERE id = ?",
                               (*changes.values(), food_id))
                except sqlite3.IntegrityError:
                    raise HTTPException(409, "A food with this barcode already exists here") from None
            audit(db, user["id"], "food_updated", food_id, {"fields": sorted(changes)})
            return food_row(db.execute("SELECT * FROM foods WHERE id = ?", (food_id,)).fetchone())

    @app.delete("/api/foods/{food_id}", status_code=204)
    def delete_food(food_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        scoped_food(db, user, food_id)
        try:
            db.execute("DELETE FROM foods WHERE id = ?", (food_id,))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "The food is used in logged meals or recipes") from None
        audit(db, user["id"], "food_deleted", food_id)
        db.commit()

    # --- food logs ---

    def iter_food_logs(db, athlete_id, where="", params=(), tail="", tail_params=(), *, oldest_first=False):
        order = " ORDER BY food_logs.day, food_logs.id" if oldest_first else " ORDER BY food_logs.day DESC, food_logs.id DESC"
        rows = db.execute(
            "SELECT food_logs.*, foods.name_bg, foods.name_en, foods.kcal AS f_kcal, foods.protein_g AS f_protein_g, "
            "foods.carbs_g AS f_carbs_g, foods.fat_g AS f_fat_g, foods.fibre_g AS f_fibre_g "
            "FROM food_logs LEFT JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? "
            + where + order + tail, (athlete_id, *params, *tail_params))
        for r in rows:
            if r["food_id"] is None:  # quick add: the values typed, never derived
                macros = {"kcal": r["kcal"], "protein_g": r["protein_g"], "carbs_g": r["carbs_g"],
                          "fat_g": r["fat_g"], "fibre_g": 0.0}
            else:
                macros = food_macros({"kcal": r["f_kcal"], "protein_g": r["f_protein_g"], "carbs_g": r["f_carbs_g"],
                                      "fat_g": r["f_fat_g"], "fibre_g": r["f_fibre_g"]}, r["grams"])
            yield {"id": r["id"], "athlete_id": r["athlete_id"], "day": r["day"], "meal": r["meal"],
                   "food_id": r["food_id"], "name_bg": r["name_bg"] if r["food_id"] else r["label"],
                   "name_en": r["name_en"] or "", "grams": r["grams"], "client_id": r["client_id"],
                   "created_at": r["created_at"], "serving_label": r["serving_label"], "quantity": r["quantity"],
                   "label": r["label"], **macros}

    def food_log_rows(*args, **kwargs):
        return list(iter_food_logs(*args, **kwargs))

    def log_row(db, athlete_id, log_id):
        return food_log_rows(db, athlete_id, "AND food_logs.id = ?", (log_id,))[0]

    def serving_grams(food, serving_label, quantity, grams):
        """grams stays the stored truth: sent grams win, else the serving's grams times the quantity."""
        if serving_label is None:
            return grams
        serving = next((s for s in servings_of(food) if s["label"] == serving_label), None)
        if serving is None:
            raise HTTPException(422, "Unknown serving for this food")
        grams = grams if grams is not None else round(serving["grams"] * (quantity or 1), 1)
        if not 0 < grams <= 5000:
            raise HTTPException(422, "grams must be above 0 and at most 5000")
        return grams

    @app.post("/api/food-logs", status_code=201, dependencies=[Depends(current_user)])
    def add_food_log(body: FoodEntry, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            return log_food(body, user, db, emit)

    def log_food(body: FoodLog, user, db, emit):
        result = save_food_log(body, user, db, emit)
        return None if result is None else JSONResponse(status_code=result[0], content=result[1])

    def save_food_log(body, user, db, emit):
        """Validate (emit None) or write one entry; (status, row) with 200 for a client_id replay."""
        require_role(user, "athlete")
        need_consent(user, "meals")
        entry = body if isinstance(body, FoodEntry) else FoodEntry.model_validate(body.model_dump())
        if emit is not None and entry.client_id is not None:
            previous = db.execute("SELECT id FROM food_logs WHERE athlete_id = ? AND client_id = ?",
                                  (user["id"], entry.client_id)).fetchone()
            if previous is not None:
                return 200, log_row(db, user["id"], previous["id"])
        check_log_day(entry.day)
        quick = entry.food_id is None and entry.catalog_id is None
        food = None if quick else resolve_food(db, user, entry.food_id, entry.catalog_id, emit is not None)
        grams = None if quick else serving_grams(food, entry.serving_label, entry.quantity, entry.grams)
        if emit is None:
            return None
        created_at = h.now_iso()
        cursor = db.execute(
            "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at, serving_label, quantity, "
            "label, kcal, protein_g, carbs_g, fat_g) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user["id"], entry.day, entry.meal, None if quick else food["id"], grams, entry.client_id, created_at,
             entry.serving_label, None if entry.serving_label is None else (entry.quantity or 1),
             entry.label, entry.kcal, *(None if not quick else (v or 0) for v in (entry.protein_g, entry.carbs_g, entry.fat_g))))
        audit(db, user["id"], "food_logged", cursor.lastrowid)
        publish(db, user, "food", cursor.lastrowid, created_at, emit)
        return 201, log_row(db, user["id"], cursor.lastrowid)

    @app.post("/api/food-logs/batch", status_code=201, dependencies=[Depends(current_user)])
    def batch_food_logs(body: FoodLogBatch, request: Request, db=Depends(get_db)):
        """Up to 50 entries in one transaction: all are logged or, if any fails, none."""
        with h.authorized_write(db, request) as (user, emit):
            rows = []
            for number, entry in enumerate(body.entries, 1):
                try:
                    rows.append(save_food_log(entry, user, db, emit)[1])
                except HTTPException as error:
                    raise HTTPException(error.status_code, f"Entry {number}: {error.detail}") from None
            audit(db, user["id"], "food_logs_batch", user["id"], {"count": len(rows)})
            return rows

    @app.post("/api/food-logs/copy", status_code=201, dependencies=[Depends(current_user)])
    def copy_food_logs(body: FoodLogCopy, request: Request, db=Depends(get_db)):
        """Copy a day (or one meal) to another day (or meal); a repeated client_id returns the first copy."""
        with h.authorized_write(db, request) as (user, emit):
            require_role(user, "athlete")
            need_consent(user, "meals")
            if body.client_id is not None:
                done = [r["id"] for r in db.execute(
                    "SELECT id FROM food_logs WHERE athlete_id = ? AND client_id >= ? AND client_id < ? ORDER BY id",
                    (user["id"], body.client_id + ":", body.client_id + ";"))]
                if done:
                    return JSONResponse(status_code=200, content=[log_row(db, user["id"], i) for i in done])
            check_log_day(body.to_day)
            sources = [r["id"] for r in db.execute(
                "SELECT id FROM food_logs WHERE athlete_id = ? AND day = ? AND (? IS NULL OR meal = ?) ORDER BY id",
                (user["id"], body.from_day, body.from_meal, body.from_meal))]
            if not sources:
                return JSONResponse(status_code=422, content={"detail": "Nothing to copy from that day",
                                                              "code": "nothing_to_copy"})
            created_at = h.now_iso()
            copied = [db.execute(
                "INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, client_id, created_at, serving_label, "
                "quantity, label, kcal, protein_g, carbs_g, fat_g) SELECT athlete_id, ?, COALESCE(?, meal), food_id, "
                "grams, ?, ?, serving_label, quantity, label, kcal, protein_g, carbs_g, fat_g FROM food_logs WHERE id = ?",
                (body.to_day, body.to_meal, None if body.client_id is None else f"{body.client_id}:{n}", created_at,
                 source)).lastrowid for n, source in enumerate(sources)]
            audit(db, user["id"], "food_logs_copied", user["id"], {"count": len(copied)})
            publish(db, user, "food", copied[0], created_at, emit)
            return [log_row(db, user["id"], i) for i in copied]

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

    @app.patch("/api/food-logs/{log_id}", dependencies=[Depends(current_user)])
    def update_food_log(log_id: SqlitePath, body: FoodLogPatch, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            require_role(user, "athlete")
            row = db.execute("SELECT * FROM food_logs WHERE id = ? AND athlete_id = ?", (log_id, user["id"])).fetchone()
            if row is None:
                raise HTTPException(404, "Meal entry not found")
            sent = body.model_fields_set
            changes = {key: getattr(body, key) for key in sent & {"day", "meal"}}
            if None in changes.values():
                raise HTTPException(422, "day and meal cannot be cleared")
            if "day" in changes:
                check_log_day(changes["day"])
            if row["food_id"] is None:
                if sent & {"grams", "serving_label", "quantity"}:
                    raise HTTPException(422, "A quick add has no grams or serving; edit its label, kcal or macros")
                changes |= {key: getattr(body, key) for key in sent & {"label", "kcal", "protein_g", "carbs_g", "fat_g"}}
                if any(changes.get(key, 0) is None for key in ("label", "kcal", "protein_g", "carbs_g", "fat_g")):
                    raise HTTPException(422, "Quick add values cannot be cleared")
            else:
                if sent & {"label", "kcal", "protein_g", "carbs_g", "fat_g"}:
                    raise HTTPException(422, "Food entries take grams or a serving, not typed values")
                food = db.execute("SELECT * FROM foods WHERE id = ?", (row["food_id"],)).fetchone()
                if sent & {"serving_label", "quantity"}:
                    label = body.serving_label if "serving_label" in sent else row["serving_label"]
                    if label is None:
                        raise HTTPException(422, "Choose a serving_label")
                    quantity = (body.quantity if "quantity" in sent else row["quantity"]) or 1
                    changes |= {"serving_label": label, "quantity": quantity,
                                "grams": serving_grams(food, label, quantity, body.grams)}
                elif "grams" in sent:
                    if body.grams is None:
                        raise HTTPException(422, "grams cannot be cleared")
                    changes |= {"grams": body.grams, "serving_label": None, "quantity": None}
            if changes:
                db.execute(f"UPDATE food_logs SET {', '.join(f'{k} = ?' for k in changes)} WHERE id = ?",
                           (*changes.values(), log_id))
            audit(db, user["id"], "food_logged", log_id, {"updated": sorted(changes)})
            publish(db, user, "food", log_id, h.now_iso(), emit)
            return log_row(db, user["id"], log_id)

    @app.delete("/api/food-logs/{log_id}", status_code=204)
    def delete_food_log(log_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if db.execute("DELETE FROM food_logs WHERE id = ? AND athlete_id = ?", (log_id, user["id"])).rowcount == 0:
            raise HTTPException(404, "Meal entry not found")
        audit(db, user["id"], "food_deleted_log", log_id)
        db.commit()

    # --- recipes: a recipe is backed by a food (source "recipe") so it is logged like any food ---

    def recipe_values(db, user, body, write, own_food=None):
        """Per-100 g values of the backing food from the items; a nutrient is known only if every item knows it."""
        items = [(resolve_food(db, user, item.food_id, item.catalog_id, write), item.grams) for item in body.items]
        if own_food is not None and any(food["id"] == own_food for food, _ in items):
            raise HTTPException(422, "A recipe cannot contain itself")
        total = sum(grams for _, grams in items)
        if total > 50000:
            raise HTTPException(422, "A recipe can weigh at most 50 kg")
        per100 = {key: round(sum(food[key] * grams for food, grams in items) / total, 3)
                  for key in ("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")}
        micro = [json.loads(food["nutrients"] or "{}") for food, _ in items]
        known = set.intersection(*(set(m) for m in micro))
        nutrients = {key: sum(m[key] * grams for m, (_, grams) in zip(micro, items)) / total for key in sorted(known)}
        servings = recipe_servings(total, body.servings_count)
        return {"name_bg": body.name, **per100, "nutrients": nutrients, "servings": servings, "source": "recipe"}, [
            (food["id"], grams) for food, grams in items]

    def write_recipe_items(db, recipe_id, items):
        db.execute("DELETE FROM recipe_items WHERE recipe_id = ?", (recipe_id,))
        db.executemany("INSERT INTO recipe_items(recipe_id, position, food_id, grams) VALUES (?, ?, ?, ?)",
                       [(recipe_id, n, food_id, grams) for n, (food_id, grams) in enumerate(items)])

    def recipe_json(db, recipe):
        food = food_row(db.execute("SELECT * FROM foods WHERE id = ?", (recipe["food_id"],)).fetchone())
        items = [{"food_id": r["food_id"], "name_bg": r["name_bg"], "grams": r["grams"],
                  **food_macros(r, r["grams"])} for r in db.execute(
            "SELECT recipe_items.food_id, recipe_items.grams, foods.name_bg, foods.kcal, foods.protein_g, "
            "foods.carbs_g, foods.fat_g, foods.fibre_g FROM recipe_items JOIN foods ON foods.id = recipe_items.food_id "
            "WHERE recipe_id = ? ORDER BY position", (recipe["id"],))]
        serving = food["servings"][1]["grams"]
        return {**dict(recipe), "total_grams": food["servings"][2]["grams"], "serving_grams": serving,
                "per_serving": food_macros(food, serving), "per_100g": food_macros(food, 100),
                "nutrients_per_100g": food["nutrients"], "items": items}

    def scoped_recipe(db, user, recipe_id, write=False):
        row = db.execute("SELECT * FROM recipes WHERE id = ?", (recipe_id,)).fetchone()
        clause, params = visible_food_sql(user)
        visible = row is not None and db.execute(f"SELECT 1 FROM recipes WHERE id = ? AND ({clause})",
                                                 (recipe_id, *params)).fetchone()
        if not visible or (write and not writable(row, user)):
            raise HTTPException(404, "Recipe not found")
        return row

    @app.get("/api/recipes")
    def list_recipes(user=Depends(current_user), db=Depends(get_db)):
        clause, params = visible_food_sql(user)
        return [recipe_json(db, r) for r in db.execute(
            f"SELECT * FROM recipes WHERE {clause} ORDER BY name, id", params)]

    @app.get("/api/recipes/{recipe_id}")
    def get_recipe(recipe_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        return recipe_json(db, scoped_recipe(db, user, recipe_id))

    @app.post("/api/recipes", status_code=201, dependencies=[Depends(current_user)])
    def add_recipe(body: RecipeBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            if user["role"] == "athlete":
                need_consent(user, "recipes")
                if body.club_id is not None:
                    raise HTTPException(422, "club_id applies to staff only")
                club_id, owner_id = user["club_id"], user["id"]
            else:
                club_id, owner_id = staff_club(db, user, body.club_id), None
            food, items = recipe_values(db, user, body, True)
            food_id = insert_food(db, club_id, owner_id, food, user["id"])
            cursor = db.execute("INSERT INTO recipes(owner_id, club_id, name, servings_count, food_id) VALUES (?, ?, ?, ?, ?)",
                                (owner_id, club_id, body.name, body.servings_count, food_id))
            write_recipe_items(db, cursor.lastrowid, items)
            audit(db, user["id"], "recipe_created", cursor.lastrowid)
            return recipe_json(db, db.execute("SELECT * FROM recipes WHERE id = ?", (cursor.lastrowid,)).fetchone())

    @app.put("/api/recipes/{recipe_id}", dependencies=[Depends(current_user)])
    def replace_recipe(recipe_id: SqlitePath, body: RecipeBody, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (user, emit):
            recipe = scoped_recipe(db, user, recipe_id, write=True)
            if user["role"] == "athlete":
                need_consent(user, "recipes")
            if body.club_id is not None and body.club_id != recipe["club_id"]:
                raise HTTPException(422, "A recipe cannot move to another club")
            food, items = recipe_values(db, user, body, True, recipe["food_id"])
            db.execute("UPDATE foods SET name_bg = ?, kcal = ?, protein_g = ?, carbs_g = ?, fat_g = ?, fibre_g = ?, "
                       "nutrients = ?, servings = ? WHERE id = ?",
                       (food["name_bg"], food["kcal"], food["protein_g"], food["carbs_g"], food["fat_g"],
                        food["fibre_g"], json.dumps(food["nutrients"]), json.dumps(food["servings"]), recipe["food_id"]))
            db.execute("UPDATE recipes SET name = ?, servings_count = ? WHERE id = ?",
                       (body.name, body.servings_count, recipe_id))
            write_recipe_items(db, recipe_id, items)
            audit(db, user["id"], "recipe_updated", recipe_id)
            return recipe_json(db, db.execute("SELECT * FROM recipes WHERE id = ?", (recipe_id,)).fetchone())

    @app.delete("/api/recipes/{recipe_id}", status_code=204)
    def delete_recipe(recipe_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        """The recipe goes; its food stays only if logged meals or other recipes still use it."""
        recipe = scoped_recipe(db, user, recipe_id, write=True)
        db.execute("DELETE FROM recipes WHERE id = ?", (recipe_id,))
        db.execute("DELETE FROM foods WHERE id = ? AND NOT EXISTS (SELECT 1 FROM food_logs WHERE food_id = foods.id) "
                   "AND NOT EXISTS (SELECT 1 FROM recipe_items WHERE food_id = foods.id)", (recipe["food_id"],))
        audit(db, user["id"], "recipe_deleted", recipe_id)
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
                f"SELECT food_logs.day, SUM({LOG_KCAL}) AS kcal FROM food_logs "
                "LEFT JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? AND food_logs.day >= ? "
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
        # Micronutrients of the day: only entries whose food knows a nutrient count; unknown is null, never zero.
        known = [(r["grams"], json.loads(r["nutrients"] or "{}")) for r in db.execute(
            "SELECT food_logs.grams, foods.nutrients FROM food_logs LEFT JOIN foods ON foods.id = food_logs.food_id "
            "WHERE food_logs.athlete_id = ? AND food_logs.day = ?", (athlete_id, day))]
        nutrients = []
        for definition in fs.DEFINITIONS:
            amounts = [values[definition["key"]] * grams / 100 for grams, values in known if definition["key"] in values]
            nutrients.append({**definition, "amount": round(sum(amounts), 3) if amounts else None,
                              "known_entries": len(amounts), "total_entries": len(known)})
        return {"athlete_id": athlete_id, "day": day,
                "totals": {key: round(value, 1) for key, value in totals.items()},
                "by_meal_kcal": {meal: round(value, 1) for meal, value in by_meal.items()},
                "target": None if target is None else dict(target),
                "remaining": None if target is None else {
                    key: None if target[key] is None else round(target[key] - totals[key], 1)
                    for key in ("kcal", "protein_g", "carbs_g", "fat_g")},
                "weekly_average": {"days_logged": days, "window": [first, day], **{
                    key: round(sum(w[key] for w in weekly.values()) / days, 1) if days else None for key in keys}},
                "nutrients": nutrients}

    # --- adaptive expenditure and the weekly coaching program (SPEC 6.3) ---

    def diary_fingerprint(db, athlete_id, day):
        """Stable hash of a day's diary, so a day confirmed complete is noticed when it is later edited."""
        return fingerprint_rows(db.execute(
            "SELECT id, meal, food_id, grams, kcal AS quick_kcal FROM food_logs WHERE athlete_id = ? AND day = ? ORDER BY id",
            (athlete_id, day)))

    def fingerprint_rows(rows):
        # A quick add (no food) adds its kcal, so editing it is noticed; food entries hash as before version 2.
        values = [[r["id"], r["meal"], r["food_id"], r["grams"]] + ([] if r["food_id"] else [r["quick_kcal"]])
                  for r in rows if r["id"] is not None]
        return hashlib.blake2b(json.dumps(values, separators=(",", ":")).encode("utf-8")).hexdigest()

    def day_calories(db, athlete_id, day):
        return db.execute(
            f"SELECT COALESCE(SUM({LOG_KCAL}), 0.0) AS kcal FROM food_logs "
            "LEFT JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? AND food_logs.day = ?",
            (athlete_id, day)).fetchone()["kcal"]

    def coaching_snaps(db, athlete_id):
        """Explicitly confirmed diary days: complete unless edited since confirmation (then not counted)."""
        rows = db.execute(
            "SELECT complete.day, complete.diary_fingerprint, food_logs.id, food_logs.meal, food_logs.food_id, "
            f"food_logs.grams, food_logs.kcal AS quick_kcal, SUM({LOG_KCAL}) OVER (PARTITION BY complete.day "
            "ORDER BY food_logs.id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS calories "
            "FROM nutrition_day_complete AS complete LEFT JOIN food_logs "
            "ON food_logs.athlete_id = complete.athlete_id AND food_logs.day = complete.day "
            "LEFT JOIN foods ON foods.id = food_logs.food_id WHERE complete.athlete_id = ? "
            "ORDER BY complete.day, food_logs.id", (athlete_id,))
        for day, group in groupby(rows, key=lambda row: row["day"]):
            diary = list(group)
            yield day, diary[0]["calories"] or 0.0, fingerprint_rows(diary) != diary[0]["diary_fingerprint"]

    def program_row(db, athlete_id):
        return db.execute("SELECT * FROM coaching_programs WHERE athlete_id = ?", (athlete_id,)).fetchone()

    def expenditure_series(db, athlete, program):
        athlete_id = athlete["id"]
        weights = tuple(tuple(row) for row in db.execute(
            "SELECT day, kg FROM weights WHERE athlete_id = ? ORDER BY day", (athlete_id,)))
        snaps = tuple(coaching_snaps(db, athlete_id))
        compute = cached_expenditure if len(weights) + len(snaps) <= 4096 else cached_expenditure.__wrapped__
        return compute(athlete_id, program["effective_date"], program["seed_kcal"], program["mode"],
                       snaps, weights, utc_today().isoformat())

    @lru_cache(maxsize=16)
    def cached_expenditure(athlete_id, effective_date, seed_kcal, mode, snaps, weights, today):
        return co.compute(effective_date, seed_kcal, mode,
                          {day: {"status": "complete", "calories": calories, "changed": changed}
                           for day, calories, changed in snaps}, dict(weights), today)

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

    # --- goals and phases, analytics, AI meal entry (SPEC 7.1) ---

    def current_trend(db, athlete_id):
        weights, trend = weight_series(db, athlete_id)
        return trend, (trend[-1]["trend_kg"] if trend else (weights[-1][1] if weights else None))

    def goal_view(db, athlete):
        program = program_row(db, athlete["id"])
        if program is None:
            return {"athlete_id": athlete["id"], "goal": None, "reason": "No goal yet. Set a phase to start."}
        trend, current = current_trend(db, athlete["id"])
        target, end = program["target_weight_kg"], program["end_date"]
        remaining = None if target is None or current is None else round(target - current, 2)
        # Projected date from the trend's own movement over the last 14 days, only when it heads to the target.
        recent = trend[-15:]
        per_day = (recent[-1]["trend_kg"] - recent[0]["trend_kg"]) / (len(recent) - 1) if len(recent) >= 8 else None
        projected = None
        if remaining and per_day and remaining * per_day > 0:
            projected = (utc_today() + timedelta(days=min(3650, round(remaining / per_day)))).isoformat()
        return {"athlete_id": athlete["id"],
                "goal": {"phase": program["goal"], "target_weight_kg": target, "end_date": end,
                         "rate_kg_week": program["rate_kg_week"], "applied": bool(program["applied"])},
                "current_trend_kg": None if current is None else round(current, 2), "remaining_kg": remaining,
                "trend_kg_week": None if per_day is None else round(per_day * 7, 2), "projected_date": projected,
                "on_track": None if projected is None or end is None else projected <= end,
                "note": "Progress from the weight trend. Numbers to discuss with your coach."}

    @app.get("/api/athletes/{athlete_id}/nutrition/goal")
    def get_goal(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        return goal_view(db, h.athlete_access(db, user, athlete_id))

    @app.put("/api/athletes/{athlete_id}/nutrition/goal", dependencies=[Depends(current_user)])
    def set_goal(athlete_id: SqlitePath, body: GoalBody, request: Request, db=Depends(get_db)):
        """A phase with a target weight and end date gives the weekly rate; the program and its guardrails do the rest."""
        with h.authorized_write(db, request) as (user, emit):
            athlete = h.athlete_access(db, user, athlete_id)
            need_consent(athlete, "a nutrition goal")
            rate = 0.0
            if body.phase != "maintain":
                _, current = current_trend(db, athlete_id)
                if current is None:
                    raise HTTPException(422, "Log a weigh-in first: the rate comes from the weight trend")
                weeks = (date.fromisoformat(body.end_date) - utc_today()).days / 7
                change = body.target_weight_kg - current
                if weeks < 1:
                    raise HTTPException(422, "The end date must be at least a week ahead")
                if (change < 0) != (body.phase == "lose") or change == 0:
                    raise HTTPException(422, f"For {body.phase} the target weight must be "
                                             f"{'below' if body.phase == 'lose' else 'above'} the current trend")
                rate = max(0.01, round(abs(change) / weeks, 2))
                if rate > 2:
                    raise HTTPException(422, "That is more than 2 kg a week: choose a later end date")
            save_program(athlete_id, Program(goal=body.phase, rate_kg_week=rate, protein_g=body.protein_g,
                                             fat_g=body.fat_g), user, db, emit)
            db.execute("UPDATE coaching_programs SET target_weight_kg = ?, end_date = ? WHERE athlete_id = ?",
                       (body.target_weight_kg, body.end_date, athlete_id))
            audit(db, user["id"], "goal_set", athlete_id, {"phase": body.phase, "rate_kg_week": rate})
            return goal_view(db, athlete)

    @app.get("/api/athletes/{athlete_id}/nutrition/analytics")
    def nutrition_analytics(athlete_id: SqlitePath, days: Annotated[int, Query()] = 30,
                            user=Depends(current_user), db=Depends(get_db)):
        athlete = h.athlete_access(db, user, athlete_id)
        if days not in (7, 30, 90):
            raise HTTPException(422, "days must be 7, 30 or 90")
        end = utc_today()
        start = (end - timedelta(days=days - 1)).isoformat()
        logged = {r["day"]: r for r in db.execute(
            f"SELECT food_logs.day, COUNT(*) AS entries, SUM({LOG_KCAL}) AS kcal, " + ", ".join(
                f"SUM(COALESCE(food_logs.grams * foods.{k} / 100, food_logs.{k})) AS {k}"
                for k in ("protein_g", "carbs_g", "fat_g")) +
            " FROM food_logs LEFT JOIN foods ON foods.id = food_logs.food_id WHERE food_logs.athlete_id = ? "
            "AND food_logs.day >= ? AND food_logs.day <= ? GROUP BY food_logs.day", (athlete_id, start, end.isoformat()))}
        keys = ("kcal", "protein_g", "carbs_g", "fat_g")
        daily = []
        for n in range(days):
            day = (end - timedelta(days=days - 1 - n)).isoformat()
            row = logged.get(day)  # a day with nothing logged is missing, not zero
            daily.append({"day": day, "entries": row["entries"] if row else 0,
                          **{k: round(row[k], 1) if row else None for k in keys}})
        target = db.execute("SELECT kcal FROM nutrition_targets WHERE athlete_id = ?", (athlete_id,)).fetchone()
        within = sum(1 for r in logged.values() if target and abs(r["kcal"] - target["kcal"]) <= target["kcal"] * 0.1)
        weights, trend = weight_series(db, athlete_id)
        program = program_row(db, athlete_id)
        history = expenditure_series(db, athlete, program)["history"] if program else []
        return {"athlete_id": athlete_id, "days": days, "start": start, "end": end.isoformat(), "daily": daily,
                "averages": {"logged_days": len(logged), **{
                    k: round(sum(r[k] for r in logged.values()) / len(logged), 1) if logged else None for k in keys}},
                "target_kcal": target["kcal"] if target else None,
                "adherence": None if target is None else {
                    "band_pct": 10, "days_within": within, "logged_days": len(logged),
                    "pct": round(100 * within / len(logged)) if logged else None},
                "weights": [{"day": d, "kg": kg} for d, kg in weights if d >= start],
                "trend": [{"day": t["day"], "trend_kg": round(t["trend_kg"], 2)} for t in trend if t["day"] >= start],
                "expenditure": [{"day": e["date"], "kcal": round(e["value_kcal"]), "status": e["status"]}
                                for e in history if e["date"] >= start and e["value_kcal"] is not None]}

    @app.post("/api/me/nutrition/ai-parse", dependencies=[Depends(current_user)])
    def ai_parse(body: AiParse, user=Depends(current_user), db=Depends(get_db)):
        """Free text to a proposal of entries for /api/food-logs/batch; nothing is logged here."""
        require_role(user, "athlete")
        url, model, headers = h.assistant_api.model_client()  # 503 when the local model is not configured
        if not app.state.heavy_slots.acquire(blocking=False):
            raise HTTPException(503, h.BUSY_DETAIL, headers=h.BUSY_HEADERS)
        try:
            parsed = fs.ask_model(url, model, headers, body.text)
        except fs.Unavailable:
            raise HTTPException(503, "The meal model is unavailable. Enter the food manually.") from None
        finally:
            app.state.heavy_slots.release()
        clause, params = visible_food_sql(user)
        day, items = body.day or utc_today().isoformat(), []
        for food in parsed:
            like = "%" + food["name"].casefold().replace("\\", "").replace("%", "").replace("_", "") + "%"
            local = db.execute(f"SELECT * FROM foods WHERE ({clause}) AND (casefold(name_en) LIKE ? OR casefold(name_bg) "
                               "LIKE ?) ORDER BY length(name_en), id LIMIT 1", (*params, like, like)).fetchone()
            match = food_row(local) if local else next(iter(fs.catalog_search(food["name"], 1)), None)
            grams = round(food["grams"], 1)
            if match is None:  # no food matched: a quick add with the model's estimate
                entry = {"label": food["name"], **{k: round(food["per100g"][src] * grams / 100, 1) for k, src in (
                    ("kcal", "calories"), ("protein_g", "protein"), ("carbs_g", "carbs"), ("fat_g", "fat"))}}
            else:
                entry = {"food_id": match["id"]} if match["id"] else {"catalog_id": match["catalog_id"]}
                entry["grams"] = grams
            items.append({"text": food["name"], "grams": grams, "estimate_per100g": food["per100g"],
                          "match": None if match is None else {k: match[k] for k in (
                              "id", "catalog_id", "name_bg", "name_en", "kcal", "protein_g", "carbs_g", "fat_g")},
                          "entry": {"day": day, "meal": body.meal, **entry}})
        return {"items": items, "entries": [item["entry"] for item in items], "estimated": True,
                "note": "Estimates to review. Nothing is logged until you send the entries to /api/food-logs/batch."}

    # --- CSV exports for staff ---

    def csv_response(db, user, athlete, kind, fields, rows):
        return h.csv_export(db, user, athlete["id"], f'athlete-{athlete["id"]}-{kind}.csv', fields, rows, {"kind": kind})

    @app.get("/api/athletes/{athlete_id}/training.csv")
    def training_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows = ({**dict(r), "athlete_name": athlete["name"]} for r in db.execute(
            "SELECT set_logs.*, exercises.name AS exercise FROM set_logs JOIN exercises ON exercises.id = "
            "set_logs.exercise_id WHERE athlete_id = ? ORDER BY day, set_no, set_logs.id", (athlete_id,)))
        return csv_response(db, user, athlete, "training", [
            "id", "athlete_id", "athlete_name", "day", "exercise", "set_no", "reps", "load_kg", "rpe", "plan_id"], rows)

    @app.get("/api/athletes/{athlete_id}/nutrition.csv")
    def nutrition_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows = ({**r, "athlete_name": athlete["name"]} for r in iter_food_logs(db, athlete_id, oldest_first=True))
        return csv_response(db, user, athlete, "nutrition", [
            "id", "athlete_id", "athlete_name", "day", "meal", "name_bg", "name_en", "grams", "kcal",
            "protein_g", "carbs_g", "fat_g", "fibre_g"], rows)

    @app.get("/api/athletes/{athlete_id}/weights.csv")
    def weights_csv(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = h.athlete_access(db, user, athlete_id)
        rows, trend = weight_series(db, athlete_id)
        by_day = {t["day"]: t["trend_kg"] for t in trend}
        return csv_response(db, user, athlete, "weights", ["athlete_id", "athlete_name", "day", "kg", "trend_kg"], (
            {"athlete_id": athlete_id, "athlete_name": athlete["name"], "day": day, "kg": kg,
             "trend_kg": by_day.get(day)} for day, kg in rows))

    return {operation.__name__: operation for operation in (
        list_plans, get_plan, list_exercises, list_set_logs, training_summary, list_foods,
        list_food_logs, athlete_weights, nutrition_estimate, get_target, nutrition_summary,
        nutrition_expenditure, get_program, create_plan, edit_plan, log_set, log_food,
        save_weight, save_target, save_program, list_workouts, get_workout, list_templates,
    )}


def export_data(db, athlete_id):
    """The athlete's training and nutrition data for the JSON export."""
    sets = (dict(r) for r in db.execute(
        "SELECT id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, workout_id, client_id, created_at FROM set_logs "
        "WHERE athlete_id = ? ORDER BY day, set_no, id", (athlete_id,)))
    workouts = (dict(r) for r in db.execute(
        "SELECT id, started_at, ended_at, title, template_id, notes, client_id FROM workouts WHERE athlete_id = ? "
        "ORDER BY started_at, id", (athlete_id,)))
    templates = ({**dict(r), "items": json.loads(r["items"])} for r in db.execute(
        "SELECT id, title, items FROM workout_templates WHERE owner_id = ? ORDER BY id", (athlete_id,)).fetchall())
    foods = (dict(r) for r in db.execute(
        "SELECT id, name_bg, name_en, kcal, protein_g, carbs_g, fat_g, fibre_g FROM foods "
        "WHERE owner_id = ? ORDER BY id", (athlete_id,)))
    meals = (dict(r) for r in db.execute(
        "SELECT food_logs.id, day, meal, food_id, foods.name_bg, foods.name_en, grams, client_id, food_logs.created_at, "
        "serving_label, quantity, label, food_logs.kcal, food_logs.protein_g, food_logs.carbs_g, food_logs.fat_g "
        "FROM food_logs LEFT JOIN foods ON foods.id = food_logs.food_id WHERE athlete_id = ? "
        "ORDER BY day, food_logs.id", (athlete_id,)))
    recipes = ({**dict(r), "items": [dict(i) for i in db.execute(
        "SELECT food_id, grams FROM recipe_items WHERE recipe_id = ? ORDER BY position", (r["id"],))]}
        for r in db.execute("SELECT id, name, servings_count, food_id FROM recipes WHERE owner_id = ? ORDER BY id",
                            (athlete_id,)).fetchall())
    weights = (dict(r) for r in db.execute("SELECT day, kg FROM weights WHERE athlete_id = ? ORDER BY day", (athlete_id,)))
    target = db.execute("SELECT kcal, protein_g, carbs_g, fat_g, set_by, updated_at FROM nutrition_targets "
                        "WHERE athlete_id = ?", (athlete_id,)).fetchone()
    program = db.execute("SELECT effective_date, seed_kcal, mode, goal, rate_kg_week, protein_g, fat_g, "
                         "applied, set_by, updated_at FROM coaching_programs WHERE athlete_id = ?",
                         (athlete_id,)).fetchone()
    complete_days = (dict(r) for r in db.execute(
        "SELECT day, diary_fingerprint, confirmed_at FROM nutrition_day_complete WHERE athlete_id = ? ORDER BY day",
        (athlete_id,)))
    return {"set_logs": sets, "custom_foods": foods, "food_logs": meals, "weights": weights,
            "nutrition_target": None if target is None else dict(target),
            "coaching_program": None if program is None else {**dict(program), "applied": bool(program["applied"])},
            "complete_diary_days": complete_days, "recipes": recipes, "workouts": workouts,
            "workout_templates": templates}


def delete_data(db, athlete_id):
    db.execute("DELETE FROM recipes WHERE owner_id = ?", (athlete_id,))
    db.execute("DELETE FROM workout_templates WHERE owner_id = ?", (athlete_id,))
    for table in ("set_logs", "workouts", "food_logs", "weights", "nutrition_targets", "coaching_programs",
                  "nutrition_day_complete"):
        db.execute(f"DELETE FROM {table} WHERE athlete_id = ?", (athlete_id,))
    db.execute("DELETE FROM foods WHERE owner_id = ?", (athlete_id,))
