"""Row-major 3x3 products and the backward smoothing step."""
import math

from .weight_inverse import inverse
from .weight_vector_difference import difference


def matrix_product(left, right):
    return [((left[3*r] * right[c]) + (left[3*r+1] * right[3+c]))
            + (left[3*r+2] * right[6+c]) for r in range(3) for c in range(3)]


def matrix_vector(matrix, vector):
    return [((matrix[3*r] * vector[0]) + (matrix[3*r+1] * vector[1]))
            + (matrix[3*r+2] * vector[2]) for r in range(3)]


def _at(values, index):
    if index < 0 or index >= len(values):
        raise IndexError(index)
    return values[index]


def smooth_state_to_horizon(history, start, end):
    state = list(_at(history[0x13], end))
    for k in range(end - 1, start - 1, -1):
        transition = _at(history[0x1f], k + 1)
        covariance = _at(history[0x0f], k)
        transposed = [transition[j] for j in (0, 3, 6, 1, 4, 7, 2, 5, 8)]
        gain = matrix_product(matrix_product(covariance, transposed),
                              inverse(_at(history[0x17], k + 1)))
        stored = _at(history[0x13], k)
        delta = difference(state, _at(history[0x1b], k + 1))
        correction = matrix_vector(gain, delta)
        state = [stored[j] + correction[j] for j in range(3)]
    return state
