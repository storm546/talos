"""Start-index search for the horizon smoothing: two binary searches and a day-cluster step."""
from .weight_cluster_end import cluster_end_position
from .weight_neighbor_pair import _date, DAY_MICROS

INT64 = range(-2**63, 2**63)
SMI_LIMIT = 1 << 30
CLUSTER_DAY_LIMIT = 2
HORIZON_MICROS = 30 * DAY_MICROS


def _int64(value, name):
    if type(value) is not int or value not in INT64:
        raise ValueError(f'{name} must be a signed 64-bit integer.')
    return value


def _length(items, name):
    if not isinstance(items, (list, tuple)) or len(items) >= SMI_LIMIT:
        raise ValueError(f'{name} must be a plain list/tuple with a small-integer length.')
    return len(items)


def member_lower_bound(positions, index):
    hi = _length(positions, 'L')
    index = _int64(index, 'anchor index')
    lo = 0
    while lo < hi:
        mid = lo + ((hi - lo) >> 1)
        if _int64(positions[mid], 'L element') < index:
            lo = mid + 1
        else:
            hi = mid
    return lo


def horizon_lower_bound(dates, threshold):
    hi = _length(dates, 'X')
    threshold = _date(threshold)
    lo = 0
    while lo < hi:
        mid = lo + ((hi - lo) >> 1)
        if _date(dates[mid]) < threshold:
            lo = mid + 1
        else:
            hi = mid
    return lo


def horizon_start(positions, dates, index, anchor, offset=HORIZON_MICROS):
    threshold = _date(_date(anchor) + _int64(offset, 'offset'))
    low = member_lower_bound(positions, index)
    high = horizon_lower_bound(dates, threshold)
    start = max(low, high)
    return low, high, (start if start < len(positions) else None)


def anchor_cluster_positions(positions, dates, index, anchor, offset=HORIZON_MICROS):
    if _length(positions, 'L') != _length(dates, 'X'):
        raise ValueError('X holds one date per L entry.')
    low, high, start = horizon_start(positions, dates, index, anchor, offset)
    if start is None:
        return None
    first = cluster_end_position(dates, len(positions), CLUSTER_DAY_LIMIT, start)
    threshold = _date(anchor) + offset
    if _date(dates[first]) > threshold and first - 1 >= low:
        second = cluster_end_position(dates, len(positions), CLUSTER_DAY_LIMIT, first - 1)
        return min(second, first), first
    return first, first
