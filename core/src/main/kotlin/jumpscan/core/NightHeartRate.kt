package jumpscan.core

/**
 * Resting heart rate estimated from the heart rate measured during sleep (SPEC 10.5), for phones whose health app
 * shares heart rate but not a resting value. Every window of [WINDOW_MS] that starts at a sample holds the samples
 * from that time up to (not including) the window end; windows with fewer than [MIN_SAMPLES] samples are ignored.
 * The estimate is the lowest window mean, rounded to a whole beat; null when no window qualifies.
 * Both constants are design choices, not checked against a reference measurement.
 */
object NightHeartRate {
    const val WINDOW_MS = 5 * 60_000L
    const val MIN_SAMPLES = 3

    /** [samples] are (epoch milliseconds, beats per minute) pairs in any order; non-finite or non-positive rates are dropped. */
    fun restingEstimate(samples: List<Pair<Long, Double>>): Int? {
        val sorted = samples.filter { it.second.isFinite() && it.second > 0 }.sortedBy { it.first }
        var lowest: Double? = null
        for (start in sorted.indices) {
            val window = sorted.subList(start, sorted.size).takeWhile { it.first < sorted[start].first + WINDOW_MS }
            if (window.size < MIN_SAMPLES) continue
            val mean = window.sumOf { it.second } / window.size
            if (lowest == null || mean < lowest) lowest = mean
        }
        return lowest?.let { Math.round(it).toInt() }
    }
}
