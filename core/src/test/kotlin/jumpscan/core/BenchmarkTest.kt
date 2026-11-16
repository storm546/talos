package jumpscan.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Optional measured-data regressions; no external data is downloaded or committed by the tests. */
class BenchmarkTest {
    private fun dataset(variable: String, fallback: String): File {
        val directory = File(System.getenv(variable) ?: fallback)
        assumeTrue(directory.isDirectory, "$variable dataset is not installed: $directory")
        return directory
    }

    private fun analyze(file: File): JumpAnalyzer.Analysis? = try {
        val (aspect, frames) = file.inputStream().use { raw ->
            (if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().use(::readLandmarkCsv)
        }
        JumpAnalyzer.analyzeWithLanding(frames, "cmj", aspect)
    } catch (_: IllegalArgumentException) {
        null // Unusable pose tracks count as errors, never as successful negative analyses.
    }

    @Test
    fun measuredTwenteFlightsKeepTheirDetectionAndTimingRates() {
        val directory = dataset("JUMPSCAN_TWENTE_DIR", "/work/jumpscan-data/twente-jumps")
        val lines = File(directory, "labels.csv").readLines()
        val header = lines.first().split(',')
        val labels = lines.drop(1).filter(String::isNotBlank).map { header.zip(it.split(',')).toMap() }
        assertEquals(80, labels.size)
        assertEquals(80, labels.map { it.getValue("file") }.distinct().size)
        for (view in listOf("front", "side")) {
            var spurious = 0
            var errors = 0
            var missed = 0
            val flightErrors = mutableListOf<Double>()
            val selected = labels.filter { it.getValue("view") == view }
            assertEquals(40, selected.size)
            for (label in selected) {
                val result = analyze(File(directory, label.getValue("file")))
                if (result == null) errors++
                val flight = result?.metrics?.flightTimeMs
                if (flight == null) { missed++; continue }
                val contact = result.contactMs!!.toDouble()
                // Match the existing diagnostic: 300 ms separates the target from cut-boundary events.
                if (abs(contact - label.getValue("force_landing_ms").toDouble()) > 300 ||
                    abs(contact - flight - label.getValue("force_takeoff_ms").toDouble()) > 300) {
                    spurious++
                } else {
                    flightErrors += abs(flight - label.getValue("force_flight_ms").toDouble())
                }
            }
            val withinFrame = flightErrors.count { it <= 1000.0 / 33 }
            val summary = "$view right=${flightErrors.size}/40 spurious=$spurious missed=$missed " +
                "MAE=${flightErrors.average()} within_frame=$withinFrame errors=$errors"
            println(summary)
            // Small margins below the achieved 28/20 matches and 19/4 within-frame results; zero spurious allowed.
            assertEquals(0, spurious, summary)
            assertTrue(flightErrors.size >= if (view == "front") 27 else 18, summary)
            assertTrue(flightErrors.average() <= if (view == "front") 30.0 else 110.0, summary)
            assertTrue(withinFrame >= if (view == "front") 18 else 3, summary)
            assertTrue(errors <= if (view == "front") 0 else 3, summary)
        }
    }

    @Test
    fun webcamNegativesAndPositiveRecallKeepTheirMeasuredRates() {
        val directory = dataset("JUMPSCAN_KAGGLE_DIR", "/work/jumpscan-data/kaggle-landmarks")
        // Measured detections with a margin of three positive clips and one/two negative clips.
        val minimumFlights = mapOf("jump_in_place" to 118, "jump_left" to 86, "jump_right" to 104)
        val maximumAlarms = mapOf("still_pose" to 7, "looking_down" to 15)
        val maximumErrors = mapOf("jump_in_place" to 23, "jump_left" to 19, "jump_right" to 11,
            "still_pose" to 2, "looking_down" to 12)
        for (cls in maximumErrors.keys) {
            val files = File(directory, cls).listFiles()!!.filter { it.name.endsWith(".csv.gz") || it.name.endsWith(".csv") }
            assertEquals(200, files.size, cls)
            val results = files.sortedBy { it.name }.map(::analyze)
            val flights = results.count { it?.metrics?.flightTimeMs != null }
            val errors = results.count { it == null }
            val summary = "$cls flights=$flights no_flight=${200 - flights - errors} errors=$errors"
            println(summary)
            assertTrue(errors <= maximumErrors.getValue(cls), summary)
            minimumFlights[cls]?.let { assertTrue(flights >= it, summary) }
            maximumAlarms[cls]?.let { assertTrue(flights <= it, summary) }
        }
    }
}
