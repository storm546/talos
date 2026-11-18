"""Backward smoothing composed over a 30-day horizon with blending between neighbouring observation positions."""
import math

from .weight_horizon_start import anchor_cluster_positions, HORIZON_MICROS
from .weight_horizon_blend import selected_blend_factor
from .weight_smoothing import smooth_state_to_horizon
from .weight_vector_difference import difference

MAX_POINTS = 256


def _date(value):
    if type(value) is not int or not -(1 << 63) <= value < (1 << 63):
        raise ValueError('Dates must be raw signed-int64 microsecond fields.')
    return value


def _history(values, size, width):
    if type(values) is not list or len(values) != size:
        raise ValueError('Each plain history must have one row per date.')
    for row in values:
        if (type(row) is not list or len(row) != width or
                any(type(value) is not float or not math.isfinite(value) for value in row)):
            raise ValueError('History rows require exact dimensions and finite doubles.')


def _finite(values):
    if any(not math.isfinite(value) for value in values):
        raise ValueError('Numeric result leaves the finite safe-arithmetic adapter domain.')
    return values


def horizon(dates, covariance, stored, membership, predicted_covariance,
            predicted_state, transitions):
    if type(dates) is not list or len(dates) > MAX_POINTS:
        raise ValueError(f'Use a plain date list with at most {MAX_POINTS} points.')
    if type(membership) is not dict:
        raise ValueError('Membership must be a plain raw-date-key dict.')
    for date in dates:
        _date(date)
        _date(date + HORIZON_MICROS)
    for key in membership:
        _date(key)
    if dates and max(dates) + HORIZON_MICROS - min(dates) >= (1 << 63):
        raise ValueError('Date differences leave the safe nonoverflowing adapter domain.')
    count = len(dates)
    _history(stored, count, 3)
    positions = [index for index, date in enumerate(dates) if date in membership]
    if not positions:
        return [row[:] for row in stored]
    for values, width in ((covariance, 9), (predicted_covariance, 9),
                          (predicted_state, 3), (transitions, 9)):
        _history(values, count, width)
    history = {0x0f: covariance, 0x13: stored, 0x17: predicted_covariance,
               0x1b: predicted_state, 0x1f: transitions}
    selected_dates = [dates[index] for index in positions]
    result = []
    for index, date in enumerate(dates):
        pair = anchor_cluster_positions(positions, selected_dates, index, date)
        if pair is None:
            first = second = count-1
            factor = 0.0
        else:
            left, right = pair
            first, second = positions[left], positions[right]
            factor = selected_blend_factor(first, second, date + HORIZON_MICROS,
                                           selected_dates[left], selected_dates[right])
        base = _finite(smooth_state_to_horizon(history, index, first))
        if first == second or factor == 0.0:
            result.append(base)
            continue
        target = _finite(smooth_state_to_horizon(history, index, second))
        delta = _finite(difference(target, base))
        products = _finite([value * factor for value in delta])
        result.append(_finite([base[j] + products[j] for j in range(3)]))
    return result
