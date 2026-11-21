"""Nutrition follow-ups (SPEC 7.4): scanned-food corrections, nutrient and recipe labels in both languages, copy from
an empty day, the seeded goal series and the in-place migration. No test touches the network."""

import json
import re
from datetime import date

from fastapi.testclient import TestClient
import httpx

from server import main
from server.test_nutrition_v2 import BAR, OFF_PRODUCT, count, goal, mock_http, recipe_body
from server.test_server import api, auth, template, temporary_directory, user_id  # noqa: F401 (fixtures)
from server.test_training_nutrition_api import day

CYRILLIC = re.compile("[Ѐ-ӿ]")


def test_scanned_food_correction_by_its_scanner_until_another_athlete_logs_it(api, auth, monkeypatch):
    client, path = api
    mock_http(monkeypatch, lambda request: httpx.Response(200, json=OFF_PRODUCT))
    a1, a2 = auth("athlete1"), auth("athlete2")
    food = client.get("/api/foods/barcode/5901234123457", headers=a1).json()
    assert food["created_by"] == user_id(path, "athlete1") and food["owner_id"] is None
    fid = food["id"]
    fixed = client.patch(f"/api/foods/{fid}", json={"name_bg": "Овесено блокче", "kcal": 410}, headers=a1)
    assert fixed.status_code == 200 and fixed.json()["kcal"] == 410
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 1}, headers=a2).status_code == 403  # same club, not the scanner
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 1}, headers=auth("athlete3")).status_code == 404  # other club
    assert client.post("/api/food-logs", headers=a1, json={"day": day(), "meal": "snack", "food_id": fid, "grams": 40}).status_code == 201
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 405}, headers=a1).status_code == 200  # own logs do not lock it
    assert client.post("/api/food-logs", headers=a2, json={"day": day(), "meal": "snack", "food_id": fid, "grams": 40}).status_code == 201
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 400}, headers=a1).status_code == 403  # another athlete logged it
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 400}, headers=auth("coach")).json()["kcal"] == 400  # staff always
    assert client.patch(f"/api/foods/{fid}", json={"kcal": 1}, headers=auth("coach2")).status_code == 404
    assert client.delete(f"/api/foods/{fid}", headers=a1).status_code == 404  # deleting stays with staff
    seeded = client.get(f"/api/foods/barcode/{BAR}", headers=a1).json()  # no recorded creator: staff only
    assert client.patch(f"/api/foods/{seeded['id']}", json={"kcal": 1}, headers=a1).status_code == 403


def test_created_by_is_recorded_for_new_foods_and_recipes(api, auth):
    client, path = api
    coach, athlete = auth("coach"), auth("athlete1")
    club = client.post("/api/foods", headers=coach, json={"name_bg": "Клубна", "kcal": 100, "protein_g": 1,
                                                         "carbs_g": 1, "fat_g": 1}).json()
    assert club["created_by"] == user_id(path, "coach")
    recipe = client.post("/api/recipes", json=recipe_body(client, athlete)[0], headers=athlete).json()
    assert count(path, "SELECT created_by FROM foods WHERE id = ?", recipe["food_id"]) == user_id(path, "athlete1")


def test_nutrient_definitions_carry_both_languages(api, auth):
    client, _ = api
    athlete = auth("athlete1")
    definitions = client.get("/api/nutrient-definitions", headers=athlete).json()
    assert len(definitions) == 32
    for d in definitions:
        assert d["name_en"] == d["name"] and not CYRILLIC.search(d["name_en"]) and CYRILLIC.search(d["name_bg"]), d
    assert {d["key"]: d["name_bg"] for d in definitions}["fiber"] == "Фибри"
    summary = client.get(f"/api/athletes/{user_id(api[1], 'athlete1')}/nutrition/summary", headers=athlete).json()
    assert all(n["name_bg"] and n["name_en"] for n in summary["nutrients"])


def test_recipe_servings_are_a_count_and_unit_not_server_text(api, auth):
    client, _ = api
    athlete = auth("athlete1")
    recipe = client.post("/api/recipes", json=recipe_body(client, athlete)[0], headers=athlete).json()
    food = client.get("/api/foods", params={"q": "Овесена каша"}, headers=athlete).json()
    servings = next(f for f in food if f["id"] == recipe["food_id"])["servings"]
    assert servings[1:] == [{"label": "serving", "count": 1, "unit": "serving", "grams": 190.0},
                            {"label": "recipe", "count": 1, "unit": "recipe", "grams": 380.0}]
    seeded = client.get("/api/recipes", headers=athlete).json()
    for r in seeded:
        labels = [s["label"] for s in next(f for f in client.get("/api/foods", params={"q": r["name"]}, headers=athlete).json()
                                           if f["id"] == r["food_id"])["servings"]]
        assert not any(CYRILLIC.search(label) for label in labels), labels
    whole = client.post("/api/food-logs", headers=athlete, json={"day": day(), "meal": "lunch", "food_id": recipe["food_id"],
                                                                 "serving_label": "recipe", "quantity": 1})
    assert whole.status_code == 201 and whole.json()["grams"] == 380 and whole.json()["serving_label"] == "recipe"


def test_copy_from_an_empty_day_is_422_nothing_to_copy(api, auth):
    client, path = api
    athlete, a1 = auth("athlete1"), user_id(path, "athlete1")
    empty = day(-20)
    assert count(path, "SELECT COUNT(*) FROM food_logs WHERE athlete_id = ? AND day = ?", a1, empty) == 0
    before = count(path, "SELECT COUNT(*) FROM food_logs")
    for body in ({"from_day": empty, "to_day": day(), "client_id": "copy-empty"},
                 {"from_day": day(-1), "from_meal": "snack", "to_day": day(), "client_id": "copy-empty-meal"}):
        if "from_meal" in body:  # make the source meal empty
            for row in client.get("/api/food-logs", params={"from": day(-1), "to": day(-1)}, headers=athlete).json():
                if row["meal"] == "snack":
                    assert client.delete(f"/api/food-logs/{row['id']}", headers=athlete).status_code == 204
            before = count(path, "SELECT COUNT(*) FROM food_logs")
        response = client.post("/api/food-logs/copy", json=body, headers=athlete)
        assert response.status_code == 422 and response.json()["code"] == "nothing_to_copy", response.text
        assert response.json()["detail"] == "Nothing to copy from that day"
        assert count(path, "SELECT COUNT(*) FROM food_logs") == before


def test_seeded_coaching_athlete_has_one_series_moving_to_the_goal(api, auth):
    client, path = api
    a6 = user_id(path, "athlete6")
    weights = client.get(f"/api/athletes/{a6}/weights", headers=auth("coach")).json()
    series = [w["kg"] for w in sorted(weights["weights"], key=lambda w: w["day"])]
    assert len(series) >= 40 and max(abs(b - a) for a, b in zip(series, series[1:])) < 1.5  # no second series
    view = client.get(f"/api/athletes/{a6}/nutrition/goal", headers=auth("coach")).json()
    assert view["goal"]["phase"] == "lose" and view["trend_kg_week"] < 0 and view["remaining_kg"] < 0
    assert view["projected_date"] and date.fromisoformat(view["projected_date"]) > date.fromisoformat(day())


def test_coach_sets_the_goal_with_the_same_endpoint_and_audit(api, auth):
    client, path = api
    a1 = user_id(path, "athlete1")
    current = client.get(f"/api/athletes/{a1}/weights", headers=auth("coach")).json()["trend"][-1]["trend_kg"]
    saved = goal(client, auth("coach"), a1, phase="gain", target_weight_kg=round(current + 1, 1), end_date=day(56))
    assert saved.status_code == 200 and saved.json()["goal"]["phase"] == "gain"
    assert client.get(f"/api/athletes/{a1}/nutrition/goal", headers=auth("athlete1")).json()["goal"]["end_date"] == day(56)
    rows = client.get("/api/audit?limit=50", headers=auth("admin")).json()
    assert any(r["action"] == "goal_set" and r["actor_id"] == user_id(path, "coach") for r in rows)


def test_migrates_version_2_foods_and_recipe_labels_in_place(api, auth):
    client, path = api
    athlete = auth("athlete1")
    recipe = client.post("/api/recipes", json=recipe_body(client, athlete)[0], headers=athlete).json()
    log = client.post("/api/food-logs", headers=athlete, json={"day": day(), "meal": "lunch", "food_id": recipe["food_id"],
                                                               "serving_label": "serving", "quantity": 1}).json()
    created = client.post("/api/foods", headers=auth("coach"), json={"name_bg": "Стара клубна", "kcal": 100,
                                                                    "protein_g": 1, "carbs_g": 1, "fat_g": 1}).json()
    old = [{"label": "1 порция", "grams": 190.0}, {"label": "цялата рецепта", "grams": 380.0}]
    with main.database(path) as db:  # back to version 2 data: Bulgarian labels and no recorded creator
        db.execute("UPDATE foods SET servings = ? WHERE id = ?", (json.dumps(old), recipe["food_id"]))
        db.execute("UPDATE food_logs SET serving_label = '1 порция' WHERE id = ?", (log["id"],))
        db.execute("UPDATE foods SET created_by = NULL")
        db.commit()
    for _ in range(2):  # a second start changes nothing
        with TestClient(main.create_app(str(path))):
            pass
        with main.database(path) as db:
            servings = json.loads(db.execute("SELECT servings FROM foods WHERE id = ?", (recipe["food_id"],)).fetchone()[0])
            assert [(s["label"], s["unit"], s["grams"]) for s in servings] == [("serving", "serving", 190.0), ("recipe", "recipe", 380.0)]
            assert db.execute("SELECT serving_label FROM food_logs WHERE id = ?", (log["id"],)).fetchone()[0] == "serving"
            assert db.execute("SELECT created_by FROM foods WHERE id = ?", (created["id"],)).fetchone()[0] == user_id(path, "coach")
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
