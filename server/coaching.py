"""Adaptive expenditure and weekly target program (SPEC 6.3).

Original Python reimplementation of a reverse-engineered numeric method; exponential weight trend +
14-day energy balance. No database and no HTTP: plain values in, plain values out, so every rule can be
checked by hand and against a fixed golden history. The storage, scope and persistence are JumpScan's own.

For each date t the expenditure steps towards the trailing 14-day energy balance: the mean logged intake
over the 14 explicitly complete diary days ending at t, minus the measured trend's weight change over the
same window times 7700 kcal/kg, divided by the window. The step is capped so the daily calorie move never
exceeds 150/7 kcal beyond the previous value. Missing intake is never invented as zero; only an explicitly
complete day (which may be an empty day with no food) contributes, and the series holds when a day is
incomplete, a bodyweight measurement is missing, or the trend cannot cover the window.
"""

from bisect import bisect_left, bisect_right
from datetime import date as Date, timedelta
import math

from .weight_trend import weight_trend_pipeline, DAY_MICROS, EPOCH

WINDOW_DAYS = 14
HISTORY_DAYS = 30
WEIGHT_DAYS = 180
KCAL_PER_KG = 7700
MAX_STEP = 150 / 7
MODES = ("updating", "holding")
GOALS = ("maintain", "lose", "gain")
GOAL_SIGN = {"maintain": 0, "lose": -1, "gain": 1}


def _shift(date, days):
    try:
        return (Date.fromisoformat(date) + timedelta(days=days)).isoformat()
    except OverflowError as error:
        raise ValueError("The requested date range exceeds the supported calendar.") from error


def _lookback(date, days):
    return _shift(date, -min(days, Date.fromisoformat(date).toordinal() - 1))


def _model(observations):
    """Single seam to the recovered weight-trend pipeline: date_us -> trend_kg over the daily calendar."""
    return {row["date_us"]: row["trend_kg"] for row in weight_trend_pipeline(observations)}


def step(t, previous, mode, snaps, weights, *, weight_days=None):
    """One day of the expenditure series; returns a row with value_kcal and a status of updating or holding.

    snaps: {iso day: {status, calories, changed}} for prior diary days. weights: {iso day: kg}.
    """
    window = [snaps.get(_shift(t, -k)) for k in range(WINDOW_DAYS, 0, -1)
              if k < Date.fromisoformat(t).toordinal()]
    complete = [s for s in window if s is not None and s["status"] == "complete" and not s["changed"]]
    base = {"date": t, "complete_days": len(complete), "window_start": _lookback(t, WINDOW_DAYS),
            "window_end": _lookback(t, 1)}

    def hold(reason):
        return {**base, "value_kcal": previous, "status": "holding", "reason": reason}

    if mode == "holding":
        return hold("Holding mode keeps the previous expenditure.")
    if len(complete) < WINDOW_DAYS:
        return hold("Needs 14 explicitly complete diary days.")
    if t not in weights:
        return hold("Needs a bodyweight measurement on this date.")
    if weight_days is None:
        weight_days = sorted(weights)
    start = bisect_left(weight_days, _lookback(t, WEIGHT_DAYS - 1))
    end = bisect_right(weight_days, t)
    retained = [(day, weights[day]) for day in weight_days[start:end]]
    if len(retained) < 2 or retained[0][0] > _shift(t, -WINDOW_DAYS):
        return hold("Bodyweight history does not cover the 14-day window.")
    try:
        observations = tuple(((Date.fromisoformat(d) - EPOCH).days * DAY_MICROS, float(kg), False)
                             for d, kg in retained)
        if any(not math.isfinite(o[1]) or not 1 <= o[1] <= 1000 for o in observations):
            raise ValueError("invalid weight")
        trend_by_us = _model(observations)
        end = trend_by_us[observations[-1][0]]
        begin = trend_by_us[observations[-1][0] - WINDOW_DAYS * DAY_MICROS]
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (end, begin)):
            raise ValueError("invalid trend")
    except (ImportError, OSError, ValueError, TypeError, KeyError, ArithmeticError, IndexError):
        return hold("Weight trend unavailable for this window.")
    candidate = math.fsum(s["calories"] for s in complete) / WINDOW_DAYS \
        - KCAL_PER_KG * (end - begin) / WINDOW_DAYS
    if not math.isfinite(candidate) or candidate <= 0:
        return hold("Energy-balance estimate was not positive and finite.")
    value = previous + max(-MAX_STEP, min(MAX_STEP, candidate - previous))
    return {**base, "value_kcal": value, "status": "updating", "reason": "Updated from 14-day energy balance."}


def compute(seed_date, seed_kcal, mode, snaps, weights, as_of):
    """Replay the daily expenditure series from the seed up to and including as_of.

    seed_date: iso day the seed takes effect. seed_kcal: starting expenditure. mode: updating or holding.
    snaps: {iso day: {status, calories, changed}}. weights: {iso day: kg}. Returns {expenditure, history}.
    """
    if seed_date is None:
        return {"expenditure": None, "history": []}
    previous = float(seed_kcal)
    series = [{"date": seed_date, "value_kcal": previous, "status": "holding", "reason": "Seed value.",
               "complete_days": 0, "window_start": _lookback(seed_date, WINDOW_DAYS),
               "window_end": _lookback(seed_date, 1)}]
    history_start = max(seed_date, _lookback(as_of, HISTORY_DAYS - 1))
    weight_days = sorted(weights) if mode != "holding" else []
    for t in weight_days[bisect_right(weight_days, seed_date):bisect_left(weight_days, history_start)]:
        previous = step(t, previous, mode, snaps, weights, weight_days=weight_days)["value_kcal"]
    t = seed_date if history_start == seed_date else _shift(history_start, -1)
    while t < as_of:
        t = _shift(t, 1)
        entry = step(t, previous, mode, snaps, weights, weight_days=weight_days)
        previous = entry["value_kcal"]
        series.append(entry)
    history_keys = ("date", "value_kcal", "status", "reason", "complete_days")
    return {"expenditure": {k: series[-1][k] for k in ("value_kcal", "status", "reason", "complete_days",
                                                       "window_start", "window_end")},
            "history": [{k: e[k] for k in history_keys} for e in series[-HISTORY_DAYS:]]}


def program_targets(expenditure, goal, rate_kg_week, protein, fat, effective_date):
    """Weekly macro/calorie targets from the current expenditure, goal and weekly rate.

    calories = expenditure +/- 7700 * rate / 7 (maintain holds); carbs fill the remainder after protein and
    fat. Raises ValueError when the calorie target leaves no room, exactly as the reference method does.
    """
    if expenditure is None:
        raise ValueError("Save an expenditure seed on or before this date first.")
    value = expenditure["value_kcal"]
    calories = value + GOAL_SIGN[goal] * KCAL_PER_KG * rate_kg_week / 7
    if not math.isfinite(calories) or not 500 <= calories <= 10000:
        raise ValueError("Calories for this goal must be between 500 and 10000.")
    carbs = (calories - 4 * protein - 9 * fat) / 4
    if carbs < 0:
        raise ValueError("Protein and fat exceed the calorie target.")
    return {"effective_date": effective_date, "expires_on": _shift(effective_date, 7), "goal": goal,
            "rate_kg_week": rate_kg_week, "expenditure_kcal": value, "mode": expenditure["status"],
            "calories": calories, "protein": protein, "fat": fat, "carbs": carbs}
