"""Catalogue-backed form uploads and exports."""

from copy import deepcopy
import csv
import io
import json

import pytest

from server import main
from server.test_form_api import FORM
from server.test_server import api, auth, template, user_id


def extended_form(exercise):
    data = deepcopy(FORM)
    data.update(exercise=exercise["id"], mode=exercise["mode"], source="live",
                side="R" if exercise["sided"] else None,
                view="side" if exercise["view"] == "side" and not exercise["signal"].get("legacy") else "front")
    if not exercise["signal"].get("legacy"):
        for key in data["summary"]:
            if key not in ("repetition_count", "partial_count", "unclear_count"):
                data["summary"][key] = None
        for key in data["repetitions"][0]:
            if key not in {"index", "status", "reasons", "start_ms", "bottom_ms", "end_ms", "ascent_time_ms", "descent_time_ms"}:
                data["repetitions"][0][key] = None
    if exercise["mode"] == "hold":
        data["repetitions"] = []
        data["summary"].update(repetition_count=0, hold_ms=4500, in_band_ms=3000)
    else:
        data["repetitions"][0]["checks"] = {check["key"]: None if i else 12.5 for i, check in enumerate(exercise["checks"])}
        if exercise["sided"] and exercise["signal"].get("legacy"):
            data["summary"]["median_flexion_asymmetry_pct"] = None
            data["repetitions"][0]["flexion_asymmetry_pct"] = None
    return data


def test_catalogue_and_every_exercise_upload(api, auth):
    client, path = api
    assert client.get("/api/form/exercises").status_code == 401
    catalogue = json.loads(main.FORM_CATALOGUE_PATH.read_text(encoding="utf-8"))
    expected = [{**{key: row[key] for key in ("id", "name_bg", "name_en", "mode", "sided", "view")},
                 "checks": [{key: check[key] for key in ("key", "label_bg", "label_en", "unit")} for check in row["checks"]]}
                for row in catalogue]
    for role in ("athlete1", "coach", "admin"):
        response = client.get("/api/form/exercises", headers=auth(role))
        assert response.status_code == 200
        assert response.json() == expected
    headers = auth("athlete1")
    for exercise in catalogue:
        response = client.post("/api/form", headers=headers, json=extended_form(exercise))
        assert response.status_code == 201, (exercise["id"], response.text)
    rows = client.get("/api/form", headers=headers).json()
    assert [row["exercise"] for row in reversed(rows)] == [exercise["id"] for exercise in catalogue]
    uid = user_id(path, "athlete1")
    response = client.get(f"/api/athletes/{uid}/form.csv", headers=auth("coach"))
    csv_rows = {row["exercise"]: row for row in csv.DictReader(io.StringIO(response.text))}
    assert csv_rows["plank"]["hold_ms"] == "4500"
    assert csv_rows["plank"]["in_band_ms"] == "3000"
    assert csv_rows["plank"]["source"] == "live"
    assert json.loads(csv_rows["push_up"]["repetitions"])[0]["checks"] == {"body_line": 12.5, "elbow_angle": None}


def test_missing_catalogue_keeps_legacy_uploads_available(api, auth, monkeypatch):
    client, _ = api
    pushup = extended_form(main.FORM_EXERCISES["push_up"])
    monkeypatch.setattr(main, "FORM_EXERCISES", None)
    headers = auth("athlete1")
    assert client.get("/api/form/exercises", headers=headers).status_code == 503
    assert client.post("/api/form", headers=headers, json=pushup).status_code == 503
    assert client.post("/api/form", headers=headers, json=FORM).status_code == 201


@pytest.mark.parametrize("exercise, section, field, value", [
    ("push_up", None, "mode", "hold"), ("push_up", None, "source", "camera"),
    ("push_up", None, "side", "L"), ("lateral_lunge", None, "side", None),
    ("push_up", "rep", "checks", {"torso_tilt": 60}),
    ("push_up", "rep", "checks", {"body_line": "60"}),
    ("push_up", "rep", "checks", {"body_line": {"x": 60}}),
    ("push_up", "rep", "checks", {"body_line": True}),
    ("push_up", "rep", "depth", 20), ("push_up", "summary", "hold_ms", 60),
    ("plank", "summary", "hold_ms", None), ("plank", "summary", "in_band_ms", 5000),
    ("plank", "summary", "hold_ms", -1), ("plank", "summary", "in_band_ms", 1.5),
    ("plank", None, "repetitions", FORM["repetitions"]), ("plank", None, "landmarks", []),
])
def test_strict_catalogue_validation(api, auth, exercise, section, field, value):
    client, _ = api
    body = extended_form(main.FORM_EXERCISES[exercise])
    target = body["repetitions"][0] if section == "rep" else body.get(section, body)
    target[field] = value
    assert client.post("/api/form", headers=auth("athlete1"), json=body).status_code == 422
