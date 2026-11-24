"""Readiness, sleep, sessions, load, the team board, nudges and the digest (SPEC 9 wave 2).

The pure-function tests use numbers worked out by hand; the derivation is in the comments.
"""

from datetime import datetime, timedelta, timezone
import json
import sqlite3
from types import SimpleNamespace

from fastapi.testclient import TestClient
import pytest

from server import main, readiness as r, readiness_api
from server.seed import seed
from server.test_server import METRICS, api, auth, template, temporary_directory, user_id
from server.test_training_nutrition_api import audit_actions, day, make_athlete

SERIES = [60, 62, 58, 61, 59, 63, 57]  # mean 60; squared deviations 0+4+4+1+1+9+9 = 28; SD = sqrt(28/6)


def test_baseline_mean_and_sample_sd():
    base = r.baseline(SERIES)
    assert base["ready"] and base["n"] == 7 and base["mean"] == 60
    assert base["sd"] == pytest.approx((28 / 6) ** 0.5)  # 2.1602


def test_baseline_with_too_few_values_is_still_learning():
    base = r.baseline([60, 61, None, 62, 59, 58, 60])  # six known values, None is ignored
    assert base == {"n": 6, "needed": 7, "ready": False, "mean": None, "sd": None}
    assert r.compare(70, base)["state"] == "learning"
    assert r.compare(None, r.baseline(SERIES))["state"] == "missing"


def test_typical_error_from_consecutive_differences():
    # differences 2, -4, 3, -2, 4, -6: mean -0.5, squared deviations sum 83.5, SD sqrt(83.5/5) = 4.0866
    assert r.differences(SERIES) == [2, -4, 3, -2, 4, -6]
    assert r.typical_error(r.differences(SERIES)) == pytest.approx((83.5 / 5) ** 0.5 / 2 ** 0.5)  # 2.8897
    assert r.typical_error([3]) is None


def test_smallest_worthwhile_change_decides_without_typical_error():
    base = r.baseline(SERIES)  # SWC = 0.5 x 2.1602 = 1.0801
    assert r.compare(61, base)["state"] == "usual"  # +1.0 is inside the SWC
    higher = r.compare(61.2, base)
    assert higher["state"] == "better" and higher["threshold"] == pytest.approx(1.0801, abs=1e-4)
    assert r.compare(61.2, base, higher_is_better=False)["state"] == "worse"  # e.g. resting heart rate
    assert r.compare(58.8, base)["state"] == "worse"


def test_typical_error_raises_the_threshold_when_larger():
    base = r.baseline(SERIES)
    error = r.typical_error(r.differences(SERIES))  # 2.8897 > SWC 1.0801
    assert r.compare(62, base, error, higher_is_better=False)["state"] == "usual"
    assert r.compare(63, base, error, higher_is_better=False)["state"] == "worse"
    assert r.compare(63, base, 0.2, higher_is_better=False)["threshold"] == pytest.approx(1.0801, abs=1e-4)


def test_ln_rmssd():
    assert r.ln_rmssd(100) == pytest.approx(4.60517) and r.ln_rmssd(None) is None


def test_sleep_duration_midpoint_and_need():
    assert r.sleep_hours("23:00", "07:00") == 8 and r.sleep_hours("22:30", "06:15") == 7.75
    assert r.sleep_midpoint("23:00", "07:00") == 900  # 03:00 is 15 h after the previous noon
    assert r.sleep_midpoint("01:00", "09:00") == 1020  # 05:00
    assert r.sleep_need(True) == (8, 10) and r.sleep_need(False) == (7, 9)
    assert r.duration_score(6.5, (8, 10)) == 62.5 and r.duration_score(11, (8, 10)) == 90
    assert r.duration_score(8.5, (8, 10)) == 100


def test_sleep_regularity_and_score():
    # midpoints 03:00, 04:00, 03:00, 04:00: mean 930, deviations +-30, SD sqrt(3600/3) = 34.64 min
    nights = [900, 960, 900, 960]
    assert r.regularity_score(34.641016) == pytest.approx(100 - 4.641016 * 100 / 90)  # 94.84
    assert r.regularity_score(30) == 100 and r.regularity_score(120) == 0 and r.regularity_score(200) == 0
    score = r.sleep_score(6.5, (8, 10), nights, quality=3)
    # (62.5 + 94.84 + 50) / 3 = 69.11
    assert score["score"] == 69 and score["midpoint_sd_min"] == pytest.approx(34.6)
    assert score["parts"] == {"duration": 62, "regularity": 95, "quality": 50}  # round() of 62.5 is 62 (banker's)
    short = r.sleep_score(6.5, (8, 10), [900, 960], quality=3)  # two nights: regularity not yet shown
    assert short["score"] == 56 and short["midpoint_sd_min"] is None  # (62.5 + 50) / 2 = 56.25
    assert r.sleep_state(85) == "better" and r.sleep_state(59) == "worse" and r.sleep_state(70) == "usual"


def test_monotony_and_strain():
    week = [10, 20, 30, 40, 50, 60, 70]  # mean 40, squared deviations 2800, SD sqrt(2800/6) = 21.602
    assert r.monotony(week) == pytest.approx(40 / (2800 / 6) ** 0.5)  # 1.8516
    assert r.strain(week) == pytest.approx(280 * 40 / (2800 / 6) ** 0.5)  # 518.46
    assert r.monotony([300] * 7) is None and r.strain([0] * 7) is None


def test_ewma_and_acute_chronic_ratio():
    assert r.ewma([100, 0, 0], 7) == pytest.approx([100, 75, 56.25])  # lambda 0.25
    assert r.ewma([100, 0], 28) == pytest.approx([100, 100 * 27 / 29])  # lambda 2/29
    assert r.acute_chronic([300] * 27)["ready"] is False
    steady = r.acute_chronic([300] * 28)
    assert steady["ready"] and steady["ratio"] == pytest.approx(1.0)
    # day 28 at 900: acute 900 x 0.25 + 300 x 0.75 = 450; chronic 900 x 2/29 + 300 x 27/29 = 341.38
    spike = r.acute_chronic([300] * 27 + [900])
    assert spike["acute"] == pytest.approx(450) and spike["chronic"] == pytest.approx(9900 / 29)
    assert spike["ratio"] == pytest.approx(450 * 29 / 9900) and r.ratio_band(spike["ratio"]) == "above"
    assert [r.ratio_band(x) for x in (1.6, 1.0, 0.7, None)] == ["well_above", "similar", "below", None]


def test_readiness_status_bands():
    def f(key, state):
        return {"key": key, "state": state}
    assert r.readiness_status([f("wellbeing", "learning"), f("jump", "learning"), f("sleep", "better")]) == "still_learning"
    assert r.readiness_status([f("wellbeing", "missing"), f("sleep", "missing")]) == "no_data"
    assert r.readiness_status([f("wellbeing", "worse"), f("hrv", "worse"), f("sleep", "usual")]) == "take_care"
    assert r.readiness_status([f("wellbeing", "worse"), f("hrv", "usual")]) == "normal"
    assert r.readiness_status([f("wellbeing", "usual"), f("sleep", "better")]) == "good"
    assert r.readiness_status([f("wellbeing", "usual"), f("hrv", "learning")]) == "normal"


# --- API ---

def put_checkin(client, headers, offset, **values):
    body = {"sleep": 3, "fatigue": 3, "soreness": 3, "stress": 3, "mood": 3, **values}
    response = client.put(f"/api/me/checkins/{day(offset)}", json=body, headers=headers)
    assert response.status_code in (200, 201), response.text
    return response.json()


def insert_checkins(path, athlete, days, **columns):
    """Direct rows for history older than the 6-day check-in window; `columns` values may be callables of n."""
    with main.database(path) as db:
        for n in days:
            values = {"sleep": 3, "fatigue": 3, "soreness": 3, "stress": 3, "mood": 3,
                      **{key: value(n) if callable(value) else value for key, value in columns.items()}}
            db.execute(f"INSERT INTO checkins(athlete_id, day, {', '.join(values)}, created_at, updated_at) "
                       f"VALUES (?, ?, {', '.join('?' * len(values))}, ?, ?)",
                       (athlete, day(-n), *values.values(), "2026-01-01T00:00:00+00:00", "2026-01-01T00:00:00+00:00"))


def factors(result, index=0):
    return {f["key"]: f for f in result["days"][index]["factors"]}


def test_checkin_accepts_heart_rate_hrv_and_sleep_times(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    saved = put_checkin(client, headers, 0, resting_hr=52, hrv_rmssd=85.5, bedtime="23:10", wake_time="07:05")
    assert (saved["resting_hr"], saved["hrv_rmssd"], saved["bedtime"], saved["wake_time"]) == (52, 85.5, "23:10", "07:05")
    for bad in ({"resting_hr": 10}, {"hrv_rmssd": 0}, {"bedtime": "23:10"}, {"bedtime": "24:00", "wake_time": "07:00"}):
        body = {"sleep": 3, "fatigue": 3, "soreness": 3, "stress": 3, "mood": 3, **bad}
        assert client.put(f"/api/me/checkins/{day()}", json=body, headers=headers).status_code == 422
    export = client.get("/api/me/export", headers=headers).json()
    assert export["checkins"][0]["resting_hr"] == 52 and export["training_sessions"] == []


def test_readiness_from_personal_baselines_with_factors(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    # 10 earlier days: resting HR 50 +- 1, RMSSD 80 ms, wellbeing 15; today HR 58 and RMSSD 50: both clearly worse
    insert_checkins(path, athlete, range(1, 11), resting_hr=lambda n: 50 + (n % 3) - 1, hrv_rmssd=lambda n: 78 + n % 5)
    put_checkin(client, headers, 0, resting_hr=58, hrv_rmssd=50.0, bedtime="23:00", wake_time="07:00")
    result = client.get(f"/api/athletes/{athlete}/readiness?days=2", headers=headers).json()
    today = factors(result)
    assert result["days"][0]["status"] == "take_care" and result["days"][0]["day"] == day()
    assert today["resting_hr"]["state"] == "worse" and today["resting_hr"]["baseline"]["n"] == 10
    week = [r.ln_rmssd(78 + n % 5) for n in range(1, 7)] + [r.ln_rmssd(50)]  # HRV and RHR: the 7-day mean
    assert today["hrv"]["state"] == "worse" and today["hrv"]["value"] == pytest.approx(sum(week) / 7)
    assert today["hrv"]["today"] == pytest.approx(r.ln_rmssd(50)) and today["hrv"]["week_values"] == 7
    assert today["resting_hr"]["value"] == pytest.approx((50 + 51 + 49 + 50 + 51 + 49 + 58) / 7)
    assert today["wellbeing"]["state"] == "usual"  # SD 0 and no change: inside the threshold
    assert today["jump"]["state"] == "learning" and today["sleep"]["sleep"]["hours"] == 8
    assert result["days"][1]["status"] in ("normal", "good", "take_care")
    assert "not medical advice" in result["note"]


def test_week_mean_rule_for_heart_rate_and_hrv():
    base = r.baseline([49, 50, 51] * 9)  # mean 50, SD 0.83: SWC 0.42
    error = 1.2
    # One high morning in a normal week: the single-day rule calls it worse, the week mean does not.
    week = [50, 51, 49, 50, 51, 49, 52]
    assert r.compare(52, base, error, higher_is_better=False)["state"] == "worse"
    usual = r.compare_week(52, week, base, error, higher_is_better=False)
    assert usual["state"] == "usual" and usual["threshold"] == pytest.approx(max(0.5 * base["sd"], error / 7 ** 0.5))
    assert r.compare_week(53, [53] * 7, base, error, higher_is_better=False)["state"] == "worse"
    assert r.compare_week(53, [53, 53], base, error, higher_is_better=False)["state"] == "learning"  # under 3 values
    assert r.compare_week(None, [53] * 6, base, error)["state"] == "missing"  # today's value is required
    assert r.compare_week(53, [53] * 7, r.baseline([50] * 3), error)["state"] == "learning"


def test_load_blocks():
    assert r.load_blocks([100] * 10) == {"acute": {"days": 7, "load": 700}, "chronic": {"days": 28, "weekly_load": None}}
    assert r.load_blocks([0] * 20 + [100] * 8)["chronic"]["weekly_load"] == 200
    assert r.load_blocks([])["acute"]["load"] == 0


def test_one_high_morning_no_longer_flags_heart_rate(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    insert_checkins(path, athlete, range(1, 28), resting_hr=lambda n: 50 + (n % 3) - 1)
    put_checkin(client, headers, 0, resting_hr=52)
    resting = factors(client.get(f"/api/athletes/{athlete}/readiness?days=1", headers=headers).json())["resting_hr"]
    assert resting["state"] == "usual" and resting["today"] == 52 and resting["week_values"] == 7
    # A whole week higher is a real change.
    insert_checkins(path, athlete, range(28, 40), resting_hr=50)
    with main.database(path) as db:
        db.execute("UPDATE checkins SET resting_hr = 54 WHERE athlete_id = ? AND day >= ?", (athlete, day(-6)))
    resting = factors(client.get(f"/api/athletes/{athlete}/readiness?days=1", headers=headers).json())["resting_hr"]
    assert resting["state"] == "worse" and resting["value"] == pytest.approx(54)


def test_too_few_values_this_week_is_still_learning(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    insert_checkins(path, athlete, range(8, 20), hrv_rmssd=80)
    put_checkin(client, headers, 0, hrv_rmssd=40)
    hrv = factors(client.get(f"/api/athletes/{athlete}/readiness?days=1", headers=headers).json())["hrv"]
    assert hrv["baseline"]["ready"] and hrv["state"] == "learning" and (hrv["week_values"], hrv["week_needed"]) == (1, 3)


def test_new_athlete_is_still_learning(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    put_checkin(client, headers, 0, resting_hr=55)
    result = client.get(f"/api/athletes/{athlete}/readiness?days=1", headers=headers).json()
    assert result["days"][0]["status"] == "still_learning"
    assert factors(result)["resting_hr"]["baseline"] == {"n": 0, "needed": 7, "ready": False, "mean": None, "sd": None}


def test_readiness_jumps_use_the_best_valid_trial_and_count_uncertain_ones(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    with main.database(path) as db:
        for n in range(1, 9):
            for trial, height in enumerate((30.0 + n % 2, 29.0 + n % 2)):
                created = (datetime.now(timezone.utc) - timedelta(days=n)).replace(hour=7, minute=trial).isoformat()
                db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                           (athlete, created, json.dumps({**METRICS, "test": "readiness", "jump_height_cm": height})))
    for height, warnings in ((25.0, []), (24.0, []), (40.0, ["uncertain_timing"])):
        body = {**METRICS, "test": "readiness", "jump_height_cm": height, "warnings": warnings}
        assert client.post("/api/jumps", json=body, headers=headers).status_code == 201
    jump = factors(client.get(f"/api/athletes/{athlete}/readiness?days=1", headers=headers).json())["jump"]
    # daily bests 31, 30, 31, ... (8 days): mean 30.5; within-morning differences are all -1, so TE = 0
    assert jump["value"] == 25 and jump["trials"] == 2 and jump["excluded"] == 1
    assert jump["baseline"]["mean"] == 30.5 and jump["typical_error"] == 0 and jump["state"] == "worse"


def test_sessions_per_day_with_types_idempotent_and_scoped(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    body = {"day": day(), "kind": "gym", "minutes": 60, "rpe": 7, "client_id": "a1"}
    first = client.post("/api/training-sessions", json=body, headers=headers)
    assert first.status_code == 201 and first.json()["load"] == 420
    assert client.post("/api/training-sessions", json=body, headers=headers).status_code == 200
    assert client.post("/api/training-sessions", headers=headers,
                       json={"day": day(), "kind": "match", "minutes": 90, "rpe": 8}).status_code == 201
    for bad in ({"kind": "nap"}, {"day": day(-31)}, {"day": day(2)}, {"minutes": 601}, {"extra": 1}):
        assert client.post("/api/training-sessions", json={**body, "client_id": None, **bad}, headers=headers).status_code == 422
    rows = client.get("/api/training-sessions", headers=headers).json()
    assert sorted(row["kind"] for row in rows) == ["gym", "match"]
    load = client.get(f"/api/athletes/{athlete}/load?days=7", headers=headers).json()
    assert load["daily"][-1] == {"day": day(), "load": 420 + 720, "sessions": 2}
    assert load["acute_chronic"]["ready"] is False and load["acute_chronic"]["days"] == 1
    assert load["acute_load"] == {"days": 7, "load": 1140}
    assert load["chronic_load"] == {"days": 28, "weekly_load": None}  # needs 28 days
    assert client.get(f"/api/training-sessions?athlete_id={athlete}", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{athlete}/load", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{athlete}/readiness", headers=auth("athlete1")).status_code == 403
    assert client.get(f"/api/athletes/{athlete}/readiness", headers=auth("coach")).status_code == 200
    assert client.delete(f"/api/training-sessions/{first.json()['id']}", headers=auth("athlete1")).status_code == 404
    assert client.delete(f"/api/training-sessions/{first.json()['id']}", headers=headers).status_code == 204
    assert {"training_session_logged", "training_session_deleted"} <= set(audit_actions(client, auth))
    _, no_consent = make_athlete(client, auth, email="noconsent@demo.test", consent=False)
    assert client.post("/api/training-sessions", json=body, headers=no_consent).status_code == 403


def test_checkin_load_becomes_a_session_and_is_edited_through_the_checkin(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    put_checkin(client, headers, 0, training_minutes=50, rpe=6)
    rows = client.get("/api/training-sessions", headers=headers).json()
    assert [(row["source"], row["load"]) for row in rows] == [("checkin", 300)]
    assert client.delete(f"/api/training-sessions/{rows[0]['id']}", headers=headers).status_code == 409
    put_checkin(client, headers, 0, training_minutes=40, rpe=5)
    assert [row["load"] for row in client.get("/api/training-sessions", headers=headers).json()] == [200]
    put_checkin(client, headers, 0)
    assert client.get("/api/training-sessions", headers=headers).json() == []


def test_load_ratio_monotony_and_strain_over_28_days(api, auth):
    client, path = api
    athlete, headers = make_athlete(client, auth)
    with main.database(path) as db:
        for n in range(1, 28):
            db.execute("INSERT INTO training_sessions(athlete_id, day, kind, minutes, rpe, source, created_at) "
                       "VALUES (?, ?, 'practice', 60, 5, 'manual', 'x')", (athlete, day(-n)))
    client.post("/api/training-sessions", json={"day": day(), "kind": "match", "minutes": 90, "rpe": 10}, headers=headers)
    load = client.get(f"/api/athletes/{athlete}/load?days=14", headers=headers).json()
    ratio = load["acute_chronic"]
    assert ratio["ready"] and ratio["days"] == 28 and ratio["ratio"] == pytest.approx(450 * 29 / 9900)
    assert ratio["band"] == "above" and len(load["daily"]) == 14 and len(load["weeks"]) == 2
    # Shown first: this week's total and the usual weekly total over 28 days (27 x 300 + 900 = 9000, / 4).
    assert load["acute_load"] == {"days": 7, "load": 2700}
    assert load["chronic_load"] == {"days": 28, "weekly_load": 2250}
    assert "secondary" in load["note"]
    latest = load["weeks"][-1]
    week = [300] * 6 + [900]  # mean 385.71, SD 226.78
    assert latest["load"] == 2700 and latest["monotony"] == pytest.approx(r.monotony(week))
    assert load["weeks"][0]["monotony"] is None  # equal loads every day
    summary = client.get(f"/api/athletes/{athlete}/training?weeks=1", headers=headers).json()
    assert summary["weekly_volume"][-1]["session_load"] > 0


def test_old_checkin_load_is_migrated_once(api):
    _, path = api
    with temporary_directory() as directory:
        old = directory / "old.db"
        with sqlite3.connect(old) as db:
            db.executescript("""
                CREATE TABLE clubs (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
                CREATE TABLE users (id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL, email TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL, role TEXT NOT NULL, password_salt BLOB NOT NULL, password_hash BLOB NOT NULL);
                CREATE TABLE checkins (id INTEGER PRIMARY KEY, athlete_id INTEGER NOT NULL, day TEXT NOT NULL,
                    sleep INTEGER, fatigue INTEGER, soreness INTEGER, stress INTEGER, mood INTEGER,
                    training_minutes INTEGER, rpe INTEGER, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    UNIQUE (athlete_id, day));
                INSERT INTO clubs VALUES (1, 'Old');
                INSERT INTO users VALUES (1, 1, 'old@demo.test', 'Old Athlete', 'athlete', x'00', x'00');
                INSERT INTO checkins VALUES (1, 1, '2026-11-01', 3, 3, 3, 3, 3, 60, 7, 't', 't');
                INSERT INTO checkins VALUES (2, 1, '2026-11-02', 3, 3, 3, 3, 3, NULL, NULL, 't', 't');
            """)
        main.init_db(old)
        main.init_db(old)  # idempotent
        with main.database(old) as db:
            rows = [dict(row) for row in db.execute("SELECT day, kind, minutes, rpe, source FROM training_sessions")]
            columns = {row["name"] for row in db.execute("PRAGMA table_info(checkins)")}
        assert rows == [{"day": "2026-11-01", "kind": "other", "minutes": 60, "rpe": 7, "source": "checkin"}]
        assert {"resting_hr", "hrv_rmssd", "bedtime", "wake_time"} <= columns


def test_board_shows_states_and_nudge_is_limited_to_once_a_day(api, auth):
    client, path = api
    athlete2, athlete7 = user_id(path, "athlete2"), user_id(path, "athlete7")
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (athlete7, day()))
    coach = auth("coach")
    board = client.get("/api/team/board", headers=coach).json()
    rows = {row["athlete_id"]: row for row in board["rows"]}
    assert rows[athlete7]["checked_in"] is False and rows[athlete2]["checked_in"] is True
    assert user_id(path, "athlete3") not in rows  # other club
    assert all(row["status"] in ("good", "normal", "take_care", "still_learning", "no_data") for row in rows.values())
    group = client.get("/api/groups", headers=coach).json()[0]
    filtered = client.get(f"/api/team/board?group_id={group['id']}", headers=coach).json()["rows"]
    assert {row["athlete_id"] for row in filtered} == set(group["member_ids"])
    assert client.get(f"/api/team/board?group_id={group['id']}", headers=auth("coach2")).status_code == 404
    assert client.get("/api/team/board", headers=auth("athlete1")).status_code == 403

    stream = client.app.state.events.try_open(athlete7, 3)
    other = client.app.state.events.try_open(athlete2, 3)
    try:
        sent = client.post("/api/team/nudge", json={"athlete_ids": [athlete7, athlete2]}, headers=coach).json()
        assert sent["sent"] == [athlete7] and sent["checked_in"] == [athlete2]
        event = stream.get_nowait()
        assert event["type"] == "nudge" and event["athlete_id"] == athlete7 and set(event) == {"type", "id", "athlete_id", "club_id", "at"}
        assert other.empty()
        again = client.post("/api/team/nudge", json={"athlete_ids": [athlete7]}, headers=coach).json()
        assert again["sent"] == [] and again["already_nudged"] == [athlete7] and stream.empty()
    finally:
        client.app.state.events.close(athlete7, stream)
        client.app.state.events.close(athlete2, other)
    assert audit_actions(client, auth).count("nudge_sent") == 1
    assert {row["athlete_id"]: row for row in client.get("/api/team/board", headers=coach).json()["rows"]}[athlete7]["nudged"]
    assert client.post("/api/team/nudge", json={"athlete_ids": [athlete7]}, headers=auth("coach2")).status_code == 404
    assert client.post("/api/team/nudge", json={"athlete_ids": [athlete7]}, headers=auth("athlete1")).status_code == 403
    assert client.post("/api/team/nudge", json={"athlete_ids": []}, headers=coach).status_code == 422


def test_digest_lists_facts_and_falls_back_without_a_model(api, auth, monkeypatch):
    client, path = api
    monkeypatch.delenv("JUMPSCAN_LLM_URL", raising=False)
    athlete7 = user_id(path, "athlete7")
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (athlete7, day()))
    digest = client.get("/api/team/digest?phrased=true&language=en", headers=auth("coach")).json()
    assert digest["text"] is None and digest["phrased_by"] is None
    assert {"kind": "missing_checkin", "athlete_id": athlete7, "name": "Мартин Колев", "nudged": False} in digest["facts"]
    assert client.get("/api/team/digest", headers=auth("athlete1")).status_code == 403


def test_digest_phrasing_uses_the_local_model_and_is_cached(api, auth, monkeypatch):
    client, path = api
    calls = []

    class Reply:
        content = b"{}"
        def raise_for_status(self): pass
        def json(self): return {"choices": [{"message": {"content": " Two athletes have not checked in. "}}]}

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
    with main.database(path) as db:
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (user_id(path, "athlete7"), day()))
    first = client.get("/api/team/digest?phrased=true&language=en", headers=auth("coach")).json()
    second = client.get("/api/team/digest?phrased=true&language=en", headers=auth("coach")).json()
    assert first["text"] == "Two athletes have not checked in." == second["text"] and first["phrased_by"] == "local_model"
    assert len(calls) == 1 and "never mention injury" in calls[0]["messages"][0]["content"]


def test_seeded_board_shows_every_state():
    with temporary_directory() as directory:
        path = directory / "seeded.db"
        seed(path, readiness=True)
        with TestClient(main.create_app(path)) as client:
            token = client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "demo1234"}).json()["token"]
            rows = client.get("/api/team/board", headers={"Authorization": "Bearer " + token}).json()["rows"]
            statuses = {row["status"] for row in rows}
            assert {"good", "normal", "take_care", "still_learning"} <= statuses, statuses
            assert any(not row["checked_in"] for row in rows)
            assert any(row["load_ready"] for row in rows) and any(row["jump"]["value"] is not None for row in rows)


def test_external_model_never_receives_a_minors_digest_facts(api, auth, monkeypatch):
    client, path = api
    sent = []

    class Reply:
        content = b"{}"
        def raise_for_status(self): pass
        def json(self): return {"choices": [{"message": {"content": "Summary."}}]}

    class Client:
        def __init__(self, **options): pass
        def __enter__(self): return self
        def __exit__(self, *error): return False
        def post(self, url, headers=None, json=None):
            sent.append(json)
            return Reply()

    monkeypatch.setenv("JUMPSCAN_LLM_URL", "https://integrate.api.nvidia.com/v1")
    monkeypatch.setenv("JUMPSCAN_LLM_MODEL", "cloud")
    monkeypatch.setattr(readiness_api, "httpx", SimpleNamespace(
        Client=Client, Timeout=readiness_api.httpx.Timeout, HTTPError=readiness_api.httpx.HTTPError))
    minor = user_id(path, "athlete7")
    with main.database(path) as db:
        db.execute("UPDATE users SET under_18 = 1 WHERE id = ?", (minor,))
        db.execute("DELETE FROM checkins WHERE athlete_id = ? AND day = ?", (minor, day()))
        db.commit()
    result = client.get("/api/team/digest?phrased=true&language=en", headers=auth("coach")).json()
    assert any(fact["athlete_id"] == minor for fact in result["facts"])  # the coach still sees every fact
    payload = json.dumps(sent)
    assert sent and f'"athlete_id": {minor}' not in payload and f'"athlete_id": {minor},' not in payload
    assert result["phrased_by"] == "external_model"
