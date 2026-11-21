package jumpscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GymMathTest {
    private val standard = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    @Test
    fun loadsTheFewestPlatesPerSide() {
        val load = GymMath.plates(100.0, 20.0, standard)
        assertTrue(load.exact)
        assertEquals(listOf(25.0, 15.0), load.plates)
        assertEquals(100.0, load.total)
        assertEquals(listOf(25.0, 25.0, 20.0, 2.5), GymMath.plates(165.0, 20.0, standard).plates)
        assertEquals(listOf(1.25), GymMath.plates(12.5, 10.0, standard).plates)
    }

    @Test
    fun emptyBarAndFractionalPlates() {
        assertEquals(emptyList<Double>(), GymMath.plates(20.0, 20.0, standard).plates)
        assertTrue(GymMath.plates(20.0, 20.0, standard).exact)
        val micro = GymMath.plates(21.0, 20.0, listOf(0.5, 0.25))
        assertTrue(micro.exact)
        assertEquals(listOf(0.5), micro.plates)
    }

    @Test
    fun findsLoadsGreedyChoiceMisses() {
        // Greedy would take 15 and be stuck with 5 left; two tens per side are right.
        val load = GymMath.plates(60.0, 20.0, listOf(15.0, 10.0))
        assertTrue(load.exact)
        assertEquals(listOf(10.0, 10.0), load.plates)
    }

    @Test
    fun impossibleLoadsReportTheNearestOnBothSides() {
        val odd = GymMath.plates(101.0, 20.0, standard)
        assertFalse(odd.exact)
        assertEquals(100.0, odd.total)
        assertEquals(102.5, odd.above)
        val belowBar = GymMath.plates(15.0, 20.0, standard)
        assertFalse(belowBar.exact)
        assertEquals(20.0, belowBar.total)
        assertEquals(emptyList<Double>(), belowBar.plates)
        val noPlates = GymMath.plates(60.0, 20.0, emptyList())
        assertFalse(noPlates.exact)
        assertEquals(20.0, noPlates.total)
        assertNull(noPlates.above)
        val onlyTwenties = GymMath.plates(70.0, 20.0, listOf(20.0))
        assertEquals(60.0, onlyTwenties.total)
        assertEquals(100.0, onlyTwenties.above)
    }

    @Test
    fun oneRepMaxFormulas() {
        assertEquals(100.0, GymMath.epley(100.0, 1), 1e-9)
        assertEquals(100.0, GymMath.brzycki(100.0, 1)!!, 1e-9)
        assertEquals(116.667, GymMath.epley(100.0, 5), 1e-3)
        assertEquals(112.5, GymMath.brzycki(100.0, 5)!!, 1e-9)
        // The two formulas meet at ten repetitions.
        assertEquals(GymMath.epley(80.0, 10), GymMath.brzycki(80.0, 10)!!, 1e-9)
        assertNull(GymMath.brzycki(50.0, 37))
    }

    @Test
    fun percentageTableRoundsDown() {
        val table = GymMath.percentTable(133.3)
        assertEquals(11, table.size)
        assertEquals(100 to 132.5, table.first())
        assertEquals(50 to 65.0, table.last())
        assertEquals(90 to 117.5, table[2])
    }

    @Test
    fun warmUpSetsClimbToTheWorkingLoad() {
        assertEquals(listOf(GymMath.WarmupSet(20.0, 10), GymMath.WarmupSet(40.0, 5), GymMath.WarmupSet(60.0, 3),
            GymMath.WarmupSet(80.0, 2)), GymMath.warmup(100.0, 20.0))
        // Light work: steps that round to the bar or repeat are dropped.
        assertEquals(listOf(GymMath.WarmupSet(20.0, 10), GymMath.WarmupSet(25.0, 2)), GymMath.warmup(30.0, 20.0))
        assertEquals(emptyList<GymMath.WarmupSet>(), GymMath.warmup(20.0, 20.0))
        assertEquals(listOf(GymMath.WarmupSet(20.0, 10), GymMath.WarmupSet(57.5, 5), GymMath.WarmupSet(85.0, 3),
            GymMath.WarmupSet(115.0, 2)), GymMath.warmup(143.0, 20.0))
    }

    @Test
    fun intervalPlans() {
        val tabata = IntervalPlan.tabata()
        assertEquals(IntervalPlan.Kind.READY, tabata.first().kind)
        assertEquals(8, tabata.count { it.kind == IntervalPlan.Kind.WORK })
        assertEquals(7, tabata.count { it.kind == IntervalPlan.Kind.REST })
        assertEquals(5 + 8 * 20 + 7 * 10, IntervalPlan.total(tabata))
        assertEquals(5 + 10 * 60, IntervalPlan.total(IntervalPlan.emom(10)))
        assertEquals(listOf(IntervalPlan.Kind.READY, IntervalPlan.Kind.WORK), IntervalPlan.amrap(12).map { it.kind })
        assertEquals(3, IntervalPlan.intervals(40, 20, 3).last().round)
    }
}
