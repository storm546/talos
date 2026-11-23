"""Field-test measurements of the wave-3 tools (SPEC 9.3): range of motion, balance, sprint, reaction, agility
and GPS cardio. The phone keeps images, landmarks and routes; only these numbers are stored.

`init_schema(db)` is called from `main.init_db`; `install(app, h)` from `main.create_app`, where `h` is the main
module (auth, scope, consent, audit and event helpers are reused).
"""

import json
from typing import Annotated, Literal, Union

from fastapi import Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .input_bounds import SQLITE_MAX

KINDS = ("rom", "balance", "sprint", "reaction", "agility", "cardio")


def init_schema(db):
    db.executescript("""
        CREATE TABLE IF NOT EXISTS measurements (
            id INTEGER PRIMARY KEY,
            athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at TEXT NOT NULL,
            kind TEXT NOT NULL,
            data TEXT NOT NULL,
            client_id TEXT NOT NULL,
            UNIQUE (athlete_id, client_id)
        );
        CREATE INDEX IF NOT EXISTS measurements_athlete ON measurements(athlete_id, created_at);
    """)


def delete_data(db, athlete_id):
    db.execute("DELETE FROM measurements WHERE athlete_id = ?", (athlete_id,))


def rows(db, athlete_id, kind=None):
    """Stored measurements oldest first, each as its fields plus id, kind, created_at and client_id."""
    return [{**json.loads(row["data"]), "id": row["id"], "athlete_id": row["athlete_id"], "kind": row["kind"],
             "created_at": row["created_at"], "client_id": row["client_id"]}
            for row in db.execute("SELECT * FROM measurements WHERE athlete_id = ? AND (? IS NULL OR kind = ?) "
                                  "ORDER BY created_at, id", (athlete_id, kind, kind))]


def export_data(db, athlete_id):
    return {"measurements": rows(db, athlete_id)}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


Unit = Annotated[float, Field(ge=0, le=1)]
Fps = Annotated[float, Field(gt=0, le=480)]
Precision = Annotated[float, Field(ge=0, le=1000)]
Degrees = Annotated[float, Field(ge=0, le=180)]


class Base(StrictModel):
    client_id: str = Field(min_length=1, max_length=128)


class Rom(Base):
    kind: Literal["rom"]
    joint: Literal["knee_flexion", "hip_flexion", "shoulder_flexion", "shoulder_abduction"]
    view: Literal["front", "side"]
    left_deg: Degrees | None
    right_deg: Degrees | None
    error_deg: float = Field(ge=0, le=45)
    quality: Unit
    warnings: list[Literal["low_visibility", "not_validated"]] = Field(max_length=2)

    @model_validator(mode="after")
    def one_side(self):
        if self.left_deg is None and self.right_deg is None:
            raise ValueError("At least one side must be measured")
        return self


class Balance(Base):
    kind: Literal["balance"]
    leg: Literal["L", "R"]
    stance_ms: int = Field(ge=0, le=60_000)
    sway_pct: float | None = Field(ge=0, le=100)
    ended_by: Literal["touch_down", "sway", "time_limit", "stopped", "lost_tracking"]
    fps: Fps
    quality: Unit


class Sprint(Base):
    kind: Literal["sprint"]
    distance_m: float = Field(ge=1, le=400)
    time_ms: int = Field(ge=100, le=120_000)
    start: Literal["cue", "crossing"]
    precision_ms: Precision
    fps: Fps


class Reaction(Base):
    kind: Literal["reaction"]
    time_ms: int = Field(ge=50, le=1500)
    precision_ms: Precision
    fps: Fps


class Agility(Base):
    kind: Literal["agility"]
    test: Literal["505", "t_test"]
    time_ms: int = Field(ge=500, le=60_000)
    precision_ms: Precision
    fps: Fps


class Cardio(Base):
    kind: Literal["cardio"]
    activity: Literal["run", "walk", "cycle"]
    distance_m: float = Field(ge=0, le=500_000)
    duration_s: int = Field(ge=1, le=86_400)
    avg_pace_s_per_km: float | None = Field(gt=0)
    splits_s: list[Annotated[float, Field(gt=0, le=86_400)]] = Field(max_length=500)

    @field_validator("avg_pace_s_per_km")
    @classmethod
    def plausible_pace(cls, value, info):
        if value is not None and info.data.get("activity") != "cycle" and not 60 <= value <= 3600:
            raise ValueError("Pace must be 60-3600 s/km for running and walking")
        return value


Measurement = Annotated[Union[Rom, Balance, Sprint, Reaction, Agility, Cardio], Field(discriminator="kind")]


def install(app, h):
    current_user, get_db = h.current_user, h.get_db

    @app.post("/api/measurements", status_code=201, dependencies=[Depends(current_user)])
    def add_measurement(body: Measurement, request: Request, db=Depends(get_db)):
        with h.authorized_write(db, request) as (athlete, emit):
            h.require_role(athlete, "athlete")
            if not athlete["consent_recorded"]:
                raise HTTPException(403, "Consent must be recorded by a coach or admin before uploading measurements")
            previous = db.execute("SELECT id, created_at FROM measurements WHERE athlete_id = ? AND client_id = ?",
                                  (athlete["id"], body.client_id)).fetchone()
            if previous is not None:
                return JSONResponse(status_code=200, content=dict(previous))
            created_at = h.now_iso()
            data = body.model_dump(exclude={"client_id", "kind"})
            row_id = db.execute(
                "INSERT INTO measurements(athlete_id, created_at, kind, data, client_id) VALUES (?, ?, ?, ?, ?)",
                (athlete["id"], created_at, body.kind, json.dumps(data), body.client_id)).lastrowid
            h.audit(db, athlete["id"], "measurement_logged", row_id, {"kind": body.kind})
            emit(h.athlete_viewers(db, athlete), {"type": "measurement", "id": row_id, "athlete_id": athlete["id"],
                                                  "club_id": athlete["club_id"], "at": created_at})
            h.publish_record(db, athlete, "measurement", row_id, created_at, emit)
        return JSONResponse(status_code=201, content={"id": row_id, "created_at": created_at})

    @app.get("/api/measurements")
    def list_measurements(athlete_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                          kind: Literal[KINDS] | None = None,
                          limit: Annotated[int, Query(ge=1, le=500)] = 100,
                          offset: Annotated[int, Query(ge=0, le=SQLITE_MAX)] = 0,
                          user=Depends(current_user), db=Depends(get_db)):
        if user["role"] == "athlete" and athlete_id is None:
            athlete_id = user["id"]
        if athlete_id is not None:
            h.athlete_access(db, user, athlete_id)
        return [{**json.loads(row["data"]), "id": row["id"], "athlete_id": row["athlete_id"], "kind": row["kind"],
                 "created_at": row["created_at"], "client_id": row["client_id"]} for row in db.execute(
            "SELECT measurements.* FROM measurements JOIN users ON users.id = measurements.athlete_id "
            "WHERE (? IS NULL OR measurements.athlete_id = ?) AND (? IS NULL OR measurements.kind = ?) "
            "AND (? != 'coach' OR users.club_id = ?) "
            "ORDER BY measurements.created_at DESC, measurements.id DESC LIMIT ? OFFSET ?",
            (athlete_id, athlete_id, kind, kind, user["role"], user["club_id"], limit, offset))]

    return {"list_measurements": list_measurements}
