"""Run with uvicorn server.main:app."""

from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator


def database_path():
    return os.environ.get("JUMPSCAN_DB", str(Path(__file__).with_name("jumpscan.db")))


@contextmanager
def database(path):
    # FastAPI can resume a synchronous dependency on a different worker thread.
    connection = sqlite3.connect(path, timeout=10, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        with connection:
            yield connection
    finally:
        connection.close()


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
            CREATE TABLE IF NOT EXISTS jumps (
                id INTEGER PRIMARY KEY,
                athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TEXT NOT NULL,
                metrics TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS jumps_athlete ON jumps(athlete_id, created_at);
        """)


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
        if not local or not domain:
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


Angle = Annotated[float, Field(ge=0, le=180)]
FrontalAngle = Annotated[float, Field(ge=-90, le=90)]


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
    knee_ankle_ratio: float = Field(ge=0, le=5)
    flexion_asymmetry_pct: float = Field(ge=0, le=100)
    quality: float = Field(ge=0, le=1)
    warnings: list[Literal["low_fps", "low_visibility", "no_flight_detected"]] = Field(max_length=5)


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
        "WHERE tokens.token_hash = ? AND tokens.expires_at > ?",
        (token_hash(parts[1]), time.time()),
    ).fetchone()
    if user is None:
        raise HTTPException(401, "Invalid or expired token", headers={"WWW-Authenticate": "Bearer"})
    return user


def require_role(user, *roles):
    if user["role"] not in roles:
        raise HTTPException(403, "Not permitted for this role")


def create_app(db_path=None):
    @asynccontextmanager
    async def lifespan(app):
        init_db(app.state.db_path)
        yield

    app = FastAPI(title="JumpScan", lifespan=lifespan, debug=False)
    app.state.db_path = db_path if db_path is not None else database_path()

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # Do not echo passwords, raw payloads, or non-JSON numeric inputs in errors.
        errors = [{key: error[key] for key in ("loc", "msg", "type")} for error in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": errors})

    @app.exception_handler(Exception)
    async def internal_error(request, exc):
        return JSONResponse(status_code=500, content={"detail": "Internal server error"})

    @app.middleware("http")
    async def response_headers(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    @app.get("/")
    def dashboard():
        return FileResponse(Path(__file__).with_name("dashboard.html"))

    @app.post("/api/auth/login")
    def login(body: Login, db: sqlite3.Connection = Depends(get_db)):
        now = time.time()
        # Serialize checks and failure inserts so concurrent attempts cannot bypass the limit.
        db.execute("BEGIN IMMEDIATE")
        db.execute("DELETE FROM login_failures WHERE email = ? AND failed_at <= ?", (body.email, now - 900))
        failures = db.execute("SELECT COUNT(*) FROM login_failures WHERE email = ?", (body.email,)).fetchone()[0]
        if failures >= 5:
            db.commit()
            raise HTTPException(429, "Too many failed logins; try again later", headers={"Retry-After": "900"})
        user = db.execute("SELECT * FROM users WHERE email = ?", (body.email,)).fetchone()
        salt, expected = (user["password_salt"], user["password_hash"]) if user else (DUMMY_SALT, DUMMY_HASH)
        matches = hmac.compare_digest(password_hash(body.password, salt), expected)
        if not matches or user is None:
            db.execute("INSERT INTO login_failures(email, failed_at) VALUES (?, ?)", (body.email, now))
            db.commit()
            raise HTTPException(401, "Invalid email or password")
        db.execute("DELETE FROM login_failures WHERE email = ?", (body.email,))
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO tokens(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                   (token_hash(token), user["id"], now + 30 * 86400))
        return {"token": token, "user": public_user(user)}

    @app.post("/api/auth/logout", status_code=204)
    def logout(authorization: Annotated[str, Header()], user=Depends(current_user), db=Depends(get_db)):
        db.execute("DELETE FROM tokens WHERE token_hash = ?", (token_hash(authorization.split()[1]),))

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
        return {"id": cursor.lastrowid, "name": body.name, "role": body.role, "club_id": club_id}

    @app.get("/api/athletes")
    def athletes(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        rows = db.execute(
            "SELECT users.id, users.name, users.club_id, COUNT(jumps.id) AS jump_count, "
            "MAX(jumps.created_at) AS last_jump_at FROM users LEFT JOIN jumps ON jumps.athlete_id = users.id "
            "WHERE users.role = 'athlete' AND (? = 'admin' OR users.club_id = ?) "
            "GROUP BY users.id ORDER BY users.name, users.id", (user["role"], user["club_id"]),
        ).fetchall()
        return [dict(row) for row in rows]

    @app.post("/api/jumps", status_code=201)
    def add_jump(body: Jump, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        created_at = datetime.now(timezone.utc).isoformat()
        cursor = db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                            (user["id"], created_at, body.model_dump_json()))
        return {"id": cursor.lastrowid, "created_at": created_at}

    @app.get("/api/jumps")
    def jumps(athlete_id: Annotated[int | None, Query(gt=0)] = None, user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete":
            if athlete_id is not None and athlete_id != user["id"]:
                raise HTTPException(403, "Athletes can read their own data only")
            athlete_id = user["id"]
        if athlete_id is not None:
            athlete = db.execute("SELECT role, club_id FROM users WHERE id = ?", (athlete_id,)).fetchone()
            if athlete is None or athlete["role"] != "athlete":
                raise HTTPException(404, "Athlete not found")
            if user["role"] == "coach" and athlete["club_id"] != user["club_id"]:
                raise HTTPException(403, "Athlete belongs to another club")
        rows = db.execute(
            "SELECT jumps.* FROM jumps JOIN users ON users.id = jumps.athlete_id "
            "WHERE (? IS NULL OR jumps.athlete_id = ?) AND (? != 'coach' OR users.club_id = ?) "
            "ORDER BY jumps.created_at DESC, jumps.id DESC",
            (athlete_id, athlete_id, user["role"], user["club_id"]),
        ).fetchall()
        return [{"id": row["id"], "athlete_id": row["athlete_id"], "created_at": row["created_at"],
                 **json.loads(row["metrics"])} for row in rows]

    @app.delete("/api/me/data", status_code=204)
    def delete_data(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        db.execute("DELETE FROM jumps WHERE athlete_id = ?", (user["id"],))

    return app


app = create_app()
