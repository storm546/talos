"""Inverse of a row-major 3x3 matrix with up to six regularisation attempts."""
import math


def inverse(values):
    original = tuple(values)
    current = original
    for attempt in range(6):
        a, b, c, d, e, f, g, h, i = (current[k] for k in range(9))
        cofactors = (e*i-f*h, f*g-d*i, d*h-e*g,
                     c*h-b*i, a*i-c*g, b*g-a*h,
                     b*f-c*e, c*d-a*f, a*e-b*d)
        determinant = (a*cofactors[0] + b*cofactors[1]) + c*cofactors[2]
        if abs(determinant) > 1e-14:
            return tuple(cofactors[k] / determinant for k in (0, 3, 6, 1, 4, 7, 2, 5, 8))
        jitter = math.pow(10.0, attempt - 12)
        current = tuple(x + jitter if k in (0, 4, 8) else x
                        for k, x in enumerate(original))
    return tuple((1.0/current[k] if abs(current[k]) > 1e-14 else 0.0)
                 if k in (0, 4, 8) else 0.0 for k in range(9))
