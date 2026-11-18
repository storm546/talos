"""Neighbouring slopes around an interval, limited by the pair limiter."""
import math

from .weight_pair_limiter import limit_pair

DAY_MICROS = 86400000000
INT64 = range(-2**63, 2**63)


class NullCheckError(ValueError):
    pass


def _date(value):
    if type(value) is not int or value not in INT64:
        raise ValueError('Dates must be int64 microsecond ints in the supported domain.')
    return value


def _days(later, earlier):
    micros = _date(later) - _date(earlier)
    if micros not in INT64:
        raise ValueError('Duration overflow is outside the supported domain.')
    quotient = abs(micros) // DAY_MICROS
    return -quotient if micros < 0 else quotient


def _slope(high, low, days):
    numerator = high - low
    if days == 0:
        return math.nan if numerator == 0. or math.isnan(numerator) else math.copysign(math.inf, numerator)
    return numerator / float(days)


def _lookup(values, key):
    value = values.get(_date(key))
    if value is not None and (type(value) is not float or not math.isfinite(value)):
        raise ValueError('Map values must be finite floats in the supported domain.')
    return value


def _required(values, key):
    value = _lookup(values, key)
    if value is None:
        raise NullCheckError('Present null neighbor')
    return value


def neighbor_pair(r1_date, r2_date, r3_date, r5_date, r6_map):
    main_days = _days(r1_date, r5_date)
    low = _lookup(r6_map, r5_date)
    if low is None:
        raise NullCheckError('r5 key missing')
    high = _lookup(r6_map, r1_date)
    if high is None:
        raise NullCheckError('r1 key missing')
    main = _slope(high, low, main_days)
    b = a = main
    if r3_date is not None and _date(r3_date) in r6_map:
        days = _days(r5_date, r3_date)
        if days > 0:
            b = _slope(low, _required(r6_map, r3_date), days)
    if r2_date is not None and _date(r2_date) in r6_map:
        days = _days(r2_date, r1_date)
        if days > 0:
            a = _slope(_required(r6_map, r2_date), high, days)
    return limit_pair(a, main, b)
