package jumpscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Hand-computed with m = 0.5^(1/13) = 0.948077514 (daily) and 0.5^(sqrt(3/7)/13) = 0.965696682 (three a week). */
class ConsistencyTest {
    private val daily = Math.pow(0.5, 1.0 / 13)

    @Test
    fun oneDoneDayFromZero() {
        assertEquals(0.051922486, Consistency.step(1.0, 0.0, true), 1e-9)
        assertEquals(0.0, Consistency.step(1.0, 0.0, false), 1e-12)
        assertEquals(1.0 - daily, Consistency.score(listOf(true)), 1e-12)
    }

    @Test
    fun streaksAndMisses() {
        assertEquals(0.147819604, Consistency.score(List(3) { true }), 1e-9)
        assertEquals(0.098593062, Consistency.score(listOf(true, false, true)), 1e-9)
        assertEquals(0.525961243, Consistency.score(List(14) { true }), 1e-9)
        assertEquals(0.451878437, Consistency.score(List(14) { it != 4 && it != 9 }), 1e-9)
        assertEquals(45, Consistency.percent(0.451878437))
    }

    @Test
    fun frequencySlowsTheChange() {
        assertEquals(0.034303318, Consistency.score(listOf(true)) { 3.0 / 7 }, 1e-9)
        assertEquals(0.216777467, Consistency.score(List(7) { true }) { 3.0 / 7 }, 1e-9)
        assertEquals(0, Consistency.percent(Consistency.score(emptyList())))
    }
}
