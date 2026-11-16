"""Run with uvicorn server.main:app."""

from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
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
from statistics import median
import time
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.docs import get_swagger_ui_html, get_swagger_ui_oauth2_redirect_html
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
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
            CREATE TABLE IF NOT EXISTS audit (
                id INTEGER PRIMARY KEY, at TEXT NOT NULL, actor_id INTEGER,
                action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL
            );
        """)
        columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
        for name, declaration in {
            "active": "INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0, 1))",
            "consent_recorded": "INTEGER NOT NULL DEFAULT 0 CHECK(consent_recorded IN (0, 1))",
            "consent_by": "TEXT", "consent_at": "TEXT",
        }.items():
            if name not in columns:
                db.execute(f"ALTER TABLE users ADD COLUMN {name} {declaration}")


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
    warnings: list[Literal["low_fps", "low_visibility", "no_flight_detected", "uncertain_timing"]] = Field(max_length=5)


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


def jump_rows(db, athlete_id):
    return [{"id": row["id"], "athlete_id": row["athlete_id"], "created_at": row["created_at"],
             **json.loads(row["metrics"])} for row in db.execute(
                 "SELECT * FROM jumps WHERE athlete_id = ? ORDER BY created_at, id", (athlete_id,))]


def summary(rows):
    numeric = [key for key in Jump.model_fields if key not in ("test", "warnings")]
    values = [{**row, "knee_flexion_peak_mean": (row["knee_flexion_peak_l"] + row["knee_flexion_peak_r"]) / 2}
              for row in rows]
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


class RequestBodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        too_large = JSONResponse(status_code=413, content={"detail": "Request body too large"})
        for name, value in scope["headers"]:
            if name.lower() == b"content-length":
                try:
                    if int(value) > 16 * 1024:
                        return await too_large(scope, receive, send)
                except ValueError:
                    pass
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > 16 * 1024:
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
                "Referrer-Policy": "no-referrer", "Content-Security-Policy": policy}

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
    def login(body: Login, db: sqlite3.Connection = Depends(get_db)):
        now = time.time()
        # Keep write transactions short; scrypt must not hold the write lock.
        db.execute("BEGIN IMMEDIATE")
        db.execute("DELETE FROM login_failures WHERE failed_at <= ?", (now - 900,))
        failures = db.execute("SELECT COUNT(*) FROM login_failures WHERE email = ?", (body.email,)).fetchone()[0]
        db.commit()
        if failures >= 5:
            audit(db, None, "login_failure", body.email, {"reason": "rate_limit"})
            db.commit()
            raise HTTPException(429, "Too many failed logins; try again later", headers={"Retry-After": "900"})
        user = db.execute("SELECT * FROM users WHERE email = ?", (body.email,)).fetchone()
        salt, expected = (user["password_salt"], user["password_hash"]) if user else (DUMMY_SALT, DUMMY_HASH)
        matches = hmac.compare_digest(password_hash(body.password, salt), expected)
        # Recheck under the write lock so concurrent hashes cannot bypass the limit.
        now = time.time()
        db.execute("BEGIN IMMEDIATE")
        failures = db.execute(
            "SELECT COUNT(*) FROM login_failures WHERE email = ? AND failed_at > ?", (body.email, now - 900),
        ).fetchone()[0]
        if failures >= 5:
            audit(db, None, "login_failure", body.email, {"reason": "rate_limit"})
            db.commit()
            raise HTTPException(429, "Too many failed logins; try again later", headers={"Retry-After": "900"})
        # Read again under the lock: a reset or deactivation during scrypt must win.
        fresh = db.execute("SELECT * FROM users WHERE email = ?", (body.email,)).fetchone()
        if (not matches or fresh is None or not fresh["active"] or
                fresh["password_hash"] != expected):
            db.execute("INSERT INTO login_failures(email, failed_at) VALUES (?, ?)", (body.email, now))
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
            "SELECT users.id, users.name, users.club_id, COUNT(jumps.id) AS jump_count, "
            "MAX(jumps.created_at) AS last_jump_at FROM users LEFT JOIN jumps ON jumps.athlete_id = users.id "
            "WHERE users.role = 'athlete' AND (? = 'admin' OR users.club_id = ?) "
            "GROUP BY users.id ORDER BY users.name, users.id LIMIT ? OFFSET ?",
            (user["role"], user["club_id"], limit, offset),
        ).fetchall()
        result = []
        for row in rows:
            athlete = db.execute("SELECT * FROM users WHERE id = ?", (row["id"],)).fetchone()
            changes = summary(jump_rows(db, row["id"]))["metrics"]
            result.append({**dict(row), "active": bool(athlete["active"]), **consent_fields(athlete),
                           "changes": {key: changes[key] for key in
                                       ("knee_ankle_ratio", "knee_flexion_peak_mean", "flexion_asymmetry_pct")}})
        return result

    @app.post("/api/jumps", status_code=201)
    def add_jump(body: Jump, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        if not user["consent_recorded"]:
            raise HTTPException(403, "Consent must be recorded by a coach or admin before uploading jumps")
        created_at = datetime.now(timezone.utc).isoformat()
        cursor = db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                            (user["id"], created_at, body.model_dump_json()))
        db.commit()
        return {"id": cursor.lastrowid, "created_at": created_at}

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
        return [{"id": row["id"], "athlete_id": row["athlete_id"], "created_at": row["created_at"],
                 **json.loads(row["metrics"])} for row in rows]

    @app.delete("/api/me/data", status_code=204)
    def delete_data(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "athlete")
        db.execute("DELETE FROM jumps WHERE athlete_id = ?", (user["id"],))
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
        result = {"user": profile, "jumps": jump_rows(db, user["id"]), "exported_at": now_iso()}
        audit(db, user["id"], "json_export", user["id"])
        db.commit()
        return JSONResponse(result, headers={"Content-Disposition": 'attachment; filename="jumpscan-data.json"'})

    @app.get("/api/athletes/{athlete_id}/export.csv")
    def export_csv(athlete_id: int, user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin", "coach")
        athlete = athlete_access(db, user, athlete_id)
        output = io.StringIO(newline="")
        writer = csv.writer(output)
        fields = ["id", "athlete_id", "athlete_name", "created_at", *Jump.model_fields]
        writer.writerow(fields)
        for row in jump_rows(db, athlete_id):
            row["athlete_name"] = athlete["name"]
            writer.writerow([csv_cell(row[field]) for field in fields])
        audit(db, user["id"], "csv_export", athlete_id)
        db.commit()
        return Response(output.getvalue(), media_type="text/csv",
                        headers={"Content-Disposition": f'attachment; filename="athlete-{athlete_id}.csv"'})

    @app.get("/api/clubs")
    def clubs(user=Depends(current_user), db=Depends(get_db)):
        require_role(user, "admin")
        return [dict(row) for row in db.execute("SELECT * FROM clubs ORDER BY name, id")]

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
        return [{**public_user(row), "email": row["email"], "active": bool(row["active"]), **consent_fields(row)} for row in rows]

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
        return [{**dict(row), "detail": json.loads(row["detail"])} for row in db.execute(
            "SELECT * FROM audit ORDER BY id DESC LIMIT ? OFFSET ?", (limit, offset))]

    return app


app = create_app()
