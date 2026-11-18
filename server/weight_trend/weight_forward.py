"""Forward filter over a daily calendar: three-component state, adaptive observation scale, outlier-weighted update."""
import math

from .weight_smoothing import matrix_product, matrix_vector
from .weight_transition import transition

_TRANSPOSE = (0, 3, 6, 1, 4, 7, 2, 5, 8)


def _compare_double(left, right):
    if math.isnan(left):
        return 0 if math.isnan(right) else 1
    if math.isnan(right):
        return -1
    if left == right:
        if left == 0.:
            return int(math.copysign(1., left) > math.copysign(1., right)) - int(
                math.copysign(1., left) < math.copysign(1., right))
        return 0
    return -1 if left < right else 1


def _clamp_double(value, lower, upper):
    if _compare_double(lower, upper) > 0:
        raise ValueError('Invalid clamp bounds')
    if math.isnan(lower):
        return lower
    if _compare_double(value, lower) < 0:
        return lower
    if _compare_double(value, upper) > 0:
        return upper
    return value


def scale_floor(value):
    if not math.isfinite(value):
        return 25.
    return max(abs(value), 25.)


def base_sigma(predicted_weight):
    return _clamp_double(scale_floor(predicted_weight) * .007, .45, .85)


def initial_velocity_sigma(weight):
    return _clamp_double(scale_floor(weight) * .0008, .04, .12)


def initial_offset_sigma(weight):
    return _clamp_double(scale_floor(weight) * .01, .6, 1.4)


def adaptive_scale(previous, innovation, sigma, predicted_weight):
    absolute = 0. if innovation == 0. else abs(innovation)
    clipped = _clamp_double(absolute, 0., sigma * 2.)
    target_sigma = _clamp_double(clipped * 1.2533141373155001, .45, .85)
    ratio = target_sigma / base_sigma(predicted_weight)
    alpha = 1. - math.pow(.5, .03333333333333333)
    return previous + alpha * (ratio - previous)


def cleanup_covariance(covariance):
    result = list(covariance)
    for first, second in ((1, 3), (2, 6), (5, 7)):
        value = (covariance[first] + covariance[second]) / 2.
        result[first] = result[second] = value
    for index in (0, 4, 8):
        value = result[index]
        result[index] = value if math.isnan(value) else max(value, 1e-12)
    return result


def forward_step(state, covariance, transition, process_noise, observation,
                 previous_scale, observation_flag=False):
    predicted, projected = predict(state, covariance, transition, process_noise)
    projected = cleanup_covariance(projected)
    sigma = _clamp_double(base_sigma(predicted[0]) * previous_scale, .45, .85)
    updated, updated_covariance, details = observe(
        predicted, projected, observation, sigma, observation_flag)
    next_scale = previous_scale
    if observation is not None and not observation_flag:
        next_scale = adaptive_scale(previous_scale, details[0], sigma, predicted[0])
    return updated, cleanup_covariance(updated_covariance), next_scale


_DAY_US = 86400000000


def calendar_us(first, last):
    difference = last - first
    days = abs(difference) // _DAY_US
    if difference < 0:
        days = -days
    return [first + k * _DAY_US for k in range(days + 1)]


def forward_history_us(entries, receiver_double, receiver_flag):
    first_date, first_weight, _ = entries[0]
    dates = calendar_us(first_date, entries[-1][0])
    by_date = {entry[0]: entry for entry in entries}
    state, covariance = initial_state(
        first_weight, receiver_flag, initial_velocity_sigma(receiver_double),
        initial_offset_sigma(receiver_double))
    history = {0x13: [state[:]], 0x0f: [covariance[:]],
               0x1b: [state[:]], 0x17: [covariance[:]],
               0x1f: [diagonal(1., 1., 1.)]}
    sigma_history = [_clamp_double(base_sigma(receiver_double) * 1., .45, .85)]
    retained_scale = 1.
    missed = 0
    noise_history = []
    counters = [0]
    for date in dates[1:]:
        missed += 1
        f, q = transition(state[0])
        q = list(q)
        if missed > 3:
            extra_scale = scale_floor(state[0]) * .001
            q[0] = q[0] + (extra_scale * extra_scale) * float(2*(missed-3)-1)
        predicted, projected = predict(state, covariance, f, q)
        projected = cleanup_covariance(projected)
        sigma = _clamp_double(base_sigma(predicted[0]) * retained_scale, .45, .85)
        sigma_history.append(sigma)
        entry = by_date.get(date)
        if entry is None:
            state, covariance = predicted, projected
        else:
            _, value, flag = entry
            state, covariance, details = observe(predicted, projected, value, sigma, flag)
            if not flag:
                retained_scale = adaptive_scale(retained_scale, details[0], sigma, predicted[0])
            missed = 0
        covariance = cleanup_covariance(covariance)
        history[0x13].append(state)
        history[0x0f].append(covariance)
        history[0x1b].append(predicted)
        history[0x17].append(projected)
        history[0x1f].append(f)
        noise_history.append(q)
        counters.append(missed)
    return dict(dates=dates, history=history, sigma=sigma_history,
                final_scale=retained_scale, missing_counter=counters,
                process_noise=noise_history, observations=by_date)


def diagonal(a, b, c):
    return [a, 0., 0., 0., b, 0., 0., 0., c]


def initial_state(first_weight, receiver_flag, helper_1788, helper_16f4):
    return [first_weight, 0., 0.], diagonal(
        .5 if receiver_flag else 4., helper_1788 * helper_1788,
        helper_16f4 * helper_16f4)


def predict(state, covariance, transition, process_noise):
    next_state = matrix_vector(transition, state)
    ft = [transition[j] for j in _TRANSPOSE]
    propagated = matrix_product(matrix_product(transition, covariance), ft)
    return next_state, [propagated[j] + process_noise[j] for j in range(9)]


def observe(state, covariance, observation, sigma, observation_flag):
    if observation is None:
        return state, covariance, None
    p = covariance
    noise_scale = sigma * (2. if observation_flag else 1.)
    noise = noise_scale * noise_scale
    innovation = observation - (state[0] + state[2])
    projected = ((p[0] + p[2]) + p[6]) + p[8]
    normalized = innovation / math.sqrt(projected + noise)
    weight = 1. if abs(normalized) <= 2. else 2. / abs(normalized)
    effective_noise = noise / max(weight, 1e-9)
    inverse_variance = 1. / (projected + effective_noise)
    gain = [((p[3*r] + p[3*r+1]*0.) + p[3*r+2]) * inverse_variance
            for r in range(3)]
    updated = [state[j] + gain[j] * innovation for j in range(3)]
    # Explicit zero products are kept as written; do not simplify them.
    kh = [component for k in gain for component in (k, k*0., k)]
    identity = diagonal(1., 1., 1.)
    residual = [identity[j] - kh[j] for j in range(9)]
    left = matrix_product(residual, p)
    joseph = matrix_product(left, [residual[j] for j in _TRANSPOSE])
    outer = [gain[r] * gain[c] for r in range(3) for c in range(3)]
    updated_covariance = [joseph[j] + outer[j] * effective_noise
                          for j in range(9)]
    return updated, updated_covariance, (innovation, effective_noise, gain)
