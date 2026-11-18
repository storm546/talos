"""Transition and process-noise matrices (flat, row-major) for the three-component state."""
import math


def normalized_scale(value):
    return max(abs(value), 25.0) if math.isfinite(value) else 25.0


def transition(value):
    scale = normalized_scale(value)
    decay_rate = math.log(2.0) / 30.0
    fast_rate = math.log(2.0) / 2.0
    slow = math.exp(-decay_rate)
    fast = math.exp(-fast_rate)
    integrated = (1.0 - slow) / decay_rate
    slow_sigma = min(max(scale * 0.0008, 0.04), 0.12)
    fast_sigma = min(max(scale * 0.01, 0.6), 1.4)
    # Product with max(0.0, -2.0) is kept exactly as written.
    noise0 = ((0.001 * scale) * max(0.0, -2.0)) ** 2 + 0.0025000000000000005
    array_f = (1.0, integrated, 0.0, 0.0, slow, 0.0, 0.0, 0.0, fast)
    array_q = (noise0, 0.0, 0.0, 0.0, slow_sigma ** 2 * (1.0 - slow ** 2),
               0.0, 0.0, 0.0, fast_sigma ** 2 * (1.0 - fast ** 2))
    return array_f, array_q
