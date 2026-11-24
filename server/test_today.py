"""Today: the scoped aggregate behind the phone's home screen and the athlete's dashboard page (SPEC 10.2)."""

from datetime import date

from server import today_api
from server.test_readiness import put_checkin
from server.test_records import jump
from server.test_server import api, auth, template, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import day, exercise_id, make_athlete

SECTIONS = ["readiness", "checkin", "plan", "training", "nutrition", "weight", "records", "next_session", "unread"]


def today(client, headers, **params):
    response = client.get("/api/me/today", params=params, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def test_fresh_athlete_gets_every_section_with_empty_states(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    result = today(client, headers)
    assert result["day"] == day() and result["role"] == "athlete" and all(k in result for k in SECTIONS)
    assert result["readiness"]["status"] == "still_learning" and result["readiness"]["still_learning"]
    assert result["checkin"] == {"done": False, "wellbeing": None, "out_of": 25}
    assert result["training"]["sessions"] == [] and result["training"]["total_load"] == 0
    assert result["training"]["acute_chronic"]["still_learning"]
    assert result["nutrition"]["logs"] == 0 and result["nutrition"]["eaten"]["kcal"] == 0
    assert result["weight"] is None and result["records"] == [] and result["unread"] == 0
    assert result["plan"]["logged"] is False


def test_sections_equal_the_separate_routes(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    put_checkin(client, headers, 0, sleep=4, mood=5)
    assert client.post("/api/training-sessions", headers=headers,
                       json={"day": day(), "kind": "gym", "minutes": 60, "rpe": 7, "client_id": "t1"}).status_code == 201
    assert client.put(f"/api/me/weights/{day()}", json={"kg": 70.5}, headers=headers).status_code in (200, 201)
    food = client.get("/api/foods", params={"q": "ориз"}, headers=headers).json()[0]["id"]
    assert client.post("/api/food-logs", headers=headers,
                       json={"day": day(), "meal": "lunch", "food_id": food, "grams": 150}).status_code == 201
    jump(client, headers, 30)
    jump(client, headers, 42, flight=410)  # a first value is not a record yet; beating it beyond MDC95 is (SPEC 8)
    result = today(client, headers)
    route = client.get(f"/api/athletes/{athlete}/readiness", params={"days": 1, "day": day()}, headers=headers).json()
    assert result["readiness"]["factors"] == route["days"][0]["factors"]
    assert result["readiness"]["status"] == route["days"][0]["status"]
    wellbeing = next(f["value"] for f in route["days"][0]["factors"] if f["key"] == "wellbeing")
    assert result["checkin"] == {"done": True, "wellbeing": wellbeing, "out_of": 25}
    assert [s["load"] for s in result["training"]["sessions"]] == [420] and result["training"]["total_load"] == 420
    assert result["training"]["daily"] == [0, 0, 0, 0, 0, 0, 420]  # the Today tile's 7-day bars (SPEC 10.9)
    assert "series" in result["weight"] and result["body"] is None and result["form"] is None
    load = client.get(f"/api/athletes/{athlete}/load", params={"days": 7, "day": day()}, headers=headers).json()
    assert result["training"]["acute_chronic"]["ratio"] == load["acute_chronic"]["ratio"]
    assert (result["training"]["acute_load"], result["training"]["chronic_load"]) == (load["acute_load"], load["chronic_load"])
    summary = client.get(f"/api/athletes/{athlete}/nutrition/summary", params={"day": day()}, headers=headers).json()
    assert result["nutrition"]["eaten"]["kcal"] == summary["totals"]["kcal"] > 0 and result["nutrition"]["logs"] == 1
    assert result["nutrition"]["target"] == summary["target"]
    weights = client.get(f"/api/athletes/{athlete}/weights", headers=headers).json()
    assert result["weight"]["kg"] == 70.5 and result["weight"]["day"] == day()
    assert result["weight"]["trend_kg"] == (weights["trend"][-1]["trend_kg"] if weights["trend"] else None)
    assert [(r["measure"], r["value"], r["unit"]) for r in result["records"]][:1] == [("jump_height", 42, "cm")]


def test_plan_lists_todays_items_bounded_and_marks_a_logged_workout(api, auth):
    client, path = api
    coach = auth("coach")
    athlete, headers = make_athlete(client, auth)
    squat = exercise_id(client, coach)
    weekday = date.fromisoformat(day()).weekday()
    items = [{"weekday": weekday, "exercise_id": squat, "sets": 3, "reps": 5}] * 25 + \
            [{"weekday": (weekday + 1) % 7, "exercise_id": squat, "sets": 1, "reps": 1}]
    body = {"title": "Много", "starts_on": day(), "weeks": 1, "athlete_id": athlete, "items": items}
    assert client.post("/api/plans", json=body, headers=coach).status_code == 201
    plan = today(client, headers)["plan"]
    assert len(plan["items"]) == today_api.MAX_PLAN_ITEMS and plan["more"] == 5 and not plan["logged"]
    assert plan["items"][0] == {"plan_id": plan["items"][0]["plan_id"], "plan": "Много", "exercise": "Клякане със щанга",
                                "sets": 3, "reps": 5, "reps_max": 5, "load_kg": None}
    assert client.post("/api/set-logs", headers=headers, json={
        "exercise_id": squat, "day": day(), "set_no": 1, "reps": 5, "load_kg": 60, "rpe": None}).status_code == 201
    assert today(client, headers)["plan"]["logged"]


def test_a_failing_section_is_null_and_the_rest_still_return(api, auth, monkeypatch):
    client, _ = api
    _, headers = make_athlete(client, auth)

    def broken(context):
        raise RuntimeError("boom")

    monkeypatch.setitem(today_api.SECTIONS, "nutrition", broken)
    result = today(client, headers)
    assert result["nutrition"] is None and result["checkin"] is not None and result["readiness"] is not None


def test_an_athlete_sees_only_their_own_data(api, auth):
    client, path = api
    _, mine = make_athlete(client, auth, email="one@demo.test")
    _, theirs = make_athlete(client, auth, email="two@demo.test")
    assert client.put(f"/api/me/weights/{day()}", json={"kg": 90}, headers=theirs).status_code in (200, 201)
    put_checkin(client, theirs, 0)
    jump(client, theirs, 50)
    result = today(client, mine)
    assert result["weight"] is None and not result["checkin"]["done"] and result["records"] == []
    # The route has no athlete parameter to point elsewhere; unknown parameters are ignored, not obeyed.
    other = user_id(path, "athlete1")
    assert today(client, mine, athlete_id=other)["weight"] is None


def test_staff_get_only_the_next_session_and_the_day_is_bounded(api, auth):
    client, _ = api
    for account in ("coach", "admin"):
        result = today(client, auth(account))
        assert result["role"] == account
        assert all(result[k] is None for k in SECTIONS if k != "next_session")
    headers = auth("athlete1")
    assert today(client, headers, day=day(-1))["day"] == day(-1)
    for bad in (day(-2), day(2), "2026-13-01", "yesterday"):
        assert client.get("/api/me/today", params={"day": bad}, headers=headers).status_code == 422
    assert client.get("/api/me/today").status_code == 401
