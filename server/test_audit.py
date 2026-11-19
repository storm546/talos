"""Regression tests for server audit findings."""

from datetime import datetime, timezone

import pytest

from server import main
from server.test_server import METRICS, api, auth, template, user_id


@pytest.mark.parametrize("kind", ["jump", "checkin", "food", "weight", "set", "complete"])
def test_writes_check_committed_consent(api, auth, kind):
    client, path = api
    athlete = user_id(path, "athlete1")
    headers = auth("athlete1")
    today = datetime.now(timezone.utc).date().isoformat()
    with main.database(path) as db:
        stale = db.execute("SELECT * FROM users WHERE id = ?", (athlete,)).fetchone()
        assert stale["consent_recorded"]
        db.execute("UPDATE users SET consent_recorded = 0 WHERE id = ?", (athlete,))
        food = db.execute("SELECT id FROM foods WHERE owner_id IS NULL AND club_id IS NULL LIMIT 1").fetchone()[0]
        exercise = db.execute("SELECT id FROM exercises WHERE club_id = ? LIMIT 1", (stale["club_id"],)).fetchone()[0]
    client.app.dependency_overrides[main.current_user] = lambda: stale
    method, endpoint, body = {
        "jump": ("POST", "/api/jumps", METRICS),
        "checkin": ("PUT", f"/api/me/checkins/{today}", dict.fromkeys(("sleep", "fatigue", "soreness", "stress", "mood"), 4)),
        "food": ("POST", "/api/food-logs", {"day": today, "meal": "lunch", "food_id": food, "grams": 100}),
        "weight": ("PUT", f"/api/me/weights/{today}", {"kg": 70}),
        "set": ("POST", "/api/set-logs", {"day": today, "exercise_id": exercise, "set_no": 1, "reps": 5}),
        "complete": ("PUT", f"/api/me/nutrition/days/{today}/complete", None),
    }[kind]
    response = client.request(method, endpoint, headers=headers, json=body)
    assert response.status_code == 403, response.text


@pytest.mark.parametrize("path", ["/api/athletes/9223372036854775808/summary",
                                  "/api/jumps?athlete_id=9223372036854775808",
                                  "/api/athletes?offset=9223372036854775808",
                                  "/api/foods?offset=9223372036854775808"])
def test_sqlite_integer_overflow_is_a_validation_error(api, auth, path):
    client, _ = api
    response = client.get(path, headers=auth("admin"))
    assert response.status_code == 422, response.text
    assert {"loc", "msg", "type"} == set(response.json()["detail"][0])


def test_sqlite_integer_overflow_in_body_is_a_validation_error(api, auth):
    client, _ = api
    response = client.post("/api/jumps", headers=auth("athlete1"), json={**METRICS, "session_id": 2**63})
    assert response.status_code == 422, response.text


def test_nutrition_summary_handles_earliest_calendar_day(api, auth):
    client, path = api
    athlete = user_id(path, "athlete1")
    response = client.get(f"/api/athletes/{athlete}/nutrition/summary", params={"day": "0001-01-01"},
                          headers=auth("athlete1"))
    assert response.status_code == 200, response.text
    assert response.json()["weekly_average"]["window"] == ["0001-01-01", "0001-01-01"]


