package jumpscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NightHeartRateTest {
    private fun minute(value: Int) = value * 60_000L

    @Test
    fun lowestFiveMinuteMeanIsTheEstimate() {
        // One sample a minute, so each full window holds five samples.
        val rates = listOf(60, 59, 58, 57, 56, 55, 54, 53, 52, 51, 50, 49, 51, 50, 52, 58, 61)
        val samples = rates.mapIndexed { i, bpm -> minute(i) to bpm.toDouble() }
        // Lowest window is minutes 9-13: (51+50+49+51+50)/5 = 50.2 -> 50 (minutes 10-14 give 50.4).
        assertEquals(50, NightHeartRate.restingEstimate(samples))
    }

    @Test
    fun roundsHalfUpAndIgnoresOrder() {
        // Window 0-4 min: 48, 49 and 49 at minutes 0, 2 and 4 -> 48.67 -> 49; any later window has fewer than three.
        val samples = listOf(minute(4) to 49.0, minute(0) to 48.0, minute(2) to 49.0)
        assertEquals(49, NightHeartRate.restingEstimate(samples))
        // 48.5 rounds up to 49.
        assertEquals(49, NightHeartRate.restingEstimate(listOf(0L to 48.0, 1000L to 49.0, 2000L to 48.0, 3000L to 49.0)))
    }

    @Test
    fun sparseOrInvalidSamplesGiveNoEstimate() {
        // Samples ten minutes apart never put three in one window.
        assertNull(NightHeartRate.restingEstimate((0..6).map { minute(it * 10) to 50.0 }))
        // A window ends before its fifth minute: samples at 0, 3 and 5 minutes are not one window.
        assertNull(NightHeartRate.restingEstimate(listOf(0L to 50.0, minute(3) to 50.0, minute(5) to 50.0)))
        assertNull(NightHeartRate.restingEstimate(listOf(0L to Double.NaN, 1L to 0.0, 2L to -4.0, 3L to 55.0)))
        assertNull(NightHeartRate.restingEstimate(emptyList()))
    }
}
