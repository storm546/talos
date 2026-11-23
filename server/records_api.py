"""Personal records and the club leaderboard (SPEC 8), computed from stored jumps, set logs and form sets.

`init_schema(db)` is called from `main.init_db`; `install(app, h)` from `main.create_app`, where `h` is the
main module (auth, scope and audit helpers are reused).
"""

from datetime import datetime, timedelta, timezone
import json
from typing import Annotated, Literal

from fastapi import Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict

from .input_bounds import SQLITE_MAX, SqlitePath

# measure: (jump field, unit, decimals used for display and for ranking ties)
JUMP_MEASURES = {"jump_height": ("jump_height_cm", "cm", 1), "flight_time": ("flight_time_ms", "ms", 0)}
EPLEY_MAX_REPS = 10
NEW_RECORD_DAYS = 7


def init_schema(db):
    columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
    for name in ("leaderboard_opt_in", "leaderboard_allowed"):
        if name not in columns:
            db.execute(f"ALTER TABLE users ADD COLUMN {name} INTEGER NOT NULL DEFAULT 0 CHECK({name} IN (0, 1))")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class LeaderboardChoice(StrictModel):
    leaderboard_opt_in: bool


def valid_jumps(db, athlete_id, since=None):
    """Jumps without any warning, oldest first, and how many were left out because of a warning."""
    rows, excluded = [], 0
    for row in db.execute("SELECT id, created_at, metrics FROM jumps WHERE athlete_id = ? "
                          "AND (? IS NULL OR created_at >= ?) ORDER BY created_at, id", (athlete_id, since, since)):
        metrics = json.loads(row["metrics"])
        if metrics.get("warnings"):
            excluded += 1
        else:
            rows.append({**metrics, "id": row["id"], "date": row["created_at"]})
    return rows, excluded


def progression(rows, key):
    """The best row and the best before it (the record it beat); a tie keeps the earlier record."""
    best = previous = None
    for row in rows:
        if best is None or key(row) > key(best):
            previous, best = best, row
    return best, previous


def epley(load, reps):
    return load if reps == 1 else load * (1 + reps / 30)


def compute_records(db, athlete_id, catalogue):
    recent = (datetime.now(timezone.utc) - timedelta(days=NEW_RECORD_DAYS)).date().isoformat()
    records = []

    def add(measure, unit, source, rows, value, rank=None, **extra):
        best, previous = progression(rows, rank or value)
        if best is None:
            return

        def point(row):
            sets = {"load_kg": row["load_kg"], "reps": row["reps"]} if source == "set_log" else {}
            return {"value": value(row), "date": row["date"], "source_id": row["id"], **sets}
        records.append({"measure": measure, "unit": unit, "source": source, **extra, **point(best),
                        "previous": point(previous) if previous else None,
                        "new": previous is not None and best["date"][:10] >= recent})

    jumps, excluded = valid_jumps(db, athlete_id)
    for measure, (field, unit, _) in JUMP_MEASURES.items():
        add(measure, unit, "jump", [j for j in jumps if j.get(field) is not None], lambda row, f=field: row[f])

    holds = {}
    for row in db.execute("SELECT id, created_at, metrics FROM form_sets WHERE athlete_id = ? ORDER BY created_at, id",
                          (athlete_id,)):
        metrics = json.loads(row["metrics"])
        entry = (catalogue or {}).get(metrics.get("exercise"))
        hold = (metrics.get("summary") or {}).get("hold_ms")
        if entry and entry.get("mode") == "hold" and not entry.get("experimental") and hold:
            holds.setdefault(entry["id"], []).append({"id": row["id"], "date": row["created_at"], "hold_ms": hold})
    for exercise, rows in holds.items():
        entry = catalogue[exercise]
        add("form_hold", "ms", "form_set", rows, lambda row: row["hold_ms"], exercise=exercise,
            name_bg=entry["name_bg"], name_en=entry["name_en"])

    # Field tests (SPEC 9.3): longest single-leg stance per leg, fastest sprint per distance and start (a beep start
    # includes reaction time, a line start does not) and fastest agility per test.
    tests = {}
    for row in db.execute("SELECT id, created_at, kind, data FROM measurements WHERE athlete_id = ? "
                          "AND kind IN ('balance', 'sprint', 'agility') ORDER BY created_at, id", (athlete_id,)):
        data = json.loads(row["data"])
        measure, extra = {"balance": ("balance_stance", {"leg": data.get("leg")}),
                          "sprint": ("sprint_time", {"distance_m": data.get("distance_m"), "start": data.get("start")}),
                          "agility": ("agility_time", {"test": data.get("test")})}[row["kind"]]
        value = data["stance_ms"] if row["kind"] == "balance" else data["time_ms"]
        tests.setdefault((measure, tuple(extra.items())), []).append({"id": row["id"], "date": row["created_at"],
                                                                        "value": value})
    for (measure, extra), rows in sorted(tests.items(), key=lambda item: (item[0][0], str(item[0][1]))):
        faster = measure != "balance_stance"
        add(measure, "ms", "measurement", rows, lambda row: row["value"],
            rank=(lambda row: -row["value"]) if faster else None, **dict(extra))

    sets = {}
    for row in db.execute(
            "SELECT set_logs.id, day AS date, exercise_id, exercises.name, reps, load_kg FROM set_logs "
            "JOIN exercises ON exercises.id = set_logs.exercise_id WHERE athlete_id = ? AND load_kg > 0 "
            "ORDER BY day, set_logs.id", (athlete_id,)):
        sets.setdefault((row["name"], row["exercise_id"]), []).append(dict(row))
    for (name, exercise_id), rows in sorted(sets.items()):
        add("heaviest_set", "kg", "set_log", rows, lambda row: row["load_kg"],
            rank=lambda row: (row["load_kg"], row["reps"]), exercise_id=exercise_id, exercise=name)
        add("estimated_1rm", "kg", "set_log", [r for r in rows if r["reps"] <= EPLEY_MAX_REPS],
            lambda row: epley(row["load_kg"], row["reps"]), exercise_id=exercise_id, exercise=name)
    return {"athlete_id": athlete_id, "excluded_jumps": excluded, "records": records}


def beats_record(db, athlete_id, source, row_id, catalogue):
    """True when the new row set a record that replaced an earlier one."""
    return any(record["source"] == source and record["source_id"] == row_id and record["previous"]
               for record in compute_records(db, athlete_id, catalogue)["records"])


def display_name(name):
    """First name and last initial: "Maria Ivanova" becomes "Maria I."."""
    parts = name.split()
    return parts[0] + " " + parts[-1][0] + "." if len(parts) > 1 else name.strip()


def on_leaderboard(athlete):
    """Opt-in by the athlete; under 18 also needs the coach's confirmation; consent must stand."""
    return bool(athlete["leaderboard_opt_in"] and athlete["consent_recorded"] and
                (not athlete["under_18"] or athlete["leaderboard_allowed"]))


def leaderboard_state(athlete):
    return {"leaderboard_opt_in": bool(athlete["leaderboard_opt_in"]),
            "leaderboard_allowed": bool(athlete["leaderboard_allowed"]),
            "under_18": bool(athlete["under_18"]), "shown": on_leaderboard(athlete)}


def install(app, h):
    current_user, get_db = h.current_user, h.get_db

    @app.get("/api/athletes/{athlete_id}/records")
    def athlete_records(athlete_id: SqlitePath, user=Depends(current_user), db=Depends(get_db)):
        h.athlete_access(db, user, athlete_id)
        return compute_records(db, athlete_id, h.FORM_EXERCISES)

    @app.patch("/api/me/profile")
    def set_leaderboard_choice(body: LeaderboardChoice, user=Depends(current_user), db=Depends(get_db)):
        h.require_role(user, "athlete")
        db.execute("UPDATE users SET leaderboard_opt_in = ? WHERE id = ?", (body.leaderboard_opt_in, user["id"]))
        h.audit(db, user["id"], "leaderboard_opt_in_changed", user["id"], body.model_dump())
        db.commit()
        return leaderboard_state(db.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone())

    @app.get("/api/clubs/me/leaderboard")
    def leaderboard(measure: Literal["jump_height", "flight_time"] = "jump_height",
                    period: Literal["30", "90", "all"] = "all",
                    group_id: Annotated[int | None, Query(gt=0, le=SQLITE_MAX)] = None,
                    user=Depends(current_user), db=Depends(get_db)):
        if group_id is not None and h.scoped_group(db, user, group_id)["club_id"] != user["club_id"]:
            raise HTTPException(404, "Group not found")
        since = None if period == "all" else (datetime.now(timezone.utc) - timedelta(days=int(period))).isoformat()
        field, unit, digits = JUMP_MEASURES[measure]
        rows, not_shown = [], 0
        for athlete in db.execute(
                "SELECT * FROM users WHERE role = 'athlete' AND active = 1 AND club_id = ? AND (? IS NULL OR id IN "
                "(SELECT user_id FROM group_members WHERE group_id = ?)) ORDER BY id",
                (user["club_id"], group_id, group_id)):
            if not on_leaderboard(athlete):
                not_shown += 1
                continue
            values = [jump for jump in valid_jumps(db, athlete["id"], since)[0] if jump.get(field) is not None]
            if values:
                best = max(values, key=lambda jump: round(jump[field], digits))
                rows.append({"name": display_name(athlete["name"]), "value": round(best[field], digits),
                             "date": best["date"], "you": athlete["id"] == user["id"]})
        rows.sort(key=lambda row: (-row["value"], row["name"]))
        for index, row in enumerate(rows):
            tied = index and row["value"] == rows[index - 1]["value"]
            row["rank"] = rows[index - 1]["rank"] if tied else index + 1
        result = {"measure": measure, "unit": unit, "period": period, "group_id": group_id, "rows": rows}
        if user["role"] == "athlete":
            result["me"] = leaderboard_state(user)
        else:
            result["not_shown"] = not_shown
        return result
