"""Nutrition version 2 (SPEC 7.1): catalogue, servings, barcode, micronutrients, diary editing, recipes, suggestions,
goals, analytics and the AI meal parse. Open Food Facts and the model are mocked; no test touches the network."""

import json
import sqlite3
from datetime import timedelta

from fastapi.testclient import TestClient
import httpx
import pytest

from server import food_sources, main
from server.test_server import api, auth, template, temporary_directory, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import audit_actions, day, foods, make_athlete

BAR = "2000000000015"  # the seeded club food with a barcode
REAL_CLIENT = httpx.Client


def mock_http(monkeypatch, handler):
    """Route the server's outgoing httpx calls to `handler`; returns the list of requests seen."""
    seen = []

    def record(request):
        seen.append(request)
        return handler(request)

    monkeypatch.setattr(food_sources.httpx, "Client", lambda **kw: REAL_CLIENT(transport=httpx.MockTransport(record), **kw))
    return seen


def no_network(request):
    raise AssertionError("no outgoing call expected")


def count(path, sql, *args):
    with main.database(path) as db:
        return db.execute(sql, args).fetchone()[0]


def oats(client, headers):
    return foods(client, headers, "Овесени ядки")[0]


@pytest.mark.parametrize("method,path", [
    ("GET", "/api/foods/recent"), ("GET", f"/api/foods/barcode/{BAR}"), ("PATCH", "/api/foods/1"),
    ("GET", "/api/nutrient-definitions"), ("PATCH", "/api/food-logs/1"), ("POST", "/api/food-logs/copy"),
    ("POST", "/api/food-logs/batch"), ("GET", "/api/recipes"), ("POST", "/api/recipes"), ("GET", "/api/recipes/1"),
    ("PUT", "/api/recipes/1"), ("DELETE", "/api/recipes/1"), ("GET", "/api/athletes/1/nutrition/goal"),
    ("PUT", "/api/athletes/1/nutrition/goal"), ("GET", "/api/athletes/1/nutrition/analytics"),
    ("POST", "/api/me/nutrition/ai-parse"),
])
def test_v2_endpoints_require_a_token(api, method, path):
    client, _ = api
    assert client.request(method, path, json={}).status_code == 401


# --- catalogue ---

def test_catalogue_search_follows_local_foods_and_materialises_once(api, auth, monkeypatch):
    client, path = api
    mock_http(monkeypatch, no_network)
    athlete = auth("athlete1")
    found = foods(client, athlete, "apple")
    local = [f for f in found if f["id"] is not None]
    hits = [f for f in found if f["id"] is None]
    assert local and hits and found[:len(local)] == local  # existing foods first
    hit = hits[0]
    assert hit["catalog_id"].startswith(("usda_sr_legacy:", "usda_foundation:", "usda_fndds:"))
    assert hit["kcal"] > 0 and hit["servings"][0] == {"label": "100 g", "grams": 100.0} and len(hit["servings"]) > 1
    assert isinstance(hit["nutrients"], dict) and hit["source_url"].startswith("https://fdc.nal.usda.gov/")
    assert all(f["catalog_id"] is None for f in foods(client, athlete, "овесени"))  # Cyrillic: bundled list only
    assert foods(client, athlete, "%") == [] and all(f["id"] for f in foods(client, athlete, "x"))  # one letter: no catalogue
    # bundled foods are not offered twice from the catalogue
    bundled = {r[0] for r in sqlite3.connect(path).execute("SELECT fdc_id FROM foods WHERE fdc_id IS NOT NULL")}
    assert not any(h["fdc_id"] in bundled for h in hits)

    body = {"day": day(), "meal": "snack", "catalog_id": hit["catalog_id"], "grams": 150}
    first = client.post("/api/food-logs", json=body, headers=athlete)
    second = client.post("/api/food-logs", json=body, headers=auth("athlete3"))  # another club, same system food
    assert first.status_code == second.status_code == 201
    assert first.json()["food_id"] == second.json()["food_id"]
    assert first.json()["kcal"] == round(hit["kcal"] * 1.5, 1)
    assert count(path, "SELECT COUNT(*) FROM foods WHERE catalog_id = ?", hit["catalog_id"]) == 1
    row = [f for f in foods(client, athlete, "apple") if f["catalog_id"] == hit["catalog_id"]]
    assert len(row) == 1 and row[0]["id"] == first.json()["food_id"] and row[0]["club_id"] is None
    assert row[0]["servings"] == hit["servings"] and row[0]["nutrients"] == hit["nutrients"]
    for bad in ({**body, "catalog_id": "usda_sr_legacy:999999999"}, {**body, "catalog_id": "other:1"},
                {**body, "food_id": 1}, {k: v for k, v in body.items() if k != "grams"}):
        assert client.post("/api/food-logs", json=bad, headers=athlete).status_code == 422, bad


# --- servings and custom foods ---

def test_servings_barcode_and_food_correction(api, auth):
    client, path = api
    coach, athlete = auth("coach"), auth("athlete1")
    food = {"name_bg": "Гранола", "kcal": 450, "protein_g": 10, "carbs_g": 60, "fat_g": 18, "brand": "Клуб",
            "barcode": "12345678", "servings": [{"label": "купичка", "grams": 45}]}
    created = client.post("/api/foods", json=food, headers=coach)
    assert created.status_code == 201
    data = created.json()
    assert data["servings"] == [{"label": "100 g", "grams": 100.0}, {"label": "купичка", "grams": 45.0}]
    assert data["barcode"] == "12345678" and data["brand"] == "Клуб" and data["source"] == "custom"
    assert client.post("/api/foods", json=food, headers=coach).status_code == 409  # unique in the club
    assert client.post("/api/foods", json=food, headers=auth("coach2")).status_code == 201  # another club
    for bad in ({**food, "barcode": "1234567"}, {**food, "barcode": "123456789012345"}, {**food, "barcode": "12a45678"},
                {**food, "servings": [{"label": "x", "grams": 0}]}):
        assert client.post("/api/foods", json=bad, headers=coach).status_code == 422
    body = {"day": day(), "meal": "breakfast", "food_id": data["id"], "serving_label": "купичка", "quantity": 2}
    log = client.post("/api/food-logs", json=body, headers=athlete)
    assert log.status_code == 201
    assert log.json()["grams"] == 90 and log.json()["serving_label"] == "купичка" and log.json()["quantity"] == 2
    assert log.json()["kcal"] == 405
    own_grams = client.post("/api/food-logs", json={**body, "grams": 50}, headers=athlete).json()
    assert own_grams["grams"] == 50  # sent grams stay the stored truth
    assert client.post("/api/food-logs", json={**body, "serving_label": "чаша"}, headers=athlete).status_code == 422
    assert client.post("/api/food-logs", json={**body, "quantity": 0}, headers=athlete).status_code == 422
    # correction: club foods by their staff, own foods by the athlete (scanner rule: test_nutrition_followups)
    fid = data["id"]
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 440}, headers=athlete).status_code == 403  # visible, not theirs (7.4)
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 440}, headers=auth("coach2")).status_code == 404
    fixed = client.patch(f"/api/foods/{fid}", json={"kcal": 440, "name_bg": " Гранола с мед "}, headers=coach)
    assert fixed.status_code == 200 and fixed.json()["kcal"] == 440 and fixed.json()["name_bg"] == "Гранола с мед"
    assert fixed.json()["protein_g"] == 10 and fixed.json()["barcode"] == "12345678"
    mine = client.post("/api/foods", json={**food, "barcode": None}, headers=athlete).json()
    assert client.patch(f"/api/foods/{mine['id']}", json={"barcode": "87654321"}, headers=athlete).json()["barcode"] == "87654321"
    for bad in ({"kcal": 1001}, {"name_bg": None}, {"brand": None}, {"extra": 1}):
        assert client.patch(f"/api/foods/{mine['id']}", json=bad, headers=athlete).status_code == 422
    system = oats(client, athlete)["id"]
    assert client.patch(f"/api/foods/{system}", json={"kcal": 1}, headers=auth("admin")).status_code == 404
    assert "food_updated" in audit_actions(client, auth)


# --- barcode lookup ---

OFF_PRODUCT = {"status": 1, "code": "5901234123457", "product": {
    "product_name": "Test oat bar", "brands": "Test brand", "serving_size": "40 g", "nutriments": {
        "energy-kcal_100g": 420, "proteins_100g": 10, "carbohydrates_100g": 60, "fat_100g": 15, "fiber_100g": 6,
        "sugars_100g": 20, "saturated-fat_100g": 3, "salt_100g": 0.5}}}


def test_barcode_local_first_without_network(api, auth, monkeypatch):
    client, path = api
    mock_http(monkeypatch, no_network)
    found = client.get(f"/api/foods/barcode/{BAR}", headers=auth("athlete1"))
    assert found.status_code == 200 and found.json()["name_bg"] == "Протеинов бар (демо)"
    assert found.json()["servings"][1] == {"label": "1 бр. (50 g)", "grams": 50}
    assert client.get(f"/api/foods/barcode/{BAR}", headers=auth("coach")).status_code == 200
    for bad in ("1234567", "123456789012345", "12345abc"):
        assert client.get(f"/api/foods/barcode/{bad}", headers=auth("athlete1")).status_code == 422


def test_barcode_from_open_food_facts_saved_once_as_club_food(api, auth, monkeypatch):
    client, path = api
    seen = mock_http(monkeypatch, lambda request: httpx.Response(200, json=OFF_PRODUCT))
    code = "5901234123457"
    created = client.get(f"/api/foods/barcode/{code}", headers=auth("athlete1"))
    assert created.status_code == 201, created.text
    food = created.json()
    assert food["source"] == "Open Food Facts" and food["source_url"] == f"https://world.openfoodfacts.org/product/{code}"
    assert food["club_id"] == count(path, "SELECT club_id FROM users WHERE id = ?", user_id(path, "athlete1"))
    assert food["owner_id"] is None and food["barcode"] == code and food["name_bg"] == "Test oat bar"
    assert (food["kcal"], food["protein_g"], food["carbs_g"], food["fat_g"], food["fibre_g"]) == (420, 10, 60, 15, 6)
    assert food["nutrients"] == {"fiber": 6, "sugar": 20, "saturated_fat": 3, "sodium": 200}
    assert food["servings"] == [{"label": "100 g", "grams": 100.0}, {"label": "40 g", "grams": 40.0}]
    request = seen[0]
    assert request.url.path == f"/api/v2/product/{code}.json" and request.url.host == "world.openfoodfacts.org"
    assert request.headers["user-agent"] == "Talos/1.3 (school project)" and "nutriments" in request.url.params["fields"]
    assert client.get(f"/api/foods/barcode/{code}", headers=auth("athlete2")).status_code == 200  # local now
    assert client.get(f"/api/foods/barcode/{code}", headers=auth("coach")).json()["id"] == food["id"]
    assert len(seen) == 1
    other = client.get(f"/api/foods/barcode/{code}", headers=auth("athlete3"))  # club 2 cannot see club 1's food
    assert other.status_code == 201 and other.json()["id"] != food["id"] and len(seen) == 2
    assert "food_scanned" in audit_actions(client, auth)
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.get("/api/foods/barcode/5900000000001", headers=unconsented).status_code == 403


@pytest.mark.parametrize("response", [
    httpx.Response(404, json={"status": 0, "status_verbose": "product not found"}),
    httpx.Response(200, json={"status": 0}),
    httpx.Response(200, json={"status": 1, "product": {"product_name": "x", "nutriments": {"energy-kcal_100g": 100}}}),
])
def test_barcode_not_found_or_incomplete_is_404_with_hint(api, auth, monkeypatch, response):
    client, path = api
    mock_http(monkeypatch, lambda request: response)
    before = count(path, "SELECT COUNT(*) FROM foods")
    missing = client.get("/api/foods/barcode/4000000000001", headers=auth("athlete1"))
    assert missing.status_code == 404 and "Create the food" in missing.json()["detail"]
    assert count(path, "SELECT COUNT(*) FROM foods") == before


@pytest.mark.parametrize("failure", [httpx.ConnectError("down"), httpx.ReadTimeout("slow"), 500])
def test_barcode_network_failure_is_503_and_saves_nothing(api, auth, monkeypatch, failure):
    client, path = api

    def handler(request):
        if isinstance(failure, Exception):
            raise failure
        return httpx.Response(failure, text="error")

    mock_http(monkeypatch, handler)
    before = count(path, "SELECT COUNT(*) FROM foods")
    assert client.get("/api/foods/barcode/4000000000002", headers=auth("athlete1")).status_code == 503
    assert count(path, "SELECT COUNT(*) FROM foods") == before



def test_barcode_lookup_retries_once_when_open_food_facts_stalls(api, auth, monkeypatch):
    client, _ = api
    attempts = []

    def handler(request):
        attempts.append(request)
        if len(attempts) == 1:
            raise httpx.ReadTimeout("stalled")
        return httpx.Response(200, json=OFF_PRODUCT)

    mock_http(monkeypatch, handler)
    response = client.get("/api/foods/barcode/5901234123457", headers=auth("athlete1"))
    assert response.status_code == 201 and len(attempts) == 2

# --- micronutrients ---

def test_nutrient_definitions_and_daily_totals_report_missing_as_missing(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    definitions = client.get("/api/nutrient-definitions", headers=athlete).json()
    assert len(definitions) == 32 and {"key", "name", "unit", "reference"} <= set(definitions[0])
    assert all(d["reference"] is None for d in definitions)
    assert client.post("/api/food-logs", json={"day": day(), "meal": "snack", "label": "Кафе", "kcal": 30},
                       headers=athlete).status_code == 201
    summary = client.get(f"/api/athletes/{a1}/nutrition/summary", params={"day": day()}, headers=athlete).json()
    nutrients = {n["key"]: n for n in summary["nutrients"]}
    assert set(nutrients) == {d["key"] for d in definitions}
    total = nutrients["fiber"]["total_entries"]
    assert total == count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ?", a1, day())
    assert nutrients["fiber"]["amount"] > 0 and 0 < nutrients["fiber"]["known_entries"] < total  # quick add unknown
    assert nutrients["sodium"]["known_entries"] < total  # the seeded bar has no sodium value
    assert all((n["amount"] is None) == (n["known_entries"] == 0) for n in nutrients.values())
    empty = client.get(f"/api/athletes/{a1}/nutrition/summary", params={"day": day(-20)}, headers=athlete).json()
    assert all(n["amount"] is None and n["total_entries"] == 0 for n in empty["nutrients"])


# --- diary editing ---

def test_quick_add_counts_in_totals_and_estimate(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    before = client.get(f"/api/athletes/{a1}/nutrition/summary", params={"day": day(-1)}, headers=athlete).json()
    quick = client.post("/api/food-logs", headers=athlete, json={
        "day": day(-1), "meal": "lunch", "label": "Мусака в стола", "kcal": 650, "protein_g": 30, "client_id": "q-1"})
    assert quick.status_code == 201
    row = quick.json()
    assert row["food_id"] is None and row["grams"] is None and row["name_bg"] == "Мусака в стола"
    assert (row["kcal"], row["protein_g"], row["carbs_g"], row["fat_g"]) == (650, 30, 0, 0)
    assert client.post("/api/food-logs", headers=athlete, json={
        "day": day(-1), "meal": "lunch", "label": "x", "kcal": 1, "client_id": "q-1"}).json() == row
    after = client.get(f"/api/athletes/{a1}/nutrition/summary", params={"day": day(-1)}, headers=athlete).json()
    assert after["totals"]["kcal"] == round(before["totals"]["kcal"] + 650, 1)
    for bad in ({"label": "x"}, {"kcal": 10}, {"label": "x", "kcal": 10, "food_id": 1},
                {"label": "x", "kcal": 10, "grams": 100}, {"label": "x", "kcal": -1}, {"label": "", "kcal": 10}):
        assert client.post("/api/food-logs", json={"day": day(), "meal": "lunch", **bad}, headers=athlete).status_code == 422
    csv_text = client.get(f"/api/athletes/{a1}/nutrition.csv", headers=auth("coach")).text
    assert "Мусака в стола" in csv_text
    assert any(m["label"] == "Мусака в стола" for m in client.get("/api/me/export", headers=athlete).json()["food_logs"])


def test_food_log_patch_scope_servings_and_quick_values(api, auth):
    client, path = api
    athlete = auth("athlete1")
    bar = client.get(f"/api/foods/barcode/{BAR}", headers=athlete).json()
    log = client.post("/api/food-logs", json={"day": day(), "meal": "snack", "food_id": bar["id"], "grams": 30},
                      headers=athlete).json()
    url = f"/api/food-logs/{log['id']}"
    served = client.patch(url, json={"serving_label": "1 бр. (50 g)", "quantity": 2}, headers=athlete)
    assert served.status_code == 200 and served.json()["grams"] == 100 and served.json()["kcal"] == 390
    again = client.patch(url, json={"quantity": 1}, headers=athlete).json()
    assert again["grams"] == 50 and again["serving_label"] == "1 бр. (50 g)"
    moved = client.patch(url, json={"grams": 75, "meal": "breakfast", "day": day(-1)}, headers=athlete).json()
    assert (moved["grams"], moved["serving_label"], moved["quantity"], moved["meal"], moved["day"]) == (75, None, None, "breakfast", day(-1))
    assert client.patch(url, json={"grams": 10}, headers=auth("athlete2")).status_code == 404
    assert client.patch(url, json={"grams": 10}, headers=auth("coach")).status_code == 403
    for bad in ({"day": day(-31)}, {"day": day(2)}, {"meal": "brunch"}, {"meal": None}, {"grams": 0}, {"grams": None},
                {"serving_label": "чаша"}, {"kcal": 100}, {"label": "x"}, {"extra": 1}, {"quantity": 1}):
        assert client.patch(url, json=bad, headers=athlete).status_code == 422, bad  # last: no serving left to scale
    quick = client.post("/api/food-logs", json={"day": day(), "meal": "snack", "label": "Сок", "kcal": 120},
                        headers=athlete).json()
    edited = client.patch(f"/api/food-logs/{quick['id']}", json={"kcal": 90, "label": "Фреш"}, headers=athlete).json()
    assert edited["kcal"] == 90 and edited["name_bg"] == "Фреш"
    for bad in ({"grams": 100}, {"serving_label": "100 g"}, {"kcal": None}):
        assert client.patch(f"/api/food-logs/{quick['id']}", json=bad, headers=athlete).status_code == 422
    assert client.patch("/api/food-logs/999999", json={"grams": 1}, headers=athlete).status_code == 404


def test_food_log_copy_day_and_meal_idempotent(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    source = day(-1)
    n_source = count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ?", a1, source)
    n_breakfast = count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ? AND meal = 'breakfast'", a1, source)
    queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    body = {"from_day": source, "to_day": day(1), "client_id": "copy-1"}
    copied = client.post("/api/food-logs/copy", json=body, headers=athlete)
    assert copied.status_code == 201 and len(copied.json()) == n_source
    assert {r["day"] for r in copied.json()} == {day(1)} and queue.get_nowait()["type"] == "food"
    replay = client.post("/api/food-logs/copy", json=body, headers=athlete)
    assert replay.status_code == 200 and replay.json() == copied.json() and queue.empty()
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ?", a1, day(1)) == n_source
    meal = client.post("/api/food-logs/copy", headers=athlete, json={
        "from_day": source, "from_meal": "breakfast", "to_day": day(), "to_meal": "snack", "client_id": "copy-2"})
    assert meal.status_code == 201 and len(meal.json()) == n_breakfast and {r["meal"] for r in meal.json()} == {"snack"}
    original = client.get("/api/food-logs", params={"from": source, "to": source}, headers=athlete).json()
    assert sorted(r["kcal"] for r in copied.json()) == sorted(r["kcal"] for r in original)
    without_id = client.post("/api/food-logs/copy", json={"from_day": source, "to_day": day()}, headers=athlete)
    assert without_id.status_code == 201
    fresh = {**body, "client_id": "copy-3"}  # a replayed client_id would return the first copy before validation
    for bad in ({**fresh, "client_id": "a:b"}, {**fresh, "to_day": day(2)}, {**fresh, "from_day": day(-25)},
                {"from_day": source}, {**fresh, "to_meal": "brunch"}):
        assert client.post("/api/food-logs/copy", json=bad, headers=athlete).status_code == 422, bad
    assert client.post("/api/food-logs/copy", json=body, headers=auth("coach")).status_code == 403
    client.app.state.events.close(user_id(path, "coach"), queue)
    assert "food_logs_copied" in audit_actions(client, auth)


def test_food_log_batch_is_all_or_nothing(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    oat = oats(client, athlete)["id"]
    entries = [{"day": day(), "meal": "breakfast", "food_id": oat, "grams": 60, "client_id": "b-1"},
               {"day": day(), "meal": "breakfast", "label": "Чай с мед", "kcal": 60, "carbs_g": 15}]
    before = count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1)
    bad = client.post("/api/food-logs/batch", headers=athlete, json={"entries": entries + [
        {"day": day(), "meal": "lunch", "food_id": 999999, "grams": 10}]})
    assert bad.status_code == 422 and bad.json()["detail"].startswith("Entry 3:")
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1) == before
    assert client.post("/api/food-logs/batch", headers=athlete, json={"entries": entries + [
        {"day": day(-40), "meal": "lunch", "food_id": oat, "grams": 10}]}).status_code == 422
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1) == before
    done = client.post("/api/food-logs/batch", json={"entries": entries}, headers=athlete)
    assert done.status_code == 201 and [r["kcal"] for r in done.json()][1] == 60 and len(done.json()) == 2
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1) == before + 2
    for body in ({"entries": []}, {"entries": [entries[1]] * 51}, {}):
        assert client.post("/api/food-logs/batch", json=body, headers=athlete).status_code == 422
    assert client.post("/api/food-logs/batch", json={"entries": entries}, headers=auth("coach")).status_code == 403
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.post("/api/food-logs/batch", json={"entries": entries}, headers=unconsented).status_code == 403
    assert "food_logs_batch" in audit_actions(client, auth)


# --- recipes ---

def recipe_body(client, headers, **changes):
    milk = next(f for f in foods(client, headers, "milk whole") if f["catalog_id"])
    return {"name": "Овесена каша", "servings_count": 2,
            "items": [{"food_id": oats(client, headers)["id"], "grams": 80}, {"catalog_id": milk["catalog_id"], "grams": 300}],
            **changes}, milk


def test_recipe_values_logging_scope_and_delete(api, auth):
    client, path = api
    athlete = auth("athlete1")
    body, milk = recipe_body(client, athlete)
    created = client.post("/api/recipes", json=body, headers=athlete)
    assert created.status_code == 201, created.text
    recipe = created.json()
    oat = oats(client, athlete)
    expected = (oat["kcal"] * 0.8 + milk["kcal"] * 3) / 2
    assert recipe["total_grams"] == 380 and recipe["serving_grams"] == 190
    assert abs(recipe["per_serving"]["kcal"] - expected) < 0.5 and len(recipe["items"]) == 2
    assert recipe["owner_id"] == user_id(path, "athlete1") and recipe["food_id"]
    log = client.post("/api/food-logs", headers=athlete, json={
        "day": day(), "meal": "breakfast", "food_id": recipe["food_id"], "serving_label": "serving", "quantity": 2})
    assert log.status_code == 201 and log.json()["grams"] == 380 and abs(log.json()["kcal"] - 2 * expected) < 1
    assert recipe["id"] in [r["id"] for r in client.get("/api/recipes", headers=athlete).json()]
    for who in ("athlete2", "coach", "coach2"):
        assert client.get(f"/api/recipes/{recipe['id']}", headers=auth(who)).status_code == 404
        assert recipe["id"] not in [r["id"] for r in client.get("/api/recipes", headers=auth(who)).json()]
        assert client.put(f"/api/recipes/{recipe['id']}", json=body, headers=auth(who)).status_code == 404
    changed = client.put(f"/api/recipes/{recipe['id']}", json={**body, "servings_count": 4, "name": "Каша"}, headers=athlete)
    assert changed.status_code == 200 and changed.json()["serving_grams"] == 95 and changed.json()["name"] == "Каша"
    self_item = {**body, "items": body["items"] + [{"food_id": recipe["food_id"], "grams": 10}]}
    assert client.put(f"/api/recipes/{recipe['id']}", json=self_item, headers=athlete).status_code == 422
    for bad in ({**body, "items": []}, {**body, "servings_count": 0}, {**body, "name": " "}, {**body, "club_id": 1},
                {**body, "items": [{"food_id": 999999, "grams": 10}]}, {**body, "items": [{"grams": 10}]}):
        assert client.post("/api/recipes", json=bad, headers=athlete).status_code == 422, bad
    _, unconsented = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert client.post("/api/recipes", json=body, headers=unconsented).status_code == 403
    # deleting a logged recipe keeps its food for the diary; an unused one takes its food along
    assert client.delete(f"/api/recipes/{recipe['id']}", headers=auth("athlete2")).status_code == 404
    assert client.delete(f"/api/recipes/{recipe['id']}", headers=athlete).status_code == 204
    assert client.get("/api/food-logs", params={"from": day(), "to": day()}, headers=athlete).json()[0]["grams"] == 380
    unused = client.post("/api/recipes", json=body, headers=athlete).json()
    assert client.delete(f"/api/recipes/{unused['id']}", headers=athlete).status_code == 204
    assert count(path, "SELECT COUNT(*) FROM foods WHERE id = ?", unused["food_id"]) == 0
    assert {"recipe_created", "recipe_updated", "recipe_deleted"} <= set(audit_actions(client, auth))


def test_club_recipe_visibility_and_export(api, auth):
    client, path = api
    body, _ = recipe_body(client, auth("coach"))
    club = client.post("/api/recipes", json=body, headers=auth("coach")).json()
    assert club["owner_id"] is None
    assert club["id"] in [r["id"] for r in client.get("/api/recipes", headers=auth("athlete2")).json()]
    assert client.get(f"/api/recipes/{club['id']}", headers=auth("athlete3")).status_code == 404
    assert client.get(f"/api/recipes/{club['id']}", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/recipes/{club['id']}", headers=auth("admin")).status_code == 200
    assert client.put(f"/api/recipes/{club['id']}", json=body, headers=auth("athlete1")).status_code == 404
    seeded = [r for r in client.get("/api/recipes", headers=auth("athlete1")).json() if r["name"] == "Пъстърва на фурна с домати"]
    assert seeded and seeded[0]["servings_count"] == 2
    athlete = auth("athlete1")
    mine = client.post("/api/recipes", json=recipe_body(client, athlete)[0], headers=athlete).json()
    export = client.get("/api/me/export", headers=athlete).json()
    assert [r["id"] for r in export["recipes"]] == [mine["id"]] and len(export["recipes"][0]["items"]) == 2
    assert client.delete("/api/me/data", headers=athlete).status_code == 204
    assert client.get("/api/me/export", headers=athlete).json()["recipes"] == []
    assert client.get(f"/api/recipes/{club['id']}", headers=athlete).status_code == 200  # club data stays


# --- suggestions ---

def test_recent_foods_for_one_tap_logging(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    recent = client.get("/api/foods/recent", headers=athlete).json()
    assert 0 < len(recent) <= 20 and len({f["id"] for f in recent}) == len(recent)
    bar = next(f for f in recent if f["barcode"] == BAR)
    assert bar["last_serving_label"] == "1 бр. (50 g)" and bar["last_grams"] == 50 and bar["uses"] >= 6
    snack = client.get("/api/foods/recent", params={"meal": "snack"}, headers=athlete).json()
    assert {f["name_bg"] for f in snack} == {"Протеинов бар (демо)", "Ябълки", "Орехи"}
    assert client.get("/api/foods/recent", params={"athlete_id": a1}, headers=auth("coach")).json() == recent
    assert client.get("/api/foods/recent", params={"athlete_id": a1}, headers=auth("coach2")).status_code == 404
    assert client.get("/api/foods/recent", params={"athlete_id": a1}, headers=auth("athlete2")).status_code == 403
    assert client.get("/api/foods/recent", params={"meal": "brunch"}, headers=athlete).status_code == 422


# --- goals and phases ---

def goal(client, headers, athlete_id, **body):
    return client.put(f"/api/athletes/{athlete_id}/nutrition/goal", headers=headers,
                      json={"protein_g": 130, "fat_g": 60, **body})


def test_goal_gives_the_rate_and_shows_progress(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    assert client.get(f"/api/athletes/{a1}/nutrition/goal", headers=athlete).json()["goal"] is None
    current = client.get(f"/api/athletes/{a1}/weights", headers=athlete).json()["trend"][-1]["trend_kg"]
    end = (main.datetime.now(main.timezone.utc).date() + timedelta(days=70)).isoformat()
    queue = client.app.state.events.try_open(user_id(path, "coach"), 3)
    saved = goal(client, athlete, a1, phase="lose", target_weight_kg=round(current - 2, 1), end_date=end)
    assert saved.status_code == 200, saved.text
    view = saved.json()
    assert view["goal"]["phase"] == "lose" and abs(view["goal"]["rate_kg_week"] - 0.2) < 0.02
    assert view["goal"]["end_date"] == end and view["current_trend_kg"] == round(current, 2)
    assert abs(view["remaining_kg"] - (round(current - 2, 1) - current)) < 0.01
    assert queue.get_nowait()["type"] == "coaching"
    program = client.get(f"/api/athletes/{a1}/nutrition/program", headers=auth("coach")).json()
    assert program["program"]["goal"] == "lose" and program["program"]["rate_kg_week"] == view["goal"]["rate_kg_week"]
    assert client.get(f"/api/athletes/{a1}/nutrition/goal", headers=auth("coach")).json() == view
    assert client.get(f"/api/athletes/{a1}/nutrition/goal", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{a1}/nutrition/goal", headers=auth("athlete2")).status_code == 403
    assert goal(client, auth("coach2"), a1, phase="maintain").status_code == 404
    soon = (main.datetime.now(main.timezone.utc).date() + timedelta(days=3)).isoformat()
    for bad in ({"phase": "gain", "target_weight_kg": round(current - 2, 1), "end_date": end},
                {"phase": "lose", "target_weight_kg": round(current - 2, 1), "end_date": soon},
                {"phase": "lose", "target_weight_kg": round(current - 30, 1), "end_date": end},
                {"phase": "lose", "target_weight_kg": 60}, {"phase": "maintain", "target_weight_kg": 60, "end_date": end},
                {"phase": "cut"}):
        assert goal(client, athlete, a1, **bad).status_code == 422, bad
    kept = goal(client, athlete, a1, phase="maintain").json()
    assert kept["goal"]["rate_kg_week"] == 0 and kept["goal"]["target_weight_kg"] is None
    client.app.state.events.close(user_id(path, "coach"), queue)
    assert "goal_set" in audit_actions(client, auth)


def test_goal_keeps_the_under_18_guardrail_and_consent(api, auth):
    client, path = api
    a7 = user_id(path, "athlete7")
    current = client.get(f"/api/athletes/{a7}/weights", headers=auth("coach")).json()["trend"][-1]["trend_kg"]
    end = (main.datetime.now(main.timezone.utc).date() + timedelta(days=28)).isoformat()
    own = goal(client, auth("athlete7"), a7, phase="lose", target_weight_kg=round(current - 2, 1), end_date=end)
    assert own.status_code == 200 and own.json()["goal"]["applied"] is False  # a deficit for an under-18 needs the coach
    program = client.get(f"/api/athletes/{a7}/nutrition/program", headers=auth("athlete7")).json()
    assert program["guardrail"] and program["applied"] is False
    uid, headers = make_athlete(client, auth, "nc@demo.test", consent=False)
    assert goal(client, auth("coach"), uid, phase="maintain").status_code == 403


# --- analytics ---

def test_analytics_daily_series_adherence_and_scope(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    for days in (7, 30, 90):
        result = client.get(f"/api/athletes/{a1}/nutrition/analytics", params={"days": days}, headers=athlete).json()
        assert len(result["daily"]) == days and result["daily"][-1]["day"] == day() and result["start"] == day(-(days - 1))
    month = client.get(f"/api/athletes/{a1}/nutrition/analytics", params={"days": 30}, headers=auth("coach")).json()
    logged = [d for d in month["daily"] if d["entries"]]
    assert month["averages"]["logged_days"] == len(logged) == 13
    assert all(d["kcal"] is None for d in month["daily"] if not d["entries"])  # missing, never zero
    assert abs(month["averages"]["kcal"] - sum(d["kcal"] for d in logged) / 13) < 0.1
    assert month["target_kcal"] == 2200 and month["adherence"]["logged_days"] == 13
    assert month["adherence"]["days_within"] == sum(1 for d in logged if abs(d["kcal"] - 2200) <= 220)
    assert month["trend"] and month["weights"] and month["expenditure"] == []
    a6 = user_id(path, "athlete6")
    coached = client.get(f"/api/athletes/{a6}/nutrition/analytics", headers=auth("coach")).json()
    assert coached["expenditure"] and all(e["kcal"] > 0 for e in coached["expenditure"])
    assert client.get(f"/api/athletes/{a1}/nutrition/analytics", params={"days": 14}, headers=athlete).status_code == 422
    assert client.get(f"/api/athletes/{a1}/nutrition/analytics", headers=auth("coach2")).status_code == 404
    assert client.get(f"/api/athletes/{a1}/nutrition/analytics", headers=auth("athlete2")).status_code == 403


# --- AI meal entry ---

def model_reply(foods_list):
    return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({"foods": foods_list})}}]})


def test_ai_parse_proposes_and_logs_nothing_until_batch(api, auth, monkeypatch):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    monkeypatch.delenv("JUMPSCAN_LLM_URL", raising=False)
    monkeypatch.delenv("JUMPSCAN_LLM_MODEL", raising=False)
    assert client.post("/api/me/nutrition/ai-parse", json={"text": "овесени ядки"}, headers=athlete).status_code == 503
    monkeypatch.setenv("JUMPSCAN_LLM_URL", "http://127.0.0.1:1/v1")
    monkeypatch.setenv("JUMPSCAN_LLM_MODEL", "test-model")
    monkeypatch.delenv("JUMPSCAN_LLM_KEY_FILE", raising=False)
    seen = mock_http(monkeypatch, lambda request: model_reply([
        {"name": "oats", "grams": 80, "per100g": {"calories": 380, "protein": 13, "fat": 7, "carbs": 66}},
        {"name": "zzqx home dish", "grams": 200, "per100g": {"calories": 150, "protein": 5, "fat": 5, "carbs": 20}}]))
    before = count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1)
    parsed = client.post("/api/me/nutrition/ai-parse", json={"text": "80 g oats and a bowl of stew", "meal": "lunch"},
                         headers=athlete)
    assert parsed.status_code == 200, parsed.text
    proposal = parsed.json()
    assert seen[0].url.path == "/v1/chat/completions" and json.loads(seen[0].content)["model"] == "test-model"
    first, second = proposal["entries"]
    assert first["food_id"] and first["grams"] == 80 and first["meal"] == "lunch" and first["day"] == day()
    assert second == {"day": day(), "meal": "lunch", "label": "zzqx home dish", "kcal": 300, "protein_g": 10,
                      "carbs_g": 40, "fat_g": 10}
    assert proposal["estimated"] is True and proposal["items"][1]["match"] is None
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1) == before
    logged = client.post("/api/food-logs/batch", json={"entries": proposal["entries"]}, headers=athlete)
    assert logged.status_code == 201 and count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ?", a1) == before + 2
    assert client.post("/api/me/nutrition/ai-parse", json={"text": "oats"}, headers=auth("coach")).status_code == 403
    for bad in ({"text": ""}, {"text": "x" * 1001}, {"text": "oats", "meal": "brunch"}, {"text": "oats", "extra": 1}):
        assert client.post("/api/me/nutrition/ai-parse", json=bad, headers=athlete).status_code == 422
    for reply in (httpx.Response(500), httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}]}),
                  model_reply([{"name": "x", "grams": -1, "per100g": {}}])):
        mock_http(monkeypatch, lambda request, reply=reply: reply)
        assert client.post("/api/me/nutrition/ai-parse", json={"text": "oats"}, headers=athlete).status_code == 503


# --- migration from the 6.3 schema ---

def test_migrates_the_6_3_nutrition_schema_in_place(tmp_path):
    path = tmp_path / "v1.db"
    with sqlite3.connect(path) as db:
        db.executescript('''
            CREATE TABLE clubs(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
            CREATE TABLE users(id INTEGER PRIMARY KEY, club_id INTEGER NOT NULL REFERENCES clubs(id),
                email TEXT NOT NULL UNIQUE COLLATE NOCASE, name TEXT NOT NULL, role TEXT NOT NULL,
                password_salt BLOB NOT NULL, password_hash BLOB NOT NULL, active INTEGER NOT NULL DEFAULT 1,
                consent_recorded INTEGER NOT NULL DEFAULT 0, consent_by TEXT, consent_at TEXT,
                under_18 INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE foods (id INTEGER PRIMARY KEY, fdc_id INTEGER, club_id INTEGER REFERENCES clubs(id) ON DELETE CASCADE,
                owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE, name_bg TEXT NOT NULL, name_en TEXT NOT NULL DEFAULT '',
                kcal REAL NOT NULL, protein_g REAL NOT NULL, carbs_g REAL NOT NULL, fat_g REAL NOT NULL,
                fibre_g REAL NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'custom');
            CREATE UNIQUE INDEX foods_fdc ON foods(fdc_id) WHERE fdc_id IS NOT NULL;
            CREATE TABLE food_logs (id INTEGER PRIMARY KEY, athlete_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                day TEXT NOT NULL, meal TEXT NOT NULL CHECK(meal IN ('breakfast', 'lunch', 'dinner', 'snack')),
                food_id INTEGER NOT NULL REFERENCES foods(id), grams REAL NOT NULL CHECK(grams > 0 AND grams <= 5000),
                client_id TEXT, created_at TEXT NOT NULL);
            CREATE INDEX food_logs_athlete ON food_logs(athlete_id, day);
            CREATE UNIQUE INDEX food_logs_client ON food_logs(athlete_id, client_id) WHERE client_id IS NOT NULL;
            CREATE TABLE coaching_programs (athlete_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                effective_date TEXT NOT NULL, seed_kcal REAL NOT NULL, mode TEXT NOT NULL, goal TEXT NOT NULL,
                rate_kg_week REAL NOT NULL, protein_g REAL NOT NULL, fat_g REAL NOT NULL,
                applied INTEGER NOT NULL DEFAULT 0, set_by INTEGER REFERENCES users(id), updated_at TEXT NOT NULL);
            INSERT INTO clubs VALUES(1, 'Legacy');
            INSERT INTO foods(id, club_id, owner_id, name_bg, kcal, protein_g, carbs_g, fat_g) VALUES (7, 1, 1, 'Стара храна', 200, 10, 20, 5);
        ''')
        salt = b"0123456789abcdef"
        db.execute("INSERT INTO users(id, club_id, email, name, role, password_salt, password_hash, consent_recorded, consent_by) "
                   "VALUES(1, 1, 'old@demo.test', 'Old', 'athlete', ?, ?, 1, 'Parent')", (salt, main.password_hash("old-password", salt)))
        db.execute("INSERT INTO food_logs VALUES (41, 1, ?, 'lunch', 7, 150, 'old-1', 'x')", (day(),))
        db.execute("INSERT INTO coaching_programs VALUES (1, ?, 2400, 'updating', 'maintain', 0, 120, 60, 1, 1, 'x')", (day(-3),))
    for _ in range(2):  # a second start changes nothing
        with TestClient(main.create_app(str(path))) as client:
            token = client.post("/api/auth/login", json={"email": "old@demo.test", "password": "old-password"}).json()["token"]
            headers = {"Authorization": "Bearer " + token}
            logs = client.get("/api/food-logs", headers=headers).json()
            assert logs[-1]["id"] == 41 and logs[-1]["kcal"] == 300 and logs[-1]["name_bg"] == "Стара храна"
            assert client.post("/api/food-logs", json={"day": day(), "meal": "snack", "label": "Бързо", "kcal": 99},
                               headers=headers).status_code == 201
            assert client.post("/api/food-logs", json={"day": day(), "meal": "lunch", "food_id": 7, "grams": 50,
                                                       "client_id": "old-1"}, headers=headers).json()["id"] == 41
            assert client.get("/api/athletes/1/nutrition/goal", headers=headers).json()["goal"]["phase"] == "maintain"
    with main.database(path) as db:
        columns = {r["name"]: r for r in db.execute("PRAGMA table_info(food_logs)")}
        assert columns["food_id"]["notnull"] == 0 and {"serving_label", "quantity", "label", "kcal"} <= set(columns)
        assert db.execute("SELECT COUNT(*) FROM food_logs").fetchone()[0] == 3  # the old row, the two quick adds
        assert {"servings", "brand", "barcode", "source_url", "catalog_id", "nutrients"} <= {
            r["name"] for r in db.execute("PRAGMA table_info(foods)")}
        assert {"target_weight_kg", "end_date"} <= {r["name"] for r in db.execute("PRAGMA table_info(coaching_programs)")}
        indexes = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}
        assert {"food_logs_client", "food_logs_athlete", "foods_catalog", "foods_barcode"} <= indexes
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        assert {"recipes", "recipe_items"} <= tables and "food_logs_v2" not in tables
        assert db.execute("SELECT COUNT(*) FROM foods WHERE fdc_id IS NOT NULL AND servings IS NULL").fetchone()[0] == 0
        assert db.execute("PRAGMA foreign_key_check").fetchall() == []


def test_seeded_goal_progress_for_the_dashboard(api, auth):
    client, path = api
    view = client.get(f"/api/athletes/{user_id(path, 'athlete6')}/nutrition/goal", headers=auth("coach")).json()
    assert view["goal"]["phase"] == "lose" and view["goal"]["target_weight_kg"] and view["goal"]["end_date"]
    assert -3 < view["remaining_kg"] < 0 and view["trend_kg_week"] is not None
