"""Weight-trend pipeline over strictly chronological (UTC-midnight microseconds, kg, flag) observations.

Numeric weight-trend model reconstructed from measured behaviour and reimplemented in original Python;
exponential trend smoothing over daily bodyweight. Each observation supplies its flag explicitly.
"""
import math

from .weight_initializer import initialize
from .weight_forward import forward_history_us, base_sigma, calendar_us
from .weight_horizon import horizon, MAX_POINTS
from .weight_requested_outputs import remap_forward_fields

DAY_MICROS = 86_400_000_000


def _utc_date(value):
    if (type(value) is not int or not -(1 << 63) <= value < (1 << 63) or
            value % DAY_MICROS != 0):
        raise ValueError('Use signed-int64 UTC-midnight microsecond dates.')
    return value


def weight_trend_pipeline(observations, *, requested_dates=None):
    """Return {date_us, trend_kg, transient_kg, sigma_kg} rows in requested order.

    observations: nonempty chronological sequence of (UTC-midnight microseconds,
    positive finite float kg, explicit bool flag). Without requested_dates the
    inclusive daily calendar from the first to the last observation is returned.
    """
    if type(observations) not in (list, tuple) or not 1 <= len(observations) <= MAX_POINTS:
        raise ValueError(f'Provide 1..{MAX_POINTS} explicit observations.')
    previous = None
    entries = []
    for observation in observations:
        if type(observation) not in (list, tuple) or len(observation) != 3:
            raise ValueError('Each observation requires date, float kg and explicit bool flag.')
        date, weight, flag = observation
        _utc_date(date)
        if previous is not None and date <= previous:
            raise ValueError('Observation dates must be strictly chronological, without duplicates.')
        if type(weight) is not float or not math.isfinite(weight) or weight <= 0.0:
            raise ValueError('Observation weight must be a positive finite float in kg.')
        if type(flag) is not bool:
            raise ValueError('Every observation requires an explicit bool flag.')
        previous = date
        entries.append((date, weight, flag))
    if (entries[-1][0] - entries[0][0]) // DAY_MICROS >= MAX_POINTS:
        raise ValueError(f'Daily calendar exceeds the {MAX_POINTS}-point analysis guard.')
    requested = calendar_us(entries[0][0], entries[-1][0])
    if requested_dates is not None:
        if type(requested_dates) not in (list, tuple) or len(requested_dates) > MAX_POINTS:
            raise ValueError(f'Use at most {MAX_POINTS} ordinary requested dates.')
        requested = [_utc_date(date) for date in requested_dates]
    enough, median = initialize([(date, weight) for date, weight, _ in entries])
    if len(entries) == 1:
        fields = ([entries[0][1]] * len(requested), [0.0] * len(requested),
                  [base_sigma(median)] * len(requested))
    else:
        forward = forward_history_us(entries, median, enough)
        history = forward['history']
        # Rows are materialised as plain lists with every number unchanged.
        transition_rows = [list(row) for row in history[0x1f]]
        smoothed = horizon(forward['dates'], history[0x0f], history[0x13],
                           forward['observations'], history[0x17], history[0x1b],
                           transition_rows)
        fields = remap_forward_fields(
            forward['dates'], [row[0] for row in smoothed],
            [row[2] for row in smoothed], forward['sigma'],
            [date for date, _, _ in entries], requested)
    return [dict(date_us=date, trend_kg=trend, transient_kg=transient, sigma_kg=sigma)
            for date, trend, transient, sigma in zip(requested, *fields)]
