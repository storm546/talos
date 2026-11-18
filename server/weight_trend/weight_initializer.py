"""Initial level and flag: median of observations within the first 7 days when at least 3 exist."""
import statistics

DAY_US = 86_400_000_000


def initialize(entries):
    anchor, fallback = entries[0]
    accepted = []
    for date, value in entries:
        difference = date - anchor
        days = (abs(difference) // DAY_US) * (1 if difference >= 0 else -1)
        if days < 7:
            accepted.append(value)
    enough = len(accepted) >= 3
    return enough, statistics.median(accepted) if enough else fallback
