"""Personal records and the club leaderboard (SPEC 8)."""

from datetime import datetime, timedelta, timezone
import json
import sqlite3

import pytest

from server import main, records_api
from server.test_form_catalogue import extended_form
from server.test_server import METRICS, api, auth, template, temporary_directory, user_id
from server.test_training_nutrition_api import audit_actions, day, exercise_id, make_athlete


def jump(client, headers, height, warnings=(), flight=None):
    body = {**METRICS, "jump_height_cm": height, "flight_time_ms": flight or height * 10, "warnings": list(warnings)}
    response = client.post("/api/jumps", json=body, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()["id"]


def log_set(client, headers, exercise, reps, load, offset=-1):
    response = client.post("/api/set-logs", headers=headers, json={
        "exercise_id": exercise, "day": day(offset), "set_no": 1, "reps": reps, "load_kg": load, "rpe": None})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def by_measure(records, measure, **match):
    return next(r for r in records["records"] if r["measure"] == measure and all(r[k] == v for k, v in match.items()))


def board(client, headers, **params):
    response = client.get("/api/clubs/me/leaderboard", params=params, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def test_jump_records_exclude_uncertain_results_and_keep_the_previous_best(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    first = jump(client, headers, 30)
    jump(client, headers, 45, ["uncertain_timing"])
    best = jump(client, headers, 32)
    jump(client, headers, 32)  # a tie does not replace the earlier record
    jump(client, headers, 50, ["low_visibility"])
    records = client.get(f"/api/athletes/{athlete}/records", headers=headers).json()
    assert records["excluded_jumps"] == 2
    height = by_measure(records, "jump_height")
    assert (height["value"], height["source_id"], height["unit"], height["new"]) == (32, best, "cm", True)
    assert (height["previous"]["value"], height["previous"]["source_id"]) == (30, first)
    assert by_measure(records, "flight_time")["value"] == 320


def test_set_records_use_epley_up_to_ten_reps(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    squat = exercise_id(client, headers)
    single = log_set(client, headers, squat, 1, 100, -5)
    log_set(client, headers, squat, 10, 80, -4)
    log_set(client, headers, squat, 12, 95, -3)  # 12 reps: no one-rep-max estimate
    heaviest = log_set(client, headers, squat, 3, 100, -2)
    records = client.get(f"/api/athletes/{athlete}/records", headers=headers).json()
    top = by_measure(records, "heaviest_set", exercise_id=squat)
    assert (top["value"], top["reps"], top["source_id"]) == (100, 3, heaviest)
    assert (top["previous"]["reps"], top["previous"]["source_id"]) == (1, single)
    estimate = by_measure(records, "estimated_1rm", exercise_id=squat)
    assert estimate["value"] == pytest.approx(110) and estimate["source_id"] == heaviest
    assert estimate["previous"]["value"] == pytest.approx(80 * (1 + 10 / 30))
    assert records_api.epley(100, 1) == 100 and records_api.epley(60, 10) == pytest.approx(80)


def test_set_without_load_and_only_long_sets_give_no_estimate(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    squat = exercise_id(client, headers)
    log_set(client, headers, squat, 15, 40)
    log_set(client, headers, squat, 8, None)
    records = client.get(f"/api/athletes/{athlete}/records", headers=headers).json()
    assert [r["measure"] for r in records["records"]] == ["heaviest_set"]
    assert records["records"][0]["previous"] is None and records["records"][0]["new"] is False


def test_form_hold_record_counts_validated_holds_only(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    catalogue = {entry["id"]: entry for entry in main.FORM_EXERCISES.values()}
    ids = []
    for exercise, hold in (("plank", 30000), ("plank", 62000), ("wall_sit", 90000)):
        body = extended_form(catalogue[exercise])
        body["summary"].update(hold_ms=hold, in_band_ms=hold // 2)
        response = client.post("/api/form", json=body, headers=headers)
        assert response.status_code == 201, response.text
        ids.append(response.json()["id"])
    holds = [r for r in client.get(f"/api/athletes/{athlete}/records", headers=headers).json()["records"]
             if r["measure"] == "form_hold"]
    assert len(holds) == 1 and holds[0]["exercise"] == "plank" and holds[0]["name_en"] == "Plank"
    assert (holds[0]["value"], holds[0]["source_id"], holds[0]["previous"]["value"]) == (62000, ids[1], 30000)


def test_records_scope(api, auth):
    client, path = api
    athlete1 = user_id(path, "athlete1")
    assert client.get(f"/api/athletes/{athlete1}/records", headers=auth("coach")).status_code == 200
    assert client.get(f"/api/athletes/{athlete1}/records", headers=auth("admin")).status_code == 200
    assert client.get(f"/api/athletes/{athlete1}/records", headers=auth("athlete1")).status_code == 200
    assert client.get(f"/api/athletes/{athlete1}/records", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{athlete1}/records", headers=auth("athlete2")).status_code == 403
    assert client.get(f"/api/athletes/{user_id(path, 'coach')}/records", headers=auth("admin")).status_code == 404
    assert client.get(f"/api/athletes/{athlete1}/records").status_code == 401


def test_record_event_only_when_a_previous_best_is_beaten(api, auth):
    client, _ = api
    events = []
    publish = client.app.state.events.publish
    client.app.state.events.publish = lambda viewers, event: (events.append(event), publish(viewers, event))
    athlete, headers = make_athlete(client, auth)
    jump(client, headers, 30)
    jump(client, headers, 28)
    jump(client, headers, 40, ["low_visibility"])
    assert [e["type"] for e in events] == ["jump", "jump", "jump"]
    beat = jump(client, headers, 33)
    record = events[-1]
    assert record == {"type": "record", "id": beat, "athlete_id": athlete, "club_id": record["club_id"],
                      "at": record["at"]}
    squat = exercise_id(client, headers)
    log_set(client, headers, squat, 5, 60)
    assert events[-1]["type"] == "set"
    log_set(client, headers, squat, 5, 70)
    assert events[-1]["type"] == "record"


def test_leaderboard_shows_opted_in_athletes_with_short_names(api, auth):
    client, path = api
    coach = board(client, auth("coach"))
    assert [row["name"] for row in sorted(coach["rows"], key=lambda r: r["name"])] == ["Александра И.", "Даниел С."]
    assert coach["not_shown"] == 4 and "me" not in coach and coach["unit"] == "cm"
    assert all(set(row) == {"rank", "name", "value", "date", "you"} for row in coach["rows"])
    mine = board(client, auth("athlete1"))
    assert "not_shown" not in mine and [row["you"] for row in mine["rows"]].count(True) == 1
    assert mine["me"] == {"leaderboard_opt_in": True, "leaderboard_allowed": False, "under_18": False, "shown": True}
    other = board(client, auth("athlete2"))
    assert other["me"]["shown"] is False and not any(row["you"] for row in other["rows"])
    assert board(client, auth("coach2"))["rows"] == [] and board(client, auth("coach2"))["not_shown"] == 1

    athlete2 = auth("athlete2")
    assert client.patch("/api/me/profile", json={"leaderboard_opt_in": True}, headers=athlete2).json()["shown"] is True
    assert len(board(client, auth("coach"))["rows"]) == 3
    assert client.patch("/api/me/profile", json={"leaderboard_opt_in": False}, headers=athlete2).status_code == 200
    assert len(board(client, auth("coach"))["rows"]) == 2
    assert audit_actions(client, auth).count("leaderboard_opt_in_changed") == 2
    for bad in ({}, {"leaderboard_opt_in": "yes"}, {"leaderboard_opt_in": True, "extra": 1}):
        assert client.patch("/api/me/profile", json=bad, headers=athlete2).status_code == 422
    assert client.patch("/api/me/profile", json={"leaderboard_opt_in": True}, headers=auth("coach")).status_code == 403
    assert client.get("/api/clubs/me/leaderboard").status_code == 401
    assert client.get("/api/clubs/me/leaderboard?measure=quality", headers=athlete2).status_code == 422
    assert client.get("/api/clubs/me/leaderboard?period=7", headers=athlete2).status_code == 422


def test_withdrawn_consent_or_deactivation_removes_an_athlete(api, auth):
    client, path = api
    athlete1 = user_id(path, "athlete1")
    client.patch(f"/api/users/{athlete1}/consent", headers=auth("coach"),
                 json={"consent_recorded": False, "consent_by": "Parent"})
    assert [row["name"] for row in board(client, auth("coach"))["rows"]] == ["Даниел С."]
    athlete5 = user_id(path, "athlete5")
    assert client.patch(f"/api/users/{athlete5}", json={"active": False}, headers=auth("coach")).status_code == 200
    result = board(client, auth("coach"))
    assert result["rows"] == [] and result["not_shown"] == 5


def test_under_18_needs_the_coach_confirmation(api, auth):
    client, path = api
    athlete7 = user_id(path, "athlete7")
    state = client.patch("/api/me/profile", json={"leaderboard_opt_in": True}, headers=auth("athlete7")).json()
    assert state == {"leaderboard_opt_in": True, "leaderboard_allowed": False, "under_18": True, "shown": False}
    assert "Мартин К." not in [row["name"] for row in board(client, auth("coach"))["rows"]]
    url = f"/api/users/{athlete7}/profile"
    assert client.patch(url, json={"leaderboard_allowed": True}, headers=auth("athlete7")).status_code == 403
    assert client.patch(url, json={"leaderboard_allowed": True}, headers=auth("coach2")).status_code == 404
    assert client.patch(url, json={"leaderboard_allowed": True}, headers=auth("coach")).json() == {
        "id": athlete7, "leaderboard_allowed": True}
    assert "Мартин К." in [row["name"] for row in board(client, auth("coach"))["rows"]]
    assert board(client, auth("athlete7"))["me"]["shown"] is True
    roster = {row["id"]: row for row in client.get("/api/athletes", headers=auth("coach")).json()}
    assert roster[athlete7]["leaderboard_allowed"] is True and roster[athlete7]["shown"] is True
    assert "leaderboard_allowed_changed" in audit_actions(client, auth)
    # The coach's confirmation alone never puts an athlete on the board.
    athlete4 = user_id(path, "athlete4")
    client.patch(f"/api/users/{athlete4}/profile", json={"under_18": True, "leaderboard_allowed": True}, headers=auth("coach"))
    assert "Виктория Г." not in [row["name"] for row in board(client, auth("coach"))["rows"]]


def test_ties_share_a_rank_and_measure_switch(api, auth):
    client, _ = api
    names = []
    for index, name in enumerate(("Ana Petrova", "Boris Kolev", "Vera Ruseva")):
        _, headers = make_athlete(client, auth, email=f"tie{index}@demo.test")
        with main.database(api[1]) as db:
            db.execute("UPDATE users SET name = ? WHERE email = ?", (name, f"tie{index}@demo.test"))
        client.patch("/api/me/profile", json={"leaderboard_opt_in": True}, headers=headers)
        jump(client, headers, 60.04 if index < 2 else 59, flight=700 - index)
        names.append(headers)
    rows = board(client, names[0])["rows"]
    assert [(row["rank"], row["name"], row["value"]) for row in rows[:3]] == [
        (1, "Ana P.", 60.0), (1, "Boris K.", 60.0), (3, "Vera R.", 59)]
    assert rows[0]["you"] and not rows[1]["you"] and rows[3]["rank"] == 4
    flight = board(client, names[0], measure="flight_time")
    assert flight["unit"] == "ms" and [row["value"] for row in flight["rows"][:3]] == [700, 699, 698]


def test_period_and_group_filters(api, auth):
    client, path = api
    athlete5 = user_id(path, "athlete5")
    old = (datetime.now(timezone.utc) - timedelta(days=60)).isoformat()
    with main.database(path) as db:
        db.execute("INSERT INTO jumps(athlete_id, created_at, metrics) VALUES (?, ?, ?)",
                   (athlete5, old, json.dumps({**METRICS, "jump_height_cm": 80})))
    value = {p: next(r["value"] for r in board(client, auth("coach"), period=p)["rows"] if r["name"] == "Даниел С.")
             for p in ("30", "90", "all")}
    assert value["90"] == value["all"] == 80 and value["30"] < 80
    groups = {g["name"]: g["id"] for g in client.get("/api/groups", headers=auth("coach")).json()}
    seniors = board(client, auth("coach"), group_id=groups["Състезатели"])
    assert [row["name"] for row in seniors["rows"]] == ["Даниел С."] and seniors["not_shown"] == 2
    assert board(client, auth("athlete1"), group_id=groups["Юноши"])["rows"][0]["name"] == "Александра И."
    other = client.get("/api/groups", headers=auth("coach2")).json()
    foreign = other[0]["id"] if other else None
    if foreign is None:
        foreign = client.post("/api/groups", json={"name": "Other"}, headers=auth("coach2")).json()["id"]
    assert client.get(f"/api/clubs/me/leaderboard?group_id={foreign}", headers=auth("coach")).status_code == 404
    assert client.get(f"/api/clubs/me/leaderboard?group_id={foreign}", headers=auth("admin")).status_code == 404
    assert client.get("/api/clubs/me/leaderboard?group_id=999999", headers=auth("athlete1")).status_code == 404


def test_display_name():
    assert records_api.display_name("Maria  Ivanova") == "Maria I."
    assert records_api.display_name("Maria Elena Ivanova") == "Maria I."
    assert records_api.display_name("Madonna") == "Madonna"


def test_export_includes_records_and_opt_in(api, auth):
    client, _ = api
    data = client.get("/api/me/export", headers=auth("athlete1")).json()
    assert data["user"]["leaderboard_opt_in"] is True and data["user"]["under_18"] is False
    assert {r["measure"] for r in data["records"]} >= {"jump_height", "flight_time", "heaviest_set"}


def test_migration_adds_opt_in_columns_to_an_existing_database(monkeypatch):
    with temporary_directory() as directory:
        path = directory / "old.db"
        monkeypatch.setattr(records_api, "init_schema", lambda db: None)
        main.init_db(path)
        with main.database(path) as db:
            db.execute("INSERT INTO clubs(name) VALUES ('Old club')")
            db.execute("INSERT INTO users(club_id, email, name, role, password_salt, password_hash, consent_recorded) "
                       "VALUES (1, 'old@demo.test', 'Old Athlete', 'athlete', x'00', x'00', 1)")
            assert "leaderboard_opt_in" not in {row["name"] for row in db.execute("PRAGMA table_info(users)")}
        monkeypatch.undo()
        main.init_db(path)
        main.init_db(path)  # idempotent
        with main.database(path) as db:
            row = db.execute("SELECT * FROM users WHERE email = 'old@demo.test'").fetchone()
            assert (row["name"], row["leaderboard_opt_in"], row["leaderboard_allowed"]) == ("Old Athlete", 0, 0)
            with pytest.raises(sqlite3.IntegrityError):
                db.execute("UPDATE users SET leaderboard_opt_in = 2")
