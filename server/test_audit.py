"""Regression tests for server audit findings."""

from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import tempfile

import pytest

from server import coaching, main
from server.test_form_api import FORM
from server.test_server import METRICS, api, auth, template, user_id
from server.test_training_nutrition import series
from server.weight_trend import weight_smoothing, weight_trend_pipeline


def test_large_personal_export_is_complete_and_spooled_before_the_audit_commit(api, auth, monkeypatch):
    client, path = api
    headers = auth("athlete1")
    athlete = user_id(path, "athlete1")
    body = "а" * 1000
    with main.database(path) as db:
        db.executemany("INSERT INTO comments(athlete_id, author_id, body, created_at) VALUES (?, ?, ?, ?)",
                       [(athlete, athlete, body, "2026-01-01")] * 600)
        expected = db.execute("SELECT COUNT(*) FROM comments WHERE athlete_id = ?", (athlete,)).fetchone()[0]

    original = main.audit
    spool = tempfile.SpooledTemporaryFile
    files = []

    def capture(*args, **kwargs):
        output = spool(*args, **kwargs)
        files.append(output)
        return output

    def audit(db, *args):
        if args[1] == "json_export":
            assert not db.in_transaction
            assert len(files) == 1 and files[0]._rolled
        original(db, *args)

    monkeypatch.setattr(main, "audit", audit)
    monkeypatch.setattr(tempfile, "SpooledTemporaryFile", capture)
    response = client.get("/api/me/export", headers=headers)
    assert response.status_code == 200, response.text[:300]
    assert files[0].closed
    assert len(response.content) > 1_048_576
    assert len(response.json()["comments"]) == expected
    assert response.json()["comments"][0]["body"] == body
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM audit WHERE action = 'json_export'").fetchone()[0] == 1


@pytest.mark.parametrize("mode", ["holding", "updating"])
def test_cold_coaching_sparse_replay_matches_daily_replay(monkeypatch, mode):
    seed = date(2020, 1, 1)
    end = date(2025, 1, 1)
    days = [(seed + timedelta(days=index)).isoformat() for index in range(18)]
    snaps = {day: {"status": "complete", "calories": 2600, "changed": False} for day in days}
    snaps[(end - timedelta(days=1)).isoformat()] = snaps[days[0]]
    weights = dict.fromkeys(days, 80.0)
    previous = 2500.0
    rows = []
    original = coaching.step
    for offset in range(1, (end - seed).days + 1):
        row = original((seed + timedelta(days=offset)).isoformat(), previous, mode, snaps, weights)
        previous = row["value_kcal"]
        rows.append(row)
    calls = []

    def step(day, *args, **kwargs):
        calls.append(day)
        return original(day, *args, **kwargs)

    monkeypatch.setattr(coaching, "step", step)
    result = coaching.compute(seed.isoformat(), 2500, mode, snaps, weights, end.isoformat())
    assert result["history"] == [{key: row[key] for key in ("date", "value_kcal", "status", "reason", "complete_days")}
                                 for row in rows[-30:]]
    assert result["expenditure"] == {key: value for key, value in rows[-1].items() if key != "date"}
    assert len(calls) <= 30 + len(weights)


@pytest.mark.parametrize("kind", ["jump", "checkin", "food", "weight", "set", "complete", "custom food", "form"])
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
        "custom food": ("POST", "/api/foods", {"name_bg": "Овес", "kcal": 100, "protein_g": 10,
                                               "carbs_g": 20, "fat_g": 5}),
        "form": ("POST", "/api/form", FORM),
    }[kind]
    response = client.request(method, endpoint, headers=headers, json=body)
    assert response.status_code == 403, response.text


@pytest.mark.parametrize("change", ["UPDATE users SET active = 0 WHERE id = ?", "DELETE FROM tokens WHERE user_id = ?"])
def test_form_write_reauthenticates_inside_transaction(api, auth, change):
    client, path = api
    athlete = user_id(path, "athlete1")
    headers = auth("athlete1")
    with main.database(path) as db:
        stale = db.execute("SELECT * FROM users WHERE id = ?", (athlete,)).fetchone()
        db.execute(change, (athlete,))
    client.app.dependency_overrides[main.current_user] = lambda: stale
    response = client.post("/api/form", headers=headers, json=FORM)
    assert response.status_code == 401, response.text
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM form_sets WHERE athlete_id = ?", (athlete,)).fetchone()[0] == 0


@pytest.mark.parametrize("path", ["/api/athletes/9223372036854775808/summary",
                                  "/api/jumps?athlete_id=9223372036854775808",
                                  "/api/athletes?offset=9223372036854775808",
                                  "/api/foods?offset=9223372036854775808",
                                  "/api/athletes/9223372036854775808/form.csv"])
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


def test_team_checkin_excludes_incomplete_baseline_rows(api, auth):
    client, path = api
    athlete = user_id(path, "athlete1")
    today = datetime.now(timezone.utc).date()
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ?", (athlete,))
        db.executemany("INSERT INTO checkins(athlete_id, day, sleep, fatigue, soreness, stress, mood, created_at, updated_at) "
                       "VALUES (?, ?, ?, 4, 4, 4, 4, '', '')", [
                           (athlete, (today - timedelta(days=delta)).isoformat(), None if delta == 5 else 4)
                           for delta in range(6)])
    response = client.get("/api/checkins/team", params={"day": today.isoformat()}, headers=auth("coach"))
    assert response.status_code == 200, response.text
    row = next(row for row in response.json() if row["athlete_id"] == athlete)
    assert row["wellbeing"] == 20 and row["below_usual"] is None


def test_summary_reads_only_the_first_and_last_three_jumps(api, auth, monkeypatch):
    client, path = api
    athlete = user_id(path, "athlete1")
    with main.database(path) as db:
        db.execute("DELETE FROM jumps WHERE athlete_id = ?", (athlete,))
        db.executemany("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)", [
            (athlete, "2026-02-01T10:00:00+00:00", json.dumps({**METRICS, "fps": value}))
            for value in range(31, 42)])
    decode = json.loads
    fps_decoded = []

    def track(payload, *args, **kwargs):
        decoded = decode(payload, *args, **kwargs)
        if isinstance(decoded, dict) and "flight_time_ms" in decoded:
            fps_decoded.append(decoded["fps"])
        return decoded

    headers = auth("coach")
    monkeypatch.setattr(main.json, "loads", track)
    response = client.get(f"/api/athletes/{athlete}/summary", headers=headers)
    assert response.status_code == 200, response.text
    assert response.json()["jump_count"] == 11
    assert fps_decoded == [31, 32, 33, 39, 40, 41]


def test_weight_smoothing_reuses_gains_and_preserves_exact_values(monkeypatch):
    def track(values):
        nonlocal calls
        calls += 1
        return original(values)

    calls = 0
    original = weight_smoothing.inverse
    monkeypatch.setattr(weight_smoothing, "inverse", track)
    rows = weight_trend_pipeline(series(180, -0.02, (3, 11, 20)))
    assert hashlib.sha256(json.dumps(rows).encode()).hexdigest() == (
        "9baa9395bbdd487a782edfd2f98ff4e864c40c2a28bd25be22d76db71eab62d3")
    assert calls <= 179


def test_expenditure_reuses_exact_inputs_and_rechecks_after_edit(api, auth, monkeypatch):
    client, path = api
    athlete = user_id(path, "athlete6")
    headers = auth("coach")
    prefix = f"/api/athletes/{athlete}/nutrition"
    computes = []
    original = coaching.compute

    def compute(*args):
        computes.append(args)
        return original(*args)

    monkeypatch.setattr(coaching, "compute", compute)
    for suffix in ("expenditure", "program", "expenditure"):
        response = client.get(f"{prefix}/{suffix}", headers=headers)
        assert response.status_code == 200, response.text
    with main.database(path) as db:
        db.execute("UPDATE weights SET kg = kg + 0.25 WHERE athlete_id = ?", (athlete,))
    assert client.get(f"{prefix}/expenditure", headers=headers).status_code == 200
    assert len(computes) == 2
