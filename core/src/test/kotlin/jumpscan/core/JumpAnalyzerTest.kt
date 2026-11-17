package jumpscan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val ASPECT = 9.0 / 16.0
private const val GROUND_Y = 0.85
private const val SEGMENT = 0.25      // thigh and shank length, in image heights
private const val HIP_SEPARATION = 0.16

private const val WORLD_SEGMENT = 0.4   // thigh and shank length in metres
private const val WORLD_HIP_X = 0.09

/**
 * Synthetic person facing the camera. Image legs are built upwards from the ankles so feet stay planted.
 * [lift] = height of the feet above ground (image heights) at time t seconds.
 * [devL] / [devR] = frontal-plane knee deviation in degrees in the IMAGE landmarks: positive moves the knee
 * towards the midline, built as thigh rotated by +dev/2 and shank by -dev/2, so the FPPA is exactly dev.
 * [flexL] / [flexR] = 3D knee flexion in degrees in the WORLD landmarks: the knee bends towards the camera
 * (along z only), so it is independent of the image deviation.
 */
private fun clip(
    fps: Int,
    seconds: Double,
    lift: (Double) -> Double,
    devL: (Double) -> Double = { 0.0 },
    devR: (Double) -> Double = devL,
    flexL: (Double) -> Double = { 0.0 },
    flexR: (Double) -> Double = flexL,
    visibility: Float = 1f,
    noise: Double = 0.0,
): List<Frame> {
    val rnd = Random(42)
    fun jitter() = if (noise == 0.0) 0.0 else rnd.nextGaussian() * noise
    return (0 until (seconds * fps).toInt()).map { k ->
        val t = k.toDouble() / fps
        val points = Array(33) { doubleArrayOf(0.5 * ASPECT, 0.5) }
        // toMid = direction (in x) from this leg towards the body midline
        fun leg(hip: Int, ankleX: Double, toMid: Double, dev: Double) {
            val a = Math.toRadians(dev / 2)
            val ankleY = GROUND_Y - 0.04 - lift(t)
            val knee = doubleArrayOf(ankleX + toMid * SEGMENT * sin(a), ankleY - SEGMENT * cos(a))
            val hipXY = doubleArrayOf(knee[0] - toMid * SEGMENT * sin(a), knee[1] - SEGMENT * cos(a))
            points[hip] = hipXY
            points[hip + 2] = knee
            points[hip + 4] = doubleArrayOf(ankleX, ankleY)
            points[hip + 6] = doubleArrayOf(ankleX, ankleY + 0.02)   // heel
            points[hip + 8] = doubleArrayOf(ankleX, ankleY + 0.04)   // foot index
        }
        // person's left leg appears on the right of the image when facing the camera
        leg(23, 0.5 * ASPECT + HIP_SEPARATION / 2, -1.0, devL(t))
        leg(24, 0.5 * ASPECT - HIP_SEPARATION / 2, +1.0, devR(t))
        // world: hip at y = 0, knee bent along z by flex/2 either side of the knee, so thigh-shank angle = flex
        val world = Array(33) { doubleArrayOf(0.0, 0.0, 0.0) }
        fun worldLeg(hip: Int, x: Double, flex: Double) {
            val h = Math.toRadians(flex / 2)
            world[hip] = doubleArrayOf(x, 0.0, 0.0)
            world[hip + 2] = doubleArrayOf(x, WORLD_SEGMENT * cos(h), -WORLD_SEGMENT * sin(h))
            for (i in intArrayOf(4, 6, 8)) world[hip + i] = doubleArrayOf(x, 2 * WORLD_SEGMENT * cos(h), 0.0)
        }
        worldLeg(23, WORLD_HIP_X, flexL(t))
        worldLeg(24, -WORLD_HIP_X, flexR(t))
        Frame(
            (k * 1000L) / fps,
            points.map { Landmark(((it[0] + jitter()) / ASPECT).toFloat(), (it[1] + jitter()).toFloat(), 0f, visibility) },
            world.map { Landmark((it[0] + 2.5 * jitter()).toFloat(), (it[1] + 2.5 * jitter()).toFloat(), (it[2] + 2.5 * jitter()).toFloat(), visibility) },
        )
    }
}

/** Rise-and-fall flexion after contact at [t0]: 0 before, peak [peak] degrees 250 ms later, back to 0 at 500 ms. */
private fun landingDev(t0: Double, peak: Double): (Double) -> Double = { t ->
    val s = t - t0
    if (s < 0 || s > 0.5) 0.0 else peak * sin(PI * s / 0.5)
}

/** Parabolic jump: airborne from [t0] for [flight] seconds, apex = g t^2 / 8 metres scaled to image heights. */
private fun parabola(t0: Double, flight: Double, metresPerImageHeight: Double = 1.8): (Double) -> Double = { t ->
    val s = t - t0
    if (s < 0 || s > flight) 0.0 else 9.81 * s * (flight - s) / 2 / metresPerImageHeight
}

/** Rule-detector invariants remain independent of the learned proposal used by the public entry point. */
class JumpAnalyzerTest {
    private fun analyzeWithLanding(frames: List<Frame>, test: String, aspect: Float = 9f / 16f,
        view: JumpAnalyzer.View = JumpAnalyzer.View.AUTO) =
        JumpAnalyzer.analyzeRulesWithLanding(frames, test, aspect, view)

    private fun analyze(frames: List<Frame>, test: String, aspect: Float = 9f / 16f,
        view: JumpAnalyzer.View = JumpAnalyzer.View.AUTO) = analyzeWithLanding(frames, test, aspect, view).metrics

    private fun profileClip(rightVisible: Boolean): List<Frame> =
        clip(60, 2.0, parabola(0.3, 0.4), devL = landingDev(0.7, 60.0), flexL = { 15.0 }).map { f ->
            val near = if (rightVisible) 24 else 23
            val far = if (rightVisible) 23 else 24
            val hip = f.landmarks[near]
            f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                when (index) {
                    11 -> hip.copy(x = hip.x - 0.01f, y = hip.y - 0.3f)
                    12 -> hip.copy(x = hip.x + 0.01f, y = hip.y - 0.3f)
                    far -> hip.copy(x = hip.x + 0.01f)
                    far + 2, far + 4, far + 6, far + 8 -> point.copy(visibility = 0f)
                    else -> point
                }
            })
        }

    @Test
    fun profileMeasuresOnlyTheVisibleLegInTheImagePlane() {
        for (right in listOf(false, true)) {
            val m = analyze(profileClip(right), "cmj")
            assertEquals(400.0, m.flightTimeMs!!, 40.0)
            assertEquals(listOf("single_leg_view"), m.warnings)
            assertEquals(60.0, (if (right) m.kneeFlexionPeakR else m.kneeFlexionPeakL)!!, 1.0)
            assertNull(if (right) m.kneeFlexionPeakL else m.kneeFlexionPeakR)
            assertNull(if (right) m.kneeFlexionContactL else m.kneeFlexionContactR)
            assertNull(m.fppaPeakL)
            assertNull(m.fppaPeakR)
            assertNull(m.kneeAnkleRatio)
            assertNull(m.flexionAsymmetryPct)
            assertTrue(m.toJson().contains("\"knee_ankle_ratio\":null"))
            assertEquals(1.0, m.quality, 0.001)
        }
    }

    @Test
    fun sideAngleScalesXByAspectAndAllowsExplicitView() {
        val frames = profileClip(false)
        val wideAspect = 2.0f
        val wide = frames.map { f -> f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
            point.copy(x = (point.x * ASPECT / wideAspect).toFloat(),
                visibility = if (index in 11..12 || index == 24) 0f else point.visibility)
        }) }
        val m = analyze(wide, "cmj", wideAspect, JumpAnalyzer.View.SIDE)
        assertEquals(60.0, m.kneeFlexionPeakL!!, 1.0)
        assertTrue("single_leg_view" in m.warnings)
        assertThrows<IllegalArgumentException> {
            analyze(frames, "cmj", view = JumpAnalyzer.View.FRONT)
        }
    }

    @Test
    fun explicitViewChoosesKneePlaneWithoutChangingFrontMetrics() {
        val frames = clip(60, 2.0, parabola(0.3, 0.4), devL = { 60.0 }, flexL = { 15.0 })
        val auto = analyze(frames, "cmj")
        val front = analyze(frames, "cmj", view = JumpAnalyzer.View.FRONT)
        assertEquals(front, auto)
        assertEquals(15.0, front.kneeFlexionPeakL!!, 1.0)
        val side = analyze(frames, "cmj", view = JumpAnalyzer.View.SIDE)
        assertEquals(60.0, side.kneeFlexionPeakL!!, 1.0)
        assertNull(side.kneeFlexionPeakR)
    }

    @Test
    fun knownFlightTimeGivesHeight() {
        val m = analyze(clip(120, 2.0, parabola(0.8, 0.5)), "cmj")
        val expectedCm = 9.81 * 0.5 * 0.5 / 8 * 100   // 30.66
        assertEquals(500.0, m.flightTimeMs!!, 40.0)
        assertEquals(expectedCm, m.jumpHeightCm!!, 4.0)
        // height is exactly g t^2 / 8 of the reported flight time
        assertEquals(9.81 * (m.flightTimeMs!! / 1000).let { it * it } / 8 * 100, m.jumpHeightCm!!, 1e-9)
        assertTrue(m.warnings.isEmpty())
    }

    @Test
    fun medialKneeGivesPositiveFppaIndependentOfFlexion() {
        // From landing at t = 0.7 (jump 0.3 s + 0.4 s flight) for 0.4 s: 20 degrees frontal deviation, 45 degrees 3D flexion
        val held: (Double) -> (Double) -> Double = { v -> { t -> if (t >= 0.7 && t < 1.1) v else 0.0 } }
        val m = analyze(clip(60, 2.0, parabola(0.3, 0.4), held(20.0), flexL = held(45.0)), "cmj")
        assertEquals(45.0, m.kneeFlexionPeakL!!, 1.0)
        assertEquals(45.0, m.kneeFlexionPeakR!!, 1.0)
        assertEquals(20.0, m.fppaPeakL!!, 1.0)
        assertEquals(20.0, m.fppaPeakR!!, 1.0)
        assertEquals(45.0, m.kneeFlexionContactL!!, 1.0)
        // ankles 0.16 apart, each knee 0.25 * sin(10 degrees) towards the middle
        assertEquals((0.16 - 2 * 0.25 * sin(Math.toRadians(10.0))) / 0.16, m.kneeAnkleRatio!!, 0.02)
    }

    @Test
    fun lateralKneeGivesNegativeFppa() {
        val dev: (Double) -> Double = { t -> if (t >= 0.7 && t < 1.1) -11.0 else 0.0 }
        val flex: (Double) -> Double = { t -> if (t >= 0.7 && t < 1.1) 30.0 else 0.0 }
        val m = analyze(clip(60, 2.0, parabola(0.3, 0.4), dev, flexL = flex), "cmj")
        assertEquals(30.0, m.kneeFlexionPeakL!!, 1.0)
        assertEquals(-11.0, m.fppaPeakL!!, 1.0)
        assertEquals(-11.0, m.fppaPeakR!!, 1.0)
        assertTrue(m.kneeAnkleRatio!! > 1.0)
    }

    @Test
    fun peakFlexionIsFoundWithinWindowAfterContact() {
        val m = analyze(clip(60, 2.0, parabola(0.3, 0.4), flexL = landingDev(0.7, 60.0)), "cmj")
        assertEquals(60.0, m.kneeFlexionPeakL!!, 1.5)
        assertTrue(m.kneeFlexionContactL!! < 10.0)
    }

    @Test
    fun symmetricInputGivesZeroAsymmetry() {
        val m = analyze(clip(60, 2.0, parabola(0.3, 0.4), flexL = landingDev(0.7, 50.0)), "cmj")
        assertEquals(0.0, m.flexionAsymmetryPct!!, 0.5)
    }

    @Test
    fun differentLegsGiveAsymmetry() {
        val m = analyze(
            clip(60, 2.0, parabola(0.3, 0.4), flexL = landingDev(0.7, 60.0), flexR = landingDev(0.7, 30.0)), "cmj",
        )
        assertEquals(50.0, m.flexionAsymmetryPct!!, 4.0)
    }

    @Test
    fun noisyInputStaysWithinTolerance() {
        val m = analyze(
            clip(60, 2.0, parabola(0.8, 0.5), flexL = landingDev(1.3, 50.0), noise = 0.002), "cmj",
        )
        assertEquals(500.0, m.flightTimeMs!!, 50.0)
        assertEquals(30.66, m.jumpHeightCm!!, 7.0)
        assertEquals(50.0, m.kneeFlexionPeakL!!, 5.0)
        assertEquals(50.0, m.kneeFlexionPeakR!!, 5.0)
        assertTrue(m.warnings.isEmpty())
    }

    @Test
    fun clipWithoutJumpWarnsNoFlight() {
        val m = analyze(clip(60, 2.0, { 0.0 }, noise = 0.001), "cmj")
        assertTrue("no_flight_detected" in m.warnings)
        assertNull(m.flightTimeMs)
        assertNull(m.jumpHeightCm)
    }

    @Test
    fun lowFpsWarns() {
        val m = analyze(clip(15, 3.0, parabola(1.0, 0.5)), "cmj")
        assertTrue("low_fps" in m.warnings)
        assertEquals(15.0, m.fps, 0.5)
    }

    @Test
    fun lowVisibilityWarnsAndSetsQuality() {
        val m = analyze(clip(60, 2.0, parabola(0.8, 0.5), visibility = 0.6f), "cmj")
        assertTrue("low_visibility" in m.warnings)
        assertEquals(0.6, m.quality, 0.01)
    }

    @Test
    fun clipStartingAboveFutureGroundDoesNotInventTakeoff() {
        // A gradual image drift can look like a landing when the floor is estimated from future frames.
        val frames = clip(30, 1.5, { t -> maxOf(0.0, 0.03 * (1 - t)) })
        assertNull(analyze(frames, "cmj").flightTimeMs)
        val drop = analyze(frames, "drop_jump")
        assertNotNull(drop.flightTimeMs)
        assertNull(drop.jumpHeightCm)
    }

    @Test
    fun dropJumpUsesReboundAndDropLandingHasNoHeight() {
        // Start on a 0.15 high box, step off (feet reach the ground at 0.6 s), rebound 0.9 s to 1.3 s.
        val box: (Double) -> Double = { t -> if (t < 0.4) 0.15 else if (t < 0.6) 0.15 * (0.6 - t) / 0.2 else 0.0 }
        val rebound = parabola(0.9, 0.4)
        val withRebound = analyze(clip(120, 2.0, { box(it) + rebound(it) }), "drop_jump")
        assertEquals(400.0, withRebound.flightTimeMs!!, 40.0)
        assertEquals(9.81 * 0.4 * 0.4 / 8 * 100, withRebound.jumpHeightCm!!, 3.0)

        val dropOnly = analyze(clip(120, 2.0, box), "drop_jump")
        assertNotNull(dropOnly.flightTimeMs)
        assertNull(dropOnly.jumpHeightCm)
        assertTrue(dropOnly.warnings.isEmpty())
    }

    @Test
    fun shortInteriorTrackingGapIsBridged() {
        val frames = clip(60, 2.0, parabola(0.8, 0.5)).map { f ->
            if (f.tMs in 1000..1099) f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0f) }) else f
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1, result.flightCount)
        assertEquals(500.0, result.metrics.flightTimeMs!!, 40.0)
        assertTrue("uncertain_timing" !in result.metrics.warnings)
    }

    @Test
    fun trackingGapAtEitherEdgeWarnsAndBracketsTiming() {
        val good = clip(60, 2.0, parabola(0.8, 0.5))
        for (gap in listOf(800L..899L, 1250L..1349L)) {
            val frames = good.map { f ->
                if (f.tMs in gap) f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0f) }) else f
            }
            val result = analyzeWithLanding(frames, "cmj")
            assertEquals(1, result.flightCount)
            assertTrue("uncertain_timing" in result.metrics.warnings)
            val minMs = result.flightTimeMinMs!!
            val maxMs = result.flightTimeMaxMs!!
            assertTrue(minMs <= 500 && maxMs >= 500)
            assertTrue(result.metrics.flightTimeMs!! in minMs.toDouble()..maxMs.toDouble())
        }
    }

    @Test
    fun longTrackingGapCannotVerifyOneFlight() {
        val frames = clip(60, 2.0, parabola(0.8, 0.5)).map { f ->
            if (f.tMs in 1000..1210) f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0f) }) else f
        }
        val metrics = analyze(frames, "cmj")
        assertNull(metrics.flightTimeMs)
        assertTrue("no_flight_detected" in metrics.warnings)
    }

    @Test
    fun invertedAirborneLegsCannotSupplyAscentEvidence() {
        val frames = clip(60, 2.0, { t -> if (t in 0.8..1.2) 0.15 else 0.0 }).map { f ->
            if (f.tMs !in 800..1200) f else f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                when (index) {
                    23, 24 -> point.copy(y = 0.30f)
                    25, 26 -> point.copy(y = 0.20f)
                    else -> point
                }
            })
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(0, result.flightCount)
        assertNull(result.metrics.flightTimeMs)
    }

    @Test
    fun impossibleSegmentLengthIsATrackingDropout() {
        val good = clip(60, 2.0, parabola(0.8, 0.5))
        val frames = good.map { f ->
            if (f.tMs !in 1000..1049) f else f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                if (index in 25..26) point.copy(x = point.x + 2f) else point
            })
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1, result.flightCount)
        assertEquals(500.0, result.metrics.flightTimeMs!!, 40.0)
    }

    @Test
    fun contactAndAnglesUseTheirOwnLandmarks() {
        val good = clip(60, 2.0, parabola(0.8, 0.5), flexL = landingDev(1.3, 50.0))
        // Hidden knees at take-off still leave observed foot contact and hip ascent.
        val hiddenKnees = good.map { f ->
            if (f.tMs !in 800..899) f else f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                if (index in 25..26) point.copy(visibility = 0f) else point
            })
        }
        assertEquals(500.0, analyze(hiddenKnees, "cmj").flightTimeMs!!, 40.0)
        // Hidden toes and heels after contact do not remove visible joint-angle measurements.
        val hiddenFeet = good.map { f ->
            if (f.tMs < 1320) f else f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                if (index in 29..32) point.copy(visibility = 0f) else point
            })
        }
        assertEquals(50.0, analyze(hiddenFeet, "cmj").kneeFlexionPeakL!!, 1.0)
    }

    @Test
    fun pushUpPhaseIsNotFlight() {
        // 0.4-1.2 s: feet up in the image but hips lower (push-up position); real 400 ms jump at 1.6 s.
        val good = clip(120, 3.0, { t -> if (t in 0.4..1.2) 0.12 else parabola(1.6, 0.4)(t) })
        fun pushUp(visible: Boolean) = good.map { f ->
            val inPhase = f.tMs in 400..1199
            val lms = if (inPhase) f.landmarks.mapIndexed { i, l -> if (i in 23..24) l.copy(y = l.y + 0.2f) else l } else f.landmarks
            f.copy(landmarks = if (inPhase && !visible) lms.map { it.copy(visibility = 0.1f) } else lms)
        }
        for (visible in listOf(true, false)) {
            val m = analyze(pushUp(visible), "cmj")
            assertEquals(400.0, m.flightTimeMs!!, 40.0)
            assertTrue(m.warnings.isEmpty())
        }
    }

    @Test
    fun tooLongFlightIsRejectedWithWarning() {
        val m = analyze(clip(60, 3.0, parabola(0.5, 1.2, metresPerImageHeight = 6.0)), "cmj")
        assertNull(m.flightTimeMs)
        assertNull(m.jumpHeightCm)
        assertTrue("no_flight_detected" in m.warnings)
    }

    @Test
    fun lowVisibilityFramesAreSkipped() {
        val good = clip(60, 2.0, parabola(0.8, 0.5))
        val bad = good.mapIndexed { i, f ->
            // every 4th frame hidden, but not during the flight (0.8-1.3 s): a gap there would reject the run
            if (i % 4 == 1 && (f.tMs < 700 || f.tMs > 1400)) f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0.1f) }) else f
        }
        val m = analyze(bad, "cmj")
        assertEquals(500.0, m.flightTimeMs!!, 60.0)
    }

    @Test
    fun tooFewUsableFramesThrows() {
        assertThrows<IllegalArgumentException> { analyze(clip(60, 0.1, { 0.0 }), "cmj") }
        val hidden = clip(60, 2.0, { 0.0 }).map { f -> f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0.1f) }) }
        assertThrows<IllegalArgumentException> { analyze(hidden, "cmj") }
    }

    @Test
    fun approachDoesNotSetGroundForASmallJump() {
        val frames = clip(25, 3.0, parabola(1.5, 0.24)).map { f ->
            if (f.tMs >= 800) f else f.copy(landmarks = f.landmarks.map { point ->
                // The approach changes apparent scale and floor height, then ends before the standing reference.
                point.copy(x = 0.5f + (point.x - 0.5f) * 1.3f, y = point.y * 1.3f - 0.1f)
            })
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1, result.flightCount)
        assertEquals(1740.0, result.contactMs!!.toDouble(), 80.0)
        assertEquals(240.0, result.metrics.flightTimeMs!!, 80.0)
    }

    @Test
    fun variableTimesAndHiddenUpperBodyOrHeelKeepVisibleLegs() {
        val frames = clip(25, 3.0, parabola(1.0, 0.4)).mapIndexed { index, f ->
            f.copy(tMs = f.tMs + if (index % 2 == 0) 0 else 12,
                landmarks = f.landmarks.mapIndexed { i, point ->
                    if (i < 23 || i == 29 || i == 30) point.copy(visibility = 0f) else point
                })
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1, result.flightCount)
        assertEquals(1400.0, result.contactMs!!.toDouble(), 80.0)
        assertEquals(400.0, result.metrics.flightTimeMs!!, 80.0)
        assertTrue(result.metrics.warnings.isEmpty())
    }

    @Test
    fun firstObservedFootContactEndsFlight() {
        val leftLift = parabola(0.8, 0.5)
        val rightLift = parabola(0.8, 0.6)
        val frames = clip(60, 2.0, leftLift).map { f ->
            val t = f.tMs / 1000.0
            f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                if (index in listOf(28, 30, 32))
                    point.copy(y = (point.y + leftLift(t) - rightLift(t)).toFloat()) else point
            })
        }
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1300.0, result.contactMs!!.toDouble(), 1000.0 / 60)
        assertEquals(500.0, result.metrics.flightTimeMs!!, 40.0)
    }

    @Test
    fun movingCrouchWithPlantedFeetStillSuppliesGround() {
        // No 250 ms interval has still hips: preparation flows straight into the jump.
        val frames = clip(60, 1.5, parabola(0.5, 0.4), devL = { t ->
            if (t < 0.5) 30 + 90 * t / 0.5 else 0.0
        })
        val result = analyzeWithLanding(frames, "cmj")
        assertEquals(1, result.flightCount)
        assertEquals(400.0, result.metrics.flightTimeMs!!, 40.0)
    }

    @Test
    fun settledHipSwayBeforeFootMotionIsNotFlight() {
        val frames = clip(25, 2.0, { t -> if (t in 1.0..1.28) 0.02 else 0.0 }).map { f ->
            f.copy(landmarks = f.landmarks.mapIndexed { index, point ->
                // The hips finish rising before the apparent foot lift, as can happen with pose drift during sway.
                if (index in 23..24) point.copy(y = (GROUND_Y - 0.04 - 2 * SEGMENT -
                    if (f.tMs in 800..1280) 0.02 else 0.0).toFloat()) else point
            })
        }
        assertNull(analyze(frames, "cmj").flightTimeMs)
    }

    @Test
    fun invalidInputThrows() {
        val frames = clip(60, 2.0, { 0.0 })
        assertThrows<IllegalArgumentException> { analyze(frames, "squat") }
        assertThrows<IllegalArgumentException> { analyze(frames.map { it.copy(landmarks = it.landmarks.drop(1)) }, "cmj") }
        assertThrows<IllegalArgumentException> { analyze(frames.map { it.copy(worldLandmarks = it.worldLandmarks.drop(1)) }, "cmj") }
        assertThrows<IllegalArgumentException> { analyze(frames.reversed(), "cmj") }
    }

    @Test
    fun jsonUsesSpecKeysAndNulls() {
        val m = analyze(clip(60, 2.0, { 0.0 }), "cmj")
        val json = m.toJson()
        val keys = Regex("\"([a-z_]+)\":").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "test", "fps", "flight_time_ms", "jump_height_cm", "knee_flexion_contact_l", "knee_flexion_contact_r",
                "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r", "knee_ankle_ratio",
                "flexion_asymmetry_pct", "quality", "warnings",
            ),
            keys,
        )
        assertTrue("\"flight_time_ms\":null" in json)
        assertTrue("\"jump_height_cm\":null" in json)
        assertTrue("\"warnings\":[\"no_flight_detected\"]" in json)
        assertTrue("\"test\":\"cmj\"" in json)
    }
}
