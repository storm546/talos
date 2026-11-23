package jumpscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.atan

/** Hand-checked numbers for SPEC 9.3. Poses are built in code; aspect 1 unless stated. */
class FieldTestsTest {
    private fun pose(points: Map<Int, Pair<Double, Double>>, hidden: Set<Int> = emptySet(), t: Long = 0): Frame {
        val marks = List(33) { i ->
            val (x, y) = points[i] ?: (0.5 to 0.5)
            Landmark(x.toFloat(), y.toFloat(), 0f, when { i in hidden -> 0.3f; i !in points -> 0.1f; else -> 0.9f })
        }
        return Frame(t, marks, marks)
    }

    @Test
    fun jointAnglesFromThreeLandmarks() {
        val knee = pose(mapOf(23 to (0.5 to 0.3), 25 to (0.5 to 0.5), 27 to (0.6 to 0.7)))
        // Thigh straight up, shank 0.1 across and 0.2 down: flexion is atan(0.5) = 26.565 degrees.
        assertEquals(Math.toDegrees(atan(0.5)), RangeOfMotion.angle(knee, 1f, RangeOfMotion.Joint.KNEE_FLEXION, 0)!!, 1e-4)
        val right = pose(mapOf(24 to (0.5 to 0.3), 26 to (0.5 to 0.5), 28 to (0.7 to 0.5)))
        assertEquals(90.0, RangeOfMotion.angle(right, 1f, RangeOfMotion.Joint.KNEE_FLEXION, 1)!!, 1e-4)
        // Aspect scales x: a 0.2-wide shank at aspect 0.5 is still perpendicular to a vertical thigh.
        assertEquals(90.0, RangeOfMotion.angle(right, .5f, RangeOfMotion.Joint.KNEE_FLEXION, 1)!!, 1e-4)
        val hip = pose(mapOf(11 to (0.5 to 0.1), 23 to (0.5 to 0.4), 25 to (0.7 to 0.4)))
        assertEquals(90.0, RangeOfMotion.angle(hip, 1f, RangeOfMotion.Joint.HIP_FLEXION, 0)!!, 1e-4)
        val overhead = pose(mapOf(23 to (0.5 to 0.6), 11 to (0.5 to 0.3), 13 to (0.5 to 0.1)))
        assertEquals(180.0, RangeOfMotion.angle(overhead, 1f, RangeOfMotion.Joint.SHOULDER_FLEXION, 0)!!, 1e-4)
        val level = pose(mapOf(23 to (0.5 to 0.6), 11 to (0.5 to 0.3), 13 to (0.7 to 0.3)))
        assertEquals(90.0, RangeOfMotion.angle(level, 1f, RangeOfMotion.Joint.SHOULDER_ABDUCTION, 0)!!, 1e-4)
        assertNull(RangeOfMotion.angle(pose(mapOf(23 to (0.5 to 0.6), 11 to (0.5 to 0.3), 13 to (0.7 to 0.3)), setOf(13)),
            1f, RangeOfMotion.Joint.SHOULDER_ABDUCTION, 0))
    }

    @Test
    fun heldAngleIsTheMedianOfTheHighestStillSecond() {
        // Rise 0..90 in 10-degree steps every 100 ms, hold 88/92 alternately from 1.0 to 2.0 s, then drop.
        val samples = (0..9).map { it * 100L to it * 10.0 } +
            (10..20).map { it * 100L to if (it % 2 == 0) 88.0 else 92.0 } + (21..25).map { it * 100L to 80.0 - (it - 21) * 10 }
        assertEquals(90.0, RangeOfMotion.held(samples)!!, 1e-9)
        assertNull(RangeOfMotion.held((0..30).map { it * 100L to it * 6.0 })) // never still
        assertNull(RangeOfMotion.held((0..8).map { it * 100L to 45.0 })) // still for 0.8 s only
        assertTrue(RangeOfMotion.stillNow((0..12).map { it * 100L to 45.0 + it % 2 }))
        assertFalse(RangeOfMotion.stillNow((0..12).map { it * 100L to 45.0 + it }))
    }

    /** Side view: shoulders and hips stacked, knee bent to 60 degrees and held 1.5 s; the far leg hidden. */
    private fun sideKnee(t: Long, flexion: Double, side: Int = 0): Frame {
        val shank = Math.toRadians(flexion)
        val far = if (side == 0) setOf(24, 26, 28) else setOf(23, 25, 27)
        return pose(mapOf(11 to (0.5 to 0.2), 12 to (0.505 to 0.2), 23 to (0.5 to 0.5), 24 to (0.505 to 0.5),
            23 + side to (0.5 to 0.5), 25 + side to (0.5 to 0.7), 27 + side to (0.5 + 0.2 * kotlin.math.sin(shank) to 0.7 + 0.2 * kotlin.math.cos(shank))),
            far, t)
    }

    @Test
    fun rangeOfMotionMeasuresTheNearSideAndAbstainsInTheWrongView() {
        val frames = (0..9).map { sideKnee(it * 66L, it * 6.0) } + (10..32).map { sideKnee(it * 66L, 60.0) }
        val result = RangeOfMotion.analyze(frames, 1f, RangeOfMotion.Joint.KNEE_FLEXION)
        assertEquals("side", result.view)
        assertEquals(60.0, result.leftDeg!!, 1e-4)
        assertNull(result.rightDeg)
        assertEquals(emptyList<String>(), result.warnings)
        val other = RangeOfMotion.analyze((0..32).map { sideKnee(it * 66L, 50.0, side = 1) }, 1f, RangeOfMotion.Joint.KNEE_FLEXION)
        val both = RangeOfMotion.merge(result, other)
        assertEquals(60.0, both.leftDeg!!, 1e-4); assertEquals(50.0, both.rightDeg!!, 1e-4)
        assertFalse(RangeOfMotion.withinError(60.0, 50.0)); assertTrue(RangeOfMotion.withinError(60.0, 52.0))
        assertTrue(both.toJson().contains("\"left_deg\":60.0,\"right_deg\":50.0,\"error_deg\":10.0"))
        // A front view (shoulders 0.2 apart) cannot show knee flexion: abstain.
        val front = (0..20).map { pose(mapOf(11 to (0.4 to 0.2), 12 to (0.6 to 0.2), 23 to (0.42 to 0.5), 24 to (0.58 to 0.5),
            25 to (0.42 to 0.7), 26 to (0.58 to 0.7), 27 to (0.42 to 0.9), 28 to (0.58 to 0.9)), t = it * 66L) }
        val refused = RangeOfMotion.analyze(front, 1f, RangeOfMotion.Joint.KNEE_FLEXION)
        assertEquals(listOf("wrong_view"), refused.warnings)
        assertNull(refused.leftDeg); assertNull(refused.rightDeg)
        val shoulders = RangeOfMotion.analyze(front, 1f, RangeOfMotion.Joint.SHOULDER_ABDUCTION)
        assertEquals(listOf("low_visibility", "not_validated"), shoulders.warnings) // elbows not visible: no angle
        assertNull(shoulders.leftDeg)
    }

    /** Front view at 25 fps; leg length 0.4 (hip y 0.5, ankle y 0.9). */
    private fun stance(t: Long, leftLift: Double, hipShift: Double = 0.0, hidden: Boolean = false) = pose(mapOf(
        23 to (0.45 + hipShift to 0.5), 24 to (0.55 + hipShift to 0.5), 25 to (0.45 to 0.7 - leftLift), 26 to (0.55 to 0.7),
        27 to (0.45 to 0.9 - leftLift), 28 to (0.55 to 0.9)),
        if (hidden) setOf(23, 24, 27, 28) else emptySet(), t)

    private fun run(frames: List<Frame>, stop: Boolean = false): Balance.Result? {
        val tracker = Balance.Tracker(1f)
        frames.forEach { tracker.feed(it) }
        return if (stop) tracker.stop() else tracker.result
    }

    @Test
    fun balanceStanceEndsAtTouchDownWithSway() {
        // Left foot lifted 0.1 (25% of leg length) from 440 ms to 5440 ms; hips sway +-0.01 every frame.
        val frames = (0..150).map { i ->
            val t = i * 40L
            stance(t, if (t in 440..5440) 0.1 else 0.0, if (i % 2 == 0) 0.01 else -0.01)
        }
        val result = run(frames)!!
        assertEquals("R", result.leg) // standing on the right leg
        assertEquals("touch_down", result.endedBy)
        assertEquals(5480 - 440L, result.stanceMs) // touch-down is the first low frame, confirmed 200 ms later
        assertEquals(2.5, result.swayPct!!, 0.01) // RMS 0.01 / leg 0.4
        assertEquals(25.0, result.fps, 1e-9)
        assertTrue(result.toJson().startsWith("{\"kind\":\"balance\",\"leg\":\"R\",\"stance_ms\":5040,"))
    }

    @Test
    fun balanceEndsOnSwayLostTrackingLimitOrStop() {
        val sway = run((0..120).map { i -> val t = i * 40L; stance(t, if (t >= 440) 0.1 else 0.0, if (t >= 3000) 0.12 else 0.0) })!!
        assertEquals("sway", sway.endedBy); assertEquals(3000 - 440L, sway.stanceMs)
        val lost = run((0..80).map { i -> val t = i * 40L; stance(t, if (t >= 440) 0.1 else 0.0, hidden = t >= 2000) })!!
        assertEquals("lost_tracking", lost.endedBy); assertEquals(1960 - 440L, lost.stanceMs)
        val limit = run((0..1550).map { i -> val t = i * 40L; stance(t, if (t >= 440) 0.1 else 0.0) })!!
        assertEquals("time_limit", limit.endedBy); assertEquals(60_000L, limit.stanceMs)
        assertEquals(0.0, limit.swayPct!!, 1e-9)
        val stopped = run((0..60).map { i -> val t = i * 40L; stance(t, if (t >= 440) 0.1 else 0.0) }, stop = true)!!
        assertEquals("stopped", stopped.endedBy); assertEquals(2400 - 440L, stopped.stanceMs)
        // A lift shorter than 300 ms never starts the clock.
        assertNull(run((0..60).map { i -> val t = i * 40L; stance(t, if (t in 440..680) 0.1 else 0.0) }, stop = true))
        // A bent standing knee (a squat) never starts the clock, even with one ankle higher.
        assertNull(run((0..60).map { i -> val t = i * 40L
            pose(mapOf(23 to (0.45 to 0.6), 24 to (0.55 to 0.6), 25 to (0.4 to 0.75), 26 to (0.65 to 0.75),
                27 to (0.45 to 0.8), 28 to (0.55 to 0.9)), t = t) }, stop = true))
        // Raised less than 8% of leg length (0.03 / 0.4 = 7.5%) is not a single-leg stance.
        assertNull(run((0..60).map { i -> stance(i * 40L, 0.03) }, stop = true))
    }

    @Test
    fun lineCrossingActivityAndOnePassagePerCrossing() {
        val previous = ByteArray(100) { 10 }
        val current = ByteArray(100) { i -> when { i < 30 -> 40; i < 40 -> 30; else -> 10 }.toByte() }
        assertEquals(0.3, LineCrossing.activity(previous, current), 1e-12) // 30 changed by 30 levels; 10 by only 20
        assertEquals(1.0, LineCrossing.activity(ByteArray(4) { 250.toByte() }, ByteArray(4) { 10 }), 1e-12) // unsigned bytes
        val detector = LineCrossing.Detector()
        val activity = listOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.2, 0.3, 0.0, 0.0, 0.0, 0.06, 0.0, 0.0, 0.0, 0.0, 0.13)
        val crossings = activity.mapIndexedNotNull { i, a -> detector.feed(i * 100L, a) }
        // Busy at 0 ms is ignored (never quiet before); armed at 400; fires at 500; the 0.06 at 1000 restarts the quiet clock.
        assertEquals(listOf(500L, 1500L), crossings)
        assertEquals(33.0, LineCrossing.precisionMs(listOf(0, 33, 67, 100)), 1e-12)
    }

    @Test
    fun reactionTimeFalseStartAndTooSlow() {
        assertEquals(LineCrossing.Reaction.Time(66), LineCrossing.reaction(1000, listOf(900L to 0.0, 1033L to 0.01, 1066L to 0.05)))
        assertEquals(LineCrossing.Reaction.FalseStart, LineCrossing.reaction(1000, listOf(950L to 0.04, 1033L to 0.2)))
        assertEquals(LineCrossing.Reaction.TooSlow, LineCrossing.reaction(1000, listOf(1033L to 0.0, 2600L to 0.2)))
        assertEquals("{\"kind\":\"reaction\",\"time_ms\":66,\"precision_ms\":33.0,\"fps\":30.0}", LineCrossing.json("reaction", 66, 33.0, 30.0))
    }

    @Test
    fun gpsDistancePaceAndSplits() {
        val degree = GpsTrack.EARTH_M * Math.PI / 180 // 111 194.93 m per degree of latitude
        assertEquals(111_194.93, GpsTrack.haversine(GpsTrack.Fix(0, 0.0, 0.0, 5f), GpsTrack.Fix(0, 1.0, 0.0, 5f)), 0.01)
        // 0.0001 degree (11.1195 m) every 4 s: 99 steps = 1100.83 m in 396 s.
        val recorder = GpsTrack.Recorder("run", 1_000)
        (0..99).forEach { recorder.add(GpsTrack.Fix(1_000 + it * 4_000L, it * 0.0001, 23.0, 5f)) }
        val summary = recorder.summary(1_000 + 99 * 4_000L)
        assertEquals(99 * degree * 0.0001, summary.distanceM, 1e-6)
        assertEquals(396L, summary.durationS)
        // The first kilometre is passed after 1000 / 11.1195 = 89.93 steps of 4 s.
        assertEquals(1, summary.splitsS.size)
        assertEquals(1000 / (degree * 0.0001) * 4, summary.splitsS[0], 1e-6)
        assertEquals(396 / (summary.distanceM / 1000), summary.paceSPerKm!!, 1e-9)
        assertEquals(359.73, summary.splitsS[0], 0.01)
    }

    @Test
    fun gpsFiltersInaccurateStillAndImpossibleFixes() {
        val step = 1 / (GpsTrack.EARTH_M * Math.PI / 180) // one metre of latitude in degrees
        val recorder = GpsTrack.Recorder("run", 0)
        assertTrue(recorder.add(GpsTrack.Fix(0, 0.0, 0.0, 5f)))
        assertFalse(recorder.add(GpsTrack.Fix(1_000, 50 * step, 0.0, 30f))) // accuracy above 25 m
        assertFalse(recorder.add(GpsTrack.Fix(2_000, 2 * step, 0.0, 5f)))   // closer than 5 m: held back
        assertTrue(recorder.add(GpsTrack.Fix(3_000, 6 * step, 0.0, 5f)))    // measured from the last accepted fix
        assertFalse(recorder.add(GpsTrack.Fix(4_000, 106 * step, 0.0, 5f))) // 100 m in 1 s is faster than 12 m/s
        assertEquals(6.0, recorder.distanceM, 1e-6)
        assertNull(recorder.summary(10_000).paceSPerKm) // under 10 m
        assertTrue(GpsTrack.Recorder("cycle", 0).run { add(GpsTrack.Fix(0, 0.0, 0.0, 5f)); add(GpsTrack.Fix(1_000, 20 * step, 0.0, 5f)) })
    }

    /** Replay of a real Pose Landmarker Lite clip (front view, wide squats, both shoes planted; see test resources README). */
    @Test
    fun replayOfARealTwoFootedClipStartsNoStanceAndRefusesSideViewAngles() {
        val (aspect, frames) = java.util.zip.GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")))
            .bufferedReader().use(::readLandmarkCsv)
        val tracker = Balance.Tracker(aspect)
        frames.forEach { tracker.feed(it) }
        assertNull(tracker.stop()) // both feet stay on the floor: no single-leg stance is timed
        assertEquals(listOf("wrong_view"), RangeOfMotion.analyze(frames, aspect, RangeOfMotion.Joint.KNEE_FLEXION).warnings)
        val shoulders = RangeOfMotion.analyze(frames, aspect, RangeOfMotion.Joint.SHOULDER_ABDUCTION)
        println("replay shoulder abduction: left=${shoulders.leftDeg} right=${shoulders.rightDeg} quality=${shoulders.quality} ${shoulders.warnings}")
        assertEquals("front", shoulders.view)
    }
}
