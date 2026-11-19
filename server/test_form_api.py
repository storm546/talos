"""Form uploads, scoped history, and athlete data rights."""

from copy import deepcopy
import csv
import io
import json

import pytest

from server import main
from server.test_server import api, auth, template, user_id


FORM = {
    "exercise": "squat", "side": None, "view": "front", "fps": 30, "quality": 0.95, "warnings": [],
    "repetitions": [{
        "index": 1, "status": "complete", "reasons": [], "start_ms": 0, "bottom_ms": 500, "end_ms": 900,
        "depth": 100, "knee_flexion_peak_l": 96, "knee_flexion_peak_r": 104, "fppa_l": -3, "fppa_r": 6,
        "flexion_asymmetry_pct": 7.69, "descent_time_ms": 500, "ascent_time_ms": 400, "hip_below_knee": None,
    }],
    "summary": {
        "repetition_count": 1, "partial_count": 0, "unclear_count": 0, "median_depth": 100,
        "median_knee_flexion_peak_l": 96, "median_knee_flexion_peak_r": 104, "median_fppa_l": -3,
        "median_fppa_r": 6, "median_flexion_asymmetry_pct": 7.69, "median_descent_time_ms": 500,
        "median_ascent_time_ms": 400, "hip_below_knee_count": None, "worst_repetition": 1,
    },
}


def test_form_roles_and_paged_history(api, auth, monkeypatch):
    client, path = api
    athlete = auth("athlete1")
    assert client.post("/api/form", json=FORM).status_code == 401
    assert client.get("/api/form").status_code == 401
    for account in ("coach", "admin"):
        assert client.post("/api/form", json=FORM, headers=auth(account)).status_code == 403

    monkeypatch.setattr(main, "now_iso", lambda: "2026-11-18T12:00:00+00:00")
    receipts = []
    for account in ("athlete1", "athlete1", "athlete2", "athlete3"):
        response = client.post("/api/form", json=FORM, headers=auth(account))
        assert response.status_code == 201, response.text
        receipts.append(response.json())
    athlete_id = user_id(path, "athlete1")
    other_club_id = user_id(path, "athlete3")
    own_rows = [{**FORM, **receipt, "athlete_id": athlete_id, "client_id": None}
                for receipt in reversed(receipts[:2])]
    assert client.get("/api/form", headers=athlete).json() == own_rows
    assert client.get("/api/form?limit=1&offset=1", headers=athlete).json() == own_rows[1:]
    assert client.get(f"/api/form?athlete_id={athlete_id}", headers=auth("coach")).json() == own_rows
    assert client.get(f"/api/form?athlete_id={other_club_id}", headers=athlete).status_code == 403
    assert client.get(f"/api/form?athlete_id={other_club_id}", headers=auth("coach")).status_code == 404
    assert client.get(f"/api/form?athlete_id={athlete_id}", headers=auth("coach2")).status_code == 404
    for account, expected in (("coach", receipts[:3]), ("coach2", receipts[3:]), ("admin", receipts)):
        response = client.get("/api/form", headers=auth(account))
        assert response.status_code == 200
        assert [row["id"] for row in response.json()] == [item["id"] for item in reversed(expected)]
    assert client.get(f"/api/form?athlete_id={other_club_id}", headers=auth("admin")).json()[0]["id"] == receipts[-1]["id"]


@pytest.mark.parametrize("section, field, value", [
    (None, "exercise", "unknown"), (None, "exercise", "single_leg_squat"), (None, "side", "L"),
    (None, "view", "auto"), (None, "fps", 481), (None, "fps", 0), (None, "fps", "30"),
    (None, "quality", 1.01), (None, "video", "unused"),
    (None, "warnings", ["low_visibility"] * 4), (None, "warnings", ["unknown"]),
    (None, "repetitions", FORM["repetitions"] * 101),
    ("repetitions", "landmarks", []), ("repetitions", "fppa_l", -91), ("repetitions", "depth", 181),
    ("repetitions", "start_ms", -1), ("repetitions", "end_ms", 900.0), ("repetitions", "ascent_time_ms", 399),
    ("repetitions", "reasons", ["tracking_gap"] * 6), ("repetitions", "status", "unknown"),
    ("summary", "median_ascent_time_ms", -1), ("summary", "median_flexion_asymmetry_pct", 101),
    ("summary", "repetition_count", 2), ("summary", "worst_repetition", 2), ("summary", "extra", 0),
])
def test_form_validation(api, auth, section, field, value):
    client, _ = api
    body = deepcopy(FORM)
    target = body["repetitions"][0] if section == "repetitions" else body[section] if section else body
    target[field] = value
    response = client.post("/api/form", json=body, headers=auth("athlete1"))
    assert response.status_code == 422, response.text


def test_form_accepts_single_leg_partial_and_unclear(api, auth):
    client, _ = api
    body = {**FORM, "exercise": "single_leg_squat", "side": "L", "view": "side",
            "warnings": ["low_visibility", "unclear_reps", "single_leg_view"]}
    partial = {**FORM["repetitions"][0], "status": "partial", "reasons": ["below_min_depth"], "depth": 40,
               "knee_flexion_peak_l": 40, "knee_flexion_peak_r": None, "fppa_l": None, "fppa_r": None,
               "flexion_asymmetry_pct": None, "hip_below_knee": False}
    unclear = dict.fromkeys(partial, None)
    unclear.update(index=2, status="unclear", reasons=["tracking_gap", "tracking_gap"], start_ms=900, end_ms=950)
    body["repetitions"] = [partial, unclear]
    body["summary"] = dict.fromkeys(FORM["summary"], None)
    body["summary"].update(repetition_count=0, partial_count=1, unclear_count=1, hip_below_knee_count=0)
    headers = auth("athlete1")
    missing_side = {key: value for key, value in body.items() if key != "side"}
    assert client.post("/api/form", json=missing_side, headers=headers).status_code == 422
    response = client.post("/api/form", json=body, headers=headers)
    assert response.status_code == 201, response.text
    assert {key: value for key, value in client.get("/api/form", headers=headers).json()[0].items()
            if key in body} == body


def test_form_accepts_maximum_repetitions(api, auth):
    client, _ = api
    body = {**FORM, "summary": {**FORM["summary"], "repetition_count": 100}, "repetitions": [
        {**FORM["repetitions"][0], "index": index + 1, "start_ms": index * 900,
         "bottom_ms": index * 900 + 500, "end_ms": index * 900 + 900} for index in range(100)
    ]}
    headers = auth("athlete1")
    assert len(json.dumps(body)) > 16 * 1024
    response = client.post("/api/form", json=body, headers=headers)
    assert response.status_code == 201, response.text
    assert len(client.get("/api/form", headers=headers).json()[0]["repetitions"]) == 100
    response = client.post("/api/form", json={**body, "video": "x" * (64 * 1024)}, headers=headers)
    assert response.status_code == 413


def test_form_consent_idempotency_audit_and_event(api, auth, monkeypatch):
    client, path = api
    athlete = auth("athlete1")
    athlete_id = user_id(path, "athlete1")
    coach = auth("coach")
    consent_url = f"/api/users/{athlete_id}/consent"

    def consent(active):
        assert client.patch(consent_url, json={"consent_recorded": active, "consent_by": "Test"},
                            headers=coach).status_code == 200

    published = []

    def publish(viewers, event):
        with main.database(path) as db:
            assert db.execute("SELECT id FROM form_sets WHERE id = ?", (event["id"],)).fetchone()[0] == event["id"]
            row = db.execute("SELECT actor_id, target FROM audit WHERE action = 'form_logged' ORDER BY id DESC").fetchone()
            assert dict(row) == {"actor_id": event["athlete_id"], "target": str(event["id"])}
        published.append((viewers, event))

    monkeypatch.setattr(client.app.state.events, "publish", publish)
    body = {**FORM, "client_id": "form-1"}
    consent(False)
    assert client.post("/api/form", json=body, headers=athlete).status_code == 403
    assert not published
    consent(True)
    created = client.post("/api/form", json=body, headers=athlete)
    assert created.status_code == 201, created.text
    retry = client.post("/api/form", json={**body, "quality": 0.9}, headers=athlete)
    assert retry.status_code == 200 and retry.json() == created.json()
    consent(False)
    assert client.post("/api/form", json=body, headers=athlete).status_code == 403
    rows = client.get("/api/form", headers=athlete).json()
    assert len(rows) == 1 and rows[0]["quality"] == FORM["quality"]
    with main.database(path) as db:
        club_id = db.execute("SELECT club_id FROM users WHERE id = ?", (athlete_id,)).fetchone()[0]
        assert db.execute("SELECT COUNT(*) FROM audit WHERE action = 'form_logged'").fetchone()[0] == 1
    assert published == [({athlete_id, user_id(path, "coach"), user_id(path, "admin")}, {
        "type": "form", "id": created.json()["id"], "athlete_id": athlete_id,
        "club_id": club_id, "at": created.json()["created_at"],
    })]
    assert client.post("/api/form", json=body, headers=auth("athlete2")).status_code == 201


def test_form_exports_and_delete(api, auth):
    client, path = api
    athlete = auth("athlete1")
    other_athlete = auth("athlete2")
    body = {**FORM, "client_id": "=test"}
    for headers in (athlete, other_athlete):
        assert client.post("/api/form", json=body, headers=headers).status_code == 201
    export = client.get("/api/me/export", headers=athlete).json()
    assert export["form_sets"] == client.get("/api/form", headers=athlete).json()
    assert len(export["form_sets"]) == 1
    athlete_id = user_id(path, "athlete1")
    url = f"/api/athletes/{athlete_id}/form.csv"
    assert client.get(url, headers=athlete).status_code == 403
    assert client.get(url, headers=auth("coach2")).status_code == 404
    for account in ("coach", "admin"):
        response = client.get(url, headers=auth(account))
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/csv")
        row, = csv.DictReader(io.StringIO(response.text))
        assert row["athlete_id"] == str(athlete_id) and row["client_id"] == "'=test"
        assert row["repetition_count"] == "1" and json.loads(row["repetitions"]) == FORM["repetitions"]
    assert client.delete("/api/me/data", headers=athlete).status_code == 204
    assert client.get("/api/form", headers=athlete).json() == []
    remaining = client.get("/api/me/export", headers=athlete).json()
    assert remaining["form_sets"] == [] and remaining["user"]["consent_recorded"]
    assert len(client.get("/api/form", headers=other_athlete).json()) == 1
