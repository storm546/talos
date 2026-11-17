package jumpscan.core

import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs

class RealVideoTest {
    @Test
    fun plantedFeetInRealSquatsAreNotFlights() {
        // Ground truth from visual review of every ffmpeg frame, zero-based at 30000/1001 fps:
        // Frames | Times (ms)       | Last contact before take-off | First landing | Confidence
        // 0–299  | 0–9976.633       | none                         | none          | high: planted shoes
        // There are zero observable jumps; sub-frame flight cannot be excluded at 29.97 fps.
        // Thus there is no last landing or flight duration to compare within 2 frames / 70 ms.
        val (aspect, frames) = GZIPInputStream(javaClass.getResourceAsStream("/squat_jumps_one_person.csv.gz")!!)
            .bufferedReader().use { readLandmarkCsv(it) }
        assertEquals(300, frames.size)
        assertEquals(400f / 720f, aspect)
        val metrics = JumpAnalyzer.analyze(frames, "cmj", aspect)
        println("stock squat ${metrics.toJson()}")
        // The detector must not propose a flight for this reviewed planted-foot clip.
        assertEquals(listOf("no_flight_detected"), metrics.warnings)
        assertNull(metrics.flightTimeMs)
        assertNull(metrics.jumpHeightCm)
        assertRanges(metrics)
    }

    @Test
    fun privateClipsMatchReviewedContacts() {
        assumeTrue(javaClass.getResource("/private/jump1.csv") != null, "Private fixtures are not installed.")
        val labels = requireNotNull(javaClass.getResourceAsStream("/private/labels.csv")) {
            "Private fixtures need the reviewed labels.csv timeline."
        }
        val rows = labels.bufferedReader().use { it.readLines().drop(1).filter(String::isNotBlank) }
        assertEquals(3, rows.size)
        assertAll(rows.map { line -> Executable {
            val row = line.split(',')
            val name = row[0]
            val lastGroundIndex = row[1].toInt()
            val lastGroundMs = row[2].toDouble()
            val contactIndex = row[3].toInt()
            val contactMs = row[4].toDouble()
            val (aspect, frames) = javaClass.getResourceAsStream("/private/$name.csv")!!
                .bufferedReader().use { readLandmarkCsv(it) }
            // Frame labels and independent ffprobe times must agree with the exported presentation timeline.
            assertEquals(lastGroundMs, frames[lastGroundIndex].tMs.toDouble(), 1.0, name)
            assertEquals(contactMs, frames[contactIndex].tMs.toDouble(), 1.0, name)
            val result = JumpAnalyzer.analyzeWithLanding(frames, "cmj", aspect)
            val metrics = result.metrics
            val reviewedFlightMs = mapOf("jump1" to 280.0, "jump2" to 360.0, "jump3" to 360.0)
            val rules = JumpAnalyzer.analyzeRulesWithLanding(frames, "cmj", aspect)
            assertEquals(reviewedFlightMs.getValue(name), rules.metrics.flightTimeMs!!, 0.0, "$name rules")
            println("$name rules_flight_ms=${rules.metrics.flightTimeMs} " +
                "learned_change_ms=${metrics.flightTimeMs!! - rules.metrics.flightTimeMs!!}")
            println("$name contact=${result.contactMs} flights=${result.flightCount} ${metrics.toJson()}")
            // jump2 also holds an earlier real jump (frames 42-49, confirmed by viewing); only the last is labelled,
            // the contact check below pins the selected flight to the labelled landing.
            assertTrue(result.flightCount >= 1, "$name must contain the labelled flight")
            val detectedContact = frames.indexOfFirst { it.tMs == result.contactMs }
            assertTrue(abs(detectedContact - contactIndex) <= 2, "$name contact at $detectedContact, labelled $contactIndex")
            // The label spans last-grounded to first-landed frame; the detector reports first-airborne to first-ground.
            // Each edge is only known to one frame, so allow two frame intervals plus the 1 ms timestamp rounding.
            assertEquals(contactMs - lastGroundMs, metrics.flightTimeMs!!, 2000.0 / metrics.fps + 1.0, name)
            assertTrue("no_flight_detected" !in metrics.warnings, name)
            assertEquals(reviewedFlightMs.getValue(name), metrics.flightTimeMs!!, 0.0, name)
            assertRanges(metrics)
            inRange(metrics.flightTimeMs!!, 0.0, 1500.0)
            inRange(metrics.jumpHeightCm!!, 0.0, 150.0)
        } })
    }

    private fun inRange(value: Double, min: Double, max: Double) {
        assertTrue(value.isFinite() && value in min..max, "$value outside $min..$max")
    }

    private fun assertRanges(metrics: JumpMetrics) {
        inRange(metrics.fps, 10.0, 480.0)
        listOf(metrics.kneeFlexionContactL!!, metrics.kneeFlexionContactR!!,
            metrics.kneeFlexionPeakL!!, metrics.kneeFlexionPeakR!!).forEach { inRange(it, 0.0, 180.0) }
        listOf(metrics.fppaPeakL!!, metrics.fppaPeakR!!).forEach { inRange(it, -90.0, 90.0) }
        inRange(metrics.kneeAnkleRatio!!, 0.0, 5.0)
        inRange(metrics.flexionAsymmetryPct!!, 0.0, 100.0)
        inRange(metrics.quality, 0.0, 1.0)
    }
}
