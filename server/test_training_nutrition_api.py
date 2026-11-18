"""Training (6.2) and nutrition (6.3) routes: roles, scope, consent, idempotency, guardrails, export, migration, seed."""

import csv
from datetime import datetime, timedelta, timezone
import io
import json
import sqlite3

from fastapi.testclient import TestClient
import pytest

from server import main
from server.seed import seed
from server.test_server import METRICS, api, auth, new_user, template, temporary_directory, user_id
from server.test_club_v2 import group_id


def day(offset=0):
    return (datetime.now(timezone.utc).date() + timedelta(days=offset)).isoformat()


def make_athlete(client, auth, email="fresh@demo.test", consent=True, coach="coach"):
    created = client.post("/api/users", json=new_user(email=email), headers=auth(coach))
    assert created.status_code == 201, created.text
    uid = created.json()["id"]
    if consent:
        assert client.patch(f"/api/users/{uid}/consent", headers=auth(coach),
                            json={"consent_recorded": True, "consent_by": "Parent"}).status_code == 200
    token = client.post("/api/auth/login", json={"email": email, "password": "long-password"}).json()["token"]
    return uid, {"Authorization": "Bearer " + token}


def exercise_id(client, headers, name="Клякане със щанга"):
    return next(e["id"] for e in client.get("/api/exercises", headers=headers).json() if e["name"] == name)


def foods(client, headers, query):
    return client.get("/api/foods", params={"q": query}, headers=headers).json()


SET = {"exercise_id": None, "set_no": 1, "reps": 8, "load_kg": 60, "rpe": 7.5}


def audit_actions(client, auth):
    return [row["action"] for row in client.get("/api/audit?limit=500", headers=auth("admin")).json()]


# --- authentication ---

@pytest.mark.parametrize("method,path", [
    ("GET", "/api/exercises"), ("POST", "/api/exercises"), ("GET", "/api/plans"), ("POST", "/api/plans"),
    ("POST", "/api/set-logs"), ("GET", "/api/set-logs"), ("GET", "/api/foods"), ("POST", "/api/foods"),
    ("POST", "/api/food-logs"), ("GET", "/api/food-logs"), ("PUT", f"/api/me/weights/{day()}"),
    ("GET", "/api/athletes/1/weights"), ("GET", "/api/athletes/1/training"), ("GET", "/api/athletes/1/nutrition/estimate"),
    ("GET", "/api/athletes/1/nutrition/target"), ("PUT", "/api/athletes/1/nutrition/target"),
    ("GET", "/api/athletes/1/nutrition/summary"), ("PATCH", "/api/users/1/profile"),
    ("GET", "/api/athletes/1/nutrition/expenditure"), ("GET", "/api/athletes/1/nutrition/program"),
    ("PUT", "/api/athletes/1/nutrition/program"), ("PUT", f"/api/me/nutrition/days/{day()}/complete"),
    ("GET", "/api/athletes/1/training.csv"), ("GET", "/api/athletes/1/nutrition.csv"), ("GET", "/api/athletes/1/weights.csv"),
])
def test_new_endpoints_require_a_token(api, method, path):
    client, _ = api
    assert client.request(method, path, json={}).status_code == 401


# --- exercises ---

def test_exercises_scope_and_roles(api, auth):
    client, path = api
    own = client.get("/api/exercises", headers=auth("athlete1")).json()
    assert {e["name"] for e in own} >= {"Клякане със щанга", "Планк"} and len({e["club_id"] for e in own}) == 1
    assert client.get("/api/exercises", headers=auth("coach")).json() == own
    club2 = client.get("/api/exercises", headers=auth("coach2")).json()
    assert club2 and {e["club_id"] for e in club2}.isdisjoint({e["club_id"] for e in own})
    assert len(client.get("/api/exercises", headers=auth("admin")).json()) == len(own) + len(club2)
    only = client.get("/api/exercises", params={"club_id": own[0]["club_id"]}, headers=auth("admin")).json()
    assert only == own
    assert client.get("/api/exercises", params={"club_id": own[0]["club_id"]}, headers=auth("coach2")).json() == club2


def test_exercise_create_validate_delete(api, auth):
    client, path = api
    coach = auth("coach")
    body = {"name": "Мостче", "kind": "mobility", "lower_body": False}
    created = client.post("/api/exercises", json=body, headers=coach)
    assert created.status_code == 201 and created.json()["name"] == "Мостче"
    assert client.post("/api/exercises", json=body, headers=coach).status_code == 409
    assert client.post("/api/exercises", json=body, headers=auth("athlete1")).status_code == 403
    for bad in ({**body, "kind": "cardio"}, {**body, "name": " "}, {**body, "extra": 1}, {"kind": "strength"},
                {**body, "name": "x" * 101}):
        assert client.post("/api/exercises", json=bad, headers=coach).status_code == 422
    other_club = client.get("/api/clubs", headers=auth("admin")).json()[-1]["id"]
    assert client.post("/api/exercises", json={**body, "name": "Чужд", "club_id": other_club}, headers=coach).status_code == 403
    assert client.post("/api/exercises", json={**body, "name": "Чужд", "club_id": other_club}, headers=auth("admin")).status_code == 201
    assert client.post("/api/exercises", json={**body, "club_id": 9999}, headers=auth("admin")).status_code == 422
    eid = created.json()["id"]
    assert client.delete(f"/api/exercises/{eid}", headers=auth("coach2")).status_code == 404
    assert client.delete(f"/api/exercises/{eid}", headers=auth("athlete1")).status_code == 403
    assert client.delete(f"/api/exercises/{eid}", headers=coach).status_code == 204
    assert client.delete(f"/api/exercises/{eid}", headers=coach).status_code == 404
    used = exercise_id(client, coach)
    assert client.delete(f"/api/exercises/{used}", headers=coach).status_code == 409  # plans use it
    actions = audit_actions(client, auth)
    assert {"exercise_created", "exercise_deleted"} <= set(actions)


# --- plans ---

def plan_body(client, headers, **changes):
    squat = exercise_id(client, headers)
    return {"title": "Тест план", "starts_on": day(), "weeks": 4, "notes": "бележка",
            "items": [{"weekday": 0, "exercise_id": squat, "sets": 3, "reps": 6, "reps_max": 8, "load_kg": 60, "rpe_target": 8},
                      {"weekday": 2, "exercise_id": squat, "sets": 2, "reps": 5}], **changes}


def test_plan_roles_scope_and_visibility(api, auth):
    client, path = api
    coach = auth("coach")
    group = group_id(path)  # Юноши: athlete1, athlete2, athlete4
    created = client.post("/api/plans", json=plan_body(client, coach, group_id=group), headers=coach)
    assert created.status_code == 201, created.text
    plan = created.json()
    assert plan["group_id"] == group and [i["reps_max"] for i in plan["items"]] == [8, 5]  # reps_max defaults to reps
    assert plan["items"][0]["exercise"] == "Клякане със щанга"
    pid = plan["id"]
    assert pid in [p["id"] for p in client.get("/api/plans", headers=auth("athlete1")).json()]
    assert pid not in [p["id"] for p in client.get("/api/plans", headers=auth("athlete5")).json()]
    assert client.get(f"/api/plans/{pid}", headers=auth("athlete1")).status_code == 200
    assert client.get(f"/api/plans/{pid}", headers=auth("athlete5")).status_code == 404
    assert client.get(f"/api/plans/{pid}", headers=auth("coach2")).status_code == 404
    assert pid in [p["id"] for p in client.get("/api/plans", headers=auth("admin")).json()]
    assert pid not in [p["id"] for p in client.get("/api/plans", headers=auth("coach2")).json()]
    mine = user_id(path, "athlete5")
    personal = client.post("/api/plans", json=plan_body(client, coach, athlete_id=mine), headers=coach).json()["id"]
    assert personal in [p["id"] for p in client.get("/api/plans", headers=auth("athlete5")).json()]
    assert personal not in [p["id"] for p in client.get("/api/plans", headers=auth("athlete6")).json()]
    club_wide = client.post("/api/plans", json=plan_body(client, coach), headers=coach).json()["id"]
    assert club_wide in [p["id"] for p in client.get("/api/plans", headers=auth("athlete6")).json()]
    assert club_wide not in [p["id"] for p in client.get("/api/plans", headers=auth("athlete3")).json()]
    assert client.post("/api/plans", json=plan_body(client, coach), headers=auth("athlete1")).status_code == 403


def test_plan_validation_and_cross_club_references(api, auth):
    client, path = api
    coach = auth("coach")
    base = plan_body(client, coach)
    other_group = client.post("/api/groups", json={"name": "Друг", "club_id": client.get(
        "/api/clubs", headers=auth("admin")).json()[-1]["id"]}, headers=auth("admin")).json()["id"]
    foreign_exercise = exercise_id(client, auth("coach2"))
    item = base["items"][0]
    bad_bodies = [
        {**base, "group_id": group_id(path), "athlete_id": user_id(path, "athlete1")},
        {**base, "group_id": other_group},
        {**base, "athlete_id": user_id(path, "athlete3")},
        {**base, "athlete_id": user_id(path, "coach")},
        {**base, "items": [{**item, "exercise_id": foreign_exercise}]},
        {**base, "items": [{**item, "exercise_id": 99999}]},
        {**base, "items": [{**item, "reps": 9, "reps_max": 8}]},
        {**base, "items": [{**item, "weekday": 7}]},
        {**base, "items": [{**item, "sets": 0}]},
        {**base, "items": [{**item, "rpe_target": 11}]},
        {**base, "items": [{**item, "bonus": 1}]},
        {**base, "items": [item] * 61},
        {**base, "starts_on": "2026-13-01"},
        {**base, "weeks": 53}, {**base, "title": " "}, {**base, "extra": 1},
    ]
    for body in bad_bodies:
        assert client.post("/api/plans", json=body, headers=coach).status_code in (422, 413), body
    assert client.get("/api/plans", headers=coach).json() == client.get("/api/plans", headers=coach).json()


def test_plan_replace_and_delete_are_scoped_and_audited(api, auth):
    client, path = api
    coach = auth("coach")
    pid = client.post("/api/plans", json=plan_body(client, coach), headers=coach).json()["id"]
    changed = client.put(f"/api/plans/{pid}", json=plan_body(client, coach, title="Нов", items=[]), headers=coach)
    assert changed.status_code == 200 and changed.json()["title"] == "Нов" and changed.json()["items"] == []
    assert client.put(f"/api/plans/{pid}", json=plan_body(client, coach), headers=auth("coach2")).status_code == 404
    assert client.put(f"/api/plans/{pid}", json=plan_body(client, coach), headers=auth("athlete1")).status_code == 403
    assert client.delete(f"/api/plans/{pid}", headers=auth("coach2")).status_code == 404
    assert client.delete(f"/api/plans/{pid}", headers=auth("athlete1")).status_code == 403
    assert client.delete(f"/api/plans/{pid}", headers=coach).status_code == 204
    assert client.get(f"/api/plans/{pid}", headers=coach).status_code == 404
    assert {"plan_created", "plan_changed", "plan_deleted"} <= set(audit_actions(client, auth))


def test_deleting_a_group_removes_its_plans_and_keeps_logged_sets(api, auth):
    client, path = api
    group = group_id(path)
    with main.database(path) as db:
        before = db.execute("SELECT COUNT(*) FROM set_logs").fetchone()[0]
        plan = db.execute("SELECT id FROM plans WHERE group_id = ?", (group,)).fetchone()[0]
    assert client.delete(f"/api/groups/{group}", headers=auth("coach")).status_code == 204
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM plans WHERE id = ?", (plan,)).fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM plan_items WHERE plan_id = ?", (plan,)).fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM set_logs").fetchone()[0] == before
        assert db.execute("SELECT COUNT(*) FROM set_logs WHERE plan_id = ?", (plan,)).fetchone()[0] == 0


# --- set logs ---

def test_set_log_roundtrip_idempotency_and_event(api, auth):
    client, path = api
    athlete = auth("athlete4")
    squat = exercise_id(client, athlete)
    coach_queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    body = {**SET, "day": day(), "exercise_id": squat, "client_id": "set-1"}
    first = client.post("/api/set-logs", json=body, headers=athlete)
    assert first.status_code == 201
    stored = first.json()
    assert stored["athlete_id"] == user_id(path, "athlete4") and stored["load_kg"] == 60 and stored["client_id"] == "set-1"
    assert coach_queue.get_nowait()["type"] == "set"
    again = client.post("/api/set-logs", json={**body, "reps": 1}, headers=athlete)
    assert again.status_code == 200 and again.json() == stored  # nothing new stored, original row returned
    assert coach_queue.empty()
    other = client.post("/api/set-logs", json={**body, "client_id": "set-2", "set_no": 2}, headers=athlete)
    assert other.status_code == 201 and other.json()["id"] != stored["id"]
    no_key = [client.post("/api/set-logs", json={k: v for k, v in body.items() if k != "client_id"}, headers=athlete).status_code
              for _ in range(2)]
    assert no_key == [201, 201]
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM set_logs WHERE client_id = 'set-1'").fetchone()[0] == 1
    # the same client id of another athlete is a different record
    other_athlete = client.post("/api/set-logs", json=body, headers=auth("athlete5"))
    assert other_athlete.status_code == 201
    assert client.app.state.events.open_count(user_id(path, "coach")) == 1
    client.app.state.events.close(user_id(path, "coach"), coach_queue)


def test_set_log_consent_role_and_validation(api, auth):
    client, path = api
    squat = exercise_id(client, auth("coach"))
    body = {**SET, "day": day(), "exercise_id": squat}
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    response = client.post("/api/set-logs", json=body, headers=unconsented)
    assert response.status_code == 403 and "Consent" in response.json()["detail"]
    assert client.post("/api/set-logs", json=body, headers=auth("coach")).status_code == 403
    assert client.post("/api/set-logs", json=body, headers=auth("admin")).status_code == 403
    athlete = auth("athlete1")
    bad = [
        {**body, "day": day(-31)}, {**body, "day": day(2)}, {**body, "day": "2026-02-30"}, {**body, "day": "18.11.2026"},
        {**body, "exercise_id": exercise_id(client, auth("coach2"))}, {**body, "exercise_id": 99999},
        {**body, "plan_id": 99999}, {**body, "set_no": 0}, {**body, "reps": 0}, {**body, "reps": 201},
        {**body, "load_kg": -1}, {**body, "load_kg": 1001}, {**body, "rpe": 10.5}, {**body, "extra": 1},
        {**body, "reps": "8"}, {**body, "client_id": ""}, {k: v for k, v in body.items() if k != "reps"},
    ]
    for item in bad:
        assert client.post("/api/set-logs", json=item, headers=athlete).status_code == 422, item
    for good in ({**body, "day": day(-30)}, {**body, "day": day(1)}, {**body, "load_kg": None, "rpe": None}):
        assert client.post("/api/set-logs", json=good, headers=athlete).status_code == 201
    club_plan = client.get("/api/plans", headers=athlete).json()[0]["id"]
    assert client.post("/api/set-logs", json={**body, "plan_id": club_plan}, headers=athlete).status_code == 201
    hidden = client.get("/api/plans", headers=auth("athlete5")).json()[0]["id"]  # the other group's plan
    assert client.post("/api/set-logs", json={**body, "plan_id": hidden}, headers=athlete).status_code == 422


def test_set_log_list_scope_and_delete(api, auth):
    client, path = api
    a1, a5 = user_id(path, "athlete1"), user_id(path, "athlete5")
    own = client.get("/api/set-logs", headers=auth("athlete1")).json()
    assert own and {r["athlete_id"] for r in own} == {a1}
    assert [r["day"] for r in own] == sorted((r["day"] for r in own), reverse=True)
    assert client.get("/api/set-logs", params={"athlete_id": a1}, headers=auth("athlete1")).json() == own
    assert client.get("/api/set-logs", params={"athlete_id": a5}, headers=auth("athlete1")).status_code == 403
    assert client.get("/api/set-logs", params={"athlete_id": a1}, headers=auth("coach")).json() == own
    assert client.get("/api/set-logs", params={"athlete_id": a1}, headers=auth("admin")).json() == own
    assert client.get("/api/set-logs", params={"athlete_id": a1}, headers=auth("coach2")).status_code == 404
    assert client.get("/api/set-logs", params={"athlete_id": 99999}, headers=auth("coach")).status_code == 404
    assert client.get("/api/set-logs", params={"athlete_id": user_id(path, "coach")}, headers=auth("coach")).status_code == 404
    assert client.get("/api/set-logs", headers=auth("coach")).status_code == 422  # staff must name the athlete
    page = client.get("/api/set-logs", params={"limit": 5, "offset": 5}, headers=auth("athlete1")).json()
    assert page == own[5:10]
    assert client.get("/api/set-logs", params={"limit": 501}, headers=auth("athlete1")).status_code == 422
    newest = own[0]["day"]
    assert {r["day"] for r in client.get("/api/set-logs", params={"from": newest}, headers=auth("athlete1")).json()} == {newest}
    assert {r["day"] for r in client.get("/api/set-logs", params={"to": own[-1]["day"]}, headers=auth("athlete1")).json()} == {own[-1]["day"]}
    log_id = own[0]["id"]
    assert client.delete(f"/api/set-logs/{log_id}", headers=auth("athlete5")).status_code == 404
    assert client.delete(f"/api/set-logs/{log_id}", headers=auth("coach")).status_code == 403
    assert client.delete(f"/api/set-logs/{log_id}", headers=auth("athlete1")).status_code == 204
    assert client.delete(f"/api/set-logs/{log_id}", headers=auth("athlete1")).status_code == 404
    assert {"set_deleted"} <= set(audit_actions(client, auth))


# --- training summary ---

def test_training_summary_progression_volume_and_scope(api, auth):
    client, path = api
    a1, a2, a5 = (user_id(path, f"athlete{n}") for n in (1, 2, 5))
    one = client.get(f"/api/athletes/{a1}/training", headers=auth("athlete1")).json()
    squat = next(s for s in one["progression"] if s["exercise"] == "Клякане със щанга")
    assert squat["action"] == "increase" and squat["change_kg"] == 5.0  # lower body step
    assert (squat["from_load_kg"], squat["suggested_load_kg"]) == (60.0, 65.0)
    bench = next(s for s in one["progression"] if s["exercise"] == "Лежанка")
    assert bench["change_kg"] == 2.5 and bench["suggested_load_kg"] == 42.5
    two = client.get(f"/api/athletes/{a2}/training", headers=auth("coach")).json()
    low = next(s for s in two["progression"] if s["exercise"] == "Клякане със щанга")
    assert (low["action"], low["change_pct"], low["suggested_load_kg"]) == ("decrease", -5, 57.0)
    assert "never applied" not in one["note"] and "automatically" in one["note"]
    weekly = one["weekly_volume"]
    assert len(weekly) == 4 and weekly == sorted(weekly, key=lambda w: w["week_start"])
    assert sum(w["sets"] for w in weekly) == 50 and sum(w["volume_kg"] for w in weekly) > 0
    assert sum(w["session_load"] for w in weekly) > 0  # from the check-ins
    assert len(client.get(f"/api/athletes/{a1}/training", params={"weeks": 8}, headers=auth("admin")).json()["weekly_volume"]) == 8
    for weeks in (0, 27):
        assert client.get(f"/api/athletes/{a1}/training", params={"weeks": weeks}, headers=auth("coach")).status_code == 422
    assert client.get(f"/api/athletes/{a5}/training", headers=auth("athlete1")).status_code == 403
    assert client.get(f"/api/athletes/{a1}/training", headers=auth("coach2")).status_code == 404
    fresh, headers = make_athlete(client, auth)
    empty = client.get(f"/api/athletes/{fresh}/training", headers=headers).json()
    assert empty["progression"] == [] and all(w["sets"] == 0 for w in empty["weekly_volume"])


def test_training_csv_staff_only_and_formula_safe(api, auth):
    client, path = api
    coach = auth("coach")
    a1 = user_id(path, "athlete1")
    client.post("/api/exercises", json={"name": "=HYPERLINK(1)", "kind": "strength"}, headers=coach)
    evil = exercise_id(client, coach, "=HYPERLINK(1)")
    assert client.post("/api/set-logs", json={**SET, "day": day(), "exercise_id": evil}, headers=auth("athlete1")).status_code == 201
    response = client.get(f"/api/athletes/{a1}/training.csv", headers=coach)
    assert response.status_code == 200 and response.headers["content-type"].startswith("text/csv")
    assert "athlete-" in response.headers["content-disposition"]
    rows = list(csv.DictReader(io.StringIO(response.text)))
    assert rows[0].keys() >= {"day", "exercise", "set_no", "reps", "load_kg", "rpe", "athlete_name"}
    assert "'=HYPERLINK(1)" in {r["exercise"] for r in rows}
    assert client.get(f"/api/athletes/{a1}/training.csv", headers=auth("athlete1")).status_code == 403
    assert client.get(f"/api/athletes/{a1}/training.csv", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{a1}/training.csv", headers=auth("admin")).status_code == 200
    with main.database(path) as db:
        assert db.execute("SELECT detail FROM audit WHERE action = 'csv_export' ORDER BY id DESC").fetchone()[0] == '{"kind": "training"}'


# --- foods ---

def test_food_list_search_and_scope(api, auth):
    client, path = api
    athlete = auth("athlete1")
    found = foods(client, athlete, "овесени")
    assert [f["name_bg"] for f in found] == ["Овесени ядки"] and found[0]["kcal"] > 300 and found[0]["fdc_id"]
    assert foods(client, athlete, "OATS, WHOLE")[0]["name_bg"] == "Овесени ядки"  # English description, any case
    assert foods(client, athlete, "%") == [] and foods(client, athlete, "_") == []  # LIKE wildcards are literal
    assert foods(client, athlete, "'; DROP TABLE foods; --") == []
    everything = client.get("/api/foods", params={"limit": 500}, headers=athlete).json()
    assert len(everything) > 300
    assert client.get("/api/foods", params={"limit": 5, "offset": 2}, headers=athlete).json() == everything[2:7]
    assert client.get("/api/foods", params={"limit": 501}, headers=athlete).status_code == 422
    assert client.get("/api/foods", params={"q": "x" * 101}, headers=athlete).status_code == 422
    for who in ("coach", "coach2", "admin"):
        assert foods(client, auth(who), "овесени")


def test_custom_foods_club_and_personal_scope(api, auth):
    client, path = api
    food = {"name_bg": "Домашна баница", "name_en": "Home banitsa", "kcal": 280, "protein_g": 9, "carbs_g": 30, "fat_g": 14}
    club_food = client.post("/api/foods", json=food, headers=auth("coach"))
    assert club_food.status_code == 201 and club_food.json()["club_id"] and club_food.json()["owner_id"] is None
    mine = client.post("/api/foods", json={**food, "name_bg": "Мое ястие"}, headers=auth("athlete1"))
    assert mine.status_code == 201 and mine.json()["owner_id"] == user_id(path, "athlete1")
    assert foods(client, auth("athlete2"), "баница") and not foods(client, auth("athlete2"), "Мое ястие")
    assert foods(client, auth("athlete1"), "Мое ястие")
    assert not foods(client, auth("athlete3"), "баница")  # other club
    assert foods(client, auth("coach"), "баница") and not foods(client, auth("coach"), "Мое ястие")
    assert not foods(client, auth("coach2"), "баница") and foods(client, auth("admin"), "баница")
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.post("/api/foods", json=food, headers=unconsented).status_code == 403
    assert client.post("/api/foods", json={**food, "club_id": 1}, headers=auth("athlete1")).status_code == 422
    other_club = client.get("/api/clubs", headers=auth("admin")).json()[-1]["id"]
    assert client.post("/api/foods", json={**food, "club_id": other_club}, headers=auth("coach")).status_code == 403
    for bad in ({**food, "kcal": -1}, {**food, "kcal": 1001}, {**food, "protein_g": 101}, {**food, "name_bg": " "},
                {**food, "extra": 1}, {k: v for k, v in food.items() if k != "kcal"}, {**food, "fat_g": "9"}):
        assert client.post("/api/foods", json=bad, headers=auth("coach")).status_code == 422
    mine_id, club_id = mine.json()["id"], club_food.json()["id"]
    system = foods(client, auth("coach"), "овесени")[0]["id"]
    assert client.delete(f"/api/foods/{system}", headers=auth("coach")).status_code == 404  # bundled foods stay
    assert client.delete(f"/api/foods/{mine_id}", headers=auth("athlete2")).status_code == 404
    assert client.delete(f"/api/foods/{mine_id}", headers=auth("coach")).status_code == 404
    assert client.delete(f"/api/foods/{club_id}", headers=auth("athlete1")).status_code == 404
    assert client.delete(f"/api/foods/{club_id}", headers=auth("coach2")).status_code == 404
    assert client.post("/api/food-logs", headers=auth("athlete1"), json={
        "day": day(), "meal": "snack", "food_id": mine_id, "grams": 100}).status_code == 201
    assert client.delete(f"/api/foods/{mine_id}", headers=auth("athlete1")).status_code == 409
    assert client.delete(f"/api/foods/{club_id}", headers=auth("coach")).status_code == 204
    assert {"food_created", "food_deleted"} <= set(audit_actions(client, auth))


# --- food logs ---

def test_food_log_macros_idempotency_scope_and_event(api, auth):
    client, path = api
    athlete = auth("athlete1")
    oats = foods(client, athlete, "Овесени ядки")[0]
    queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    body = {"day": day(), "meal": "breakfast", "food_id": oats["id"], "grams": 150, "client_id": "meal-1"}
    created = client.post("/api/food-logs", json=body, headers=athlete)
    assert created.status_code == 201
    log = created.json()
    assert log["kcal"] == round(oats["kcal"] * 1.5, 1) and log["protein_g"] == round(oats["protein_g"] * 1.5, 1)
    assert log["name_bg"] == "Овесени ядки" and log["grams"] == 150
    assert queue.get_nowait()["type"] == "food"
    again = client.post("/api/food-logs", json={**body, "grams": 1}, headers=athlete)
    assert again.status_code == 200 and again.json() == log and queue.empty()
    listed = client.get("/api/food-logs", params={"from": day(), "to": day()}, headers=athlete).json()
    assert log in listed
    a1 = user_id(path, "athlete1")
    assert client.get("/api/food-logs", params={"athlete_id": a1, "from": day(), "to": day()}, headers=auth("coach")).json() == listed
    assert client.get("/api/food-logs", params={"athlete_id": a1}, headers=auth("coach2")).status_code == 404
    assert client.get("/api/food-logs", params={"athlete_id": a1}, headers=auth("athlete2")).status_code == 403
    assert client.get("/api/food-logs", headers=auth("coach")).status_code == 422
    assert client.delete(f"/api/food-logs/{log['id']}", headers=auth("athlete2")).status_code == 404
    assert client.delete(f"/api/food-logs/{log['id']}", headers=auth("coach")).status_code == 403
    assert client.delete(f"/api/food-logs/{log['id']}", headers=athlete).status_code == 204
    assert client.delete(f"/api/food-logs/{log['id']}", headers=athlete).status_code == 404
    client.app.state.events.close(user_id(path, "coach"), queue)
    assert {"food_logged", "food_deleted_log"} <= set(audit_actions(client, auth))


def test_food_log_consent_roles_and_validation(api, auth):
    client, path = api
    oats = foods(client, auth("athlete1"), "Овесени ядки")[0]["id"]
    body = {"day": day(), "meal": "lunch", "food_id": oats, "grams": 100}
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.post("/api/food-logs", json=body, headers=unconsented).status_code == 403
    assert client.post("/api/food-logs", json=body, headers=auth("coach")).status_code == 403
    athlete = auth("athlete1")
    personal = client.post("/api/foods", json={"name_bg": "Тайно", "kcal": 1, "protein_g": 0, "carbs_g": 0, "fat_g": 0},
                           headers=auth("athlete2")).json()["id"]
    club_two = client.post("/api/foods", json={"name_bg": "Клуб две", "kcal": 1, "protein_g": 0, "carbs_g": 0, "fat_g": 0},
                           headers=auth("coach2")).json()["id"]
    for bad in ({**body, "meal": "brunch"}, {**body, "grams": 0}, {**body, "grams": 5001}, {**body, "food_id": 99999},
                {**body, "food_id": personal}, {**body, "food_id": club_two}, {**body, "day": day(-31)},
                {**body, "day": day(2)}, {**body, "extra": 1}, {**body, "grams": "100"}, {**body, "day": "x"}):
        assert client.post("/api/food-logs", json=bad, headers=athlete).status_code == 422, bad
    assert client.post("/api/food-logs", json={**body, "grams": 5000, "day": day(-30)}, headers=athlete).status_code == 201


# --- weights ---

def test_weight_put_replace_trend_and_validation(api, auth):
    client, path = api
    athlete, uid = None, None
    uid, athlete = make_athlete(client, auth)
    assert client.put(f"/api/me/weights/{day(-3)}", json={"kg": 80}, headers=athlete).status_code == 201
    queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    replaced = client.put(f"/api/me/weights/{day(-3)}", json={"kg": 79.5}, headers=athlete)
    assert replaced.status_code == 200 and replaced.json() == {"athlete_id": uid, "day": day(-3), "kg": 79.5}
    assert queue.get_nowait()["type"] == "weight"
    client.app.state.events.close(user_id(path, "coach"), queue)
    assert client.get(f"/api/athletes/{uid}/weights", headers=athlete).json()["trend"] == []  # one weigh-in: no trend
    for offset, kg in ((-2, 79.4), (-1, 79.0), (0, 78.8)):
        assert client.put(f"/api/me/weights/{day(offset)}", json={"kg": kg}, headers=athlete).status_code == 201
    result = client.get(f"/api/athletes/{uid}/weights", headers=athlete).json()
    assert [w["kg"] for w in result["weights"]] == [79.5, 79.4, 79.0, 78.8]
    assert all(w["trend_kg"] is not None for w in result["weights"])
    assert [t["day"] for t in result["trend"]] == [day(-3), day(-2), day(-1), day(0)]
    assert result["trend"][0]["trend_kg"] == pytest.approx(79.5, abs=0.3) and "sigma_kg" in result["trend"][0]
    assert "exponential" in result["method"]
    clipped = client.get(f"/api/athletes/{uid}/weights", params={"from": day(-1)}, headers=auth("coach")).json()
    assert [w["day"] for w in clipped["weights"]] == [day(-1), day(0)] and len(clipped["trend"]) == 2
    assert client.get(f"/api/athletes/{uid}/weights", headers=auth("admin")).status_code == 200
    assert client.get(f"/api/athletes/{uid}/weights", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{uid}/weights", headers=auth("athlete1")).status_code == 403
    for path_day, body in ((day(-31), {"kg": 80}), (day(2), {"kg": 80}), ("nonsense", {"kg": 80}), (day(), {"kg": 19.9}),
                           (day(), {"kg": 400.1}), (day(), {"kg": "80"}), (day(), {"kg": 80, "extra": 1}), (day(), {})):
        assert client.put(f"/api/me/weights/{path_day}", json=body, headers=athlete).status_code == 422, (path_day, body)
    assert client.put(f"/api/me/weights/{day()}", json={"kg": 80}, headers=auth("coach")).status_code == 403
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.put(f"/api/me/weights/{day()}", json={"kg": 80}, headers=unconsented).status_code == 403
    assert client.delete(f"/api/me/weights/{day(-3)}", headers=athlete).status_code == 204
    assert client.delete(f"/api/me/weights/{day(-3)}", headers=athlete).status_code == 404
    assert client.delete(f"/api/me/weights/{day()}", headers=auth("coach")).status_code == 403
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM weights WHERE athlete_id = ?", (uid,)).fetchone()[0] == 3
    assert {"weight_logged", "weight_deleted"} <= set(audit_actions(client, auth))


def fill(path, athlete_id, food_days, weigh_days, kcal=2000, start=-14, kg=80.0, drift=-0.05):
    """Insert controlled logs straight into the database: one 100 kcal/100 g food, `grams` chosen for `kcal`."""
    with main.database(path) as db:
        food = db.execute("SELECT id FROM foods WHERE name_bg = 'Тест 100'").fetchone()
        food = food[0] if food else db.execute(
            "INSERT INTO foods(name_bg, kcal, protein_g, carbs_g, fat_g) VALUES ('Тест 100', 100, 0, 0, 0)").lastrowid
        for n in range(food_days):
            db.execute("INSERT INTO food_logs(athlete_id, day, meal, food_id, grams, created_at) VALUES (?, ?, 'lunch', ?, ?, 'x')",
                       (athlete_id, day(start + n), food, kcal))
        for n in range(weigh_days):
            db.execute("INSERT INTO weights(athlete_id, day, kg) VALUES (?, ?, ?)", (athlete_id, day(start + n), kg + drift * n))


def test_estimate_formula_window_counts_and_label(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    fill(path, uid, food_days=12, weigh_days=10, kcal=2000)
    result = client.get(f"/api/athletes/{uid}/nutrition/estimate", headers=athlete).json()
    assert result["available"] and result["food_days"] == 12 and result["weigh_ins"] == 10
    assert result["mean_intake_kcal"] == 2000 and result["trend_days"] == 9
    assert result["trend_change_kg"] < 0 and "estimate from your own logs" in result["label"]
    assert result["window_start"] == day(-14) and result["window_end"] == day() and result["window_days"] == 15
    assert result["expenditure_kcal"] == round(2000 - result["trend_change_kg"] * 7700 / 9, -1) or abs(
        result["expenditure_kcal"] - (2000 - result["trend_change_kg"] * 7700 / 9)) < 20
    assert result["expenditure_kcal"] > 2000  # losing weight on 2000 kcal: burning more than that
    assert client.get(f"/api/athletes/{uid}/nutrition/estimate", headers=auth("coach")).json() == result
    assert client.get(f"/api/athletes/{uid}/nutrition/estimate", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{uid}/nutrition/estimate", headers=auth("athlete1")).status_code == 403


def test_estimate_needs_enough_data(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    fill(path, uid, food_days=9, weigh_days=8)
    short = client.get(f"/api/athletes/{uid}/nutrition/estimate", headers=athlete).json()
    assert short["available"] is False and short["reason"] == "not_enough_data" and short["food_days"] == 9
    assert "expenditure_kcal" not in short and short["needed_food_days"] == 10 and short["needed_weigh_ins"] == 8
    uid2, athlete2 = make_athlete(client, auth, "second@demo.test")
    fill(path, uid2, food_days=10, weigh_days=7)
    assert client.get(f"/api/athletes/{uid2}/nutrition/estimate", headers=athlete2).json()["available"] is False
    fresh = client.get(f"/api/athletes/{make_athlete(client, auth, 'third@demo.test')[0]}/nutrition/estimate", headers=auth("coach")).json()
    assert fresh["available"] is False and fresh["food_days"] == 0 and fresh["weigh_ins"] == 0


# --- targets and guardrails ---

def put_target(client, headers, athlete_id, kcal, **extra):
    return client.put(f"/api/athletes/{athlete_id}/nutrition/target", json={"kcal": kcal, **extra}, headers=headers)


def test_under_18_flag_is_set_by_staff_only(api, auth):
    client, path = api
    a1 = user_id(path, "athlete1")
    assert client.patch(f"/api/users/{a1}/profile", json={"under_18": True}, headers=auth("athlete1")).status_code == 403
    assert client.patch(f"/api/users/{a1}/profile", json={"under_18": True}, headers=auth("coach2")).status_code == 404
    assert client.patch(f"/api/users/{user_id(path, 'coach')}/profile", json={"under_18": True}, headers=auth("admin")).status_code == 422
    for bad in ({"under_18": "yes"}, {}, {"under_18": True, "extra": 1}):
        assert client.patch(f"/api/users/{a1}/profile", json=bad, headers=auth("coach")).status_code == 422
    assert client.patch(f"/api/users/{a1}/profile", json={"under_18": True}, headers=auth("coach")).json() == {"id": a1, "under_18": True}
    users = {u["id"]: u for u in client.get("/api/users", headers=auth("coach")).json()}
    assert users[a1]["under_18"] is True and users[user_id(path, "athlete2")]["under_18"] is False
    roster = {a["id"]: a for a in client.get("/api/athletes", headers=auth("coach")).json()}
    assert roster[a1]["under_18"] is True
    assert client.get(f"/api/athletes/{a1}/nutrition/target", headers=auth("athlete1")).json()["under_18"] is True
    assert client.patch(f"/api/users/{a1}/profile", json={"under_18": False}, headers=auth("admin")).status_code == 200
    with main.database(path) as db:
        detail = db.execute("SELECT detail FROM audit WHERE action = 'profile_changed' ORDER BY id").fetchall()
    assert [json.loads(r[0]) for r in detail][:2] == [{"under_18": True}, {"under_18": False}]


def test_under_18_athlete_cannot_set_a_deficit_but_coach_can(api, auth):
    client, path = api
    a7 = user_id(path, "athlete7")  # seeded under 18 with an estimate
    minor, coach = auth("athlete7"), auth("coach")
    info = client.get(f"/api/athletes/{a7}/nutrition/target", headers=minor).json()
    estimate = info["estimate_kcal"]
    assert info["under_18"] is True and estimate and estimate > 1500 and info["target"]["kcal"] == 2600
    refused = put_target(client, minor, a7, estimate - 1)
    assert refused.status_code == 422 and "coach" in refused.json()["detail"]
    assert client.get(f"/api/athletes/{a7}/nutrition/target", headers=minor).json()["target"]["kcal"] == 2600  # unchanged
    assert put_target(client, minor, a7, 1499).status_code == 422
    ok = put_target(client, minor, a7, estimate, protein_g=120)
    assert ok.status_code == 200 and ok.json()["target"]["kcal"] == estimate and ok.json()["target"]["set_by"] == a7
    assert put_target(client, minor, a7, estimate + 300).status_code == 200
    deficit = put_target(client, coach, a7, 1500)
    assert deficit.status_code == 200 and deficit.json()["target"]["kcal"] == 1500
    assert deficit.json()["target"]["set_by"] == user_id(path, "coach")
    assert put_target(client, coach, a7, 1499).status_code == 422  # the floor binds coaches
    assert put_target(client, auth("admin"), a7, 1499).status_code == 422
    assert put_target(client, auth("admin"), a7, 1800).status_code == 200


def test_under_18_without_an_estimate_must_ask_the_coach(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    assert client.patch(f"/api/users/{uid}/profile", json={"under_18": True}, headers=auth("coach")).status_code == 200
    assert put_target(client, athlete, uid, 3500).status_code == 422
    assert put_target(client, auth("coach"), uid, 2200).status_code == 200


def test_adult_deficit_allowed_above_the_floor(api, auth):
    client, path = api
    a1 = user_id(path, "athlete1")
    estimate = client.get(f"/api/athletes/{a1}/nutrition/target", headers=auth("athlete1")).json()["estimate_kcal"]
    assert put_target(client, auth("athlete1"), a1, max(1500, estimate - 400)).status_code == 200
    assert put_target(client, auth("athlete1"), a1, 1500).status_code == 200
    floor = put_target(client, auth("athlete1"), a1, 1499)
    assert floor.status_code == 422 and "1500" in floor.json()["detail"]
    assert put_target(client, auth("coach"), a1, 1000).status_code == 422


def test_target_scope_consent_validation_and_protein_range(api, auth):
    client, path = api
    a1, a5 = user_id(path, "athlete1"), user_id(path, "athlete5")
    assert put_target(client, auth("athlete1"), a5, 2500).status_code == 403
    assert put_target(client, auth("coach2"), a1, 2500).status_code == 404
    assert client.get(f"/api/athletes/{a1}/nutrition/target", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{a5}/nutrition/target", headers=auth("athlete1")).status_code == 403
    uid, _ = make_athlete(client, auth, consent=False)
    blocked = put_target(client, auth("coach"), uid, 2500)
    assert blocked.status_code == 403 and "Consent" in blocked.json()["detail"]
    for body in ({"kcal": 0}, {"kcal": 2500.5}, {"kcal": 10001}, {"kcal": "2500"}, {"kcal": 2500, "protein_g": -1},
                 {"kcal": 2500, "extra": 1}, {}):
        assert client.put(f"/api/athletes/{a1}/nutrition/target", json=body, headers=auth("coach")).status_code == 422
    result = put_target(client, auth("coach"), a1, 2600, protein_g=140, carbs_g=300, fat_g=80).json()
    assert result["target"] == {**result["target"], "kcal": 2600, "protein_g": 140, "carbs_g": 300, "fat_g": 80}
    low, high = result["protein_range_g"]["min"], result["protein_range_g"]["max"]
    assert result["protein_range_g"]["basis_kg"] > 40 and low == round(1.4 * result["protein_range_g"]["basis_kg"])
    assert high == round(2.0 * result["protein_range_g"]["basis_kg"]) and "discuss with your coach" in result["note"]
    assert client.get(f"/api/athletes/{a1}/nutrition/target", headers=auth("athlete1")).json()["target"]["kcal"] == 2600
    fresh, fresh_headers = make_athlete(client, auth, "nt@demo.test")
    empty = client.get(f"/api/athletes/{fresh}/nutrition/target", headers=fresh_headers).json()
    assert empty["target"] is None and empty["protein_range_g"] is None and empty["estimate_kcal"] is None
    assert {"target_set", "profile_changed"} <= set(audit_actions(client, auth)) | {"profile_changed"}
    with main.database(path) as db:
        assert db.execute("SELECT detail FROM audit WHERE action = 'target_set' ORDER BY id DESC").fetchone()[0] == '{"kcal": 2600}'


# --- daily summary ---

def test_nutrition_summary_totals_against_target_and_weekly_average(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    fill(path, uid, food_days=3, weigh_days=0, kcal=1000, start=-2)  # 1000 kcal on each of 3 days
    assert put_target(client, auth("coach"), uid, 2500, protein_g=100).status_code == 200
    summary = client.get(f"/api/athletes/{uid}/nutrition/summary", headers=athlete).json()
    assert summary["day"] == day() and summary["totals"]["kcal"] == 1000.0
    assert summary["by_meal_kcal"] == {"breakfast": 0.0, "lunch": 1000.0, "dinner": 0.0, "snack": 0.0}
    assert summary["target"]["kcal"] == 2500 and summary["remaining"] == {"kcal": 1500.0, "protein_g": 100.0, "carbs_g": None, "fat_g": None}
    assert summary["weekly_average"]["days_logged"] == 3 and summary["weekly_average"]["kcal"] == 1000.0
    earlier = client.get(f"/api/athletes/{uid}/nutrition/summary", params={"day": day(-2)}, headers=auth("coach")).json()
    assert earlier["weekly_average"]["days_logged"] == 1 and earlier["totals"]["kcal"] == 1000.0
    nothing = client.get(f"/api/athletes/{uid}/nutrition/summary", params={"day": day(-30)}, headers=athlete).json()
    assert nothing["totals"]["kcal"] == 0 and nothing["weekly_average"]["kcal"] is None
    assert client.get(f"/api/athletes/{uid}/nutrition/summary", params={"day": "bad"}, headers=athlete).status_code == 422
    assert client.get(f"/api/athletes/{uid}/nutrition/summary", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{uid}/nutrition/summary", headers=auth("athlete1")).status_code == 403


# --- CSV exports ---

def test_nutrition_and_weights_csv(api, auth):
    client, path = api
    a1 = user_id(path, "athlete1")
    coach = auth("coach")
    nutrition = client.get(f"/api/athletes/{a1}/nutrition.csv", headers=coach)
    rows = list(csv.DictReader(io.StringIO(nutrition.text)))
    assert nutrition.status_code == 200 and len(rows) == 156 and rows[0]["day"] <= rows[-1]["day"]
    assert rows[0].keys() >= {"day", "meal", "name_bg", "name_en", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "fibre_g"}
    weights = list(csv.DictReader(io.StringIO(client.get(f"/api/athletes/{a1}/weights.csv", headers=coach).text)))
    assert len(weights) == 12 and weights[0]["trend_kg"]
    for kind in ("nutrition.csv", "weights.csv"):
        assert client.get(f"/api/athletes/{a1}/{kind}", headers=auth("athlete1")).status_code == 403
        assert client.get(f"/api/athletes/{a1}/{kind}", headers=auth("coach2")).status_code == 404
        assert client.get(f"/api/athletes/{a1}/{kind}", headers=auth("admin")).status_code == 200
    with main.database(path) as db:
        kinds = [json.loads(r[0])["kind"] for r in db.execute("SELECT detail FROM audit WHERE action = 'csv_export' ORDER BY id")]
    assert kinds[:4] == ["nutrition", "weights", "nutrition", "weights"][:len(kinds[:4])] and "weights" in kinds


# --- export and deletion ---

def test_export_contains_new_data_and_delete_removes_it(api, auth):
    client, path = api
    athlete = auth("athlete1")
    a1 = user_id(path, "athlete1")
    mine = client.post("/api/foods", json={"name_bg": "Моя храна", "kcal": 10, "protein_g": 1, "carbs_g": 1, "fat_g": 1},
                       headers=athlete).json()["id"]
    assert client.post("/api/food-logs", json={"day": day(), "meal": "snack", "food_id": mine, "grams": 100}, headers=athlete).status_code == 201
    export = client.get("/api/me/export", headers=athlete)
    data = export.json()
    assert export.status_code == 200 and data["user"]["under_18"] is False
    assert len(data["set_logs"]) == 50 and {"exercise_id", "reps", "load_kg", "rpe", "client_id"} <= data["set_logs"][0].keys()
    assert len(data["food_logs"]) == 157 and data["food_logs"][0]["name_bg"] and len(data["weights"]) == 12
    assert [f["name_bg"] for f in data["custom_foods"]] == ["Моя храна"] and data["nutrition_target"]["kcal"] == 2200
    assert "password" not in export.text and "token" not in export.text
    other = client.get("/api/me/export", headers=auth("athlete2")).json()
    assert all(s["id"] not in {x["id"] for x in data["set_logs"]} for s in other["set_logs"])
    assert client.delete("/api/me/data", headers=athlete).status_code == 204
    after = client.get("/api/me/export", headers=athlete).json()
    assert after["set_logs"] == after["food_logs"] == after["weights"] == after["custom_foods"] == [] == after["jumps"]
    assert after["nutrition_target"] is None and after["user"]["consent_recorded"] is True
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM plans").fetchone()[0] == 2  # coach-owned data stays
        assert db.execute("SELECT COUNT(*) FROM set_logs WHERE athlete_id != ?", (a1,)).fetchone()[0] > 0
        assert db.execute("SELECT COUNT(*) FROM foods WHERE owner_id = ?", (a1,)).fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM foods WHERE owner_id IS NULL").fetchone()[0] >= 332
    assert client.get("/api/me/export", headers=auth("athlete2")).json()["weights"]  # other athletes untouched


# --- migration and seed ---

def test_migrates_previous_schema_and_keeps_users(tmp_path):
    path = tmp_path / "old.db"
    with sqlite3.connect(path) as db:
        db.executescript('''
            CREATE TABLE clubs(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
            CREATE TABLE users(id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL REFERENCES clubs(id),
                email TEXT NOT NULL UNIQUE COLLATE NOCASE, name TEXT NOT NULL, role TEXT NOT NULL,
                password_salt BLOB NOT NULL, password_hash BLOB NOT NULL, active INTEGER NOT NULL DEFAULT 1,
                consent_recorded INTEGER NOT NULL DEFAULT 0, consent_by TEXT, consent_at TEXT);
            CREATE TABLE tokens(token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires_at REAL NOT NULL);
            CREATE TABLE jumps(id INTEGER PRIMARY KEY, athlete_id INTEGER NOT NULL REFERENCES users(id), created_at TEXT NOT NULL,
                metrics TEXT NOT NULL, session_id INTEGER, client_id TEXT);
            INSERT INTO clubs VALUES(1, 'Legacy');
        ''')
        salt = b"0123456789abcdef"
        db.execute("INSERT INTO users(id, club_id, email, name, role, password_salt, password_hash, consent_recorded, consent_by) "
                   "VALUES(1, 1, 'old@demo.test', 'Old', 'athlete', ?, ?, 1, 'Parent')", (salt, main.password_hash("old-password", salt)))
        db.execute("INSERT INTO jumps VALUES(1, 1, '2026-01-01T00:00:00+00:00', ?, NULL, NULL)", (json.dumps(METRICS),))
    for _ in range(2):  # a second start changes nothing
        with TestClient(main.create_app(str(path))) as client:
            token = client.post("/api/auth/login", json={"email": "old@demo.test", "password": "old-password"}).json()["token"]
            headers = {"Authorization": "Bearer " + token}
            assert client.get("/api/me/export", headers=headers).json()["user"]["under_18"] is False
            assert len(client.get("/api/jumps", headers=headers).json()) == 1
            assert len(client.get("/api/foods", params={"limit": 500}, headers=headers).json()) > 300
    with main.database(path) as db:
        tables = {row["name"] for row in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        assert {"exercises", "plans", "plan_items", "set_logs", "foods", "food_logs", "weights", "nutrition_targets",
                "coaching_programs", "nutrition_day_complete"} <= tables
        assert db.execute("SELECT under_18 FROM users").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM foods").fetchone()[0] == len(__import__("server.training_nutrition_api", fromlist=["x"]).read_foods_csv())
        assert {"set_logs_client", "food_logs_client", "foods_fdc"} <= {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}


def test_seed_training_nutrition_is_idempotent_and_synthetic(api):
    client, path = api
    tables = ("exercises", "plans", "plan_items", "set_logs", "food_logs", "weights", "nutrition_targets", "foods")
    with main.database(path) as db:
        before = {t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables}
        assert db.execute("SELECT email FROM users WHERE under_18 = 1").fetchall()[0][0] == "athlete7@demo.test"
        assert db.execute("SELECT COUNT(*) FROM users WHERE under_18 = 1").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM plans WHERE group_id IS NOT NULL").fetchone()[0] == 2  # one per demo group
        assert db.execute("SELECT COUNT(DISTINCT athlete_id) FROM set_logs").fetchone()[0] == 6
        assert db.execute("SELECT COUNT(DISTINCT day) FROM weights WHERE athlete_id = (SELECT id FROM users WHERE email = 'athlete1@demo.test')").fetchone()[0] == 12
        assert db.execute("SELECT COUNT(*) FROM exercises").fetchone()[0] == 26 and before["foods"] >= 300
    seed(path)
    seed(path)
    with main.database(path) as db:
        assert {t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in tables} == before


# --- adaptive expenditure and the weekly coaching program ---

def confirm_day(client, headers, offset):
    return client.put(f"/api/me/nutrition/days/{day(offset)}/complete", headers=headers)


def flat_history(client, path, uid, headers, days_n=28, kcal=2000):
    """A clean flat history (stable weight) so the adaptive expenditure settles at the intake, then confirmed."""
    fill(path, uid, food_days=days_n, weigh_days=days_n, kcal=kcal, start=-(days_n - 1), kg=80.0, drift=0.0)
    for n in range(days_n):
        assert confirm_day(client, headers, -n).status_code == 200


def set_program(client, headers, athlete_id, goal, rate, protein_g=150, fat_g=50):
    return client.put(f"/api/athletes/{athlete_id}/nutrition/program",
                      json={"goal": goal, "rate_kg_week": rate, "protein_g": protein_g, "fat_g": fat_g}, headers=headers)


def test_day_complete_consent_scope_and_idempotency(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    assert confirm_day(client, auth("coach"), 0).status_code == 403  # staff cannot confirm an athlete's day
    first = confirm_day(client, athlete, -1)
    assert first.status_code == 200 and first.json()["status"] == "complete" and first.json()["day"] == day(-1)
    again = confirm_day(client, athlete, -1)  # idempotent re-confirmation
    assert again.status_code == 200 and again.json() == first.json()
    assert client.put(f"/api/me/nutrition/days/2026-13-02/complete", headers=athlete).status_code == 422
    assert client.put(f"/api/me/nutrition/days/{day(-60)}/complete", headers=athlete).status_code == 422  # too old
    unconsented, head = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert confirm_day(client, head, -1).status_code == 403
    assert confirm_day(client, head, -1).json()["detail"].startswith("Consent")
    assert client.delete(f"/api/me/nutrition/days/{day(-1)}/complete", headers=athlete).status_code == 204
    assert client.delete(f"/api/me/nutrition/days/{day(-1)}/complete", headers=athlete).status_code == 404


def test_program_needs_enough_data_and_returns_no_guess(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    empty = client.get(f"/api/athletes/{uid}/nutrition/program", headers=athlete)
    assert empty.status_code == 200 and empty.json()["program"] is None
    assert client.get(f"/api/athletes/{uid}/nutrition/expenditure", headers=athlete).json()["expenditure"] is None
    refused = set_program(client, athlete, uid, "lose", 0.5)
    assert refused.status_code == 422 and "enough" in refused.json()["detail"].lower()


def test_program_holds_without_fourteen_complete_days(api, auth):
    client, path = api
    uid, athlete = make_athlete(client, auth)
    fill(path, uid, food_days=12, weigh_days=10, kcal=2000)  # enough to seed the one-shot estimate, no complete days
    created = set_program(client, athlete, uid, "maintain", 0.0)
    assert created.status_code == 200 and created.json()["program"]["mode"] == "holding"  # holds, not a guess
    series = client.get(f"/api/athletes/{uid}/nutrition/expenditure", headers=athlete).json()
    assert series["expenditure"]["status"] == "holding" and series["expenditure"]["complete_days"] == 0


def test_program_guardrails_floor_and_under_18(api, auth):
    client, path = api
    # Adult athlete, stable 2000 kcal: expenditure settles at 2000.
    adult, head = make_athlete(client, auth, "adult@demo.test")
    flat_history(client, path, adult, head)
    ok = set_program(client, head, adult, "lose", 0.25)  # 2000 - 7700*0.25/7 = 1725
    assert ok.status_code == 200 and ok.json()["applied"] is True and ok.json()["guardrail"] is None
    assert ok.json()["program"]["recommended_target"]["calories"] == 1725
    assert client.get(f"/api/athletes/{adult}/nutrition/target", headers=head).json()["target"]["kcal"] == 1725
    floored = set_program(client, head, adult, "lose", 1.0, protein_g=50, fat_g=20)  # 900 kcal, below the floor
    assert floored.status_code == 200 and floored.json()["applied"] is False and "1500" in floored.json()["guardrail"]
    assert client.get(f"/api/athletes/{adult}/nutrition/target", headers=head).json()["target"]["kcal"] == 1725  # unchanged

    # Under-18 athlete: a self-set deficit is shown but not applied without a coach.
    minor, mhead = make_athlete(client, auth, "minor@demo.test")
    assert client.patch(f"/api/users/{minor}/profile", json={"under_18": True}, headers=auth("coach")).status_code == 200
    flat_history(client, path, minor, mhead)
    shown = set_program(client, mhead, minor, "lose", 0.25)
    assert shown.status_code == 200 and shown.json()["applied"] is False and "coach" in shown.json()["guardrail"]
    assert client.get(f"/api/athletes/{minor}/nutrition/target", headers=mhead).json()["target"] is None  # not applied
    by_coach = set_program(client, auth("coach"), minor, "lose", 0.25)
    assert by_coach.status_code == 200 and by_coach.json()["applied"] is True
    assert client.get(f"/api/athletes/{minor}/nutrition/target", headers=mhead).json()["target"]["kcal"] == 1725


def test_program_scope_idempotency_audit_and_events(api, auth):
    client, path = api
    adult, head = make_athlete(client, auth, "prog@demo.test")
    flat_history(client, path, adult, head)
    assert set_program(client, auth("coach2"), adult, "maintain", 0.0).status_code == 404  # cross-club
    assert client.get(f"/api/athletes/{adult}/nutrition/program", headers=auth("coach2")).status_code == 404
    assert set_program(client, auth("athlete1"), adult, "maintain", 0.0).status_code == 403  # other athlete
    for bad in ({"goal": "lose", "rate_kg_week": 0, "protein_g": 150, "fat_g": 50},  # lose needs a positive rate
                {"goal": "maintain", "rate_kg_week": 0.5, "protein_g": 150, "fat_g": 50},  # maintain needs rate 0
                {"goal": "lose", "rate_kg_week": 3, "protein_g": 150, "fat_g": 50}, {"goal": "x", "rate_kg_week": 0}):
        assert client.put(f"/api/athletes/{adult}/nutrition/program", json=bad, headers=head).status_code == 422
    first = set_program(client, head, adult, "gain", 0.25)
    assert first.status_code == 200 and first.json()["applied"] is True
    again = set_program(client, head, adult, "gain", 0.25)  # idempotent upsert: same program state
    assert again.json()["program"] == first.json()["program"] and again.json()["applied"] == first.json()["applied"]
    assert "coaching_program_set" in audit_actions(client, auth)
    export = client.get("/api/me/export", headers=head).json()
    assert export["coaching_program"]["goal"] == "gain" and export["coaching_program"]["applied"] is True
    assert len(export["complete_diary_days"]) == 28 and export["complete_diary_days"][0]["day"] == day(-27)
    assert client.delete("/api/me/data", headers=head).status_code == 204
    after = client.get("/api/me/export", headers=head).json()
    assert after["coaching_program"] is None and after["complete_diary_days"] == []
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM coaching_programs WHERE athlete_id = ?", (adult,)).fetchone()[0] == 0


def test_seed_demo_athlete_shows_an_adaptive_program(api, auth):
    client, path = api
    a6 = user_id(path, "athlete6")
    series = client.get(f"/api/athletes/{a6}/nutrition/expenditure", headers=auth("coach")).json()
    assert series["expenditure"]["status"] == "updating"  # a mature, adapting series from the seeded history
    assert sum(1 for e in series["history"] if e["status"] == "updating") >= 10
    program = client.get(f"/api/athletes/{a6}/nutrition/program", headers=auth("coach")).json()
    assert program["program"] is not None and program["program"]["goal"] == "lose"
    assert program["program"]["recommended_target"]["calories"] > 0
