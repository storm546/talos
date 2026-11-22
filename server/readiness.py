"""Personal baselines, readiness, sleep and training load (SPEC 9, wave 2). Pure functions, no database.

JumpScan's own combination of published methods. It is information for athlete and coach, not a validated
model and not medical advice. The constants below are design choices and are listed in the help text.

- Baseline: mean and sample SD of the athlete's own values over the previous 28 days, at least 7 values,
  otherwise "still learning".
- A value counts as better or worse than usual only when it differs from the baseline mean by more than the
  smallest worthwhile change, 0.5 x SD (Hopkins 2000; Buchheit 2014; Plews 2013 for ln RMSSD), and more than the
  typical error, the SD of trial-to-trial differences divided by sqrt 2 (Hopkins 2000).
- Sleep: duration against an age-based need, 8-10 h under 18 and 7-9 h for adults (Hirshkowitz 2015), and
  night-to-night regularity as the SD of the sleep midpoint over the last 7 nights (after Phillips 2017).
- Load: session RPE x minutes per session, summed per day (Foster 2001); weekly monotony = mean / SD of the
  7 daily loads and strain = weekly load x monotony (Foster 1998); acute:chronic ratio of exponentially
  weighted daily loads with 7 and 28 day spans, lambda = 2 / (span + 1) (Williams 2017).
"""

from math import log, sqrt
from statistics import fmean, stdev

WINDOW_DAYS = 28
MIN_VALUES = 7
SWC_FACTOR = 0.5
SLEEP_NIGHTS = 7
MIN_SLEEP_NIGHTS = 3
ACUTE_DAYS = 7
CHRONIC_DAYS = 28


def baseline(values, minimum=MIN_VALUES):
    """Mean and sample SD of the known values; not ready ("still learning") below `minimum` values."""
    values = [value for value in values if value is not None]
    ready = len(values) >= minimum
    return {"n": len(values), "needed": minimum, "ready": ready,
            "mean": fmean(values) if ready else None, "sd": stdev(values) if ready else None}


def differences(values):
    """Consecutive differences of an ordered series (day to day, or trial to trial within a morning)."""
    return [after - before for before, after in zip(values, values[1:])]


def typical_error(diffs):
    """Hopkins 2000: SD of the differences between repeated measurements / sqrt 2; None below two differences."""
    return stdev(diffs) / sqrt(2) if len(diffs) >= 2 else None


def compare(value, base, error=None, higher_is_better=True):
    """Today's value against the baseline.

    state: "learning" (baseline not ready), "missing" (no value today), "usual", "better" or "worse".
    The threshold is the larger of the smallest worthwhile change (0.5 x SD) and the typical error.
    """
    if not base["ready"]:
        return {"state": "learning", "difference": None, "threshold": None}
    if value is None:
        return {"state": "missing", "difference": None, "threshold": None}
    threshold = max(SWC_FACTOR * base["sd"], error or 0.0)
    difference = value - base["mean"]
    if abs(difference) <= threshold:
        state = "usual"
    else:
        state = "better" if (difference > 0) == higher_is_better else "worse"
    return {"state": state, "difference": difference, "threshold": threshold}


def ln_rmssd(rmssd_ms):
    """HRV is entered as RMSSD in milliseconds (what watches show) and compared as ln(RMSSD) (Plews 2013)."""
    return None if rmssd_ms is None or rmssd_ms <= 0 else log(rmssd_ms)


# --- sleep ---

def clock_minutes(text):
    hours, minutes = text.split(":")
    return int(hours) * 60 + int(minutes)


def sleep_hours(bedtime, wake_time):
    """Time in bed from bedtime to wake time, across midnight when needed."""
    return ((clock_minutes(wake_time) - clock_minutes(bedtime)) % 1440) / 60


def sleep_midpoint(bedtime, wake_time):
    """Midpoint of the night in minutes after the previous noon, so nights across midnight stay comparable."""
    start = (clock_minutes(bedtime) - 720) % 1440
    return start + sleep_hours(bedtime, wake_time) * 30


def sleep_need(under_18):
    """Recommended hours (Hirshkowitz 2015): 14-17 years 8-10 h, adults 7-9 h."""
    return (8, 10) if under_18 else (7, 9)


def duration_score(hours, need):
    """100 inside the recommended range; 25 points less per hour short, 10 less per hour over."""
    low, high = need
    if hours < low:
        return max(0.0, 100 - 25 * (low - hours))
    if hours > high:
        return max(0.0, 100 - 10 * (hours - high))
    return 100.0


def regularity_score(midpoint_sd):
    """100 when the midpoint varies by 30 min or less, 0 at 120 min or more, linear between."""
    return None if midpoint_sd is None else max(0.0, min(100.0, 100 - (midpoint_sd - 30) * 100 / 90))


def sleep_score(hours, need, midpoints, quality=None):
    """Mean of the available parts: duration, regularity (3+ nights of the last 7) and the 1-5 sleep item."""
    midpoints = midpoints[-SLEEP_NIGHTS:]
    spread = stdev(midpoints) if len(midpoints) >= MIN_SLEEP_NIGHTS else None
    parts = {"duration": duration_score(hours, need), "regularity": regularity_score(spread),
             "quality": None if quality is None else (quality - 1) * 25.0}
    shown = [value for value in parts.values() if value is not None]
    return {"score": round(fmean(shown)), "hours": round(hours, 2), "need_hours": list(need),
            "midpoint_sd_min": None if spread is None else round(spread, 1), "nights": len(midpoints),
            "parts": {key: None if value is None else round(value) for key, value in parts.items()}}


def sleep_state(score):
    """Sleep has fixed bands rather than a personal baseline: 85+ better, under 60 worse."""
    return "better" if score >= 85 else "worse" if score < 60 else "usual"


# --- load ---

def monotony(week):
    """Foster 1998: mean / SD of the 7 daily loads (rest days count as 0); None when every day is equal."""
    spread = stdev(week)
    return None if spread == 0 else fmean(week) / spread


def strain(week):
    value = monotony(week)
    return None if value is None else sum(week) * value


def ewma(loads, span):
    """Williams 2017: today = load x lambda + yesterday x (1 - lambda), lambda = 2 / (span + 1), started at day one."""
    weight = 2 / (span + 1)
    result = []
    for load in loads:
        result.append(load if not result else load * weight + result[-1] * (1 - weight))
    return result


def acute_chronic(loads):
    """Ratio of the 7-day and 28-day weighted averages; needs 28 days of history, else still learning."""
    if len(loads) < CHRONIC_DAYS:
        return {"ready": False, "days": len(loads), "needed": CHRONIC_DAYS, "acute": None, "chronic": None, "ratio": None}
    acute, chronic = ewma(loads, ACUTE_DAYS)[-1], ewma(loads, CHRONIC_DAYS)[-1]
    return {"ready": True, "days": len(loads), "needed": CHRONIC_DAYS, "acute": acute, "chronic": chronic,
            "ratio": acute / chronic if chronic > 0 else None}


def ratio_band(ratio):
    """Neutral words for the ratio: a comparison with the athlete's usual load, not a forecast."""
    if ratio is None:
        return None
    return "well_above" if ratio > 1.5 else "above" if ratio > 1.3 else "below" if ratio < 0.8 else "similar"


# --- readiness ---

def readiness_status(factors):
    """good / normal / take_care / still_learning / no_data from factor states.

    still_learning: no baseline factor is ready yet. no_data: nothing recorded for the day.
    take_care: two or more factors worse than usual. good: none worse and at least one better. Else normal.
    """
    if not any(f["state"] != "learning" for f in factors if f["key"] != "sleep"):
        return "still_learning"
    measured = [f["state"] for f in factors if f["state"] in ("usual", "better", "worse")]
    if not measured:
        return "no_data"
    worse = measured.count("worse")
    if worse >= 2:
        return "take_care"
    return "good" if worse == 0 and "better" in measured else "normal"
