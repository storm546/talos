package jumpscan.core

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LearnedFlightTest {
    private data class Fixture(val aspect: Float, val frames: List<Frame>,
        val times: DoubleArray, val probabilities: DoubleArray)

    private fun fixture(name: String): Fixture {
        val rows = javaClass.getResourceAsStream("/learned/$name.txt")!!.bufferedReader().use { it.readLines() }
            .map { line -> line.split(' ').map(String::toDouble) }
        val (observed, count, aspect) = rows.first()
        val points = listOf(11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)
        val frames = rows.drop(1).take(observed.toInt()).map { row ->
            val landmarks = MutableList(33) { Landmark(0f, 0f, 0f, 1f) }
            points.forEachIndexed { j, p -> landmarks[p] = Landmark(row[1 + 3 * j].toFloat(),
                row[2 + 3 * j].toFloat(), 0f, row[3 + 3 * j].toFloat()) }
            Frame(row[0].toLong(), landmarks, landmarks)
        }
        val expected = rows.drop(1 + observed.toInt())
        assertEquals(count.toInt(), expected.size)
        return Fixture(aspect.toFloat(), frames, expected.map { it[0] }.toDoubleArray(),
            expected.map { it[1] }.toDoubleArray())
    }

    @Test
    fun probabilitiesMatchIndependentPythonOnFrontSideAndWebcam() {
        for (name in listOf("twente-front", "twente-side", "webcam")) {
            val fixture = fixture(name)
            val input = LearnedFlight.features(fixture.frames, fixture.aspect, fps = 33.0)!!
            assertArrayEquals(fixture.times, input.times, 1e-8, name)
            val actual = LearnedFlight.probabilities(input.features)
            val error = actual.indices.maxOf { abs(actual[it] - fixture.probabilities[it]) }
            println("$name max_probability_error=$error samples=${actual.size}")
            assertArrayEquals(fixture.probabilities, actual, 1e-6, name)
        }
    }

    @Test
    fun highCaptureRatesUseThirtyFpsAndKeepContinuousEdges() {
        val fixture = fixture("twente-front")
        for (fps in listOf(60, 120)) {
            val frames = (0..(fps * 3)).map { i ->
                val time = fixture.frames.first().tMs + i * 1000L / fps
                fixture.frames.minBy { abs(it.tMs - time) }.copy(tMs = time)
            }
            val input = LearnedFlight.features(frames, fixture.aspect)!!
            assertEquals(1000.0 / 30, input.times[1] - input.times[0], 1e-8)
            assertTrue(input.features.size < frames.size)
            val interpolated = LearnedFlight.crossings(LearnedFlight.probabilities(input.features), input.times)!!
            val result = LearnedFlight.predict(frames, fixture.aspect)!!.edges!!
            assertEquals(interpolated.first, result.takeoffMs)
            assertEquals(interpolated.second, result.contactMs)
        }
    }

    @Test
    fun missingGridCellsInterpolatePositionsAndEraseVisibility() {
        val fixture = fixture("twente-front")
        val frames = fixture.frames.filterIndexed { i, _ -> i !in 20..23 }
        val input = LearnedFlight.features(frames, fixture.aspect, fps = 33.0)!!
        for (i in 20..23) {
            assertEquals(0.0, input.features[i][36])
            for (j in 0..11) {
                assertEquals(0.0, input.features[i][j + 24])
                val expected = input.features[19][j] + (input.features[24][j] - input.features[19][j]) * (i - 19) / 5
                assertEquals(expected, input.features[i][j], 1e-12)
            }
        }
    }

    @Test
    fun invalidInputsAreDistinctFromNoFlight() {
        val fixture = fixture("twente-front")
        assertNull(LearnedFlight.predict(fixture.frames.take(1), fixture.aspect))
        val invalid = fixture.frames.map { frame -> frame.copy(landmarks = frame.landmarks.mapIndexed { i, p ->
            if (i == 27) frame.landmarks[23] else p
        }) }
        assertNull(LearnedFlight.predict(invalid, fixture.aspect))
        val still = fixture.frames.map { it.copy(landmarks = fixture.frames.first().landmarks) }
        assertNotNull(LearnedFlight.predict(still, fixture.aspect))
        assertNull(LearnedFlight.predict(still, fixture.aspect)!!.edges)
    }

    @Test
    fun longestRunUsesStrictThresholdTwoSamplesFirstTieAndInterpolatedCrossings() {
        val step = 1000.0 / 33
        fun edges(vararg p: Double) = LearnedFlight.crossings(p, DoubleArray(p.size) { it * step })
        assertNull(edges(0.5, 0.5, 0.1))
        assertNull(edges(0.1, 0.8, 0.1))
        val run = edges(0.1, 0.7, 0.8, 0.1, 0.7, 0.8, 0.1)!!
        assertEquals(2.0 / 3 * step, run.first, 1e-10)
        assertEquals((2 + 3.0 / 7) * step, run.second, 1e-10)
        assertEquals(0.0 to step, edges(0.9, 0.9))
        assertEquals(step * 3 to step * 6, edges(0.8, 0.8, 0.1, 0.5, 0.9, 0.9, 0.9))
    }

    @Test
    fun policyAveragesAgreementFlagsRulesAndNeverPromotesSuggestions() {
        val rules = LearnedFlight.Edges(1000.0, 1400.0)
        fun select(edges: LearnedFlight.Edges?, source: LearnedFlight.Edges? = rules) =
            JumpAnalyzer.combineFlight(source, LearnedFlight.Prediction(edges), rulesUncertain = true)
        assertEquals(LearnedFlight.Edges(1050.0, 1350.0) to false,
            select(LearnedFlight.Edges(1100.0, 1300.0)))
        assertEquals(rules to true, select(LearnedFlight.Edges(1101.0, 1400.0)))
        assertEquals(rules to true, select(LearnedFlight.Edges(1000.0, 1501.0)))
        assertEquals(rules to true, select(null))
        assertEquals(null to false, select(rules, source = null))
        assertEquals(null to false, select(null, source = null))
        assertEquals(rules to false, JumpAnalyzer.combineFlight(rules, null))
        assertEquals(rules to true, JumpAnalyzer.combineFlight(rules, null, rulesUncertain = true))
    }

    @Test
    fun nativeTrainingRatesAndLowRateFallback() {
        val fixture = fixture("twente-front")
        for (fps in listOf(24, 25, 30, 33, 36)) {
            val frames = (0..fps * 3).map { i -> fixture.frames.first().copy(tMs = i * 1000L / fps) }
            val input = LearnedFlight.features(frames, fixture.aspect)!!
            assertEquals(1000.0 / fps, input.times[1] - input.times[0], 1e-8)
        }
        assertNull(LearnedFlight.features(fixture.frames, fixture.aspect, fps = 23.99))
    }

    @Test
    fun unusableLearnedInputFallsBackWithoutAnExtraWarning() {
        val fixture = fixture("twente-side")
        val frames = fixture.frames.map { f -> f.copy(landmarks = f.landmarks.mapIndexed { i, p ->
            when (i) {
                27 -> f.landmarks[23]
                25, 29, 31 -> p.copy(visibility = 0f)
                else -> p
            }
        }) }
        assertNull(LearnedFlight.predict(frames, fixture.aspect))
        assertEquals(JumpAnalyzer.analyzeRulesWithLanding(frames, "cmj", fixture.aspect, JumpAnalyzer.View.SIDE),
            JumpAnalyzer.analyzeWithLanding(frames, "cmj", fixture.aspect, JumpAnalyzer.View.SIDE))
    }

    @Test
    fun manualEdgesOverrideAUsableLearnedPrediction() {
        val fixture = fixture("twente-front")
        assertNotNull(LearnedFlight.predict(fixture.frames, fixture.aspect)!!.edges)
        val start = fixture.frames[2].tMs
        val end = fixture.frames[12].tMs
        val result = JumpAnalyzer.analyzeWithLanding(fixture.frames, "cmj", fixture.aspect,
            takeoffMs = start, landingMs = end)
        assertEquals((end - start).toDouble(), result.metrics.flightTimeMs!!, 0.0)
        assertEquals(start, result.takeoffMs)
        assertEquals(end, result.contactMs)
        assertTrue("manual_edges" in result.metrics.warnings)
        assertFalse("uncertain_timing" in result.metrics.warnings)
        assertNull(result.learnedSuggestion)
    }
}
