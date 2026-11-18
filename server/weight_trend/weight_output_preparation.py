"""In-place cubic interpolation of interior calendar days between observation anchors."""
import math

from .weight_neighbor_pair import DAY_MICROS, NullCheckError, _date, _days, neighbor_pair

MAX_GAP_DAYS = 3660


def _finite(*values):
    if not all(type(value) is float and math.isfinite(value) for value in values):
        raise ValueError('Nonfinite/intermediate-overflow arithmetic is outside this model.')


def _bounds(left, right):
    low, high = min(left, right), max(left, right)
    if left == right == 0.:
        low = -0. if math.copysign(1., left) < 0 or math.copysign(1., right) < 0 else 0.
        high = 0. if math.copysign(1., left) > 0 or math.copysign(1., right) > 0 else -0.
    return low, high


def _compare(left, right):
    if left == right:
        if left == 0.:
            return (math.copysign(1., left) > math.copysign(1., right)) - (math.copysign(1., left) < math.copysign(1., right))
        return 0
    return -1 if left < right else 1


def _interpolate(step, days, left, right, left_slope, right_slope):
    duration = float(days)
    t = float(step) / duration
    square = t*t
    cube = square*t
    twice_cube = cube*2.
    triple_square = square*3.
    h00 = (twice_cube-triple_square)+1.
    h10 = (cube-square*2.)+t
    h01 = cube*(-2.)+triple_square
    h11 = cube-square
    first = h00*left
    second = (h10*duration)*left_slope
    subtotal = first+second
    third = h01*right
    subtotal2 = subtotal+third
    fourth = (h11*duration)*right_slope
    result = subtotal2+fourth
    _finite(first, second, subtotal, third, subtotal2, fourth, result)
    low, high = _bounds(left, right)
    return low if _compare(result, low) < 0 else high if _compare(result, high) > 0 else result


def prepare_output(requested_dates, values):
    if type(values) is not dict:
        raise ValueError('Only ordinary dictionaries are modeled.')
    if not values:
        return None
    if type(requested_dates) not in (list, tuple):
        raise ValueError('Only ordinary anchor lists/tuples are modeled.')
    for key, value in values.items():
        _date(key)
        if value is not None:
            _finite(value)
    for key in requested_dates:
        _date(key)
    ordered = sorted(set(requested_dates))
    if len(ordered) < 2:
        return None
    snapshot = values.copy()
    anchors = [key for key in ordered if key in snapshot]
    for index in range(len(anchors)-1):
        left_date, right_date = anchors[index:index+2]
        days = _days(right_date, left_date)
        if days <= 1:
            continue
        if days > MAX_GAP_DAYS:
            raise ValueError('Gap exceeds this model resource guard.')
        left, right = values[left_date], values[right_date]
        if left is None or right is None:
            raise NullCheckError('Null interval endpoint')
        previous = anchors[index-1] if index > 0 else None
        following = anchors[index+2] if index+2 < len(anchors) else None
        right_slope, left_slope = neighbor_pair(right_date, following, previous, left_date, snapshot)
        _finite(left_slope, right_slope)
        for step in range(1, days):
            date = _date(left_date + step*DAY_MICROS)
            if date in values:
                values[date] = _interpolate(step, days, left, right, left_slope, right_slope)
    return None
