"""Today: one scoped aggregate for the phone's home screen and the athlete's dashboard page (SPEC 10.2).

Every section calls the same route functions the app uses one by one, as the caller, so its scope and its numbers
are always the ones those routes return. A section that fails or has nothing to show is null; the others still
return. No tables of its own.
"""

from datetime import date, datetime, timedelta, timezone
from typing import Annotated

from fastapi import Depends, HTTPException, Query

from .readiness_api import wellbeing_sum

MAX_PLAN_ITEMS, MAX_SESSIONS, MAX_RECORDS = 20, 10, 10


def route(app, path):
    """The endpoint function of a GET route, called directly with explicit arguments."""
    return next(r.endpoint for r in app.router.routes if getattr(r, "path", None) == path and "GET" in r.methods)


def readiness(c):
    days = c.get("/api/athletes/{athlete_id}/readiness")(athlete_id=c.uid, days=1, day=c.day, user=c.user, db=c.db)
    today = days["days"][0]
    return {"status": today["status"], "still_learning": today["status"] == "still_learning",
            "checked_in": today["checked_in"], "factors": today["factors"], "note": days["note"]}


def checkin(c):
    row = c.db.execute("SELECT * FROM checkins WHERE athlete_id = ? AND day = ?", (c.uid, c.day)).fetchone()
    return {"done": row is not None, "wellbeing": None if row is None else wellbeing_sum(dict(row)), "out_of": 25}


def plan(c):
    weekday, items = c.date.weekday(), []
    for p in c.get("/api/plans")(club_id=None, user=c.user, db=c.db):
        start = date.fromisoformat(p["starts_on"])
        if not start <= c.date < start + timedelta(weeks=p["weeks"]):
            continue
        items += [{"plan_id": p["id"], "plan": p["title"], "exercise": i["exercise"], "sets": i["sets"],
                   "reps": i["reps"], "reps_max": i["reps_max"], "load_kg": i["load_kg"]}
                  for i in p["items"] if i["weekday"] == weekday]
    workouts = c.get("/api/workouts")(athlete_id=None, from_=c.day, to=c.day, limit=1, offset=0, user=c.user, db=c.db)
    sets = c.db.execute("SELECT COUNT(*) FROM set_logs WHERE athlete_id = ? AND day = ?", (c.uid, c.day)).fetchone()[0]
    return {"items": items[:MAX_PLAN_ITEMS], "more": max(len(items) - MAX_PLAN_ITEMS, 0),
            "logged": bool(workouts) or sets > 0}


def training(c):
    sessions = c.get("/api/training-sessions")(athlete_id=None, from_=c.day, to=c.day, user=c.user, db=c.db)
    load = c.get("/api/athletes/{athlete_id}/load")(athlete_id=c.uid, days=7, day=c.day, user=c.user, db=c.db)
    ratio = load["acute_chronic"]
    return {"sessions": [{k: s[k] for k in ("id", "kind", "minutes", "rpe", "load", "source")}
                         for s in sessions[:MAX_SESSIONS]],
            "total_load": sum(s["load"] for s in sessions),
            "acute_chronic": {**ratio, "still_learning": not ratio["ready"]}}


def nutrition(c):
    summary = c.get("/api/athletes/{athlete_id}/nutrition/summary")(athlete_id=c.uid, day=c.day, user=c.user, db=c.db)
    keys = ("kcal", "protein_g", "carbs_g", "fat_g")
    logs = c.db.execute("SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ?", (c.uid, c.day)).fetchone()[0]
    return {"eaten": {k: summary["totals"][k] for k in keys}, "target": summary["target"], "logs": logs}


def weight(c):
    series = c.get("/api/athletes/{athlete_id}/weights")(athlete_id=c.uid, from_=None, to=c.day, user=c.user, db=c.db)
    if not series["weights"]:
        return None
    latest = series["weights"][-1]
    trend = series["trend"][-1] if series["trend"] else None
    return {"day": latest["day"], "kg": latest["kg"], "trend_kg": trend and trend["trend_kg"],
            "trend_day": trend and trend["day"]}


def records(c):
    found = c.get("/api/athletes/{athlete_id}/records")(athlete_id=c.uid, user=c.user, db=c.db)["records"]
    since = (c.date - timedelta(days=6)).isoformat()
    recent = sorted((r for r in found if r["new"] and since <= r["date"][:10] <= c.day),
                    key=lambda r: r["date"], reverse=True)
    return [{k: r.get(k) for k in ("measure", "value", "unit", "date", "exercise", "name_bg", "name_en")}
            for r in recent[:MAX_RECORDS]]


def next_session(c):
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    sessions = c.get("/api/sessions")(from_=now, to=None, limit=20, offset=0, user=c.user, db=c.db)
    upcoming = next((s for s in sessions if not s["cancelled"]), None)
    return upcoming and {k: upcoming[k] for k in ("id", "title", "starts_at", "place", "test")}


def unread(c):
    return c.get("/api/me/unread")(user=c.user, db=c.db)["comments"]


SECTIONS = {"readiness": readiness, "checkin": checkin, "plan": plan, "training": training, "nutrition": nutrition,
            "weight": weight, "records": records, "next_session": next_session, "unread": unread}
EVERYONE = {"next_session"}  # the rest is the athlete's own data; staff get null


class Context:
    def __init__(self, app, user, db, day):
        self.app, self.user, self.db, self.uid = app, user, db, user["id"]
        self.date, self.day = day, day.isoformat()

    def get(self, path):
        return route(self.app, path)


def install(app, h):
    @app.get("/api/me/today")
    def today(day: Annotated[str | None, Query(max_length=10)] = None,
              user=Depends(h.current_user), db=Depends(h.get_db)):
        server_today = datetime.now(timezone.utc).date()
        try:
            chosen = date.fromisoformat(day) if day else server_today
        except ValueError:
            raise HTTPException(422, "day must be YYYY-MM-DD") from None
        if abs((chosen - server_today).days) > 1:
            raise HTTPException(422, "day must be within one day of the server's date (UTC)")
        context = Context(app, user, db, chosen)
        result = {"day": chosen.isoformat(), "role": user["role"]}
        for name, section in SECTIONS.items():
            if user["role"] != "athlete" and name not in EVERYONE:
                result[name] = None
                continue
            try:
                result[name] = section(context)
            except Exception:  # one broken or forbidden section must not hide the others (SPEC 10.2)
                result[name] = None
        return result
