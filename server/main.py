"""Run with uvicorn server.main:app."""

from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timedelta, timezone
import asyncio
import hashlib
import hmac
import base64
import csv
import io
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
from statistics import median
import threading
import time
import unicodedata
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.docs import get_swagger_ui_html, get_swagger_ui_oauth2_redirect_html
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import assistant_api, training_nutrition_api


def database_path():
    return os.environ.get("JUMPSCAN_DB", str(Path(__file__).with_name("jumpscan.db")))


@contextmanager
def database(path):
    # FastAPI can resume a synchronous dependency on a different worker thread.
    connection = sqlite3.connect(path, timeout=10, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    # SQLite LIKE/lower() only fold ASCII; expose Unicode case folding for Cyrillic search.
    connection.create_function("casefold", 1, lambda s: s.casefold() if isinstance(s, str) else s, deterministic=True)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


@contextmanager
def write_transaction(db, event_bus):
    events = []
    db.execute("BEGIN IMMEDIATE")
    try:
        yield lambda viewers, event: events.append((viewers, event))
        db.commit()
    except BaseException:
        db.rollback()
        raise
    for viewers, event in events:
        event_bus.publish(viewers, event)


def init_db(path):
    with database(path) as db:
        db.executescript("""
            CREATE TABLE IF NOT EXISTS clubs (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE
            );
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY,
                club_id INTEGER NOT NULL REFERENCES clubs(id),
                email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                name TEXT NOT NULL,
                role TEXT NOT NULL CHECK(role IN ('admin', 'coach', 'athlete')),
                password_salt BLOB NOT NULL,
                password_hash BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS tokens (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS login_failures (
                email TEXT NOT NULL, failed_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS failures_email ON login_failures(email, failed_at);
            CREATE TABLE IF NOT EXISTS address_failures (
                address TEXT NOT NULL, failed_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS failures_address ON address_failures(address, failed_at);
            CREATE INDEX IF NOT EXISTS tokens_expiry ON tokens(expires_at);
            CREATE TABLE IF NOT EXISTS jumps (
                id INTEGER PRIMARY KEY,
                athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TEXT NOT NULL,
                metrics TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS jumps_athlete ON jumps(athlete_id, created_at);
            CREATE TABLE IF NOT EXISTS form_sets (
                id INTEGER PRIMARY KEY,
                athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TEXT NOT NULL,
                metrics TEXT NOT NULL,
                client_id TEXT
            );
            CREATE INDEX IF NOT EXISTS form_sets_athlete ON form_sets(athlete_id, created_at);
            CREATE UNIQUE INDEX IF NOT EXISTS form_sets_client ON form_sets(athlete_id, client_id)
                WHERE client_id IS NOT NULL;
            CREATE TABLE IF NOT EXISTS audit (
                id INTEGER PRIMARY KEY, at TEXT NOT NULL, actor_id INTEGER,
                action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS groups (
                id INTEGER PRIMARY KEY,
                club_id INTEGER NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                UNIQUE(club_id, name)
            );
            CREATE TABLE IF NOT EXISTS group_members (
                group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                PRIMARY KEY (group_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY,
                club_id INTEGER NOT NULL REFERENCES clubs(id),
                group_id INTEGER REFERENCES groups(id),
                title TEXT NOT NULL,
                starts_at TEXT NOT NULL,
                place TEXT NOT NULL DEFAULT '',
                test TEXT NOT NULL CHECK(test IN ('cmj', 'drop_jump')),
                trials INTEGER NOT NULL DEFAULT 3 CHECK(trials BETWEEN 1 AND 5),
                notes TEXT NOT NULL DEFAULT '',
                created_by INTEGER REFERENCES users(id),
                cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN (0, 1))
            );
            CREATE INDEX IF NOT EXISTS sessions_club ON sessions(club_id, starts_at);
            CREATE TABLE IF NOT EXISTS checkins (
                id INTEGER PRIMARY KEY,
                athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                day TEXT NOT NULL,
                sleep INTEGER CHECK(sleep BETWEEN 1 AND 5),
                fatigue INTEGER CHECK(fatigue BETWEEN 1 AND 5),
                soreness INTEGER CHECK(soreness BETWEEN 1 AND 5),
                stress INTEGER CHECK(stress BETWEEN 1 AND 5),
                mood INTEGER CHECK(mood BETWEEN 1 AND 5),
                training_minutes INTEGER CHECK(training_minutes BETWEEN 0 AND 600),
                rpe INTEGER CHECK(rpe BETWEEN 0 AND 10),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE (athlete_id, day)
            );
            CREATE INDEX IF NOT EXISTS checkins_athlete_day ON checkins(athlete_id, day);
            CREATE TABLE IF NOT EXISTS comments (
                id INTEGER PRIMARY KEY,
                athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                author_id INTEGER NOT NULL REFERENCES users(id),
                jump_id INTEGER REFERENCES jumps(id) ON DELETE SET NULL,
                session_id INTEGER REFERENCES sessions(id) ON DELETE SET NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL,
                read_at TEXT
            );
            CREATE INDEX IF NOT EXISTS comments_athlete ON comments(athlete_id, id);
        """)
        columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
        for name, declaration in {
            "active": "INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1))",
            "consent_recorded": "INTEGER NOT NULL DEFAULT 0 CHECK(consent_recorded IN (0, 1))",
            "consent_by": "TEXT", "consent_at": "TEXT",
        }.items():
            if name not in columns:
                db.execute(f"ALTER TABLE users ADD COLUMN {name} {declaration}")
        columns = {row["name"] for row in db.execute("PRAGMA table_info(jumps)")}
        for name, declaration in {"session_id": "INTEGER REFERENCES sessions(id)", "client_id": "TEXT"}.items():
            if name not in columns:
                db.execute(f"ALTER TABLE jumps ADD COLUMN {name} {declaration}")
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS jumps_client ON jumps(athlete_id, client_id) "
                   "WHERE client_id IS NOT NULL")
        training_nutrition_api.init_schema(db)  # training and nutrition tables (SPEC 6.2, 6.3)
        assistant_api.init_schema(db)


def password_hash(password, salt):
    return hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1, dklen=32)


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


DUMMY_SALT = b"JumpScan dummy salt"
DUMMY_HASH = password_hash("unused dummy credential", DUMMY_SALT)


def public_user(user):
    return {key: user[key] for key in ("id", "name", "role", "club_id")}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Login(StrictModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=1024)

    @field_validator("email")
    @classmethod
    def valid_email(cls, value):
        value = value.strip().casefold()
        if value.count("@") != 1 or any(c.isspace() for c in value):
            raise ValueError("Enter a valid email address")
        local, domain = value.split("@")
        # Reject obviously broken shapes (no dot, leading/trailing dot, doubled dot,
        # invalid domain characters) so malformed addresses are not storable.
        if (not local or not domain or len(value) > 254 or "." not in domain
                or domain.startswith(".") or domain.endswith(".")
                or ".." in domain or local.startswith(".") or local.endswith(".")
                or ".." in local
                or not all(c.isalnum() or c in "-." for c in domain)):
            raise ValueError("Enter a valid email address")
        return value


class NewUser(Login):
    password: str = Field(min_length=8, max_length=1024)
    name: str = Field(min_length=1, max_length=100)
    role: Literal["admin", "coach", "athlete"]
    club_id: int | None = Field(default=None, gt=0)

    @field_validator("name")
    @classmethod
    def valid_name(cls, value):
        if not value.strip():
            raise ValueError("Name must not be blank")
        return value.strip()


class Consent(StrictModel):
    consent_recorded: bool
    consent_by: str = Field(min_length=1, max_length=100)

    _name = field_validator("consent_by")(NewUser.valid_name.__func__)


class UserStatus(StrictModel):
    active: bool


class ResetPassword(StrictModel):
    password: str = Field(min_length=8, max_length=1024)


class NewClub(StrictModel):
    name: str = Field(min_length=1, max_length=100)

    _name = field_validator("name")(NewUser.valid_name.__func__)


class NewGroup(StrictModel):
    name: str = Field(min_length=1, max_length=80)
    club_id: int | None = Field(default=None, gt=0)

    _name = field_validator("name")(NewUser.valid_name.__func__)


class RenameGroup(StrictModel):
    name: str = Field(min_length=1, max_length=80)

    _name = field_validator("name")(NewUser.valid_name.__func__)


class GroupMembers(StrictModel):
    athlete_ids: list[int] = Field(max_length=500)


class SessionBody(StrictModel):
    title: str = Field(min_length=1, max_length=100)
    starts_at: str = Field(min_length=10, max_length=64)
    place: str = Field(default="", max_length=100)
    test: Literal["cmj", "drop_jump"]
    trials: int = Field(default=3, ge=1, le=5)
    notes: str = Field(default="", max_length=1000)
    group_id: int | None = Field(default=None, gt=0)
    cancelled: bool = False

    @field_validator("title")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("Title must not be blank")
        return value.strip()

    @field_validator("starts_at")
    @classmethod
    def valid_timestamp(cls, value):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError("starts_at must be an ISO 8601 timestamp") from None
        if parsed.utcoffset() is None or not 2000 <= parsed.year <= 2100:
            raise ValueError("starts_at must include a timezone and a year from 2000 to 2100")
        return parsed.astimezone(timezone.utc).isoformat()


class Checkin(StrictModel):
    sleep: int = Field(ge=1, le=5)
    fatigue: int = Field(ge=1, le=5)
    soreness: int = Field(ge=1, le=5)
    stress: int = Field(ge=1, le=5)
    mood: int = Field(ge=1, le=5)
    training_minutes: int | None = Field(default=None, ge=0, le=600)
    rpe: int | None = Field(default=None, ge=0, le=10)

    @model_validator(mode="after")
    def load_pairs(self):
        if (self.training_minutes is None) != (self.rpe is None):
            raise ValueError("training_minutes and rpe must be set together")
        return self


class CommentBody(StrictModel):
    athlete_id: int = Field(gt=0)
    body: str = Field(min_length=1, max_length=1000)
    jump_id: int | None = Field(default=None, gt=0)
    session_id: int | None = Field(default=None, gt=0)

    @field_validator("body")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("Comment must not be blank")
        return value


Angle = Annotated[float | None, Field(ge=0, le=180)]
FrontalAngle = Annotated[float | None, Field(ge=-90, le=90)]


class Jump(StrictModel):
    test: Literal["drop_jump", "cmj"]
    fps: float = Field(ge=10, le=480)
    flight_time_ms: float | None = Field(ge=0, le=1500)
    jump_height_cm: float | None = Field(ge=0, le=150)
    knee_flexion_contact_l: Angle
    knee_flexion_contact_r: Angle
    knee_flexion_peak_l: Angle
    knee_flexion_peak_r: Angle
    fppa_peak_l: FrontalAngle
    fppa_peak_r: FrontalAngle
    knee_ankle_ratio: float | None = Field(ge=0, le=5)
    flexion_asymmetry_pct: float | None = Field(ge=0, le=100)
    quality: float = Field(ge=0, le=1)
    warnings: list[Literal["low_fps", "low_visibility", "no_flight_detected", "uncertain_timing", "single_leg_view", "manual_edges"]] = Field(max_length=6)
    session_id: int | None = Field(default=None, gt=0)
    client_id: str | None = Field(default=None, min_length=1, max_length=128)


MAX_FORM_REPETITIONS = 100
FORM_CATALOGUE_PATH = Path(os.environ.get("JUMPSCAN_FORM_CATALOGUE") or
                           Path(__file__).resolve().parents[1] / "core/src/main/resources/form_exercises.json")
FORM_EXERCISES = ({entry["id"]: entry for entry in json.loads(FORM_CATALOGUE_PATH.read_text(encoding="utf-8"))}
                  if FORM_CATALOGUE_PATH.is_file() else None)
FormTime = Annotated[int, Field(ge=0, le=2**63 - 1)]
FormCount = Annotated[int, Field(ge=0, le=MAX_FORM_REPETITIONS)]
FormIndex = Annotated[int, Field(ge=1, le=MAX_FORM_REPETITIONS)]
Percentage = Annotated[float | None, Field(ge=0, le=100)]
FormReason = Literal["below_min_depth", "incomplete_start", "incomplete_end", "tracking_gap", "too_fast",
                     "degenerate_geometry"]


class FormRepetition(StrictModel):
    index: FormIndex
    status: Literal["complete", "partial", "unclear"]
    reasons: list[FormReason] = Field(max_length=5)
    start_ms: FormTime
    bottom_ms: FormTime | None
    end_ms: FormTime
    depth: Angle
    knee_flexion_peak_l: Angle
    knee_flexion_peak_r: Angle
    fppa_l: FrontalAngle
    fppa_r: FrontalAngle
    flexion_asymmetry_pct: Percentage
    descent_time_ms: FormTime | None
    ascent_time_ms: FormTime | None
    hip_below_knee: bool | None
    checks: dict[str, float | None] | None = Field(default=None, max_length=4)

    @model_validator(mode="after")
    def check_cycle(self):
        if self.end_ms < self.start_ms:
            raise ValueError("Repetition ends before it starts")
        if self.status == "unclear":
            if not self.reasons or "below_min_depth" in self.reasons:
                raise ValueError("Unclear repetitions require an unclear reason")
            if self.model_dump(exclude={"index", "status", "reasons", "start_ms", "end_ms"}, exclude_none=True):
                raise ValueError("Unclear repetitions cannot include measurements")
            return self
        if self.reasons != (["below_min_depth"] if self.status == "partial" else []):
            raise ValueError("Reasons must match repetition status")
        if self.bottom_ms is None or not self.start_ms <= self.bottom_ms <= self.end_ms:
            raise ValueError("Repetition bottom must be between start and end")
        if (self.descent_time_ms != self.bottom_ms - self.start_ms
                or self.ascent_time_ms != self.end_ms - self.bottom_ms):
            raise ValueError("Repetition phase durations must match timestamps")
        return self


class FormSummary(StrictModel):
    repetition_count: FormCount
    partial_count: FormCount
    unclear_count: FormCount
    median_depth: Angle
    median_knee_flexion_peak_l: Angle
    median_knee_flexion_peak_r: Angle
    median_fppa_l: FrontalAngle
    median_fppa_r: FrontalAngle
    median_flexion_asymmetry_pct: Percentage
    median_descent_time_ms: float | None = Field(ge=0, le=2**63 - 1)
    median_ascent_time_ms: float | None = Field(ge=0, le=2**63 - 1)
    hip_below_knee_count: FormCount | None
    worst_repetition: FormIndex | None
    hold_ms: FormTime | None = None
    in_band_ms: FormTime | None = None


class FormSet(StrictModel):
    exercise: str = Field(min_length=1, max_length=40)
    side: Literal["L", "R"] | None
    view: Literal["front", "side"]
    fps: float = Field(gt=0, le=480)
    quality: float = Field(ge=0, le=1)
    summary: FormSummary
    warnings: list[Literal["low_visibility", "unclear_reps", "single_leg_view"]] = Field(max_length=3)
    repetitions: list[FormRepetition] = Field(max_length=MAX_FORM_REPETITIONS)
    client_id: str | None = Field(default=None, min_length=1, max_length=128)
    mode: Literal["reps", "hold"] | None = None
    source: Literal["live", "video"] | None = None

    @model_validator(mode="after")
    def check_set(self):
        if FORM_EXERCISES is None:
            if (self.exercise not in {"squat", "single_leg_squat"} or self.mode is not None or self.source is not None
                    or any(rep.checks is not None for rep in self.repetitions)):
                raise HTTPException(503, "Form exercise catalogue is unavailable")
            legacy, sided, mode, keys = True, self.exercise == "single_leg_squat", "reps", set()
        else:
            exercise = FORM_EXERCISES.get(self.exercise)
            if exercise is None:
                raise ValueError("Unknown form exercise")
            legacy, sided, mode = exercise["signal"].get("legacy", False), exercise["sided"], exercise["mode"]
            keys = {check["key"] for check in exercise["checks"]}
        if sided != (self.side is not None):
            raise ValueError("Exercise side must match the catalogue")
        if self.mode is not None and self.mode != mode:
            raise ValueError("Mode must match the exercise")
        if any(rep.checks is not None and not keys.issuperset(rep.checks) for rep in self.repetitions):
            raise ValueError("Check keys must belong to the exercise")
        if any(rep.index != index for index, rep in enumerate(self.repetitions, 1)):
            raise ValueError("Repetition indices must start at 1 and follow array order")
        summary = self.summary
        complete = [rep for rep in self.repetitions if rep.status == "complete"]
        if (summary.repetition_count != len(complete)
                or summary.partial_count != sum(rep.status == "partial" for rep in self.repetitions)
                or summary.unclear_count != sum(rep.status == "unclear" for rep in self.repetitions)):
            raise ValueError("Summary counts must match repetition statuses")
        if mode == "hold":
            if (self.repetitions or summary.hold_ms is None or summary.in_band_ms is None
                    or summary.in_band_ms > summary.hold_ms):
                raise ValueError("Holds require hold durations and no repetitions")
        elif summary.hold_ms is not None or summary.in_band_ms is not None:
            raise ValueError("Hold durations require a hold exercise")
        if not legacy:
            cycle_fields = {"index", "status", "reasons", "start_ms", "bottom_ms", "end_ms", "descent_time_ms", "ascent_time_ms", "checks"}
            count_fields = {"repetition_count", "partial_count", "unclear_count", "hold_ms", "in_band_ms"}
            if (summary.model_dump(exclude=count_fields, exclude_none=True)
                    or any(rep.model_dump(exclude=cycle_fields, exclude_none=True) for rep in self.repetitions)):
                raise ValueError("Squat measurements require a squat exercise")
            return self
        if (complete and summary.worst_repetition not in {rep.index for rep in complete}
                or not complete and summary.worst_repetition is not None):
            raise ValueError("Worst repetition must reference a complete repetition, if any")
        if self.view == "front":
            if summary.hip_below_knee_count is not None or any(
                    rep.hip_below_knee is not None for rep in self.repetitions):
                raise ValueError("Hip-below-knee measurements require side view")
        else:
            if summary.hip_below_knee_count != sum(rep.hip_below_knee is True for rep in complete):
                raise ValueError("Hip-below-knee count must match complete repetitions")
            if any(value is not None for value in (summary.median_fppa_l, summary.median_fppa_r)) or any(
                    rep.fppa_l is not None or rep.fppa_r is not None for rep in self.repetitions):
                raise ValueError("FPPA measurements require front view")
        if self.view == "side" or self.side is not None:
            if summary.median_flexion_asymmetry_pct is not None or any(
                    rep.flexion_asymmetry_pct is not None for rep in self.repetitions):
                raise ValueError("Flexion asymmetry requires both legs")
        return self


def get_db(request: Request):
    with database(request.app.state.db_path) as db:
        yield db


def current_user(
    authorization: Annotated[str | None, Header()] = None,
    db: sqlite3.Connection = Depends(get_db),
):
    parts = (authorization or "").split()
    if len(parts) != 2 or parts[0].lower() != "bearer" or len(parts[1]) > 256:
        raise HTTPException(401, "Authentication required", headers={"WWW-Authenticate": "Bearer"})
    user = db.execute(
        "SELECT users.* FROM users JOIN tokens ON users.id = tokens.user_id "
        "WHERE tokens.token_hash = ? AND tokens.expires_at > ? AND users.active = 1",
        (token_hash(parts[1]), time.time()),
    ).fetchone()
    if user is None:
        raise HTTPException(401, "Invalid or expired token", headers={"WWW-Authenticate": "Bearer"})
    return user


def require_role(user, *roles):
    if user["role"] not in roles:
        raise HTTPException(403, "Not permitted for this role")


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def audit(db, actor_id, action, target, detail=None):
    db.execute("INSERT INTO audit(at, actor_id, action, target, detail) VALUES (?, ?, ?, ?, ?)",
               (now_iso(), actor_id, action, str(target), json.dumps(detail or {}, ensure_ascii=False)))


def athlete_access(db, user, athlete_id):
    if user["role"] == "athlete" and user["id"] != athlete_id:
        raise HTTPException(403, "Athletes can read their own data only")
    athlete = db.execute("SELECT * FROM users WHERE id = ? AND role = 'athlete'", (athlete_id,)).fetchone()
    if athlete is None or (user["role"] == "coach" and athlete["club_id"] != user["club_id"]):
        raise HTTPException(404, "Athlete not found")
    return athlete


def managed_user(db, user, target_id):
    require_role(user, "admin", "coach")
    target = db.execute("SELECT * FROM users WHERE id = ?", (target_id,)).fetchone()
    if target is None or (user["role"] == "coach" and
                          (target["club_id"] != user["club_id"] or target["role"] != "athlete")):
        raise HTTPException(404, "User not found")
    return target


def scoped_group(db, user, group_id):
    """Resolve a group to its row; cross-club and unknown groups return 404."""
    row = db.execute("SELECT * FROM groups WHERE id = ?", (group_id,)).fetchone()
    if row is None or (user["role"] != "admin" and row["club_id"] != user["club_id"]):
        raise HTTPException(404, "Group not found")
    return row


def scoped_session(db, user, session_id):
    """Resolve a session to its row, honouring the caller's visibility rules."""
    row = db.execute(
        "SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
    if row is None or not session_visible(db, user, row):
        raise HTTPException(404, "Session not found")
    return row


def session_visible(db, user, row):
    """A session is visible to: admin (all), members of its club (coach), and an
    athlete of that club only when it is club-wide or in one of their groups."""
    if user["role"] == "admin":
        return True
    if row["club_id"] != user["club_id"]:
        return False
    if user["role"] == "coach" or row["group_id"] is None:
        return True
    return db.execute(
        "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
        (row["group_id"], user["id"])).fetchone() is not None


def session_athletes(db, session):
    """Athlete ids whose jumps belong in this session's report."""
    if session["group_id"] is None:
        return [row["id"] for row in db.execute(
            "SELECT id FROM users WHERE role = 'athlete' AND club_id = ? ORDER BY id",
            (session["club_id"],))]
    return [row["user_id"] for row in db.execute(
        "SELECT group_members.user_id FROM group_members JOIN users ON users.id = group_members.user_id "
        "WHERE group_members.group_id = ? AND users.club_id = ? AND users.role = 'athlete' "
        "ORDER BY group_members.user_id", (session["group_id"], session["club_id"]))]


def validated_group_id(db, user, group_id, require_club=None):
    """Confirm a group_id belongs to the caller's scope (and optionally a given club)."""
    if group_id is None:
        return None
    group = db.execute("SELECT * FROM groups WHERE id = ?", (group_id,)).fetchone()
    if group is None:
        raise HTTPException(422, "Unknown group")
    if user["role"] == "coach" and group["club_id"] != user["club_id"]:
        raise HTTPException(422, "Group must belong to the same club")
    if require_club is not None and group["club_id"] != require_club:
        raise HTTPException(422, "Group must belong to the same club as the session")
    return group_id


class EventBus:
    """In-process fan-out for the /api/events stream (one worker)."""

    def __init__(self):
        self._subscribers = {}
        self._lock = threading.Lock()

    def try_open(self, user_id, limit):
        with self._lock:
            subscriptions = self._subscribers.setdefault(int(user_id), [])
            if len(subscriptions) >= limit:
                return None
            queue = asyncio.Queue()
            subscriptions.append(queue)
            return queue

    def close(self, user_id, queue):
        with self._lock:
            subscriptions = self._subscribers.get(int(user_id), [])
            if queue in subscriptions:
                subscriptions.remove(queue)

    def open_count(self, user_id):
        with self._lock:
            return len(self._subscribers.get(int(user_id), []))

    def publish(self, viewers, event):
        """Queue the event onto every open stream whose user id is in `viewers`."""
        with self._lock:
            queues = [(user, q) for user, queues in self._subscribers.items()
                      for q in queues if user in viewers]
        for _, queue in queues:
            queue.put_nowait(event)


def jump_rows(db, athlete_id):
    return [{**json.loads(row["metrics"]), "id": row["id"], "athlete_id": row["athlete_id"],
             "created_at": row["created_at"], "session_id": row["session_id"],
             "client_id": row["client_id"]}
            for row in db.execute(
                "SELECT * FROM jumps WHERE athlete_id = ? ORDER BY created_at, id", (athlete_id,))]


def form_set_rows(rows):
    return [{"id": row["id"], "athlete_id": row["athlete_id"], "created_at": row["created_at"],
             "client_id": row["client_id"], **json.loads(row["metrics"])} for row in rows]


def athlete_form_sets(db, athlete_id):
    return form_set_rows(db.execute(
        "SELECT * FROM form_sets WHERE athlete_id = ? ORDER BY created_at, id", (athlete_id,)))


def summary(rows):
    numeric = [key for key in Jump.model_fields if key not in ("test", "warnings", "session_id", "client_id")]
    def peak_mean(row):  # side-view jumps measure one leg; the other is null
        peaks = [v for v in (row["knee_flexion_peak_l"], row["knee_flexion_peak_r"]) if v is not None]
        return sum(peaks) / len(peaks) if peaks else None
    values = [{**row, "knee_flexion_peak_mean": peak_mean(row)} for row in rows]
    metrics = {}
    for key in [*numeric, "knee_flexion_peak_mean"]:
        first = [row[key] for row in values[:3] if row[key] is not None]
        last = [row[key] for row in values[-3:] if row[key] is not None]
        baseline, recent = median(first) if first else None, median(last) if last else None
        change = recent - baseline if recent is not None and baseline is not None else None
        metrics[key] = {"baseline": baseline, "latest": values[-1][key] if values else None,
                        "last_three_median": recent, "change_absolute": change,
                        "change_percent": change / baseline * 100 if change is not None and baseline else None,
                        "baseline_count": len(first), "recent_count": len(last)}
    return {"jump_count": len(rows), "first_jump_at": rows[0]["created_at"] if rows else None,
            "last_jump_at": rows[-1]["created_at"] if rows else None,
            "baseline_complete": len(rows) >= 3, "metrics": metrics}


def consent_fields(user):
    return {"consent_recorded": bool(user["consent_recorded"]),
            "consent_by": user["consent_by"], "consent_at": user["consent_at"]}


def csv_cell(value):
    if value is None:
        return ""
    if isinstance(value, (int, float)):
        return value
    value = json.dumps(value, ensure_ascii=False) if isinstance(value, list) else str(value)
    # Whitespace/control prefixes can hide formulas from a spreadsheet importer.
    stripped = value.lstrip(" \t\r\n\v\f\ufeff")
    return "'" + value if stripped.startswith(("=", "+", "-", "@")) or value.startswith(("\t", "\r", "\n")) else value


def ical_escape(text):
    """Escape a TEXT value per RFC 5545 section 3.3.11."""
    text = "".join(c for c in text if c in "\r\n" or unicodedata.category(c) not in ("Cc", "Zl", "Zp"))
    text = text.replace("\\", "\\\\")
    text = text.replace(";", "\\;")
    text = text.replace(",", "\\,")
    text = text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
    return text


def ical_fold(text):
    """Fold `text` into lines of at most 75 octets each (RFC 5545 section 3.1)."""
    lines = []
    while len(text.encode()) > 75:
        cut = 74
        while len(text[:cut].encode()) > 75:
            cut -= 1
        lines.append(text[:cut])
        text = " " + text[cut:]
    lines.append(text)
    return "\r\n".join(lines)


def session_viewers(db, session):
    """User ids whose stream should receive an event about this session."""
    viewers = staff_viewers(db, session["club_id"])
    for row in db.execute(
            "SELECT id FROM users WHERE role = 'athlete' AND active = 1 AND club_id = ?",
            (session["club_id"],)):
        if session["group_id"] is None or db.execute(
                "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
                (session["group_id"], row["id"])).fetchone():
            viewers.add(row["id"])
    return viewers


def athlete_viewers(db, athlete):
    """User ids whose stream should receive an athlete-scoped event."""
    viewers = staff_viewers(db, athlete["club_id"])
    viewers.add(athlete["id"])
    return viewers


def staff_viewers(db, club_id):
    return {row["id"] for row in db.execute(
        "SELECT id FROM users WHERE active = 1 AND (role = 'admin' OR (role = 'coach' AND club_id = ?))",
        (club_id,))}


class RequestBodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        limit = 64 * 1024 if (scope.get("method"), scope.get("path")) == ("POST", "/api/form") else 16 * 1024
        too_large = JSONResponse(status_code=413, content={"detail": "Request body too large"})
        for name, value in scope["headers"]:
            if name.lower() == b"content-length":
                try:
                    if int(value) > limit:
                        return await too_large(scope, receive, send)
                except ValueError:
                    pass
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > limit:
                return await too_large(scope, receive, send)
            body.extend(chunk)
            if not message.get("more_body", False):
                break

        async def buffered_receive():
            nonlocal body
            if body is not None:
                message = {"type": "http.request", "body": bytes(body), "more_body": False}
                body = None
                return message
            return await receive()

        await self.app(scope, buffered_receive, send)


def client_address(request):
    return request.client.host if request.client else "unknown"


def create_app(db_path=None):
    @asynccontextmanager
    async def lifespan(app):
        init_db(app.state.db_path)
        yield

    app = FastAPI(title="JumpScan Club Platform", version="2.0.0",
                  description="Club landing mechanics, consent, personal baselines and coach discussions. No video is stored.",
                  lifespan=lifespan, debug=False)
    app.state.db_path = db_path if db_path is not None else database_path()
    app.add_middleware(RequestBodyLimit)
    app.state.events = EventBus()
    training_tools = training_nutrition_api.install(app, sys.modules[__name__])
    app.mount("/static", StaticFiles(directory=Path(__file__).with_name("static")), name="static")

    csp = ("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
           "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
    docs_html = get_swagger_ui_html(openapi_url=app.openapi_url, title=app.title + " - Swagger UI",
                                    oauth2_redirect_url=app.swagger_ui_oauth2_redirect_url).body.decode()
    bootstrap = docs_html.split("<script>")[1].split("</script>")[0]
    docs_hash = base64.b64encode(hashlib.sha256(bootstrap.encode()).digest()).decode()
    redirect_html = get_swagger_ui_oauth2_redirect_html().body.decode()
    redirect_script = redirect_html.split("<script>")[1].split("</script>")[0]
    redirect_hash = base64.b64encode(hashlib.sha256(redirect_script.encode()).digest()).decode()

    def security_headers(path):
        policy = csp
        if path == "/docs":
            # Swagger's fixed bootstrap is hashed; the dashboard needs no inline script or CDN.
            policy = policy.replace("script-src 'self'", f"script-src 'self' https://cdn.jsdelivr.net 'sha256-{docs_hash}'")
            policy = policy.replace("style-src 'self'", "style-src 'self' https://cdn.jsdelivr.net")
            policy = policy.replace("img-src 'self' data:", "img-src 'self' data: https://fastapi.tiangolo.com")
        elif path == "/docs/oauth2-redirect":
            policy = policy.replace("script-src 'self'", f"script-src 'self' 'sha256-{redirect_hash}'")
        elif path == "/redoc":
            policy = policy.replace("script-src 'self'", "script-src 'self' https://cdn.jsdelivr.net")
            # ReDoc generates styles at runtime; this exception is restricted to its documentation page.
            policy = policy.replace("style-src 'self'", "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com")
            policy += "; font-src 'self' https://fonts.gstatic.com"
        return {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                "Referrer-Policy": "no-referrer", "Content-Security-Policy": policy,
                "Strict-Transport-Security": "max-age=31536000"}

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # Do not echo passwords, raw payloads, or non-JSON numeric inputs in errors.
        errors = [{key: error[key] for key in ("loc", "msg", "type")} for error in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": errors})

    @app.exception_handler(Exception)
    async def internal_error(request, exc):
        return JSONResponse(status_code=500, content={"detail": "Internal server error"},
                            headers=security_headers(request.url.path))

    @app.middleware("http")
    async def response_headers(request, call_next):
        response = await call_next(request)
        response.headers.update(security_headers(request.url.path))
        return response

    @app.get("/")
    def dashboard():
        return FileResponse(Path(__file__).with_name("dashboard.html"))

    @app.post("/api/auth/login")
    def login(body: Login, request: Request, db: sqlite3.Connection = Depends(get_db)):
        now = time.time()
        address = client_address(request)
        # Keep write transactions short; scrypt must not hold the write lock.
        db.execute("BEGIN IMMEDIATE")
        # Prune expired tokens and stale failures; the per-address window binds the failure tables.
        db.execute("DELETE FROM tokens WHERE expires_at <= ?", (now,))
        db.execute("DELETE FROM login_failures WHERE failed_at <= ?", (now - 900,))
        db.execute("DELETE FROM address_failures WHERE failed_at <= ?", (now - 900,))
        failures = db.execute(
            "SELECT COUNT(*) FROM login_failures WHERE email = ? AND failed_at > ?",
            (body.email, now - 900)).fetchone()[0]
        address_failures = db.execute(
            "SELECT COUNT(*) FROM address_failures WHERE address = ? AND failed_at > ?",
            (address, now - 900)).fetchone()[0]
        db.commit()
        if failures >= 5 or address_failures >= 30:
            audit(db, None, "login_failure", body.email,
                  {"reason": "rate_limit", "scope": "address" if failures < 5 else "email"})
            db.commit()
            raise HTTPException(429, "Too many failed logins; try again later", headers={"Retry-After": "900"})
        user = db.execute("SELECT * FROM users WHERE email = ?", (body.email,)).fetchone()
        salt, expected = (user["password_salt"], user["password_hash"]) if user else (DUMMY_SALT, DUMMY_HASH)
        matches = hmac.compare_digest(password_hash(body.password, salt), expected)
        # Recheck under the write lock so concurrent hashes cannot bypass the limits.
        now = time.time()
        db.execute("BEGIN IMMEDIATE")
        failures = db.execute(
            "SELECT COUNT(*) FROM login_failures WHERE email = ? AND failed_at > ?", (body.email, now - 900),
        ).fetchone()[0]
        address_failures = db.execute(
            "SELECT COUNT(*) FROM address_failures WHERE address = ? AND failed_at > ?",
            (address, now - 900)).fetchone()[0]
        if failures >= 5 or address_failures >= 30:
            audit(db, None, "login_failure", body.email,
                  {"reason": "rate_limit", "scope": "address" if failures < 5 else "email"})
            db.commit()
            raise HTTPException(429, "Too many failed logins; try again later", headers={"Retry-After": "900"})
        # Read again under the lock: a reset or deactivation during scrypt must win.
        fresh = db.execute("SELECT * FROM users WHERE email = ?", (body.email,)).fetchone()
        if (not matches or fresh is None or not fresh["active"] or
                fresh["password_hash"] != expected):
            db.execute("INSERT INTO login_failures(email, failed_at) VALUES (?, ?)", (body.email, now))
            db.execute("INSERT INTO address_failures(address, failed_at) VALUES (?, ?)", (address, now))
            audit(db, None, "login_failure", body.email)
            db.commit()
            raise HTTPException(401, "Invalid email or password")
        db.execute("DELETE FROM login_failures WHERE email = ?", (body.email,))
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO tokens(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                   (token_hash(token), user["id"], now + 30 * 86400))
        audit(db, user["id"], "login_success", user["id"])
        db.commit()
        return {"token": token, "user": public_user(user)}

    @app.post("/api/auth/logout", status_code=204)
    def logout(authorization: Annotated[str, Header()], user=Depends(current_user), db=Depends(get_db)):
        db.execute("DELETE FROM tokens WHERE token_hash = ?", (token_hash(authorization.split()[1]),))
        db.commit()

    @app.get("/api/me")
    def me(user=Depends(current_user)):
        return public_user(user)

    @app.post("/api/users", status_code=201)
    def add_user(body: NewUser, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        club_id = body.club_id if body.club_id is not None else user["club_id"]
        if user["role"] == "coach" and (body.role != "athlete" or club_id != user["club_id"]):
            raise HTTPException(403, "Coaches can create athletes in their own club only")
        if db.execute("SELECT id FROM clubs WHERE id = ?", (club_id,)).fetchone() is None:
            raise HTTPException(422, "Unknown club")
        salt = secrets.token_bytes(16)
        try:
            cursor = db.execute(
                "INSERT INTO users(club_id, email, name, role, password_salt, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                (club_id, body.email, body.name, body.role, salt, password_hash(body.password, salt)),
            )
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Email already exists") from None
        audit(db, user["id"], "user_created", cursor.lastrowid, {"role": body.role, "club_id": club_id})
        db.commit()
        return {"id": cursor.lastrowid, "name": body.name, "role": body.role, "club_id": club_id}

    @app.get("/api/athletes")
    def athletes(limit: Annotated[int, Query(ge=1, le=500)] = 100,
                 offset: Annotated[int, Query(ge=0)] = 0, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        rows = db.execute(
            "SELECT users.id, users.name, users.club_id, users.active, users.consent_recorded, "
            "users.consent_by, users.consent_at, users.under_18, COUNT(jumps.id) AS jump_count, "
            "MAX(jumps.created_at) AS last_jump_at FROM users LEFT JOIN jumps ON jumps.athlete_id = users.id "
            "WHERE users.role = 'athlete' AND (? = 'admin' OR users.club_id = ?) "
            "GROUP BY users.id ORDER BY users.name, users.id LIMIT ? OFFSET ?",
            (user["role"], user["club_id"], limit, offset),
        ).fetchall()
        result = []
        ids = [row["id"] for row in rows]
        histories = {}
        if ids:
            # One batched fetch of every listed athlete's jumps replaces the per-athlete reads.
            marks = ",".join("?" * len(ids))
            histories = {athlete_id: [] for athlete_id in ids}
            for jump in db.execute(
                    f"SELECT * FROM jumps WHERE athlete_id IN ({marks}) ORDER BY athlete_id, created_at, id",
                    ids):
                histories[jump["athlete_id"]].append(jump)
        for row in rows:
            changes = summary([{**json.loads(jump["metrics"]), "id": jump["id"],
                                "athlete_id": jump["athlete_id"], "created_at": jump["created_at"],
                                "session_id": jump["session_id"], "client_id": jump["client_id"]}
                               for jump in histories[row["id"]]])["metrics"]
            result.append({**dict(row), "active": bool(row["active"]), "under_18": bool(row["under_18"]),
                           **consent_fields(row),
                           "changes": {key: changes[key] for key in
                                       ("knee_ankle_ratio", "knee_flexion_peak_mean", "flexion_asymmetry_pct")}})
        return result

    @app.post("/api/jumps", status_code=201)
    def add_jump(body: Jump, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if not user["consent_recorded"]:
            raise HTTPException(403, "Consent must be recorded by a coach or admin before uploading jumps")
        if body.client_id is not None:
            previous = db.execute(
                "SELECT id, created_at FROM jumps WHERE athlete_id = ? AND client_id = ?",
                (user["id"], body.client_id)).fetchone()
            if previous is not None:
                return JSONResponse(status_code=200,
                                    content={"id": previous["id"], "created_at": previous["created_at"]})
        if body.session_id is not None:
            session = db.execute("SELECT * FROM sessions WHERE id = ?", (body.session_id,)).fetchone()
            if session is None or not session_visible(db, user, session):
                raise HTTPException(422, "Session not found")
            if session["cancelled"]:
                raise HTTPException(422, "Cannot upload to a cancelled session")
        created_at = now_iso()
        metrics = {key: value for key, value in body.model_dump().items()
                   if key not in ("session_id", "client_id")}
        try:
            cursor = db.execute(
                "INSERT INTO jumps(athlete_id, created_at, metrics, session_id, client_id) VALUES (?, ?, ?, ?, ?)",
                (user["id"], created_at, json.dumps(metrics), body.session_id, body.client_id))
        except sqlite3.IntegrityError:
            db.rollback()
            previous = db.execute(
                "SELECT id, created_at FROM jumps WHERE athlete_id = ? AND client_id = ?",
                (user["id"], body.client_id)).fetchone()
            if previous is None:
                raise
            return JSONResponse(status_code=200,
                                content={"id": previous["id"], "created_at": previous["created_at"]})
        jump_id = cursor.lastrowid
        athlete = db.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
        db.commit()
        app.state.events.publish(athlete_viewers(db, athlete),
                                 {"type": "jump", "id": jump_id, "athlete_id": user["id"],
                                  "club_id": user["club_id"], "at": created_at})
        return JSONResponse(status_code=201, content={"id": jump_id, "created_at": created_at})

    @app.get("/api/jumps")
    def jumps(athlete_id: Annotated[int | None, Query(gt=0)] = None,
              limit: Annotated[int, Query(ge=1, le=500)] = 100,
              offset: Annotated[int, Query(ge=0)] = 0, user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete":
            if athlete_id is not None and athlete_id != user["id"]:
                raise HTTPException(403, "Athletes can read their own data only")
            athlete_id = user["id"]
        if athlete_id is not None:
            athlete = db.execute("SELECT role, club_id FROM users WHERE id = ?", (athlete_id,)).fetchone()
            if (athlete is None or athlete["role"] != "athlete"
                    or (user["role"] == "coach" and athlete["club_id"] != user["club_id"])):
                raise HTTPException(404, "Athlete not found")
        rows = db.execute(
            "SELECT jumps.* FROM jumps JOIN users ON users.id = jumps.athlete_id "
            "WHERE (? IS NULL OR jumps.athlete_id = ?) AND (? != 'coach' OR users.club_id = ?) "
            "ORDER BY jumps.created_at DESC, jumps.id DESC LIMIT ? OFFSET ?",
            (athlete_id, athlete_id, user["role"], user["club_id"], limit, offset),
        ).fetchall()
        return [{**json.loads(row["metrics"]), "id": row["id"], "athlete_id": row["athlete_id"],
                 "created_at": row["created_at"], "session_id": row["session_id"],
                 "client_id": row["client_id"]} for row in rows]

    @app.post("/api/form", status_code=201)
    def add_form_set(body: FormSet, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        with write_transaction(db, app.state.events) as emit:
            athlete = athlete_access(db, user, user["id"])
            if not athlete["consent_recorded"]:
                raise HTTPException(403, "Consent must be recorded by a coach or admin before uploading form sets")
            if body.client_id is not None:
                previous = db.execute(
                    "SELECT id, created_at FROM form_sets WHERE athlete_id = ? AND client_id = ?",
                    (athlete["id"], body.client_id)).fetchone()
                if previous is not None:
                    return JSONResponse(status_code=200, content=dict(previous))
            created_at = now_iso()
            cursor = db.execute(
                "INSERT INTO form_sets(athlete_id, created_at, metrics, client_id) VALUES (?, ?, ?, ?)",
                (athlete["id"], created_at, body.model_dump_json(exclude={"client_id"}, exclude_unset=True), body.client_id))
            form_id = cursor.lastrowid
            audit(db, athlete["id"], "form_logged", form_id)
            emit(athlete_viewers(db, athlete), {"type": "form", "id": form_id, "athlete_id": athlete["id"],
                                              "club_id": athlete["club_id"], "at": created_at})
        return JSONResponse(status_code=201, content={"id": form_id, "created_at": created_at})

    @app.get("/api/form/exercises")
    def list_form_exercises(user=Depends(current_user)):
        if FORM_EXERCISES is None:
            raise HTTPException(503, "Form exercise catalogue is unavailable")
        fields = ("id", "name_bg", "name_en", "mode", "sided", "view")
        check_fields = ("key", "label_bg", "label_en", "unit")
        return [{**{field: row[field] for field in fields},
                 "checks": [{field: check[field] for field in check_fields} for check in row["checks"]]}
                for row in FORM_EXERCISES.values()]

    @app.get("/api/form")
    def list_form_sets(athlete_id: Annotated[int | None, Query(gt=0, le=2**63 - 1)] = None,
                       limit: Annotated[int, Query(ge=1, le=500)] = 100,
                       offset: Annotated[int, Query(ge=0, le=2**63 - 1)] = 0,
                       user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete" and athlete_id is None:
            athlete_id = user["id"]
        if athlete_id is not None:
            athlete_access(db, user, athlete_id)
        return form_set_rows(db.execute(
            "SELECT form_sets.* FROM form_sets JOIN users ON users.id = form_sets.athlete_id "
            "WHERE (? IS NULL OR form_sets.athlete_id = ?) AND (? != 'coach' OR users.club_id = ?) "
            "ORDER BY form_sets.created_at DESC, form_sets.id DESC LIMIT ? OFFSET ?",
            (athlete_id, athlete_id, user["role"], user["club_id"], limit, offset)))

    @app.delete("/api/me/data", status_code=204)
    def delete_data(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        db.execute("DELETE FROM comments WHERE athlete_id = ?", (user["id"],))
        db.execute("DELETE FROM checkins WHERE athlete_id = ?", (user["id"],))
        training_nutrition_api.delete_data(db, user["id"])
        assistant_api.delete_data(db, user["id"])
        db.execute("DELETE FROM jumps WHERE athlete_id = ?", (user["id"],))
        db.execute("DELETE FROM form_sets WHERE athlete_id = ?", (user["id"],))
        audit(db, user["id"], "data_deleted", user["id"])
        db.commit()

    @app.patch("/api/users/{target_id}/consent")
    def set_consent(target_id: int, body: Consent, user=Depends(current_user), db=Depends(get_db)):
        target = managed_user(db, user, target_id)
        if target["role"] != "athlete":
            raise HTTPException(422, "Consent applies to athletes only")
        at = now_iso()
        db.execute("UPDATE users SET consent_recorded = ?, consent_by = ?, consent_at = ? WHERE id = ?",
                   (body.consent_recorded, body.consent_by, at, target_id))
        audit(db, user["id"], "consent_changed", target_id, body.model_dump())
        db.commit()
        return {"consent_recorded": body.consent_recorded, "consent_by": body.consent_by, "consent_at": at}

    @app.get("/api/athletes/{athlete_id}/summary")
    def athlete_summary(athlete_id: int, user=Depends(current_user), db=Depends(get_db)):
        athlete_access(db, user, athlete_id)
        return {"athlete_id": athlete_id, **summary(jump_rows(db, athlete_id))}

    @app.get("/api/me/export")
    def export_me(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        profile = {**public_user(user), "email": user["email"], "active": bool(user["active"]), **consent_fields(user)}
        checkins = [dict(row) for row in db.execute(
            "SELECT day, sleep, fatigue, soreness, stress, mood, training_minutes, rpe, "
            "created_at, updated_at FROM checkins WHERE athlete_id = ? ORDER BY day", (user["id"],))]
        comments = [dict(row) for row in db.execute(
            "SELECT id, author_id, jump_id, session_id, body, created_at, read_at FROM comments "
            "WHERE athlete_id = ? ORDER BY created_at, id", (user["id"],))]
        profile["under_18"] = bool(user["under_18"])
        result = {"user": profile, "jumps": jump_rows(db, user["id"]), "checkins": checkins,
                  "comments": comments, "form_sets": athlete_form_sets(db, user["id"]),
                  **training_nutrition_api.export_data(db, user["id"]),
                  "exported_at": now_iso()}
        audit(db, user["id"], "json_export", user["id"])
        db.commit()
        return JSONResponse(result, headers={"Content-Disposition": 'attachment; filename="jumpscan-data.json"'})

    @app.get("/api/athletes/{athlete_id}/export.csv")
    def export_csv(athlete_id: int, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = athlete_access(db, user, athlete_id)
        output = io.StringIO(newline="")
        writer = csv.writer(output)
        metric_fields = [key for key in Jump.model_fields if key not in ("session_id", "client_id")]
        fields = ["id", "athlete_id", "athlete_name", "created_at", "session_id", *metric_fields]
        writer.writerow(fields)
        for row in jump_rows(db, athlete_id):
            row["athlete_name"] = athlete["name"]
            writer.writerow([csv_cell(row.get(field)) for field in fields])
        audit(db, user["id"], "csv_export", athlete_id)
        db.commit()
        return Response(output.getvalue(), media_type="text/csv",
                        headers={"Content-Disposition": f'attachment; filename="athlete-{athlete_id}.csv"'})

    @app.get("/api/athletes/{athlete_id}/form.csv")
    def form_csv(athlete_id: int, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = athlete_access(db, user, athlete_id)
        output = io.StringIO(newline="")
        writer = csv.writer(output)
        hold_fields = ("hold_ms", "in_band_ms")
        fields = ["id", "athlete_id", "athlete_name", "created_at", "client_id", "exercise", "side", "view",
                  "fps", "quality", *(key for key in FormSummary.model_fields if key not in hold_fields),
                  "warnings", "repetitions", "mode", "source", *hold_fields]
        writer.writerow(fields)
        for row in athlete_form_sets(db, athlete_id):
            row.update(row.pop("summary"), athlete_name=athlete["name"])
            writer.writerow([csv_cell(row.get(field)) for field in fields])
        audit(db, user["id"], "csv_export", athlete_id, {"kind": "form"})
        db.commit()
        return Response(output.getvalue(), media_type="text/csv",
                        headers={"Content-Disposition": f'attachment; filename="athlete-{athlete_id}-form.csv"'})

    @app.get("/api/clubs")
    def clubs(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin")
        return [dict(row) for row in db.execute("SELECT * FROM clubs ORDER BY id")]

    @app.post("/api/clubs", status_code=201)
    def add_club(body: NewClub, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin")
        try:
            cursor = db.execute("INSERT INTO clubs(name) VALUES (?)", (body.name,))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Club already exists") from None
        audit(db, user["id"], "club_created", cursor.lastrowid)
        db.commit()
        return {"id": cursor.lastrowid, "name": body.name}

    @app.get("/api/users")
    def users(limit: Annotated[int, Query(ge=1, le=500)] = 100,
              offset: Annotated[int, Query(ge=0)] = 0, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        rows = db.execute("SELECT * FROM users WHERE (? = 'admin' OR club_id = ?) ORDER BY name, id LIMIT ? OFFSET ?",
                          (user["role"], user["club_id"], limit, offset))
        return [{**public_user(row), "email": row["email"], "active": bool(row["active"]),
                 "under_18": bool(row["under_18"]), **consent_fields(row)} for row in rows]

    @app.patch("/api/users/{target_id}")
    def user_status(target_id: int, body: UserStatus, user=Depends(current_user), db=Depends(get_db)):
        managed_user(db, user, target_id)
        if not body.active and user["id"] == target_id:
            raise HTTPException(403, "You cannot deactivate yourself")
        db.execute("UPDATE users SET active = ? WHERE id = ?", (body.active, target_id))
        if not body.active:
            db.execute("DELETE FROM tokens WHERE user_id = ?", (target_id,))
        audit(db, user["id"], "user_activated" if body.active else "user_deactivated", target_id)
        db.commit()
        return {"id": target_id, "active": body.active}

    @app.post("/api/users/{target_id}/password", status_code=204)
    def reset_password(target_id: int, body: ResetPassword, user=Depends(current_user), db=Depends(get_db)):
        managed_user(db, user, target_id)
        salt = secrets.token_bytes(16)
        hashed = password_hash(body.password, salt)
        db.execute("UPDATE users SET password_salt = ?, password_hash = ? WHERE id = ?", (salt, hashed, target_id))
        db.execute("DELETE FROM tokens WHERE user_id = ?", (target_id,))
        audit(db, user["id"], "password_reset", target_id)
        db.commit()

    @app.get("/api/audit")
    def audit_log(limit: Annotated[int, Query(ge=1, le=500)] = 100,
                  offset: Annotated[int, Query(ge=0)] = 0, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin")
        # The actor's email makes the log readable without a second lookup; deleted users leave it null.
        return [{**dict(row), "detail": json.loads(row["detail"])} for row in db.execute(
            "SELECT audit.*, users.email AS actor_email FROM audit LEFT JOIN users ON users.id = audit.actor_id "
            "ORDER BY audit.id DESC LIMIT ? OFFSET ?", (limit, offset))]

    @app.get("/api/groups")
    def list_groups(club_id: Annotated[int | None, Query(gt=0)] = None,
                    user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "admin":
            rows = db.execute("SELECT * FROM groups WHERE (? IS NULL OR club_id = ?) ORDER BY name, id",
                              (club_id, club_id)).fetchall()
            show_members = True
        elif user["role"] == "coach":
            rows = db.execute("SELECT * FROM groups WHERE club_id = ? ORDER BY name, id",
                              (user["club_id"],)).fetchall()
            show_members = True
        else:
            rows = db.execute(
                "SELECT groups.* FROM groups JOIN group_members ON group_members.group_id = groups.id "
                "WHERE group_members.user_id = ? AND groups.club_id = ? ORDER BY groups.name, groups.id",
                (user["id"], user["club_id"])).fetchall()
            show_members = False
        result = []
        for row in rows:
            item = dict(row)
            if show_members:
                item["member_ids"] = [m["user_id"] for m in db.execute(
                    "SELECT user_id FROM group_members WHERE group_id = ? ORDER BY user_id", (row["id"],))]
            result.append(item)
        return result

    @app.post("/api/groups", status_code=201)
    def add_group(body: NewGroup, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        club_id = body.club_id if body.club_id is not None else user["club_id"]
        if user["role"] == "coach" and club_id != user["club_id"]:
            raise HTTPException(403, "Coaches can create groups in their own club only")
        if db.execute("SELECT id FROM clubs WHERE id = ?", (club_id,)).fetchone() is None:
            raise HTTPException(422, "Unknown club")
        try:
            cursor = db.execute("INSERT INTO groups(club_id, name) VALUES (?, ?)", (club_id, body.name))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "A group with this name already exists in the club") from None
        audit(db, user["id"], "group_created", cursor.lastrowid)
        db.commit()
        return {"id": cursor.lastrowid, "club_id": club_id, "name": body.name}

    @app.patch("/api/groups/{group_id}")
    def rename_group(group_id: int, body: RenameGroup, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        group = scoped_group(db, user, group_id)
        if user["role"] == "coach" and group["club_id"] != user["club_id"]:
            raise HTTPException(404, "Group not found")
        try:
            db.execute("UPDATE groups SET name = ? WHERE id = ?", (body.name, group_id))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "A group with this name already exists in the club") from None
        audit(db, user["id"], "group_changed", group_id)
        db.commit()
        return {"id": group_id, "club_id": group["club_id"], "name": body.name}

    @app.put("/api/groups/{group_id}/members")
    def set_group_members(group_id: int, body: GroupMembers, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        group = scoped_group(db, user, group_id)
        unique = list(dict.fromkeys(body.athlete_ids))
        for athlete_id in unique:
            athlete = db.execute("SELECT role, club_id, active FROM users WHERE id = ?", (athlete_id,)).fetchone()
            if (athlete is None or athlete["role"] != "athlete"
                    or athlete["club_id"] != group["club_id"]):
                raise HTTPException(422, "Every member must be an athlete of the same club")
        db.execute("DELETE FROM group_members WHERE group_id = ?", (group_id,))
        db.executemany("INSERT INTO group_members(group_id, user_id) VALUES (?, ?)",
                       [(group_id, athlete_id) for athlete_id in unique])
        audit(db, user["id"], "group_changed", group_id)
        db.commit()
        return {"id": group_id, "athlete_ids": unique}

    @app.delete("/api/groups/{group_id}", status_code=204)
    def delete_group(group_id: int, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        scoped_group(db, user, group_id)
        # Sessions of the group become club-wide; memberships are dropped.
        db.execute("UPDATE sessions SET group_id = NULL WHERE group_id = ?", (group_id,))
        db.execute("DELETE FROM group_members WHERE group_id = ?", (group_id,))
        db.execute("DELETE FROM groups WHERE id = ?", (group_id,))
        audit(db, user["id"], "group_deleted", group_id)
        db.commit()

    @app.get("/api/sessions")
    def list_sessions(from_: Annotated[str | None, Query(alias="from")] = None,
                      to: Annotated[str | None, Query()] = None,
                      limit: Annotated[int, Query(ge=1, le=500)] = 100,
                      offset: Annotated[int, Query(ge=0)] = 0,
                      user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "admin":
            clause, params = "1", ()
        elif user["role"] == "coach":
            clause, params = "sessions.club_id = ?", (user["club_id"],)
        else:
            clause = ("sessions.club_id = ? AND (sessions.group_id IS NULL "
                      "OR sessions.group_id IN (SELECT group_id FROM group_members WHERE user_id = ?))")
            params = (user["club_id"], user["id"])
        where = [clause]
        if from_ is not None:
            where.append("julianday(sessions.starts_at) >= julianday(?)")
            params = (*params, from_)
        if to is not None:
            where.append("julianday(sessions.starts_at) <= julianday(?)")
            params = (*params, to)
        sql = ("SELECT sessions.*, (SELECT COUNT(*) FROM jumps WHERE jumps.session_id = sessions.id "
               "AND jumps.athlete_id IN (SELECT id FROM users WHERE (? = 'admin' OR (users.club_id = ?)) "
               "AND (? = 'admin' OR users.club_id = ?))) AS scope_jump_count FROM sessions WHERE "
               + " AND ".join(where) + " ORDER BY julianday(sessions.starts_at), sessions.id LIMIT ? OFFSET ?")
        rows = db.execute(sql, (user["role"], user["club_id"], user["role"], user["club_id"],
                                *params, limit, offset)).fetchall()
        result = []
        for row in rows:
            session = dict(row)
            # jump_count reflects the caller's scope: admin/coach see all jumps of the session; an
            # athlete sees only their own.
            if user["role"] == "athlete":
                session["jump_count"] = db.execute(
                    "SELECT COUNT(*) FROM jumps WHERE session_id = ? AND athlete_id = ?",
                    (row["id"], user["id"])).fetchone()[0]
            else:
                session["jump_count"] = row["scope_jump_count"]
            session.pop("scope_jump_count", None)
            result.append(session)
        return result

    @app.post("/api/sessions", status_code=201)
    def add_session(body: SessionBody, user=Depends(current_user), db=Depends(get_db)):
        with write_transaction(db, app.state.events) as emit:
            return create_session(body, user, db, emit)

    def create_session(body: SessionBody, user, db, emit):
        require_role(user, "admin", "coach")
        group_id = validated_group_id(db, user, body.group_id, require_club=user["club_id"])
        if emit is None:
            return
        cursor = db.execute(
            "INSERT INTO sessions(club_id, group_id, title, starts_at, place, test, trials, notes, "
            "created_by, cancelled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (user["club_id"], group_id, body.title, body.starts_at, body.place, body.test,
             body.trials, body.notes, user["id"], body.cancelled))
        session_id = cursor.lastrowid
        audit(db, user["id"], "session_created", session_id)
        session = db.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
        emit(session_viewers(db, session),
             {"type": "session", "id": session_id, "club_id": user["club_id"], "at": now_iso()})
        return {**dict(session), "jump_count": 0}

    @app.patch("/api/sessions/{session_id}")
    def edit_session(session_id: int, body: SessionBody, user=Depends(current_user), db=Depends(get_db)):
        with write_transaction(db, app.state.events) as emit:
            return update_session(session_id, body, user, db, emit)

    def update_session(session_id: int, body: SessionBody, user, db, emit):
        require_role(user, "admin", "coach")
        session = scoped_session(db, user, session_id)
        if user["role"] == "coach" and session["club_id"] != user["club_id"]:
            raise HTTPException(404, "Session not found")
        group_id = validated_group_id(db, user, body.group_id, require_club=session["club_id"])
        if emit is None:
            return
        db.execute(
            "UPDATE sessions SET group_id = ?, title = ?, starts_at = ?, place = ?, test = ?, "
            "trials = ?, notes = ?, cancelled = ? WHERE id = ?",
            (group_id, body.title, body.starts_at, body.place, body.test, body.trials, body.notes,
             body.cancelled, session_id))
        audit(db, user["id"], "session_changed", session_id)
        session = db.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
        emit(session_viewers(db, session),
             {"type": "session", "id": session_id, "club_id": session["club_id"], "at": now_iso()})
        return dict(session)

    @app.get("/api/sessions/{session_id}/report")
    def session_report(session_id: int, user=Depends(current_user), db=Depends(get_db)):
        session = scoped_session(db, user, session_id)
        require_role(user, "admin", "coach", "athlete")
        if user["role"] == "athlete" and user["id"] not in session_athletes(db, session):
            raise HTTPException(404, "Session not found")
        athletes = session_athletes(db, session)
        if user["role"] == "athlete":
            athletes = [user["id"]]
        numeric = [key for key in Jump.model_fields
                   if key not in ("test", "warnings", "session_id", "client_id")]
        rows = []
        for athlete_id in athletes:
            athlete = db.execute("SELECT id, name, active FROM users WHERE id = ?", (athlete_id,)).fetchone()
            jumps = [json.loads(row["metrics"]) for row in db.execute(
                "SELECT metrics FROM jumps WHERE session_id = ? AND athlete_id = ? ORDER BY created_at, id",
                (session_id, athlete_id))]
            trials_done = len(jumps)
            warnings_count = sum(len(jump.get("warnings", [])) for jump in jumps)
            medians = {}
            for key in numeric:
                values = [jump[key] for jump in jumps if jump.get(key) is not None]
                medians[key] = median(values) if values else None
            rows.append({"athlete_id": athlete_id, "name": athlete["name"], "trials_done": trials_done,
                         "trials": session["trials"], **medians, "warnings_count": warnings_count})
        return {"session_id": session_id, "trials": session["trials"], "athletes": rows}

    @app.get("/api/me/calendar.ics")
    def calendar_ics(user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "admin":
            clause, params = "1", ()
        elif user["role"] == "coach":
            clause, params = "club_id = ?", (user["club_id"],)
        else:
            clause = ("club_id = ? AND (group_id IS NULL OR group_id IN "
                      "(SELECT group_id FROM group_members WHERE user_id = ?))")
            params = (user["club_id"], user["id"])
        from_day = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
        sessions = db.execute(
            "SELECT * FROM sessions WHERE julianday(starts_at) >= julianday(?) AND "
            + clause + " ORDER BY julianday(starts_at), id",
            (from_day, *params)).fetchall()
        now = datetime.now(timezone.utc)
        lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//JumpScan//Club Platform//EN"]
        for session in sessions:
            try:
                start = datetime.fromisoformat(session["starts_at"].replace("Z", "+00:00"))
                if start.utcoffset() is None:
                    continue
                start = start.astimezone(timezone.utc)
            except (ValueError, OverflowError):
                continue  # Invalid timestamps saved before validation must not break the feed.
            dtstart = start.strftime("%Y%m%dT%H%M%SZ")
            dtstamp = now.strftime("%Y%m%dT%H%M%SZ")
            summary = ical_escape(session["title"])
            location = ical_escape(session["place"])
            description = ical_escape(session["notes"])
            lines += ["BEGIN:VEVENT",
                      "UID:session-" + str(session["id"]) + "@jumpscan",
                      "DTSTAMP:" + dtstamp,
                      "DTSTART:" + dtstart,
                      "DURATION:PT1H",
                      "SUMMARY:" + summary,
                      "LOCATION:" + location,
                      "DESCRIPTION:" + description]
            if session["cancelled"]:
                lines += ["STATUS:CANCELLED"]
            lines += ["END:VEVENT"]
        lines += ["END:VCALENDAR", ""]
        body = "\r\n".join(ical_fold(line) for line in lines)
        return Response(body + "\r\n", media_type="text/calendar",
                        headers={"Content-Disposition": 'attachment; filename="jumpscan.ics"'})

    @app.put("/api/me/checkins/{day}", status_code=201)
    def put_checkin(day: str, body: Checkin, user=Depends(current_user), db=Depends(get_db)):
        with write_transaction(db, app.state.events) as emit:
            return save_checkin(day, body, user, db, emit)

    def save_checkin(day: str, body: Checkin, user, db, emit):
        require_role(user, "athlete")
        if not user["consent_recorded"]:
            raise HTTPException(403, "Consent must be recorded by a coach or admin before recording check-ins")
        try:
            day_date = datetime.strptime(day, "%Y-%m-%d").date()
        except ValueError:
            raise HTTPException(422, "day must be YYYY-MM-DD") from None
        today = datetime.now(timezone.utc).date()
        if not (today - timedelta(days=6) <= day_date <= today + timedelta(days=1)):
            raise HTTPException(422, "Check-in day must be from 6 days before today through tomorrow (UTC)")
        if emit is None:
            return
        day = day_date.isoformat()
        now = now_iso()
        existing = db.execute("SELECT * FROM checkins WHERE athlete_id = ? AND day = ?",
                              (user["id"], day)).fetchone()
        db.execute(
            "INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, "
            "training_minutes, rpe, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(athlete_id, day) DO UPDATE SET sleep = excluded.sleep, fatigue = excluded.fatigue, "
            "soreness = excluded.soreness, stress = excluded.stress, mood = excluded.mood, "
            "training_minutes = excluded.training_minutes, rpe = excluded.rpe, updated_at = excluded.updated_at",
            (user["id"], day, body.sleep, body.fatigue, body.soreness, body.stress, body.mood,
             body.training_minutes, body.rpe, now, now))
        status = 201 if existing is None else 200
        emit(athlete_viewers(db, user), {"type": "checkin", "id": user["id"], "athlete_id": user["id"],
                                        "club_id": user["club_id"], "at": now})
        return JSONResponse(status_code=status, content={"athlete_id": user["id"], "day": day,
                                                         **body.model_dump()})

    @app.get("/api/checkins")
    def list_checkins(athlete_id: Annotated[int | None, Query(gt=0)] = None,
                      from_: Annotated[str | None, Query(alias="from")] = None,
                      to: Annotated[str | None, Query()] = None,
                      user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete":
            if athlete_id is not None and athlete_id != user["id"]:
                raise HTTPException(403, "Athletes can read their own data only")
            athlete_id = user["id"]
        if athlete_id is not None:
            athlete = db.execute("SELECT role, club_id FROM users WHERE id = ?", (athlete_id,)).fetchone()
            if (athlete is None or athlete["role"] != "athlete"
                    or (user["role"] == "coach" and athlete["club_id"] != user["club_id"])):
                raise HTTPException(404, "Athlete not found")
        where = []
        params = []
        if athlete_id is not None:
            where.append("checkins.athlete_id = ?")
            params.append(athlete_id)
        if user["role"] == "coach":
            where.append("checkins.athlete_id IN (SELECT id FROM users WHERE club_id = ?)")
            params.append(user["club_id"])
        if from_ is not None:
            where.append("checkins.day >= ?")
            params.append(from_)
        if to is not None:
            where.append("checkins.day <= ?")
            params.append(to)
        clause = " AND ".join(where) if where else "1"
        return [dict(row) for row in db.execute(
            "SELECT * FROM checkins WHERE " + clause + " ORDER BY day DESC, id DESC", params)]

    @app.get("/api/checkins/team")
    def team_checkins(day: Annotated[str | None, Query()] = None, club_id: Annotated[int | None, Query(gt=0)] = None,
                      user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        day = day if day is not None else datetime.now(timezone.utc).strftime("%Y-%m-%d")
        if user["role"] == "admin":
            clause, params = "1", ()
            if club_id is not None:
                clause, params = "club_id = ?", (club_id,)
        else:
            clause, params = "club_id = ?", (user["club_id"],)
        athletes = db.execute(
            "SELECT id, name FROM users WHERE role = 'athlete' AND active = 1 AND "
            + clause + " ORDER BY name, id", params).fetchall()
        result = []
        for athlete in athletes:
            checkin = db.execute("SELECT * FROM checkins WHERE athlete_id = ? AND day = ?",
                                 (athlete["id"], day)).fetchone()
            if checkin is None:
                result.append({"athlete_id": athlete["id"], "name": athlete["name"], "checkin": None,
                               "wellbeing": None, "load": None, "below_usual": None})
                continue
            items = [checkin[key] for key in ("sleep", "fatigue", "soreness", "stress", "mood")]
            wellbeing = sum(items) if all(v is not None for v in items) else sum(v for v in items if v is not None)
            load = (checkin["rpe"] * checkin["training_minutes"]
                    if checkin["rpe"] is not None and checkin["training_minutes"] is not None else None)
            previous = [row for row in db.execute(
                "SELECT sleep, fatigue, soreness, stress, mood FROM checkins "
                "WHERE athlete_id = ? AND day < ? AND day >= date(?, '-14 day') ORDER BY day DESC",
                (athlete["id"], day, day))]
            below = None
            if wellbeing is not None and len(previous) >= 5:
                previous_sums = []
                for row in previous:
                    values = [row[key] for key in ("sleep", "fatigue", "soreness", "stress", "mood")]
                    previous_sums.append(sum(values))
                baseline = median(previous_sums)
                below = wellbeing <= baseline - 3
            result.append({"athlete_id": athlete["id"], "name": athlete["name"],
                           "checkin": dict(checkin), "wellbeing": wellbeing, "load": load,
                           "below_usual": below})
        return result

    @app.post("/api/comments", status_code=201)
    def add_comment(body: CommentBody, user=Depends(current_user), db=Depends(get_db)):
        with write_transaction(db, app.state.events) as emit:
            return write_comment(body, user, db, emit)

    def write_comment(body: CommentBody, user, db, emit):
        athlete = db.execute("SELECT * FROM users WHERE id = ? AND role = 'athlete'",
                             (body.athlete_id,)).fetchone()
        if athlete is None:
            raise HTTPException(404, "Athlete not found")
        if user["role"] == "athlete" and user["id"] != body.athlete_id:
            raise HTTPException(403, "Athletes can comment on their own thread only")
        if user["role"] == "coach" and athlete["club_id"] != user["club_id"]:
            raise HTTPException(404, "Athlete not found")
        if not athlete["consent_recorded"]:
            raise HTTPException(403, "Consent must be recorded by a coach or admin before recording comments")
        if body.jump_id is not None:
            jump = db.execute("SELECT athlete_id FROM jumps WHERE id = ?", (body.jump_id,)).fetchone()
            if jump is None or jump["athlete_id"] != body.athlete_id:
                raise HTTPException(422, "Referenced jump does not belong to this athlete")
        if body.session_id is not None:
            session = db.execute("SELECT * FROM sessions WHERE id = ?", (body.session_id,)).fetchone()
            if session is None or not session_visible(db, athlete, session):
                raise HTTPException(422, "Referenced session is not visible to this athlete")
        if emit is None:
            return
        now = now_iso()
        cursor = db.execute(
            "INSERT INTO comments(athlete_id, author_id, jump_id, session_id, body, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (body.athlete_id, user["id"], body.jump_id, body.session_id, body.body, now))
        audit(db, user["id"], "comment_created", cursor.lastrowid)
        emit(athlete_viewers(db, athlete), {"type": "comment", "id": cursor.lastrowid,
                                           "athlete_id": body.athlete_id, "club_id": athlete["club_id"], "at": now})
        return {"id": cursor.lastrowid, "athlete_id": body.athlete_id, "author_id": user["id"],
                "jump_id": body.jump_id, "session_id": body.session_id, "body": body.body,
                "created_at": now, "read_at": None}

    @app.get("/api/comments")
    def list_comments(athlete_id: Annotated[int, Query(gt=0)],
                      limit: Annotated[int, Query(ge=1, le=500)] = 100,
                      offset: Annotated[int, Query(ge=0)] = 0,
                      user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete" and user["id"] != athlete_id:
            raise HTTPException(403, "Athletes can read their own thread only")
        athlete = db.execute("SELECT role, club_id FROM users WHERE id = ?", (athlete_id,)).fetchone()
        if (athlete is None or athlete["role"] != "athlete"
                or (user["role"] == "coach" and athlete["club_id"] != user["club_id"])):
            raise HTTPException(404, "Athlete not found")
        rows = db.execute(
            "SELECT comments.*, users.name AS author_name, users.role AS author_role "
            "FROM comments JOIN users ON users.id = comments.author_id WHERE comments.athlete_id = ? "
            "ORDER BY comments.id DESC LIMIT ? OFFSET ?", (athlete_id, limit, offset)).fetchall()
        return [dict(row) for row in rows]

    @app.get("/api/me/unread")
    def unread(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        count = db.execute(
            "SELECT COUNT(*) FROM comments WHERE athlete_id = ? AND author_id != ? AND read_at IS NULL",
            (user["id"], user["id"])).fetchone()[0]
        return {"comments": count}

    @app.post("/api/me/read", status_code=204)
    def mark_read(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        db.execute("UPDATE comments SET read_at = ? WHERE athlete_id = ? AND author_id != ? AND read_at IS NULL",
                   (now_iso(), user["id"], user["id"]))
        db.commit()

    @app.get("/api/events")
    async def events(request: Request, user=Depends(current_user)):
        queue = request.app.state.events.try_open(user["id"], 3)
        if queue is None:
            raise HTTPException(429, "Too many open event streams")

        async def stream():
            try:
                while True:
                    try:
                        event = await asyncio.wait_for(queue.get(), timeout=25)
                    except asyncio.TimeoutError:
                        event = None
                    with database(request.app.state.db_path) as db:
                        try:
                            current_user(request.headers.get("authorization"), db)
                        except HTTPException:
                            break
                    yield f"data: {json.dumps(event)}\n\n" if event is not None else ": keep-alive\n\n"
            finally:
                request.app.state.events.close(user["id"], queue)

        return StreamingResponse(stream(), media_type="text/event-stream",
                                 headers={"X-Accel-Buffering": "no"})

    assistant_api.install(app, sys.modules[__name__], {**training_tools, **{
        operation.__name__: operation for operation in (
            athletes, athlete_summary, jumps, list_form_sets, list_sessions, session_report, list_checkins,
            team_checkins, list_comments, create_session, update_session, save_checkin, write_comment,
        )
    }})
    return app


app = create_app()
