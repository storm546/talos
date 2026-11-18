"""Blend factor between two smoothed states, from truncated whole-day differences in raw microseconds."""
import math

DAY_MICROS = 86_400_000_000


def _int64(value):
    if type(value) is not int or not -(1 << 63) <= value < (1 << 63):
        raise ValueError('Use signed-int64 indices and raw DateTime microseconds.')
    return value


def _wrapped_days(later, earlier):
    micros = ((later - earlier + (1 << 63)) % (1 << 64)) - (1 << 63)
    days = abs(micros) // DAY_MICROS
    return -days if micros < 0 else days


def selected_blend_factor(first_index, second_index, threshold, left_date, right_date):
    for value in (first_index, second_index, threshold, left_date, right_date):
        _int64(value)
    if first_index == second_index or right_date <= left_date:
        return 0.0
    numerator = float(_wrapped_days(threshold, left_date))
    denominator = float(_wrapped_days(right_date, left_date))
    if denominator == 0.0:
        quotient = math.nan if numerator == 0.0 else math.copysign(math.inf, numerator)
    else:
        quotient = numerator / denominator
    if math.isnan(quotient) or quotient > 1.0:
        return 1.0
    if quotient <= 0.0:
        return 0.0
    return quotient
