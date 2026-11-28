package jumpscan.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KneeCheckTest {
    private fun m(ratio: Double?, vararg warnings: String) = KneeCheck.Mark(ratio, warnings.toList())

    @Test
    fun needsThreeUsableFrontJumpsWithKneesCavingIn() {
        assertTrue(KneeCheck.suggestCheckup(listOf(m(0.5), m(0.55), m(0.9))))
        assertFalse(KneeCheck.suggestCheckup(listOf(m(0.5), m(0.5))))
        assertFalse(KneeCheck.suggestCheckup(listOf(m(0.5), m(0.9), m(1.1))))
    }

    @Test
    fun ignoresSideViewAndUnreliableJumps() {
        assertFalse(KneeCheck.suggestCheckup(listOf(m(0.5), m(null), m(0.5, "uncertain_timing"), m(1.2), m(1.2))))
        assertTrue(KneeCheck.suggestCheckup(listOf(m(0.5), m(null), m(0.5, "manual_edges"), m(0.4))))
    }

    @Test
    fun onlyTheNewestJumpsCount() {
        assertFalse(KneeCheck.suggestCheckup(listOf(m(1.1), m(1.2), m(1.0), m(0.4), m(0.4), m(0.4))))
    }
}
