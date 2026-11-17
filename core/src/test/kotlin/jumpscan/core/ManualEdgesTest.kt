package jumpscan.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.cos
import kotlin.math.sin

class ManualEdgesTest {
    private fun frames(): List<Frame> = List(51) { index ->
        val time = index * 40L
        val points = MutableList(33) { Landmark(0.5f, 0.5f, 0f, 1f) }
        val world = points.toMutableList()
        val flexion = Math.toRadians(if (time in 800..1320) 60.0 else 10.0)
        for (side in 0..1) {
            val x = if (side == 0) 0.4f else 0.6f
            for ((joint, y) in listOf(23 to 0.3f, 25 to 0.55f, 27 to 0.8f, 29 to 0.82f, 31 to 0.84f))
                points[joint + side] = Landmark(x, y, 0f, 1f)
            world[23 + side] = Landmark(x, 0f, 0f, 1f)
            world[25 + side] = Landmark(x, 0.4f, 0f, 1f)
            world[27 + side] = Landmark(x, (0.4 + 0.4 * cos(flexion)).toFloat(), (0.4 * sin(flexion)).toFloat(), 1f)
        }
        Frame(time, points, world)
    }

    @Test
    fun manualFlightAndHeightAreExactForBothTests() {
        for (test in listOf("cmj", "drop_jump")) {
            val result = JumpAnalyzer.analyzeWithLanding(frames(), test, takeoffMs = 400, landingMs = 800)
            assertEquals(400.0, result.metrics.flightTimeMs!!, 0.0)
            assertEquals(9.81 * 0.4 * 0.4 / 8 * 100, result.metrics.jumpHeightCm!!, 1e-10)
            assertEquals(400L, result.takeoffMs)
            assertEquals(800L, result.contactMs)
            assertEquals(listOf("manual_edges"), result.metrics.warnings)
            assertEquals(result.metrics, JumpAnalyzer.analyze(frames(), test, takeoffMs = 400, landingMs = 800))
        }
        assertEquals(1000.0, JumpAnalyzer.analyze(frames(), "cmj", takeoffMs = 0, landingMs = 1000).flightTimeMs!!, 0.0)
    }

    @Test
    fun landingWindowFollowsTheMarkedContact() {
        val early = JumpAnalyzer.analyze(frames(), "cmj", takeoffMs = 400, landingMs = 800)
        val late = JumpAnalyzer.analyze(frames(), "cmj", takeoffMs = 1000, landingMs = 1400)
        assertEquals(60.0, early.kneeFlexionContactL!!, 1e-4)
        assertEquals(60.0, early.kneeFlexionPeakR!!, 1e-4)
        assertEquals(10.0, late.kneeFlexionContactL!!, 1e-4)
        assertEquals(10.0, late.kneeFlexionPeakR!!, 1e-4)
    }

    @Test
    fun manualFlightDoesNotNeedTrackedFootPointsAndKeepsQualityWarnings() {
        val hidden = frames().map { frame -> frame.copy(landmarks = frame.landmarks.mapIndexed { i, point ->
            when (i) {
                in 29..32 -> point.copy(visibility = 0f)
                in 23..28 -> point.copy(visibility = 0.6f)
                else -> point
            }
        }) }
        val result = JumpAnalyzer.analyze(hidden, "cmj", takeoffMs = 400, landingMs = 800)
        assertEquals(setOf("low_visibility", "manual_edges"), result.warnings.toSet())
        assertEquals(400.0, result.flightTimeMs!!, 0.0)
    }

    @Test
    fun invalidEdgesAreRejected() {
        for ((takeoff, landing) in listOf(400L to null, null to 800L, 401L to 800L,
                400L to 801L, -40L to 800L, 400L to 2040L, 800L to 800L, 800L to 400L, 0L to 1040L)) {
            assertThrows<IllegalArgumentException> {
                JumpAnalyzer.analyzeWithLanding(frames(), "cmj", takeoffMs = takeoff, landingMs = landing)
            }
        }
    }

    @Test
    fun detectedTakeoffIsTheStartOfTheReportedFlight() {
        val jump = frames().map { frame ->
            val elapsed = (frame.tMs - 600) / 1000.0
            val lift = if (elapsed in 0.0..0.4) (9.81 * elapsed * (0.4 - elapsed) / 2 / 1.8).toFloat() else 0f
            frame.copy(landmarks = frame.landmarks.mapIndexed { i, point ->
                if (i in 23..32) point.copy(y = point.y - lift) else point
            })
        }
        val result = JumpAnalyzer.analyzeWithLanding(jump, "cmj")
        assertNotNull(result.takeoffMs)
        assertEquals(result.metrics.flightTimeMs!!, (result.contactMs!! - result.takeoffMs!!).toDouble(), 0.0)
        val manual = JumpAnalyzer.analyzeWithLanding(jump, "cmj", takeoffMs = 480, landingMs = 1040)
        assertEquals(560.0, manual.metrics.flightTimeMs!!, 0.0)
        assertEquals(listOf("manual_edges"), manual.metrics.warnings)
    }

    @Test
    fun defaultAndExplicitAutomaticPathsMatch() {
        val automatic = JumpAnalyzer.analyzeWithLanding(frames(), "cmj")
        assertEquals(automatic, JumpAnalyzer.analyzeWithLanding(frames(), "cmj", takeoffMs = null, landingMs = null))
        assertNull(automatic.takeoffMs)
        assertNull(automatic.contactMs)
        assertTrue("no_flight_detected" in automatic.metrics.warnings)
        assertFalse("manual_edges" in automatic.metrics.warnings)
    }
}
