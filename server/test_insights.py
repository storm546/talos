"""Today findings and the daily briefing (SPEC 10.9)."""

import json
import re
import time
from types import SimpleNamespace

from fastapi.testclient import TestClient

from server import insights, main, readiness_api
from server.seed import seed
from server.test_server import api, auth, template, temporary_directory, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import day, make_athlete

CARDS = {"readiness", "sleep", "nutrition", "weight", "training", "week", "records", "body", "form"}


def check(found):
    for f in found:
        assert set(f) == {"card", "tone", "title", "text"} and f["card"] in CARDS and f["tone"] in ("good", "info", "watch")
        assert f["title"] and f["text"] and "None" not in f["text"] and "nan" not in f["text"]


def test_seeded_athletes_get_findings_in_both_languages():
    with temporary_directory() as directory:
        path = directory / "seeded.db"
        seed(path, readiness=True)
        with TestClient(main.create_app(path)) as client:
            token = client.post("/api/auth/login", json={"email": "athlete1@demo.test", "password": "demo1234"}).json()["token"]
            headers = {"Authorization": "Bearer " + token}
            bg = client.get("/api/me/today", headers=headers).json()["findings"]
            en = client.get("/api/me/today", params={"language": "en"}, headers=headers).json()["findings"]
            check(bg), check(en)
            assert [f["card"] for f in bg] == [f["card"] for f in en]
            assert {"readiness", "nutrition", "training"} <= {f["card"] for f in en}, en
            assert any("," in f["text"] for f in bg)  # Bulgarian decimal comma
            assert not any(re.search(r"\d (kcal|kg|g)\b", f["text"]) for f in bg), bg  # Cyrillic units as in the app
            coach = client.post("/api/auth/login", json={"email": "coach@demo.test", "password": "demo1234"}).json()["token"]
            assert client.get("/api/me/today", headers={"Authorization": "Bearer " + coach}).json()["findings"] == []


def test_fresh_athlete_findings_say_what_is_missing(api, auth):
    client, _ = api
    _, headers = make_athlete(client, auth)
    found = client.get("/api/me/today", params={"language": "en"}, headers=headers).json()["findings"]
    check(found)
    assert {"card": "nutrition", "tone": "info", "title": "Nothing logged today",
            "text": "A photo of the plate or a barcode is the quickest way."} in found
    assert any(f["card"] == "readiness" and f["title"] == "Learning your usual values" for f in found)


def test_rules_for_sleep_weight_and_load():
    t = insights.Text("en")
    good = {"status": "good", "factors": [{"key": "resting_hr", "value": 48.0, "state": "better", "baseline": {"mean": 50.0}}]}
    assert insights.recovery(t, good)["text"].endswith("A good day for a harder session.")
    assert insights.recovery(t, good, {"reason": "tired", "mode": "lighter"})["text"].endswith("follow the lighter plan.")
    short = {"factors": [{"key": "sleep", "sleep": {"hours": 5.9, "need_hours": [7, 9], "midpoint_sd_min": None, "nights": 0}}]}
    assert insights.sleep(t, short)["tone"] == "watch"
    trend = [{"day": f"2026-11-{d:02d}", "trend_kg": 80 - 0.05 * d} for d in range(1, 29)]
    goal = {"goal": {"phase": "lose", "rate_kg_week": 0.5}}
    on_track = insights.weight(t, {"trend": trend}, goal)
    assert on_track["tone"] == "good" and "−0.35 kg a week" in on_track["text"] and "−0.50 kg a week" in on_track["text"]
    assert insights.weight(t, {"trend": trend}, {"goal": {"phase": "gain", "rate_kg_week": 0.25}})["tone"] == "watch"
    spike = {"acute_chronic": {"still_learning": False, "band": "well_above"}, "acute_load": {"load": 3000},
             "chronic_load": {"weekly_load": 2000}}
    assert insights.training(t, spike)["title"] == "Load rising fast (+50 %)"


def test_briefing_phrases_only_findings_and_skips_minors_with_an_external_model(api, auth, monkeypatch):
    client, path = api
    sent = []

    class Reply:
        content = b"{}"
        def raise_for_status(self): pass
        def json(self): return {"choices": [{"message": {"content": "A steady day."}}]}

    class Client:
        def __init__(self, **options): pass
        def __enter__(self): return self
        def __exit__(self, *error): return False
        def post(self, url, headers=None, json=None):
            sent.append(json)
            return Reply()

    uid, headers = make_athlete(client, auth)
    assert client.get("/api/me/briefing", headers=headers).json()["text"] is None  # no model configured
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("JUMPSCAN_LLM_MODEL", "local")
    monkeypatch.setattr(readiness_api, "httpx", SimpleNamespace(
        Client=Client, Timeout=readiness_api.httpx.Timeout, HTTPError=readiness_api.httpx.HTTPError))
    first = client.get("/api/me/briefing", params={"language": "en"}, headers=headers).json()
    second = client.get("/api/me/briefing", params={"language": "en"}, headers=headers).json()
    assert first["text"] == "A steady day." == second["text"] and first["phrased_by"] == "local_model" and len(sent) == 1
    facts = json.loads(sent[0]["messages"][1]["content"])
    assert set(facts) == {"findings", "plan_today"} and facts["findings"] == [
        {k: f[k] for k in ("card", "tone", "title", "text")} for f in first["findings"]]
    assert "never mention injury" in sent[0]["messages"][0]["content"]
    # New findings (a food log): the previous text comes back at once, marked stale, while a new one is written.
    food = client.get("/api/foods", params={"q": "банан"}, headers=headers).json()
    assert food and client.post("/api/food-logs", headers=headers, json={"day": day(), "meal": "snack", "food_id": food[0]["id"],
                                                                         "grams": 120, "client_id": "f1"}).status_code == 201
    stale = client.get("/api/me/briefing", params={"language": "en"}, headers=headers).json()
    assert stale["text"] == "A steady day." and stale["stale"] is True
    for _ in range(50):
        if not insights._pending:
            break
        time.sleep(0.1)
    assert len(sent) == 2 and client.get("/api/me/briefing", params={"language": "en"}, headers=headers).json()["stale"] is False
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "https://integrate.api.nvidia.com/v1")
    with main.database(path) as db:
        db.execute("UPDATE users SET under_18 = 1 WHERE id = ?", (uid,))
        db.commit()
    minor = client.get("/api/me/briefing", params={"language": "bg"}, headers=headers).json()
    assert minor["text"] is None and minor["findings"] and len(sent) == 2
