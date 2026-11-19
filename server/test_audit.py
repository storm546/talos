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


