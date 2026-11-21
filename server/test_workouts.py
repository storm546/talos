"""Workouts, workout templates and set logs linked to a workout (SPEC 9, wave 1)."""

from datetime import datetime, timedelta, timezone
import re
import sqlite3

from fastapi.testclient import TestClient

from server import main
from server.test_server import api, auth, template, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import audit_actions, day, exercise_id, make_athlete


def stamp(hours_ago=0.0):
    return (datetime.now(timezone.utc) - timedelta(hours=hours_ago)).isoformat()


def workout(client_id="w-1", **extra):
    return {"started_at": stamp(1.5), "ended_at": stamp(0.5), "title": "Сила", "notes": "", "client_id": client_id, **extra}


def items(client, headers):
    return [{"exercise_id": exercise_id(client, headers), "sets": 3, "reps": 5, "load_kg": 60, "rest_s": 120},
            {"exercise_id": exercise_id(client, headers, "Планк"), "sets": 2, "reps": 1, "superset": 1}]


# --- templates ---

def test_templates_owner_and_club_scope(api, auth):
    client, path = api
    athlete, coach = auth("athlete1"), auth("coach")
    private = client.post("/api/workout-templates", json={"title": "Моя", "items": items(client, athlete)}, headers=athlete)
    assert private.status_code == 201, private.text
    mine = private.json()
    assert mine["club_id"] is None and mine["owner_id"] == user_id(path, "athlete1")
    assert mine["items"][0]["exercise"] == "Клякане със щанга" and mine["items"][1]["superset"] == 1
    shared = client.post("/api/workout-templates", json={"title": "Клубна", "items": items(client, coach)}, headers=coach)
    assert shared.status_code == 201 and shared.json()["club_id"] is not None
    seen = lambda who: {t["id"] for t in client.get("/api/workout-templates", headers=auth(who)).json()}
    assert {mine["id"], shared.json()["id"]} <= seen("athlete1")
    assert mine["id"] not in seen("athlete2") and shared.json()["id"] in seen("athlete2")
    assert mine["id"] not in seen("coach") and shared.json()["id"] in seen("coach")
    assert not {mine["id"], shared.json()["id"]} & (seen("coach2") | seen("athlete3"))
    assert shared.json()["id"] in seen("admin") and mine["id"] not in seen("admin")
    # The owner edits a private template; an athlete cannot edit the club's template.
    renamed = client.put(f"/api/workout-templates/{mine['id']}", json={"title": "Моя 2", "items": items(client, athlete)},
                         headers=athlete)
    assert renamed.status_code == 200 and renamed.json()["title"] == "Моя 2"
    body = {"title": "x", "items": items(client, coach)}
    assert client.put(f"/api/workout-templates/{shared.json()['id']}", json=body, headers=athlete).status_code == 404
    assert client.put(f"/api/workout-templates/{mine['id']}", json=body, headers=auth("athlete2")).status_code == 404
    assert client.put(f"/api/workout-templates/{shared.json()['id']}", json=body, headers=coach).status_code == 200
    assert client.delete(f"/api/workout-templates/{mine['id']}", headers=athlete).status_code == 204
    assert {"workout_template_created", "workout_template_changed", "workout_template_deleted"} <= set(audit_actions(client, auth))


def test_template_cross_club_requests_return_404(api, auth):
    client, _ = api
    coach = auth("coach")
    shared = client.post("/api/workout-templates", json={"title": "Клубна", "items": items(client, coach)}, headers=coach).json()
    body = {"title": "Чужда", "items": items(client, coach)}
    for who in ("coach2", "athlete3"):
        assert client.put(f"/api/workout-templates/{shared['id']}", json=body, headers=auth(who)).status_code == 404
        assert client.delete(f"/api/workout-templates/{shared['id']}", headers=auth(who)).status_code == 404
    # Exercises of another club, another club id from a coach, unknown fields and empty lists are rejected.
    other = [{"exercise_id": exercise_id(client, auth("coach2")), "sets": 3, "reps": 5}]
    assert client.post("/api/workout-templates", json={"title": "x", "items": other}, headers=coach).status_code == 422
    club2 = client.get("/api/exercises", headers=auth("coach2")).json()[0]["club_id"]
    assert client.post("/api/workout-templates", json={**body, "club_id": club2}, headers=coach).status_code == 403
    assert client.post("/api/workout-templates", json={**body, "club_id": club2}, headers=auth("athlete1")).status_code == 422
    for bad in ({**body, "items": []}, {**body, "extra": 1}, {**body, "title": " "},
                {**body, "items": [{**body["items"][0], "sets": 0}]}):
        assert client.post("/api/workout-templates", json=bad, headers=coach).status_code == 422
    assert client.delete(f"/api/workout-templates/{shared['id']}", headers=coach).status_code == 204
    assert client.delete(f"/api/workout-templates/{shared['id']}", headers=coach).status_code == 404


def test_template_needs_consent(api, auth):
    client, _ = api
    _, headers = make_athlete(client, auth, consent=False)
    response = client.post("/api/workout-templates", json={"title": "x", "items": items(client, headers)}, headers=headers)
    assert response.status_code == 403


# --- workouts ---

def test_workout_idempotent_client_id_and_event(api, auth):
    client, path = api
    athlete = auth("athlete4")
    coach_queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    first = client.post("/api/workouts", json=workout(), headers=athlete)
    assert first.status_code == 201, first.text
    stored = first.json()
    assert stored["athlete_id"] == user_id(path, "athlete4") and stored["duration_s"] == 3600 and stored["sets"] == 0
    assert stored["started_at"].endswith("+00:00")
    assert coach_queue.get_nowait()["type"] == "workout"
    again = client.post("/api/workouts", json={**workout(), "title": "Друго"}, headers=athlete)
    assert again.status_code == 200 and again.json() == stored and coach_queue.empty()
    assert client.post("/api/workouts", json=workout(), headers=auth("athlete5")).status_code == 201  # another athlete
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM workouts WHERE client_id = 'w-1'").fetchone()[0] == 2
    assert "workout_logged" in audit_actions(client, auth)
    client.app.state.events.close(user_id(path, "coach"), coach_queue)


def test_set_logs_carry_workout_id_and_summary(api, auth):
    client, path = api
    athlete = auth("athlete4")
    created = client.post("/api/workouts", json=workout("w-sets"), headers=athlete).json()
    squat = exercise_id(client, athlete)
    base = {"day": day(), "exercise_id": squat, "reps": 5, "load_kg": 60, "rpe": None}
    by_client = client.post("/api/set-logs", json={**base, "set_no": 1, "workout_client_id": "w-sets", "client_id": "s1"},
                            headers=athlete)
    assert by_client.status_code == 201 and by_client.json()["workout_id"] == created["id"]
    by_id = client.post("/api/set-logs", json={**base, "set_no": 2, "reps": 3, "workout_id": created["id"]}, headers=athlete)
    assert by_id.status_code == 201 and by_id.json()["workout_id"] == created["id"]
    plain = client.post("/api/set-logs", json={**base, "set_no": 3}, headers=athlete)
    assert plain.status_code == 201 and plain.json()["workout_id"] is None
    # Unknown, foreign and doubled references are rejected.
    foreign = client.post("/api/workouts", json=workout("w-foreign"), headers=auth("athlete5")).json()["id"]
    for bad in ({"workout_client_id": "missing"}, {"workout_id": foreign},
                {"workout_id": created["id"], "workout_client_id": "w-sets"}):
        assert client.post("/api/set-logs", json={**base, "set_no": 4, **bad}, headers=athlete).status_code == 422
    listed = client.get("/api/workouts", headers=athlete).json()
    row = next(w for w in listed if w["id"] == created["id"])
    assert row["sets"] == 2 and row["exercises"] == 1 and row["volume_kg"] == 480
    detail = client.get(f"/api/workouts/{created['id']}", headers=athlete).json()
    assert [s["set_no"] for s in detail["set_logs"]] == [1, 2] and detail["set_logs"][0]["exercise"] == "Клякане със щанга"
    # Deleting the workout keeps its sets as plain training history.
    assert client.delete(f"/api/workouts/{created['id']}", headers=athlete).status_code == 204
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM set_logs WHERE id = ? AND workout_id IS NULL",
                          (by_client.json()["id"],)).fetchone()[0] == 1


def test_workout_scope_roles_and_validation(api, auth):
    client, path = api
    athlete = auth("athlete1")
    created = client.post("/api/workouts", json=workout("w-scope"), headers=athlete).json()
    a1 = user_id(path, "athlete1")
    assert client.get(f"/api/workouts/{created['id']}", headers=auth("coach")).status_code == 200
    assert any(w["id"] == created["id"] for w in client.get(f"/api/workouts?athlete_id={a1}", headers=auth("coach")).json())
    assert client.get(f"/api/workouts/{created['id']}", headers=auth("admin")).status_code == 200
    for who in ("coach2", "athlete2", "athlete3"):
        assert client.get(f"/api/workouts/{created['id']}", headers=auth(who)).status_code == 404
    assert client.get(f"/api/workouts?athlete_id={a1}", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/workouts?athlete_id={a1}", headers=auth("athlete2")).status_code == 403
    assert client.delete(f"/api/workouts/{created['id']}", headers=auth("athlete2")).status_code == 404
    assert client.delete(f"/api/workouts/{created['id']}", headers=auth("coach")).status_code == 403
    assert client.post("/api/workouts", json=workout("w-coach"), headers=auth("coach")).status_code == 403
    _, no_consent = make_athlete(client, auth, consent=False)
    assert client.post("/api/workouts", json=workout("w-consent"), headers=no_consent).status_code == 403
    old = (datetime.now(timezone.utc) - timedelta(days=40)).isoformat()
    for bad in ({"started_at": "2026-11-20T10:00:00"}, {"ended_at": stamp(3)}, {"started_at": old, "ended_at": None},
                {"started_at": stamp(30), "ended_at": stamp(1)}, {"title": ""}, {"extra": 1}):
        assert client.post("/api/workouts", json={**workout("w-bad"), **bad}, headers=athlete).status_code == 422, bad
    private = client.post("/api/workout-templates", json={"title": "Чужда", "items": items(client, auth("athlete2"))},
                          headers=auth("athlete2")).json()["id"]
    assert client.post("/api/workouts", json=workout("w-tpl", template_id=private), headers=athlete).status_code == 422
    # An open workout (no end yet) has no duration.
    assert client.post("/api/workouts", json=workout("w-open", ended_at=None), headers=athlete).json()["duration_s"] is None


def test_export_and_delete_cover_workouts(api, auth):
    client, path = api
    athlete = auth("athlete1")
    assert client.post("/api/workout-templates", json={"title": "Моя", "items": items(client, athlete)},
                       headers=athlete).status_code == 201
    data = client.get("/api/me/export", headers=athlete).json()
    assert [w["title"] for w in data["workouts"]] == ["Демо тренировка"]
    assert {"Моя", "Моята бърза тренировка"} == {t["title"] for t in data["workout_templates"]}
    assert any(s["workout_id"] == data["workouts"][0]["id"] for s in data["set_logs"])
    assert client.delete("/api/me/data", headers=athlete).status_code == 204
    after = client.get("/api/me/export", headers=athlete).json()
    assert after["workouts"] == after["workout_templates"] == []
    with main.database(path) as db:  # the coach's shared template stays
        assert db.execute("SELECT COUNT(*) FROM workout_templates WHERE club_id IS NOT NULL").fetchone()[0] == 1


def test_seed_has_a_finished_workout_and_two_templates(api, auth):
    client, path = api
    rows = client.get("/api/workouts", headers=auth("athlete1")).json()
    assert len(rows) == 1 and rows[0]["duration_s"] == 64 * 60 and rows[0]["sets"] > 0 and rows[0]["volume_kg"] > 0
    titles = {t["title"]: t for t in client.get("/api/workout-templates", headers=auth("athlete1")).json()}
    assert set(titles) == {"Долна част: сила", "Моята бърза тренировка"}
    assert rows[0]["template_id"] == titles["Долна част: сила"]["id"]
    from server.seed import seed
    seed(path)
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM workout_templates").fetchone()[0] == 2
        assert db.execute("SELECT COUNT(*) FROM workouts").fetchone()[0] == 1


def test_migrates_a_database_without_workouts(tmp_path):
    from server.seed import seed
    from server.training_nutrition_api import SCHEMA
    path = tmp_path / "old.db"
    seed(path)
    old_table = re.search(r"CREATE TABLE IF NOT EXISTS set_logs \(.*?\n    \);", SCHEMA, re.S).group(0)
    columns = "id, athlete_id, day, exercise_id, set_no, reps, load_kg, rpe, plan_id, client_id, created_at"
    with sqlite3.connect(path) as db:  # rebuild the shape of a database from before SPEC 9, keeping its sets
        db.executescript(f"""PRAGMA foreign_keys = OFF; DROP INDEX set_logs_workout;
            {old_table.replace("set_logs", "set_logs_old", 1)}
            INSERT INTO set_logs_old({columns}) SELECT {columns} FROM set_logs;
            DROP TABLE set_logs; ALTER TABLE set_logs_old RENAME TO set_logs;
            DROP TABLE workouts; DROP TABLE workout_templates;""")
        before = db.execute("SELECT COUNT(*) FROM set_logs").fetchone()[0]
        assert before > 0 and "workout_id" not in {r[1] for r in db.execute("PRAGMA table_info(set_logs)")}
    for _ in range(2):  # a second start changes nothing
        main.init_db(path)
    with main.database(path) as db:
        assert "workout_id" in {r["name"] for r in db.execute("PRAGMA table_info(set_logs)")}
        assert {"workouts", "workout_templates", "workouts_client", "set_logs_workout"} <= {
            r["name"] for r in db.execute("SELECT name FROM sqlite_master")}
        assert db.execute("SELECT COUNT(*) FROM set_logs WHERE workout_id IS NULL").fetchone()[0] == before
    with TestClient(main.create_app(str(path))) as client:
        token = client.post("/api/auth/login", json={"email": "athlete1@demo.test", "password": "demo1234"}).json()["token"]
        headers = {"Authorization": "Bearer " + token}
        created = client.post("/api/workouts", json=workout("w-migrated"), headers=headers)
        assert created.status_code == 201
        logged = client.post("/api/set-logs", json={"day": day(), "exercise_id": exercise_id(client, headers), "set_no": 1,
                                                    "reps": 5, "workout_client_id": "w-migrated"}, headers=headers)
        assert logged.status_code == 201 and logged.json()["workout_id"] == created.json()["id"]
