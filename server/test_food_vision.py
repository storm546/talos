"""Food photos, pantry recipes and nearby stores (SPEC 10.6); every model and Overpass call is mocked."""

import base64
import json
import logging
from time import time
from types import SimpleNamespace

import httpx
import pytest

from server import food_vision_api as fv, main
from server.test_server import api, auth, template, user_id

JPEG = b"\xff\xd8\xff\xe0" + b"PRIVATE-PIXELS-" * 400 + b"\xff\xd9"
IMAGE = base64.b64encode(JPEG).decode()
MARKER = base64.b64encode(b"PRIVATE-PIXELS-" * 3).decode()[:24]


@pytest.fixture
def fixture_mode(monkeypatch):
    monkeypatch.setenv("JUMPSCAN_FOOD_FIXTURE", "1")


@pytest.fixture
def model(monkeypatch):
    """A fake OpenAI-style endpoint; `replies` is a list of content strings or status codes, `seen` the requests."""
    monkeypatch.delenv("JUMPSCAN_FOOD_FIXTURE", raising=False)
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "http://127.0.0.1:1/v1")
    monkeypatch.delenv("JUMPSCAN_LLM_KEY_FILE", raising=False)
    monkeypatch.delenv("JUMPSCAN_LLM_FALLBACK", raising=False)
    state = SimpleNamespace(replies=[], seen=[])

    def handler(request):
        state.seen.append(json.loads(request.content))
        reply = state.replies.pop(0)
        if isinstance(reply, int):
            return httpx.Response(reply, json={})
        return httpx.Response(200, json={"choices": [{"message": {"reasoning_content": "thinking", "content": reply}}]})

    real = httpx.Client
    monkeypatch.setattr(fv, "httpx", SimpleNamespace(
        Timeout=httpx.Timeout, TransportError=httpx.TransportError,
        Client=lambda **kw: real(transport=httpx.MockTransport(handler), **kw)))
    return state


def photo(client, headers, path="/api/me/nutrition/photo", **extra):
    return client.post(path, headers=headers, json={"image_jpeg_base64": IMAGE, **extra})


def catalogue(client, headers, name):
    return client.get("/api/foods", params={"q": name, "limit": 1}, headers=headers).json()[0]


def test_meal_photo_numbers_come_from_the_catalogue_and_nothing_is_saved(api, auth, fixture_mode):
    client, path = api
    with main.database(path) as db:
        logs = db.execute("SELECT COUNT(*) FROM food_logs").fetchone()[0]
    response = photo(client, auth("athlete1"), meal="lunch")
    assert response.status_code == 200, response.text
    body = response.json()
    items = {item["label"]: item for item in body["items"]}
    rice = items["rice white cooked"]
    food = catalogue(client, auth("athlete1"), "rice white cooked")
    assert (rice["food_id"], rice["catalog_id"]) == (food["id"], None if food["id"] else food["catalog_id"])
    assert rice["kcal"] == round(food["kcal"] * 180 / 100, 1) and rice["grams"] == 180
    sauce = items["zzz house sauce"]
    assert sauce["food_id"] is None and sauce["catalog_id"] is None and sauce["kcal"] is None
    matched = [item for item in body["items"] if item["kcal"] is not None]
    assert len(matched) == 3 and body["totals"]["kcal"] == pytest.approx(sum(i["kcal"] for i in matched), abs=0.2)
    with main.database(path) as db:
        assert db.execute("SELECT COUNT(*) FROM food_logs").fetchone()[0] == logs


def test_no_image_is_persisted_logged_or_audited(api, auth, model, caplog):
    client, path = api
    caplog.set_level(logging.DEBUG)
    model.replies = ['<think>a plate</think>```json\n{"foods":[{"name":"banana raw","name_bg":"банан","grams":120,'
                     '"confidence":0.9}]}\n```',
                     '{"ingredients":[{"name":"eggs","name_bg":"яйца"},{"name":"eggs","name_bg":"яйца"}]}']
    assert photo(client, auth("athlete1")).json()["items"][0]["kcal"] > 0
    pantry = photo(client, auth("athlete1"), "/api/me/nutrition/pantry").json()
    assert [item["label"] for item in pantry["ingredients"]] == ["eggs"] and pantry["ingredients"][0]["name_en"]
    # The image went to the model as an OpenAI image part ...
    part = model.seen[0]["messages"][1]["content"][1]
    assert part == {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + IMAGE}}
    assert model.seen[0]["max_tokens"] >= 3000
    # ... and nowhere on the server: database (with its WAL), files next to it, logs, audit.
    with main.database(path) as db:
        dump = "\n".join(db.iterdump())
        audit = db.execute("SELECT action, target, detail FROM audit WHERE action = 'food_photo_analysed'").fetchall()
    assert MARKER not in dump
    for file in path.parent.iterdir():
        assert MARKER.encode() not in file.read_bytes() and b"PRIVATE-PIXELS" not in file.read_bytes()
    assert MARKER not in caplog.text and "PRIVATE" not in caplog.text
    assert [(row["action"], row["detail"]) for row in audit] == [("food_photo_analysed", "{}")] * 2


def test_minors_cannot_send_photos_or_ingredients_to_an_external_model(api, auth, model, monkeypatch):
    client, path = api
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "https://integrate.api.nvidia.com/v1")
    with main.database(path) as db:
        db.execute("UPDATE users SET under_18 = 1 WHERE id = ?", (user_id(path, "athlete1"),))
        db.commit()
    for route in ("/api/me/nutrition/photo", "/api/me/nutrition/pantry"):
        response = photo(client, auth("athlete1"), route)
        assert response.status_code == 403 and "under 18" in response.json()["detail"]
    ideas = client.post("/api/me/nutrition/recipe-ideas", headers=auth("athlete1"), json={"ingredients": ["eggs"]})
    assert ideas.status_code == 403 and model.seen == []
    # A model on the club's own network is allowed.
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "http://192.168.0.5:8080/v1")
    model.replies = ['{"foods":[]}']
    assert photo(client, auth("athlete1")).json()["items"] == []


def test_image_validation_and_body_limit(api, auth, fixture_mode):
    client, _ = api
    headers = auth("athlete1")
    big = base64.b64encode(b"\xff\xd8\xff" + bytes(fv.IMAGE_BYTES)).decode()
    assert client.post("/api/me/nutrition/photo", headers=headers, json={"image_jpeg_base64": big}).status_code in (413, 422)
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n" + bytes(100)).decode()
    assert client.post("/api/me/nutrition/photo", headers=headers, json={"image_jpeg_base64": png}).status_code == 422
    response = client.post("/api/me/nutrition/pantry", headers=headers, json={"image_jpeg_base64": "not base64!"})
    assert response.status_code == 422 and "not base64" not in response.text
    assert client.post("/api/me/nutrition/photo", headers=headers, json={"image_jpeg_base64": IMAGE, "x": 1}).status_code == 422
    # Other routes keep the small body limit.
    assert client.post("/api/me/nutrition/recipe-ideas", headers=headers,
                       json={"ingredients": ["x" * 50] * 30, "pad": "y" * 20000}).status_code == 413
    assert client.post("/api/me/nutrition/photo", json={"image_jpeg_base64": IMAGE}).status_code == 401


def test_model_failures_and_the_shared_rate_limit(api, auth, model):
    client, path = api
    model.replies = [503]
    assert photo(client, auth("athlete1")).status_code == 503
    model.replies = ["no json here"]
    response = photo(client, auth("athlete1"))
    assert response.status_code == 503 and IMAGE[:40] not in response.text
    with main.database(path) as db:
        db.executemany("INSERT INTO assistant_requests VALUES (?, ?)", [(user_id(path, "athlete1"), time())] * 20)
        db.commit()
    assert photo(client, auth("athlete1")).status_code == 429
    assert client.post("/api/me/nutrition/recipe-ideas", headers=auth("athlete1"),
                       json={"ingredients": ["eggs"]}).status_code == 429


def test_recipe_ideas_compute_nutrition_and_name_nearby_stores(api, auth, fixture_mode):
    client, _ = api
    headers = auth("athlete1")
    stores = [{"name": "Kaufland Сердика", "brand": "Kaufland", "distance_m": 1240},
              {"name": "Lidl", "brand": "Lidl", "distance_m": 400}, {"name": "Lidl 2", "brand": "Lidl", "distance_m": 900}]
    response = client.post("/api/me/nutrition/recipe-ideas", headers=headers, json={
        "ingredients": ["eggs", "tomatoes raw", "cheese feta", "yogurt plain", "spinach raw"],
        "goal": "high_protein", "servings": 2, "stores": stores})
    assert response.status_code == 200, response.text
    recipes = response.json()["recipes"]
    assert len(recipes) == 3
    omelette = recipes[0]
    assert omelette["uses_from_pantry"] == ["яйца", "спанак", "сирене"] and omelette["missing"] == ["зехтин"]
    eggs = catalogue(client, headers, "eggs")
    assert omelette["ingredients"][0]["name_en"] == eggs["name_en"]
    expected = sum(catalogue(client, headers, item["name"])["kcal"] * item["grams"] / 100
                   for item in omelette["ingredients"] if item["name_en"]) / 2
    assert omelette["per_serving"]["kcal"] == pytest.approx(expected, abs=0.2)
    assert omelette["stores_line"] == "Можете да ги намерите в: Lidl (400\u00a0м), Kaufland (1,2\u00a0км)"
    assert omelette["steps"] and omelette["minutes"] == 15
    plain = client.post("/api/me/nutrition/recipe-ideas", headers=headers,
                        json={"ingredients": ["eggs"], "language": "en", "stores": stores[1:2]}).json()["recipes"]
    assert plain[0]["stores_line"] == "You may find them at: Lidl (400\u00a0m)"
    assert client.post("/api/me/nutrition/recipe-ideas", headers=headers,
                       json={"ingredients": ["eggs"]}).json()["recipes"][0]["stores_line"] is None
    for bad in ({"ingredients": []}, {"ingredients": ["eggs"], "servings": 0}, {"ingredients": ["eggs"], "goal": "keto"}):
        assert client.post("/api/me/nutrition/recipe-ideas", headers=headers, json=bad).status_code == 422


def test_nearby_stores_round_the_position_and_cache_per_cell(api, auth, monkeypatch):
    client, _ = api
    calls = []

    def overpass(lat, lon):
        calls.append((lat, lon))
        return fv.FIXTURE_STORES + [{"type": "node", "lat": 42.80, "lon": 23.32, "tags": {"shop": "supermarket", "name": "Far"}},
                                    {"type": "node", "lat": 42.70, "lon": 23.32, "tags": {"shop": "supermarket"}}]

    monkeypatch.setattr(fv, "overpass", overpass)
    response = client.post("/api/stores/nearby", headers=auth("athlete1"), json={"lat": 42.70321, "lon": 23.32187})
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["lat"], body["lon"]) == (42.7, 23.32) and "OpenStreetMap" in body["attribution"]
    assert [s["name"] for s in body["stores"]] == ["Плод-зеленчук", "Lidl", "Kaufland"]
    assert body["stores"][0]["kind"] == "greengrocer" and body["stores"][1]["brand"] == "Lidl"
    assert [s["distance_m"] for s in body["stores"]] == sorted(s["distance_m"] for s in body["stores"])
    client.post("/api/stores/nearby", headers=auth("athlete2"), json={"lat": 42.6990, "lon": 23.3160})
    assert calls == [(42.7, 23.32)]
    assert client.post("/api/stores/nearby", headers=auth("athlete1"), json={"lat": 91.0, "lon": 0.0}).status_code == 422
    assert client.get("/api/stores/nearby?lat=42.7&lon=23.32", headers=auth("athlete1")).status_code == 405


def test_overpass_failure_is_a_503(monkeypatch):
    real = httpx.Client
    monkeypatch.setattr(fv, "httpx", SimpleNamespace(Timeout=httpx.Timeout, Client=lambda **kw: real(
        transport=httpx.MockTransport(lambda request: httpx.Response(504)), **kw)))
    with pytest.raises(fv.HTTPException) as error:
        fv.overpass(42.7, 23.32)
    assert error.value.status_code == 503


def test_json_reply_ignores_reasoning_and_fences():
    assert fv.json_reply('<think>{"no": 1}</think>\n```json\n{"foods": []}\n```') == {"foods": []}
    with pytest.raises(ValueError):
        fv.json_reply("[]")
