"""Maps forward-filter fields onto the requested dates, holding the end values outside the calendar."""
import math

from .weight_neighbor_pair import _date, NullCheckError, DAY_MICROS
from .weight_output_preparation import prepare_output


def remap_forward_fields(dates, primary, secondary, tertiary, anchors, requested):
    for sequence in (dates, primary, secondary, tertiary, anchors, requested):
        if type(sequence) not in (list, tuple):
            raise ValueError('Only ordinary list/tuple inputs are modeled.')
    if not dates:
        raise ValueError('The multi-observation path requires a nonempty calendar.')
    if any(len(values) != len(dates) for values in (primary, secondary, tertiary)):
        raise ValueError('Forward fields must share their calendar length.')
    for date in (*dates, *anchors, *requested):
        _date(date)
    for values in (primary, secondary, tertiary):
        if any(type(value) is not float or not math.isfinite(value) for value in values):
            raise ValueError('This adapter requires finite binary64 field values.')
    maps = [dict(zip(dates, values)) for values in (primary, secondary, tertiary)]
    prepare_output(anchors, maps[0])
    first, last = dates[0], dates[-1]
    boundaries = [(values[first], values[last]) for values in maps]
    output = ([], [], [])
    for date in requested:
        if date < first:
            values = [pair[0] for pair in boundaries]
        elif date > last:
            values = [pair[1] for pair in boundaries]
        else:
            values = [mapping.get(date) for mapping in maps]
            if any(value is None for value in values):
                raise NullCheckError('Missing requested date')
        for target, value in zip(output, values):
            target.append(value)
    return output
