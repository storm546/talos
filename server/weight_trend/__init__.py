"""Numeric weight-trend model reconstructed from measured behaviour and reimplemented in original Python;
exponential trend smoothing over daily bodyweight.

`weight_trend_pipeline` is the numeric model on (UTC-midnight microseconds, kg, flag) tuples.
`daily_trend` adapts dated kilograms to it and back.
"""
from datetime import date as Date, timedelta
import math

from .weight_trend_pipeline import weight_trend_pipeline

DAY_MICROS = 86_400_000_000
EPOCH = Date(1970, 1, 1)
HISTORY_DAYS = 180


def daily_trend(rows):
    """rows: iterable of (YYYY-MM-DD, kg), any order, one value per day.

    Returns [{day, trend_kg, transient_kg, sigma_kg}] for every calendar day from the first to the
    last weigh-in within the retained history (the last HISTORY_DAYS days up to the latest weigh-in).
    Fewer than two weigh-ins give an empty list.
    """
    by_day = {}
    for day, kg in rows:
        by_day[Date.fromisoformat(day)] = float(kg)
    if len(by_day) < 2:
        return []
    last = max(by_day)
    cutoff = last - timedelta(days=HISTORY_DAYS - 1)
    observations = tuple(((day - EPOCH).days * DAY_MICROS, kg, False)
                         for day, kg in sorted(by_day.items()) if day >= cutoff)
    if len(observations) < 2:
        return []
    result = []
    for row in weight_trend_pipeline(observations):
        values = (row["trend_kg"], row["transient_kg"], row["sigma_kg"])
        if not all(math.isfinite(value) for value in values):
            raise ValueError("Weight trend model returned an invalid value")
        result.append({"day": (EPOCH + timedelta(days=row["date_us"] // DAY_MICROS)).isoformat(),
                       "trend_kg": values[0], "transient_kg": values[1], "sigma_kg": values[2]})
    return result
