"""Cluster end position: last index within a day limit of a starting date, in raw microseconds."""
from .weight_neighbor_pair import _date, _days, DAY_MICROS


def cluster_end_position(dates, bound_length, day_limit, start):
    if not isinstance(dates, (list, tuple)):
        raise ValueError('Use a plain date list/tuple in the supported domain.')
    if type(bound_length) is not int or not 0 <= bound_length < (1 << 30):
        raise ValueError('The separate collection length must be a valid small-integer length.')
    if type(day_limit) is not int or not -(1 << 63) <= day_limit < (1 << 63):
        raise ValueError('Day limit must be a signed 64-bit integer.')
    if type(start) is not int or not -(1 << 63) <= start < (1 << 63):
        raise ValueError('Start must be a signed 64-bit integer.')
    if not 0 <= start < len(dates):
        raise IndexError('Initial date-list range check failed.')
    origin = _date(dates[start])
    position = start
    while position + 1 < bound_length:
        following = position + 1
        if following >= len(dates):
            raise IndexError('Following date-list range check failed.')
        if _days(dates[following], origin) > day_limit:
            break
        position = following
    return position
