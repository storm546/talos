"""Pure maths (progression, volume, energy balance, guardrails), the weight-trend model and the food list."""

from datetime import date, timedelta
import math
from pathlib import Path
import sqlite3
import sys

import pytest

from server import main, training_nutrition as tn
from server.training_nutrition_api import FOODS_CSV, load_foods, read_foods_csv
from server.weight_trend import daily_trend, weight_trend_pipeline

DAY = 86_400_000_000
ORIGINAL = Path("/opt/personal-nutrition/trend_model")


# --- progression ---

def sets(*rows):
    return [{"reps": r, "load_kg": load, "rpe": rpe} for r, load, rpe in rows]


PLANNED = {"sets": 3, "reps": 6, "reps_max": 8, "rpe_target": 8.0}
TOP = sets((8, 100.0, 7.5), (8, 100.0, 8.0), (8, 100.0, 8.0))
MID = sets((7, 100.0, 8.0), (6, 100.0, 8.0), (6, 100.0, 8.0))
LOW = sets((6, 100.0, 9.0), (5, 100.0, 9.0), (5, 100.0, 9.0))


def session(day, rows):
    return {"day": day, "sets": rows}


def test_progression_adds_2_5_kg_after_two_sessions_at_the_top():
    result = tn.progression(PLANNED, [session("2026-11-01", MID), session("2026-11-04", TOP), session("2026-11-08", TOP)])
    assert result["action"] == "increase"
    assert (result["change_kg"], result["from_load_kg"], result["suggested_load_kg"]) == (2.5, 100.0, 102.5)
    assert result["days"] == ["2026-11-04", "2026-11-08"]


def test_progression_lower_body_step_is_five_kg():
    result = tn.progression(PLANNED, [session("a", TOP), session("b", TOP)], step_kg=5.0)
    assert result["suggested_load_kg"] == 105.0


def test_progression_takes_five_percent_off_after_two_sessions_below_the_range():
    result = tn.progression(PLANNED, [session("a", LOW), session("b", LOW)])
    assert (result["action"], result["change_pct"], result["suggested_load_kg"]) == ("decrease", -5, 95.0)
    assert tn.progression(PLANNED, [session("a", sets((4, 62.5, None))), session("b", sets((5, 62.5, None)))]
                          )["suggested_load_kg"] == 59.4  # 62.5 x 0.95 = 59.375


def test_progression_keeps_in_every_other_case():
    assert tn.progression(PLANNED, [session("a", TOP), session("b", MID)])["action"] == "keep"
    assert tn.progression(PLANNED, [session("a", LOW), session("b", MID)])["action"] == "keep"
    assert tn.progression(PLANNED, [session("a", TOP)]) == {"action": "keep", "reason": "need_two_sessions", "days": ["a"]}
    assert tn.progression(PLANNED, [])["action"] == "keep"


def test_progression_top_needs_every_planned_set_at_or_below_the_rpe_target():
    harder = sets((8, 100.0, 8.5), (8, 100.0, 8.0), (8, 100.0, 8.0))
    assert tn.progression(PLANNED, [session("a", TOP), session("b", harder)])["action"] == "keep"
    unlogged = sets((8, 100.0, None), (8, 100.0, 8.0), (8, 100.0, 8.0))
    assert tn.progression(PLANNED, [session("a", TOP), session("b", unlogged)])["action"] == "keep"
    no_target = {**PLANNED, "rpe_target": None}
    assert tn.progression(no_target, [session("a", unlogged), session("b", unlogged)])["action"] == "increase"


def test_progression_top_needs_all_planned_sets_done():
    two = TOP[:2]
    assert tn.progression(PLANNED, [session("a", TOP), session("b", two)])["action"] == "keep"
    extra = TOP + sets((3, 100.0, 9.0))  # a back-off set beyond the plan does not matter
    assert tn.progression(PLANNED, [session("a", extra), session("b", extra)])["action"] == "increase"


def test_progression_bodyweight_has_no_load_to_suggest():
    bare = sets((8, None, 7.0), (8, None, 7.0), (8, None, 7.0))
    result = tn.progression(PLANNED, [session("a", bare), session("b", bare)])
    assert result["action"] == "increase" and result["suggested_load_kg"] is None


# --- weekly volume ---

def test_weekly_volume_hand_computed():
    rows = [("2026-11-10", 5, 100.0), ("2026-11-11", 8, 50.0), ("2026-11-12", 10, None),
            ("2026-11-16", 3, 80.0), ("2026-11-08", 5, 100.0), ("2026-11-30", 5, 100.0)]
    loads = [("2026-11-10", 6, 60), ("2026-11-17", 7, None), ("2026-11-17", 5, 90), ("2026-11-01", 5, 90)]
    assert tn.weekly_volume(rows, loads, 2, "2026-11-18") == [
        {"week_start": "2026-11-09", "sets": 3, "volume_kg": 900.0, "unloaded_sets": 1, "session_load": 360},
        {"week_start": "2026-11-16", "sets": 1, "volume_kg": 240.0, "unloaded_sets": 0, "session_load": 450}]
    assert [w["sets"] for w in tn.weekly_volume([], [], 3, "2026-11-18")] == [0, 0, 0]


def test_week_starts_on_monday():
    assert tn.week_start("2026-11-16") == tn.week_start("2026-11-22") == "2026-11-16"
    assert tn.week_start("2026-11-15") == "2026-11-09"


# --- energy balance ---

END = date(2026, 11, 18)


def days(start, count, step=1):
    return [(date.fromisoformat(start) + timedelta(days=n * step)).isoformat() for n in range(count)]


def test_estimate_hand_computed():
    food = {day: 2000.0 for day in days("2026-11-09", 10)}
    weighed = ["2026-11-05", "2026-11-07", "2026-11-09", "2026-11-11", "2026-11-13", "2026-11-15", "2026-11-17", "2026-11-18"]
    trend = {"2026-11-05": 80.0, "2026-11-18": 79.0}
    result = tn.energy_balance(food, trend, weighed, END)
    assert result["available"] and result["food_days"] == 10 and result["weigh_ins"] == 8
    assert result["trend_days"] == 13 and result["trend_change_kg"] == -1.0 and result["mean_intake_kcal"] == 2000
    assert result["expenditure_kcal"] == pytest.approx(2000 + 7700 / 13)  # 2592.31
    assert (result["window_start"], result["window_end"], result["window_days"]) == ("2026-11-05", "2026-11-18", 14)
    assert result["label"] == "estimate from your own logs"
    gaining = tn.energy_balance(food, {"2026-11-05": 80.0, "2026-11-18": 80.65}, weighed, END)
    assert gaining["expenditure_kcal"] == pytest.approx(2000 - 0.65 * 7700 / 13)  # 1615


def test_estimate_thresholds_are_ten_food_days_and_eight_weigh_ins():
    weighed = days("2026-11-05", 8)
    trend = {day: 80.0 for day in weighed}
    assert tn.energy_balance({d: 2000.0 for d in days("2026-11-09", 10)}, trend, weighed, END)["available"]
    short_food = tn.energy_balance({d: 2000.0 for d in days("2026-11-09", 9)}, trend, weighed, END)
    assert short_food == {**short_food, "available": False, "reason": "not_enough_data", "food_days": 9}
    assert "expenditure_kcal" not in short_food
    short_weights = tn.energy_balance({d: 2000.0 for d in days("2026-11-09", 10)}, trend, weighed[:7], END)
    assert not short_weights["available"] and short_weights["weigh_ins"] == 7
    assert not tn.energy_balance({}, {}, [], END)["available"]


def test_estimate_ignores_data_outside_the_28_day_window():
    old = days("2026-09-01", 20)  # long before the window
    result = tn.energy_balance({d: 2500.0 for d in old}, {d: 80.0 for d in old}, old, END)
    assert not result["available"] and result["food_days"] == 0 and result["weigh_ins"] == 0
    food = {d: 2000.0 for d in days("2026-10-22", 28)}  # exactly 28 days, first day included
    weighed = days("2026-10-22", 28, 3)
    result = tn.energy_balance(food, {d: 80.0 for d in weighed}, weighed, END)
    assert result["window_start"] == "2026-10-22" and result["window_days"] == 28
    assert result["food_days"] == 28 and result["weigh_ins"] == 10


def test_estimate_window_is_never_shorter_than_14_days():
    food = {d: 2000.0 for d in days("2026-11-10", 9)}
    result = tn.energy_balance(food, {}, [], END)
    assert result["window_days"] == 14 and result["window_start"] == "2026-11-05"


# --- guardrails ---

def test_target_guardrails():
    assert tn.check_target(1499, None, False, True)  # floor for everyone, coaches too
    assert tn.check_target(1499, 1400, True, False)
    assert tn.check_target(1500, 1400, False, False) is None
    assert tn.check_target(2000, 2200, False, False) is None  # an adult may choose a deficit
    assert tn.check_target(2000, 2200, True, True) is None  # a coach may set it for an under-18
    assert tn.check_target(2000, 2200, True, False)  # but the athlete may not
    assert tn.check_target(2200, 2200, True, False) is None
    assert tn.check_target(3000, None, True, False)  # no estimate: ask the coach
    assert tn.check_target(3000, None, True, True) is None
    assert tn.protein_range(80) == [112, 160]


# --- weight trend ---

def series(count, drift, flags=()):
    days_used, weights = [], []
    for n in range(count):
        if n % 7 == 4:
            continue  # missed days
        days_used.append(n)
        weights.append(80.0 + drift * n + (0.6 if n % 3 == 0 else -0.4 if n % 5 == 0 else 0.1 * ((n * 7) % 5 - 2)))
    return [(d * DAY + 1_700_000_000 // 86400 * DAY, w, d in flags) for d, w in zip(days_used, weights)]


@pytest.fixture
def original():
    if not ORIGINAL.is_dir():
        pytest.skip("the owner's original model is not on this machine")
    sys.path.insert(0, str(ORIGINAL))
    try:
        from weight_trend_pipeline import weight_trend_pipeline as pipeline
        yield pipeline
    finally:
        sys.path.remove(str(ORIGINAL))
        for name in [n for n in sys.modules if n.startswith("weight_") and not n.startswith("weight_trend")]:
            del sys.modules[name]


@pytest.mark.parametrize("flags", [(), (3, 11, 20)])
def test_trend_is_numerically_identical_to_the_original_model(original, flags):
    observations = series(34, -0.06, flags)
    assert 28 <= len(observations) <= 34
    ours, theirs = weight_trend_pipeline(observations), original(observations)
    assert len(ours) == len(theirs) > 30
    for mine, reference in zip(ours, theirs):
        assert mine["date_us"] == reference["date_us"]
        for key in ("trend_kg", "transient_kg", "sigma_kg"):
            assert abs(mine[key] - reference[key]) < 1e-9, key


def test_trend_follows_the_direction_and_is_smoother_than_the_raw_series():
    for drift in (-0.08, 0.08):
        observations = series(30, drift)
        rows = [((date(1970, 1, 1) + timedelta(days=o[0] // DAY)).isoformat(), o[1]) for o in observations]
        trend = daily_trend(rows)
        values = [t["trend_kg"] for t in trend]
        assert (values[-1] - values[0]) * drift > 0
        assert abs(values[-1] - values[0]) > 0.5 * abs(drift) * 20
        raw_values = [w for _, w in rows]
        assert sum(abs(b - a) for a, b in zip(values, values[1:])) / (len(values) - 1) < \
            sum(abs(b - a) for a, b in zip(raw_values, raw_values[1:])) / (len(raw_values) - 1)


def test_daily_trend_adapter_calendar_and_limits():
    assert daily_trend([]) == [] and daily_trend([("2026-11-01", 80.0)]) == []
    rows = [("2026-11-05", 79.0), ("2026-11-01", 80.0), ("2026-11-03", 79.6)]  # unordered input
    trend = daily_trend(rows)
    assert [t["day"] for t in trend] == ["2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05"]
    assert all(set(t) == {"day", "trend_kg", "transient_kg", "sigma_kg"} and math.isfinite(t["trend_kg"]) for t in trend)
    old = [("2025-01-01", 90.0)] + [(d, 80.0) for d in days("2026-11-01", 5)]  # beyond the 180-day history
    assert daily_trend(old)[0]["day"] == "2026-11-01"
    with pytest.raises(ValueError):
        weight_trend_pipeline([(0, 80.0, False), (0, 81.0, False)])  # dates must increase


def test_no_third_party_product_names_in_shipped_trend_code():
    package = Path(main.__file__).parent / "weight_trend"
    text = "\n".join(p.read_text() for p in package.glob("*.py")).lower()
    for word in ("macrofactor", "libapp", "sha256", "personal-nutrition", "apk"):
        assert word not in text
    assert "reconstructed from measured behaviour" in (package / "__init__.py").read_text()


# --- food list ---

def test_food_csv_columns_ids_and_provenance():
    header = [line for line in FOODS_CSV.read_text(encoding="utf-8").splitlines() if line.startswith("#")]
    assert any("CC0" in line for line in header) and any("2025-12-18" in line for line in header)
    rows = read_foods_csv()
    assert 300 <= len(rows) <= 400
    assert list(rows[0]) == ["fdc_id", "name_bg", "name_en", "kcal", "protein_g", "carbs_g", "fat_g", "fibre_g", "source"]
    ids = [r["fdc_id"] for r in rows]
    assert len(set(ids)) == len(ids)
    assert len({r["name_bg"] for r in rows}) == len(rows)
    for r in rows:
        assert r["name_bg"] and r["name_en"] and r["source"] in ("sr_legacy_food", "foundation_food")
        values = [float(r[k]) for k in ("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")]
        assert all(0 <= v <= 1000 for v in values) and values[1] + values[2] + values[3] <= 100.5


def test_food_loader_is_idempotent_and_keeps_custom_foods():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    from server.training_nutrition_api import SCHEMA
    db.executescript("CREATE TABLE clubs(id INTEGER PRIMARY KEY); CREATE TABLE users(id INTEGER PRIMARY KEY);"
                     "CREATE TABLE groups(id INTEGER PRIMARY KEY);" + SCHEMA)
    for _ in range(3):
        load_foods(db)
    assert db.execute("SELECT COUNT(*) FROM foods").fetchone()[0] == len(read_foods_csv())
    assert db.execute("SELECT COUNT(*) FROM foods WHERE club_id IS NULL AND owner_id IS NULL").fetchone()[0] == len(read_foods_csv())
