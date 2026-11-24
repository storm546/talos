"""Field-test measurements of the wave-3 tools (SPEC 9.3): validation, scope, idempotency, records, export, migration."""

import pytest

from server import main, measurements_api
from server.test_server import api, auth, template, temporary_directory, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import audit_actions, make_athlete

ROM = {"kind": "rom", "joint": "knee_flexion", "view": "side", "left_deg": 131.5, "right_deg": None,
       "error_deg": 10, "quality": 0.86, "warnings": []}
BALANCE = {"kind": "balance", "leg": "L", "stance_ms": 12_400, "sway_pct": 3.2, "ended_by": "touch_down",
           "fps": 15, "quality": 0.9}
SPRINT = {"kind": "sprint", "distance_m": 20, "time_ms": 3480, "start": "cue", "precision_ms": 33.3, "fps": 30}
REACTION = {"kind": "reaction", "time_ms": 240, "precision_ms": 33.3, "fps": 30}
AGILITY = {"kind": "agility", "test": "505", "time_ms": 2610, "precision_ms": 16.7, "fps": 60}
CARDIO = {"kind": "cardio", "activity": "run", "distance_m": 5120.4, "duration_s": 1650, "avg_pace_s_per_km": 322.2,
          "splits_s": [318.0, 321.5, 330.1, 325.0, 322.9]}
ALL = (ROM, BALANCE, SPRINT, REACTION, AGILITY, CARDIO)


def post(client, headers, body, client_id, status=201):
    response = client.post("/api/measurements", json={**body, "client_id": client_id}, headers=headers)
    assert response.status_code == status, response.text
    return response.json()


def test_every_kind_round_trips_newest_first(api, auth):
    client, _ = api
    athlete_id, athlete = make_athlete(client, auth)
    ids = [post(client, athlete, body, f"m-{n}")["id"] for n, body in enumerate(ALL)]
    rows = client.get("/api/measurements", headers=athlete).json()
    assert [row["id"] for row in rows] == ids[::-1]
    for row, body in zip(rows[::-1], ALL):
        assert {key: row[key] for key in body} == body
        assert row["athlete_id"] == athlete_id and row["client_id"].startswith("m-")
    only = client.get("/api/measurements", params={"kind": "balance"}, headers=athlete).json()
    assert [row["kind"] for row in only] == ["balance"]
    assert len(client.get("/api/measurements", params={"limit": 2, "offset": 1}, headers=athlete).json()) == 2


@pytest.mark.parametrize("body, field, value", [
    (ROM, "joint", "elbow_flexion"), (ROM, "left_deg", 181), (ROM, "warnings", ["wrong_view"]), (ROM, "extra", 1),
    (BALANCE, "stance_ms", 60_001), (BALANCE, "ended_by", "fell"), (BALANCE, "stance_ms", 12.5), (BALANCE, "leg", "both"),
    (BALANCE, "sway_velocity_pct", -0.1), (BALANCE, "sway_velocity_pct", 1000.1), (BALANCE, "noise_floor_pct", "low"),
    (BALANCE, "noise_floor_pct", 1001),
    (SPRINT, "time_ms", 99), (SPRINT, "distance_m", 0), (SPRINT, "start", "gun"),
    (REACTION, "time_ms", 1501), (REACTION, "fps", 0),
    (AGILITY, "test", "illinois"), (AGILITY, "precision_ms", -1),
    (CARDIO, "avg_pace_s_per_km", 30), (CARDIO, "splits_s", [0]), (CARDIO, "duration_s", 0), (CARDIO, "activity", "swim"),
    (CARDIO, "kind", "steps"),
])
def test_validation_rejects_bad_fields(api, auth, body, field, value):
    client, _ = api
    response = client.post("/api/measurements", json={**body, field: value, "client_id": "x"}, headers=auth("athlete1"))
    assert response.status_code == 422, response.text


def test_balance_sway_velocity_and_noise_floor_are_optional(api, auth):
    client, _ = api
    headers = auth("athlete1")
    with_speed = post(client, headers, {**BALANCE, "sway_velocity_pct": 7.5, "noise_floor_pct": 2.25}, "v1")
    without = post(client, headers, BALANCE, "v2")  # older apps send neither field
    nulls = post(client, headers, {**BALANCE, "sway_velocity_pct": None, "noise_floor_pct": None}, "v3")
    rows = {row["id"]: row for row in client.get("/api/measurements", params={"kind": "balance"}, headers=headers).json()}
    assert (rows[with_speed["id"]]["sway_velocity_pct"], rows[with_speed["id"]]["noise_floor_pct"]) == (7.5, 2.25)
    assert rows[without["id"]]["sway_velocity_pct"] is None and rows[nulls["id"]]["noise_floor_pct"] is None


def test_rom_needs_one_side_and_cycling_allows_fast_pace(api, auth):
    client, _ = api
    athlete = auth("athlete1")
    post(client, athlete, {**ROM, "left_deg": None}, "none", status=422)
    post(client, athlete, {**CARDIO, "activity": "cycle", "avg_pace_s_per_km": 95.0}, "bike")
    post(client, athlete, {**CARDIO, "distance_m": 4, "avg_pace_s_per_km": None, "splits_s": []}, "short")
    assert client.post("/api/measurements", json={**ROM}, headers=athlete).status_code == 422  # client_id required


def test_roles_scope_and_cross_club_404(api, auth):
    client, path = api
    post(client, auth("athlete1"), BALANCE, "a1")
    post(client, auth("athlete3"), BALANCE, "a3")  # second club
    athlete_id, other = user_id(path, "athlete1"), user_id(path, "athlete3")
    for staff in ("coach", "admin"):
        assert client.post("/api/measurements", json={**BALANCE, "client_id": "s"}, headers=auth(staff)).status_code == 403
    coach_rows = client.get("/api/measurements", headers=auth("coach")).json()
    assert athlete_id in {row["athlete_id"] for row in coach_rows} and other not in {row["athlete_id"] for row in coach_rows}
    assert client.get("/api/measurements", params={"athlete_id": other}, headers=auth("coach")).status_code == 404
    assert client.get("/api/measurements", params={"athlete_id": athlete_id}, headers=auth("coach2")).status_code == 404
    assert client.get("/api/measurements", params={"athlete_id": other}, headers=auth("athlete1")).status_code == 403
    assert {row["athlete_id"] for row in client.get("/api/measurements", headers=auth("admin")).json()} >= {athlete_id, other}
    assert client.get(f"/api/athletes/{other}/records", headers=auth("coach")).status_code == 404
    assert client.get("/api/measurements").status_code == 401


def test_consent_idempotency_audit_and_events(api, auth, monkeypatch):
    client, path = api
    athlete, headers = make_athlete(client, auth, consent=False)
    post(client, headers, SPRINT, "same", status=403)
    assert client.patch(f"/api/users/{athlete}/consent", headers=auth("coach"),
                        json={"consent_recorded": True, "consent_by": "Parent"}).status_code == 200
    published = []
    monkeypatch.setattr(client.app.state.events, "publish", lambda viewers, event: published.append((viewers, event)))
    first = post(client, headers, SPRINT, "same")
    retry = client.post("/api/measurements", json={**SPRINT, "time_ms": 3000, "client_id": "same"}, headers=headers)
    assert retry.status_code == 200 and retry.json() == first
    assert [row["time_ms"] for row in client.get("/api/measurements", headers=headers).json()] == [3480]
    assert audit_actions(client, auth).count("measurement_logged") == 1
    assert [event["type"] for _, event in published] == ["measurement"]
    assert published[0][1]["id"] == first["id"] and user_id(path, "coach") in published[0][0]
    # A faster time at the same distance replaces the record and publishes a record event.
    published.clear()
    post(client, headers, {**SPRINT, "time_ms": 3300}, "faster")
    assert [event["type"] for _, event in published] == ["measurement", "record"]


def by(records, measure, **match):
    return next(r for r in records if r["measure"] == measure and all(r[k] == v for k, v in match.items()))


def test_records_longest_stance_per_leg_and_fastest_time_per_distance_and_test(api, auth):
    client, _ = api
    athlete, headers = make_athlete(client, auth)
    first = post(client, headers, BALANCE, "b1")["id"]
    best = post(client, headers, {**BALANCE, "stance_ms": 20_000}, "b2")["id"]
    post(client, headers, {**BALANCE, "stance_ms": 20_000}, "b3")  # a tie keeps the earlier record
    right = post(client, headers, {**BALANCE, "leg": "R", "stance_ms": 8000}, "b4")["id"]
    slow = post(client, headers, SPRINT, "s1")["id"]
    fast = post(client, headers, {**SPRINT, "time_ms": 3300}, "s2")["id"]
    post(client, headers, {**SPRINT, "time_ms": 3400}, "s3")
    ten = post(client, headers, {**SPRINT, "distance_m": 10, "time_ms": 2100}, "s4")["id"]
    flying = post(client, headers, {**SPRINT, "start": "crossing", "time_ms": 2900}, "s5")["id"]
    t_test = post(client, headers, {**AGILITY, "test": "t_test", "time_ms": 10_500}, "t1")["id"]
    post(client, headers, REACTION, "r1")
    records = client.get(f"/api/athletes/{athlete}/records", headers=auth("coach")).json()["records"]
    left = by(records, "balance_stance", leg="L")
    assert (left["value"], left["source_id"], left["unit"], left["source"]) == (20_000, best, "ms", "measurement")
    assert (left["previous"]["value"], left["previous"]["source_id"]) == (12_400, first)
    assert (by(records, "balance_stance", leg="R")["source_id"], by(records, "balance_stance", leg="R")["previous"]) == (right, None)
    twenty = by(records, "sprint_time", distance_m=20, start="cue")
    assert (twenty["value"], twenty["source_id"], twenty["previous"]["source_id"]) == (3300, fast, slow)
    assert by(records, "sprint_time", distance_m=10)["source_id"] == ten
    # a line start leaves out reaction time, so it is a separate record and never beats a beep start
    assert (by(records, "sprint_time", distance_m=20, start="crossing")["source_id"], twenty["source_id"]) == (flying, fast)
    assert by(records, "agility_time", test="t_test")["source_id"] == t_test
    assert not any(r["measure"].startswith("reaction") for r in records)


def test_export_and_delete(api, auth):
    client, _ = api
    _, athlete = make_athlete(client, auth)
    _, other = make_athlete(client, auth, email="second@demo.test")
    for headers in (athlete, other):
        post(client, headers, CARDIO, "c1")
    exported = client.get("/api/me/export", headers=athlete).json()
    assert len(exported["measurements"]) == 1 and exported["measurements"][0]["splits_s"] == CARDIO["splits_s"]
    assert "route" not in exported["measurements"][0]
    assert client.delete("/api/me/data", headers=athlete).status_code == 204
    assert client.get("/api/measurements", headers=athlete).json() == []
    assert len(client.get("/api/measurements", headers=other).json()) == 1


def test_migration_creates_the_table_on_an_existing_database(monkeypatch):
    with temporary_directory() as directory:
        path = directory / "old.db"
        monkeypatch.setattr(measurements_api, "init_schema", lambda db: None)
        main.init_db(path)
        with main.database(path) as db:
            db.execute("INSERT INTO clubs(name) VALUES ('Old club')")
            assert db.execute("SELECT name FROM sqlite_master WHERE name = 'measurements'").fetchone() is None
        monkeypatch.undo()
        main.init_db(path)
        main.init_db(path)  # idempotent
        with main.database(path) as db:
            columns = [row["name"] for row in db.execute("PRAGMA table_info(measurements)")]
            assert columns == ["id", "athlete_id", "created_at", "kind", "data", "client_id"]
            assert db.execute("SELECT name FROM clubs").fetchone()["name"] == "Old club"
