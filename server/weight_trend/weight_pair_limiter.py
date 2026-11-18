"""Slope-pair limiter: sign agreement, magnitude cap at three times the main slope, norm limit."""
import math


def _compare(left, right):
    if math.isnan(left):
        return 0 if math.isnan(right) else 1
    if math.isnan(right):
        return -1
    if left == right:
        if left == 0.:
            a, b = math.copysign(1., left), math.copysign(1., right)
            return (a > b) - (a < b)
        return 0
    return -1 if left < right else 1


def _clamp(value, lower, upper):
    if _compare(lower, upper) > 0:
        raise ValueError('Clamp bounds are reversed')
    if math.isnan(lower):
        return lower
    if _compare(value, lower) < 0:
        return lower
    if _compare(value, upper) > 0:
        return upper
    return value


def limit_pair(a, delta, b):
    if any(type(value) is not float for value in (a, delta, b)):
        raise ValueError('Use Python floats for binary64 inputs.')
    if abs(delta) < 1e-12:
        return 0., 0.
    product_b, product_a = b * delta, a * delta
    if not (math.isnan(product_b) or product_b > 0.):
        b = 0.
    if not (math.isnan(product_a) or product_a > 0.):
        a = 0.
    magnitude = delta if math.isnan(delta) else abs(delta)
    cap = magnitude * 3.
    lower = -cap
    b = _clamp(b, lower, cap)
    a = _clamp(a, lower, cap)
    ratio_b, ratio_a = b / delta, a / delta
    squared = (ratio_b * ratio_b) + (ratio_a * ratio_a)
    if squared > 9.:
        factor = 3. / math.sqrt(squared)
        b = b * factor
        a = a * factor
    return a, b
