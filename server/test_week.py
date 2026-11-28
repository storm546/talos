"""The personal loop (SPEC 10.3): consistency score, weekly report and "not feeling 100%"."""

from datetime import date, timedelta
import math
from types import SimpleNamespace

import pytest

from server import main, readiness_api, week_api
from server.test_readiness import insert_checkins, put_checkin
from server.test_records import jump
from server.test_server import api, auth, template, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import audit_actions, day, exercise_id, make_athlete

DAILY = 0.5 ** (1 / 13)


def iso_week(offset_weeks=0):
    monday = date.fromisoformat(day()) - timedelta(days=date.fromisoformat(day()).weekday(), weeks=offset_weeks)
    year, week, _ = monday.isocalendar()
    return f"{year}-W{week:02d}", monday


def get(client, headers, path, **params):
    response = client.get(path, params=params, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


# --- the formula: the same hand-computed values as core/ConsistencyTest.kt ---

def test_score_matches_the_core_values():
    assert week_api.step(1, 0, True) == pytest.approx(0.051922486, abs=1e-9)
    assert week_api.score([True] * 3) == pytest.approx(0.147819604, abs=1e-9)
    assert week_api.score([True, False, True]) == pytest.approx(0.098593062, abs=1e-9)
    assert week_api.score([True] * 14) == pytest.approx(0.525961243, abs=1e-9)
    assert week_api.score([n not in (4, 9) for n in range(14)]) == pytest.approx(0.451878437, abs=1e-9)
    assert week_api.score([True], lambda i: 3 / 7) == pytest.approx(0.034303318, abs=1e-9)
    assert week_api.score([True] * 7, lambda i: 3 / 7) == pytest.approx(0.216777467, abs=1e-9)
    assert week_api.score([]) == 0


def test_gym_streak_counts_full_weeks_and_an_open_week_does_not_break_it():
    last = date(2026, 11, 26)  # Thursday
    monday = date(2026, 11, 23)
    days = lambda m, n: {(m + timedelta(days=i)).isoformat() for i in range(n)}
    two_weeks = days(monday - timedelta(weeks=1), 3) | days(monday - timedelta(weeks=2), 4)
    assert week_api.streak(two_weeks, [], last) == {"weeks": 2, "done": 0, "target": 3, "days": [False] * 7}
    assert week_api.streak(two_weeks | days(monday, 3), [], last)["weeks"] == 3
    gap = days(monday - timedelta(weeks=1), 2) | days(monday - timedelta(weeks=2), 3)
    assert week_api.streak(gap, [], last)["weeks"] == 0
    plan = [(monday - timedelta(weeks=4), 8, {0, 3})]
    assert week_api.streak(days(monday - timedelta(weeks=1), 2) | days(monday, 2), plan, last) == {
        "weeks": 2, "done": 2, "target": 2, "days": [True, True] + [False] * 5}


def test_consistency_starts_on_the_first_day_with_data_and_counts_the_last_14_days(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    assert get(client, headers, "/api/me/today")["week"]["consistency"] == {"checkin": None, "training": None, "streak": None}
    insert_checkins(path, athlete, [n for n in range(14) if n not in (4, 9)])
    assert client.post("/api/training-sessions", headers=headers,
                       json={"day": day(), "kind": "gym", "minutes": 30, "rpe": 5}).status_code == 201
    result = get(client, headers, "/api/me/today")["week"]["consistency"]
    expected = week_api.score([n not in (4, 9) for n in range(13, -1, -1)])
    assert result["checkin"] == {"score": round(expected, 4), "percent": round(expected * 100), "done": 12, "days": 14}
    # Training: 13 missed days then one done, three a week with no plan (frequency 3/7).
    m = 0.5 ** (math.sqrt(3 / 7) / 13)
    assert result["training"]["score"] == round(1 - m, 4) and result["training"]["done"] == 1


def test_training_frequency_follows_the_plan(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    coach = auth("coach")
    squat = exercise_id(client, coach)
    items = [{"weekday": w, "exercise_id": squat, "sets": 3, "reps": 5} for w in (0, 3)]
    assert client.post("/api/plans", headers=coach, json={"title": "Две", "starts_on": day(-3), "weeks": 2,
                                                          "athlete_id": athlete, "items": items}).status_code == 201
    assert client.post("/api/set-logs", headers=headers, json={
        "exercise_id": squat, "day": day(-1), "set_no": 1, "reps": 5, "load_kg": 60, "rpe": None}).status_code == 201
    result = get(client, headers, "/api/me/today")["week"]["consistency"]["training"]
    # First data day is yesterday; the plan applies on both days: frequency 2/7.
    assert result["score"] == round(week_api.score([True, False], lambda i: 2 / 7), 4) and result["days"] == 2


# --- the weekly report ---

def test_week_facts_equal_the_routes_and_text_is_null_without_a_model(api, auth, monkeypatch):
    monkeypatch.delenv("JUMPSCAN_LLM_URL", raising=False)
    client, path = api
    athlete, headers = make_athlete(client, auth)
    week, monday = iso_week()
    squat = exercise_id(client, auth("coach"))
    put_checkin(client, headers, 0, sleep=4)
    for n, (kind, minutes, rpe) in enumerate([("gym", 60, 7), ("practice", 30, 5)]):
        assert client.post("/api/training-sessions", headers=headers, json={
            "day": day(), "kind": kind, "minutes": minutes, "rpe": rpe, "client_id": f"w{n}"}).status_code == 201
    for set_no, load in ((1, 60), (2, 70)):
        assert client.post("/api/set-logs", headers=headers, json={
            "exercise_id": squat, "day": day(), "set_no": set_no, "reps": 5, "load_kg": load, "rpe": None}).status_code == 201
    jump(client, headers, 30)
    jump(client, headers, 42, flight=410)  # beyond the smallest detectable change (11.3 cm, 96.6 ms)
    assert client.put(f"/api/me/weights/{day()}", json={"kg": 70.5}, headers=headers).status_code in (200, 201)
    result = get(client, headers, "/api/me/week")
    assert result["week"] == week and result["from"] == monday.isoformat()
    assert result["to"] == (monday + timedelta(days=6)).isoformat() and result["text"] is None
    training = result["training"]
    assert training["sessions"] == 2 and training["minutes"] == 90 and training["load"] == 570 and training["days"] == 1
    assert training["sets"] == 2 and training["volume_kg"] == 650
    assert training["top_exercises"] == [{"exercise": "Клякане със щанга", "sets": 2, "volume_kg": 650}]
    assert [(r["measure"], r["value"]) for r in result["records"]][:1] == [("jump_height", 42)]
    route = get(client, headers, f"/api/athletes/{athlete}/readiness", days=1, day=day())
    wellbeing = next(f["value"] for f in route["days"][0]["factors"] if f["key"] == "wellbeing")
    assert result["checkins"] == {"days": 1, "wellbeing_avg": wellbeing}
    assert sum(result["readiness"].values()) <= 7 and set(result["readiness"]) == set(week_api.STATUSES)
    summary = get(client, headers, f"/api/athletes/{athlete}/nutrition/summary", day=result["to"])
    assert result["nutrition"]["days_logged"] == summary["weekly_average"]["days_logged"]
    trend = get(client, headers, f"/api/athletes/{athlete}/weights", **{"from": result["from"], "to": result["to"]})["trend"]
    assert result["weight"]["end_trend"] == (trend[-1]["trend_kg"] if trend else None)
    assert result["consistency"]["checkin"]["done"] == 1
    previous = get(client, headers, "/api/me/week", week=iso_week(1)[0])
    assert previous["training"]["sessions"] == 0 and previous["training"]["load"] == 0
    assert result["training"]["load_previous_week"] == previous["training"]["load"]


def test_week_text_comes_from_the_model_once_per_set_of_facts(api, auth, monkeypatch):
    client, path = api
    _, headers = make_athlete(client, auth)
    calls = []

    class Reply:
        content = b"{}"
        def raise_for_status(self): pass
        def json(self): return {"choices": [{"message": {"content": " A steady week. "}}]}

    class Client:
        def __init__(self, **options): pass
        def __enter__(self): return self
        def __exit__(self, *error): return False
        def post(self, url, headers=None, json=None):
            calls.append(json)
            return Reply()

    monkeypatch.setenv("JUMPSCAN_LLM_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("JUMPSCAN_LLM_MODEL", "local")
    monkeypatch.setattr(readiness_api, "httpx", SimpleNamespace(
        Client=Client, Timeout=readiness_api.httpx.Timeout, HTTPError=readiness_api.httpx.HTTPError))
    assert get(client, headers, "/api/me/week", language="en")["text"] == "A steady week."
    assert get(client, headers, "/api/me/week", language="en")["text"] == "A steady week." and len(calls) == 1
    prompt = calls[0]["messages"][0]["content"]
    assert "never add a number" in prompt and "medical" in prompt and "in English" in prompt
    put_checkin(client, headers, 0)  # new facts: phrased again
    get(client, headers, "/api/me/week", language="en")
    get(client, headers, "/api/me/week", language="bg")
    assert len(calls) == 3 and "(Вие)" in calls[2]["messages"][0]["content"]


def test_week_is_for_athletes_and_bounded(api, auth):
    client, _ = api
    _, headers = make_athlete(client, auth)
    assert client.get("/api/me/week", headers=auth("coach")).status_code == 403
    assert client.get("/api/me/week").status_code == 401
    assert client.get("/api/me/week", params={"week": iso_week(52)[0]}, headers=headers).status_code == 200
    for bad in (iso_week(53)[0], iso_week(-1)[0], "2026-W60", "2026-48", "week"):
        assert client.get("/api/me/week", params={"week": bad}, headers=headers).status_code == 422, bad


# --- "not feeling 100%" ---

def adjust(client, headers, reason="tired", mode="lighter", offset=0):
    return client.put(f"/api/me/days/{day(offset)}/adjustment", json={"reason": reason, "mode": mode}, headers=headers)


def test_lighter_and_rest_change_todays_plan(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    coach = auth("coach")
    squat = exercise_id(client, coach)
    weekday = date.fromisoformat(day()).weekday()
    items = [{"weekday": weekday, "exercise_id": squat, "sets": 3, "reps": 5, "load_kg": 62.5},
             {"weekday": weekday, "exercise_id": squat, "sets": 1, "reps": 5, "load_kg": None}]
    assert client.post("/api/plans", headers=coach, json={"title": "План", "starts_on": day(), "weeks": 1,
                                                          "athlete_id": athlete, "items": items}).status_code == 201
    assert adjust(client, headers).json() == {"day": day(), "reason": "tired", "mode": "lighter"}
    result = get(client, headers, "/api/me/today")
    plan = result["plan"]
    assert plan["adjusted"] == "lighter" and result["adjustment"] == {"reason": "tired", "mode": "lighter"}
    # 62.5 x 0.9 = 56.25 -> 57.5 kg (nearest 2.5); one set fewer, never below one.
    assert [(i["sets"], i["load_kg"]) for i in plan["items"]] == [(2, 57.5), (1, None)]
    assert adjust(client, headers, "ill", "rest").status_code == 200
    plan = get(client, headers, "/api/me/today")["plan"]
    assert plan["adjusted"] == "rest" and plan["items"] == []
    assert client.delete(f"/api/me/days/{day()}/adjustment", headers=headers).status_code == 204
    result = get(client, headers, "/api/me/today")
    assert result["plan"]["adjusted"] is None and result["adjustment"] is None
    assert [(i["sets"], i["load_kg"]) for i in result["plan"]["items"]] == [(3, 62.5), (1, None)]
    assert client.delete(f"/api/me/days/{day()}/adjustment", headers=headers).status_code == 404
    assert [week_api.lighter({"sets": 5, "load_kg": kg})["load_kg"] for kg in (100, 60, 20, 2.5)] == [90, 55, 17.5, 2.5]


def test_readiness_lists_the_flag_and_staff_see_only_a_neutral_mark(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    before = get(client, headers, f"/api/athletes/{athlete}/readiness", days=1)["days"][0]
    assert adjust(client, headers, "aches", "rest").status_code == 200
    own = get(client, headers, f"/api/athletes/{athlete}/readiness", days=1)["days"][0]
    flag = next(f for f in own["factors"] if f["key"] == "adjustment")
    assert flag == {"key": "adjustment", "value": "aches", "mode": "rest", "unit": None, "state": "flagged"}
    assert own["status"] == before["status"]  # listed, never scored
    assert get(client, headers, "/api/me/today")["readiness"]["factors"] == own["factors"]
    coach = auth("coach")
    seen = get(client, coach, f"/api/athletes/{athlete}/readiness", days=1)["days"][0]
    assert next(f for f in seen["factors"] if f["key"] == "adjustment")["value"] is None
    rows = {row["athlete_id"]: row for row in get(client, coach, "/api/team/board")["rows"]}
    assert rows[athlete]["not_100"] is True and rows[user_id(path, "athlete1")]["not_100"] is False
    assert "aches" not in str(rows[athlete])


def test_adjustment_scope_bounds_audit_export_and_delete(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    assert adjust(client, headers, offset=-1).status_code == 200
    for offset in (-2, 2):
        assert adjust(client, headers, offset=offset).status_code == 422
    assert adjust(client, headers, reason="injured").status_code == 422
    assert client.put(f"/api/me/days/{day()}/adjustment", headers=headers,
                      json={"reason": "busy", "mode": "rest", "note": "x"}).status_code == 422
    assert adjust(client, auth("coach")).status_code == 403
    _, no_consent = make_athlete(client, auth, email="noconsent@demo.test", consent=False)
    assert adjust(client, no_consent).status_code == 403
    assert {"day_adjusted"} <= set(audit_actions(client, auth))
    with main.database(path) as db:
        details = [row[0] for row in db.execute("SELECT detail FROM audit WHERE action = 'day_adjusted'")]
    assert details and all("tired" not in d and "lighter" not in d for d in details)
    exported = client.get("/api/me/export", headers=headers).json()
    assert [(r["day"], r["reason"], r["mode"]) for r in exported["day_adjustments"]] == [(day(-1), "tired", "lighter")]
    assert client.delete("/api/me/data", headers=headers).status_code == 204
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM day_adjustments WHERE athlete_id = ?", (athlete,)).fetchone()[0] == 0
