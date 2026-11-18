"""Pure maths for training progression, weekly volume and the energy-balance estimate (SPEC 6.2, 6.3).

No database, no HTTP: plain values in, plain values out, so every rule can be checked by hand.
"""

from datetime import date, timedelta
from statistics import mean

KCAL_PER_KG = 7700
WINDOW_DAYS = 28
MIN_WINDOW_DAYS = 14
MIN_FOOD_DAYS = 10
MIN_WEIGH_INS = 8
KCAL_FLOOR = 1500
PROTEIN_PER_KG = (1.4, 2.0)
DECREASE_PCT = 5


def progression(planned, sessions, step_kg=2.5):
    """Double progression for one exercise; a suggestion only, never applied.

    planned: {sets, reps (bottom of range), reps_max (top of range), rpe_target or None}.
    sessions: oldest first, each {day, sets: [{reps, load_kg or None, rpe or None}]} with sets in order.
    Looks at the last two sessions. A session reached the top when at least `sets` sets were done, each
    of the first `sets` reached reps_max and, when an RPE target exists, was logged at or below it.
    It missed the bottom when any set was below `reps`. Two tops: add step_kg to the last load.
    Two misses: take 5 % off the last load. Anything else: keep.
    """
    last = sessions[-2:]
    if len(last) < 2:
        return {"action": "keep", "reason": "need_two_sessions", "days": [s["day"] for s in last]}

    def reached_top(session):
        done = session["sets"][:planned["sets"]]
        target = planned.get("rpe_target")
        return len(done) >= planned["sets"] and all(
            s["reps"] >= planned["reps_max"]
            and (target is None or (s["rpe"] is not None and s["rpe"] <= target)) for s in done)

    def missed_bottom(session):
        return any(s["reps"] < planned["reps"] for s in session["sets"])

    loads = [s["load_kg"] for s in last[-1]["sets"] if s["load_kg"] is not None]
    load = max(loads) if loads else None
    days = [s["day"] for s in last]
    if all(reached_top(s) for s in last):
        return {"action": "increase", "reason": "top_of_range_twice", "days": days, "change_kg": step_kg,
                "from_load_kg": load, "suggested_load_kg": None if load is None else round(load + step_kg, 2)}
    if all(missed_bottom(s) for s in last):
        return {"action": "decrease", "reason": "below_range_twice", "days": days, "change_pct": -DECREASE_PCT,
                "from_load_kg": load,
                "suggested_load_kg": None if load is None else round(load * (100 - DECREASE_PCT) / 100, 1)}
    return {"action": "keep", "reason": "no_change", "days": days}


def week_start(day):
    """Monday of the week containing the ISO date `day`."""
    parsed = date.fromisoformat(day)
    return (parsed - timedelta(days=parsed.weekday())).isoformat()


def weekly_volume(sets, loads, weeks, end_day):
    """Per-week summary for the `weeks` calendar weeks ending with the week of `end_day`, oldest first.

    sets: (day, reps, load_kg or None). loads: (day, rpe, training_minutes), the existing session-RPE load.
    volume_kg = sum of reps x load_kg; sets without a load count in `sets` and `unloaded_sets` only.
    """
    last = date.fromisoformat(week_start(end_day))
    result = {(last - timedelta(weeks=n)).isoformat(): {"sets": 0, "volume_kg": 0.0, "unloaded_sets": 0, "session_load": 0}
              for n in range(weeks)}
    for day, reps, load_kg in sets:
        week = result.get(week_start(day))
        if week is not None:
            week["sets"] += 1
            if load_kg is None:
                week["unloaded_sets"] += 1
            else:
                week["volume_kg"] += reps * load_kg
    for day, rpe, minutes in loads:
        week = result.get(week_start(day))
        if week is not None and rpe is not None and minutes is not None:
            week["session_load"] += rpe * minutes
    return [{"week_start": start, **result[start]} for start in sorted(result)]


def energy_balance(food_kcal_by_day, trend_by_day, weigh_in_days, end):
    """Expenditure estimate from the athlete's own logs.

    food_kcal_by_day: {iso day: kcal} for days with food logged. trend_by_day: {iso day: trend kg} from the
    weight-trend model. weigh_in_days: iso days with a weigh-in. end: last day (date) of the window.
    The window is the last 28 days, shortened to start at the first logged day but never below 14 days.
    Needs at least 10 food days and 8 weigh-ins inside it, else no estimate.
    estimate = mean logged intake - (trend change in kg x 7700) / days, where the change runs from the
    first to the last weigh-in in the window and days is the span between them.
    """
    limit = end - timedelta(days=WINDOW_DAYS - 1)
    inside = lambda day: limit <= date.fromisoformat(day) <= end
    food = {day: kcal for day, kcal in food_kcal_by_day.items() if inside(day)}
    weighed = sorted(day for day in set(weigh_in_days) if inside(day))
    first = min([date.fromisoformat(day) for day in (*food, *weighed)], default=end)
    start = max(limit, min(first, end - timedelta(days=MIN_WINDOW_DAYS - 1)))
    result = {"available": False, "window_start": start.isoformat(), "window_end": end.isoformat(),
              "window_days": (end - start).days + 1, "food_days": len(food), "weigh_ins": len(weighed),
              "needed_food_days": MIN_FOOD_DAYS, "needed_weigh_ins": MIN_WEIGH_INS,
              "label": "estimate from your own logs"}
    if len(food) < MIN_FOOD_DAYS or len(weighed) < MIN_WEIGH_INS:
        return {**result, "reason": "not_enough_data"}
    days = (date.fromisoformat(weighed[-1]) - date.fromisoformat(weighed[0])).days
    change = trend_by_day[weighed[-1]] - trend_by_day[weighed[0]]
    intake = mean(food.values())
    return {**result, "available": True, "reason": None, "mean_intake_kcal": intake, "trend_change_kg": change,
            "trend_days": days, "expenditure_kcal": intake - change * KCAL_PER_KG / days}


def check_target(kcal, estimate_kcal, under_18, set_by_staff):
    """Guardrails for a daily kcal target. Returns None when allowed, else a plain explanation.

    Nobody can go below 1500 kcal. An athlete under 18 setting their own target cannot go below the
    current estimate (or set one at all while there is no estimate); a coach or admin can.
    """
    if kcal < KCAL_FLOOR:
        return f"A target below {KCAL_FLOOR} kcal is not allowed"
    if under_18 and not set_by_staff:
        if estimate_kcal is None:
            return "Ask your coach to set a target: there is no estimate from your logs yet"
        if kcal < estimate_kcal:
            return "Ask your coach: a target below your estimate has to be set by a coach"
    return None


def protein_range(kg):
    """Protein range in grams per day for a body weight, 1.4-2.0 g per kg: shown, not prescribed."""
    return [round(PROTEIN_PER_KG[0] * kg), round(PROTEIN_PER_KG[1] * kg)]
