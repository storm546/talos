"""Food search in both languages with a typo fallback (SPEC 10.4)."""

import csv
import time

from server import food_sources as fs
from server.test_server import api, auth, template
from server.test_training_nutrition_api import foods


def test_bulgarian_catalogue_names_are_data_with_their_source():
    text = fs.CATALOG_BG.read_text(encoding="utf-8")
    assert "Talos project" in text and "USDA FoodData Central" in text
    names = fs.bulgarian_names()
    assert len(names) >= 150 and len(set(names.values())) == len(names)
    with fs.LOCAL_FOODS.open(encoding="utf-8") as handle:
        local = list(csv.DictReader(line for line in handle if not line.startswith("#")))
    assert not {fdc for _, fdc in names} & {int(row["fdc_id"]) for row in local}  # no food named twice
    for (source, fdc_id), name in list(names.items())[:20]:
        food = fs.catalog_get(f"{source}:{fdc_id}")
        assert food["name_bg"] == name and food["name_en"] != name


def test_edit_distance_and_trigram_similarity():
    assert fs.edit_distance("мялко", "мляко", 2) == 1  # a swap of neighbouring letters is one edit
    assert fs.edit_distance("bananna", "banana", 2) == 1
    assert fs.edit_distance("баница", "патица", 1) == 2  # above the limit: limit + 1
    assert fs.edit_distance("abc", "abcdef", 1) == 2
    assert fs.trigram_similarity("banana", "banana") == 1
    assert 0 < fs.trigram_similarity("brocoli", "broccoli") < 1


def test_correct_only_changes_unknown_words():
    assert fs.correct("кисело мялко") == "кисело мляко"
    assert fs.correct("chiken breast") == "chicken breast"
    assert fs.correct("banana") is None  # known word
    assert fs.correct("баница") is None  # two edits from "патица": no guess for short words
    assert fs.correct("xyz") is None  # under four letters


def test_catalogue_search_in_bulgarian_and_english():
    apple = fs.catalog_search("ябълка", 5)
    assert apple and apple[0]["name_bg"] == "Ябълка" and apple[0]["name_en"] == "Apple, raw"
    assert fs.catalog_search("apple raw", 5)[0]["name_en"].startswith("Apple")


def test_route_finds_bulgarian_typo_and_word_order(api, auth):
    client, _ = api
    headers = auth("athlete1")
    assert foods(client, headers, "домат")[0]["name_bg"] == "Домати"  # the food named by the query comes first
    assert foods(client, headers, "olive oil")[0]["name_en"].startswith("Oil, olive")  # words in any order
    assert any(f["name_bg"].startswith("Кисело мляко") for f in foods(client, headers, "кисело мялко")[:3])
    assert foods(client, headers, "bananna")[0]["name_en"].startswith("Bananas")
    catalogue = next(f for f in foods(client, headers, "краставица") if f["catalog_id"])
    assert catalogue["name_bg"].startswith("Краставица") and catalogue["id"] is None
    assert foods(client, headers, "qqqqzzzz") == []
    page = client.get("/api/foods", params={"q": "мляко", "limit": 2, "offset": 1}, headers=headers).json()
    assert len(page) == 2 and page[0] == foods(client, headers, "мляко")[1]


def test_typo_fallback_is_fast_enough():
    fs.vocabulary()  # built once per process
    started = time.monotonic()
    for query in ("кисело мялко", "bananna", "spagetti", "avacado"):
        fs.correct(query)
    assert time.monotonic() - started < 2.0
