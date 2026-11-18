package jumpscan.core

import java.io.File
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows

class FormAnalyzerTest {
    /** Independent geometry: image FPPA and world flexion are separately prescribed; planted ankles. */
    private fun series(peaks: List<Double> = listOf(100.0), rightScale: Double = 1.0,
        sideView: Boolean = false, noise: Double = 0.0): List<Frame> {
        val random = Random(71)
        fun jitter() = random.nextGaussian() * noise
        val seconds = 1.0 + peaks.size * 3.0
        return (0..(seconds * 50).toInt()).map { k ->
            val t = k / 50.0
            val rep = ((t - 0.5) / 3.0).toInt()
            val phase = t - 0.5 - rep * 3.0
            val fraction = if (t < 0.5 || rep !in peaks.indices) 0.0 else when {
                phase < 1.0 -> phase
                phase < 1.4 -> 1.0 // flat bottom must remain one repetition
                phase < 2.4 -> 2.4 - phase
                else -> 0.0
            }
            val image = MutableList(33) { Landmark(0.5f, 0.2f, 0f, 1f) }
            val world = image.toMutableList()
            for ((hip, sign) in listOf(23 to -1, 24 to 1)) {
                val peak = peaks.getOrElse(rep) { 0.0 } * if (hip == 24) rightScale else 1.0
                val a = Math.toRadians(peak * fraction / 2)
                val x = if (hip == 23) 0.65 else 0.35
                val h = 0.25 * cos(a)
                val dx = if (sideView) 0.25 * sin(a) else sign * h * tan(Math.toRadians(10.0 * fraction))
                fun imagePoint(xp: Double, yp: Double) = Landmark((xp + jitter()).toFloat(), (yp + jitter()).toFloat(), 0f, 1f)
                image[hip] = imagePoint(x, 0.9 - 2 * h)
                image[hip + 2] = imagePoint(x + dx, 0.9 - h)
                image[hip + 4] = imagePoint(x, 0.9)
                world[hip] = Landmark(x.toFloat(), 0f, 0f, 1f)
                world[hip + 2] = Landmark(x.toFloat(), (0.4 * cos(a)).toFloat(), (-0.4 * sin(a)).toFloat(), 1f)
                world[hip + 4] = Landmark(x.toFloat(), (0.8 * cos(a)).toFloat(), 0f, 1f)
            }
            image[11] = Landmark(if (sideView) .495f else .7f, .15f, 0f, 1f)
            image[12] = Landmark(if (sideView) .505f else .3f, .15f, 0f, 1f)
            Frame(k * 20L, image, world)
        }
    }

    private fun analyze(fs: List<Frame>, exercise: String = "squat", side: String? = null,
        aspect: Float = 1f, view: JumpAnalyzer.View = JumpAnalyzer.View.FRONT) =
        FormAnalyzer.analyze(fs, exercise, side, aspect, view)

    @Test
    fun knownCyclesDepthAsymmetryFppaPausePartialAndNoise() {
        for (noise in listOf(0.0, 0.0004)) {
            val m = analyze(series(listOf(100.0, 44.0, 80.0), rightScale = 0.8, noise = noise))
            assertEquals(2, m.summary.repetitionCount)
            assertEquals(1, m.summary.partialCount, m.toJson())
            assertEquals(0, m.summary.unclearCount)
            val r = m.repetitions.first()
            assertEquals(100.0, r.kneeFlexionPeakL!!, 0.2)
            assertEquals(80.0, r.kneeFlexionPeakR!!, 0.2)
            assertEquals(90.0, r.depth!!, 0.2)
            assertEquals(20.0, r.fppaL!!, 0.5)
            assertEquals(20.0, r.fppaR!!, 0.5)
            assertEquals(20.0, r.flexionAsymmetryPct!!, 0.2)
            assertNull(r.hipBelowKnee)
            assertTrue(r.bottomMs!! in 1480..1920, m.toJson())
            if (noise == 0.0) assertEquals(1500L, r.bottomMs)
            // Noise can place the maximum anywhere within the prescribed 400 ms bottom hold.
            assertTrue(r.descentTimeMs!! in 600..1400, m.toJson())
            assertTrue(r.ascentTimeMs!! in 600..1450, m.toJson())
            assertEquals(r.endMs - r.startMs, r.descentTimeMs + r.ascentTimeMs)
            assertEquals(81.0, m.summary.medianDepth!!, 0.2)
            assertEquals(3, m.summary.worstRepetition)
            assertEquals(listOf("below_min_depth"), m.repetitions[1].reasons)
            assertTrue(m.warnings.isEmpty())
        }
    }

    @Test
    fun eachSingleLegUsesOnlyRequestedSide() {
        for (side in listOf("L", "R")) {
            val far = if (side == "L") 26 else 25
            val fs = series(rightScale = 0.8).map { f -> f.copy(landmarks = f.landmarks.mapIndexed { i, p ->
                if (i == far || i == far + 2) p.copy(visibility = 0f) else p
            }) }
            val m = analyze(fs, "single_leg_squat", side)
            assertEquals(1, m.summary.repetitionCount)
            val r = m.repetitions.single()
            assertEquals(if (side == "L") 100.0 else 80.0, r.depth!!, 0.2)
            assertNotNull(if (side == "L") r.fppaL else r.fppaR)
            assertNull(if (side == "L") r.kneeFlexionPeakR else r.kneeFlexionPeakL)
            assertNull(r.flexionAsymmetryPct)
            assertEquals(listOf("single_leg_view"), m.warnings)
        }
    }

    @Test
    fun sideUsesImageAspectIgnoresWorldAndDoesNotCopyFarLeg() {
        val fs = series(sideView = true).map { f -> f.copy(
            landmarks = f.landmarks.mapIndexed { i, p -> p.copy(x = p.x / 2, visibility = if (i == 26) 0f else 1f) },
            worldLandmarks = List(33) { Landmark(0f, 0f, 0f, 0f) }) }
        val m = analyze(fs, aspect = 2f, view = JumpAnalyzer.View.SIDE)
        assertEquals(1, m.summary.repetitionCount)
        assertEquals(100.0, m.summary.medianDepth!!, 0.2)
        assertNull(m.repetitions.single().kneeFlexionPeakR)
        assertNull(m.repetitions.single().fppaL)
        assertEquals(false, m.repetitions.single().hipBelowKnee)
        assertEquals(0, m.summary.hipBelowKneeCount)
        assertEquals(listOf("single_leg_view"), m.warnings)
        val right = analyze(series(sideView = true), "single_leg_squat", "R", view = JumpAnalyzer.View.SIDE)
        assertNull(right.repetitions.single().kneeFlexionPeakL)
        assertNotNull(right.repetitions.single().kneeFlexionPeakR)
    }

    @Test
    fun incompleteAndOccludedCyclesStayUnclear() {
        val fs = series()
        for (cut in listOf(fs.filter { it.tMs >= 1400 }, fs.filter { it.tMs <= 1900 })) {
            val m = analyze(cut)
            assertEquals(0, m.summary.repetitionCount)
            assertEquals(1, m.summary.unclearCount)
            assertTrue("unclear_reps" in m.warnings)
            assertNull(m.repetitions.single().depth)
        }
        val hidden = fs.map { f -> if (f.tMs in 1400..1600) f.copy(landmarks = f.landmarks.map { it.copy(visibility = 0f) }) else f }
        val m = analyze(hidden)
        assertEquals(0, m.summary.repetitionCount)
        assertTrue(m.summary.unclearCount > 0)
        assertTrue(m.repetitions.all { "tracking_gap" in it.reasons })
        assertTrue(m.warnings.containsAll(listOf("low_visibility", "unclear_reps")))
        val dropped = analyze(fs.filterNot { it.tMs in 1400..1600 })
        assertEquals(0, dropped.summary.repetitionCount)
        assertTrue("unclear_reps" in dropped.warnings)
    }

    @Test
    fun refusesInsufficientVisibilityInvalidInputsAndMissingWorld() {
        val fs = series()
        assertThrows<IllegalArgumentException> { analyze(fs.take(9)) }
        assertThrows<IllegalArgumentException> { analyze(fs.map { f -> f.copy(landmarks = f.landmarks.map { it.copy(visibility = .1f) }) }) }
        assertThrows<IllegalArgumentException> { analyze(fs, "single_leg_squat") }
        assertThrows<IllegalArgumentException> { analyze(fs, side = "L") }
        assertThrows<IllegalArgumentException> { analyze(fs, "lunge") }
        assertThrows<IllegalArgumentException> { analyze(fs.reversed()) }
        assertThrows<IllegalArgumentException> { analyze(fs, aspect = Float.NaN) }
        assertThrows<IllegalArgumentException> { analyze(fs.map { it.copy(worldLandmarks = emptyList()) }) }
        assertThrows<IllegalArgumentException> { analyze(fs.map { f -> f.copy(worldLandmarks = f.worldLandmarks.map { it.copy(visibility = 0f) }) }) }
        val bad = analyze(fs.map { f -> f.copy(worldLandmarks = List(33) { Landmark(0f, 0f, 0f, 1f) }) })
        assertEquals(listOf("degenerate_geometry"), bad.repetitions.single().reasons)
        assertNull(bad.summary.medianDepth)
    }

    @Test
    fun stillPoseHasNoInventedRepetitionsAndJsonHasFixedNulls() {
        val fs = series().filter { it.tMs < 500 }
        val m = analyze(fs)
        assertEquals(0, m.summary.repetitionCount)
        assertTrue(m.repetitions.isEmpty())
        assertTrue(m.toJson().contains("\"worst_repetition\":null"))
        assertTrue(m.toJson().contains("\"hip_below_knee_count\":null"))
        assertTrue(m.toJson().contains("\"median_depth\":null"))
        assertTrue(m.toJson().startsWith("{\"exercise\":\"squat\",\"side\":null,\"view\":\"front\""))
    }


    @Test
    fun fppaSignAndIsolatedLandmarkSpikesUseSharedGeometryAndSmoothing() {
        val fs = series().mapIndexed { k, f -> f.copy(landmarks = f.landmarks.mapIndexed { i, p ->
            when {
                k % 31 == 10 && i in listOf(23,24,25,26,27,28) -> p.copy(x = p.x + .12f, y = p.y + .12f)
                i == 25 || i == 26 -> p.copy(x = 2 * f.landmarks[i - 2].x - p.x)
                else -> p
            }
        }) }
        val m = analyze(fs)
        assertEquals(1, m.summary.repetitionCount)
        assertEquals(-20.0, m.repetitions.single().fppaL!!, 1.0)
        assertEquals(-20.0, m.repetitions.single().fppaR!!, 1.0)
    }

    @Test
    fun autoViewAndHipBelowKneeFlagComeFromImageGeometry() {
        val fs = series(sideView = true).map { f -> f.copy(landmarks = f.landmarks.mapIndexed { i, p ->
            when {
                i == 24 -> p.copy(x = f.landmarks[23].x + .01f)
                i == 26 -> p.copy(visibility = 0f)
                i == 23 && f.tMs in 1400..1900 -> p.copy(y = f.landmarks[25].y + .02f)
                else -> p
            }
        }) }
        val m = analyze(fs, view = JumpAnalyzer.View.AUTO)
        assertEquals("side", m.view)
        assertEquals(1, m.summary.repetitionCount)
        assertEquals(true, m.repetitions.single().hipBelowKnee)
        assertEquals(1, m.summary.hipBelowKneeCount)
        assertNull(m.repetitions.single().fppaL)
    }


    @Test
    fun eachCycleUsesItsLocalStandingHeightDespiteSlowDrift() {
        val fs = series(listOf(100.0, 100.0, 100.0)).map { f -> f.copy(landmarks = f.landmarks.map { p ->
            p.copy(y = p.y + (f.tMs / 1000.0 * .002).toFloat())
        }) }
        val m = analyze(fs)
        assertEquals(3, m.summary.repetitionCount, m.toJson())
        assertEquals(0, m.summary.unclearCount)
    }

    @Test
    fun batchReadsCompressedAndPlainTracksAndReportsRefusal(@TempDir dir: File) {
        val input = File(dir, "input").apply { mkdir() }
        val fixture = javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")!!.readBytes()
        File(input, "stock.csv.gz").writeBytes(fixture)
        File(input, "stock.csv").writeBytes(GZIPInputStream(fixture.inputStream()).readBytes())
        File(input, "bad.csv").writeText("header\n0,1\n")
        val out = File(dir, "out.jsonl")
        assertTrue(runFormBatch(input, out).startsWith("clips=3 errors=1"))
        val lines = out.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"status\":\"error\""))
        val metrics = lines.drop(1).map { it.substringAfter("\"metrics\":") }
        assertEquals(metrics[0], metrics[1])
    }
}
