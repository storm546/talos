package jumpscan.core

import java.io.File
import java.security.MessageDigest
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

    private fun analyze(file: File, rules: Boolean = false): JumpAnalyzer.Analysis? = try {
        val (aspect, frames) = file.inputStream().use { raw ->
            (if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().use(::readLandmarkCsv)
        }
        if (rules) JumpAnalyzer.analyzeRulesWithLanding(frames, "cmj", aspect)
        else JumpAnalyzer.analyzeWithLanding(frames, "cmj", aspect)
    } catch (_: IllegalArgumentException) {
        null // Unusable tracks count as errors, never as successful negative analyses.
    }

    @Test
    fun measuredTwenteFlightsKeepTheirDetectionAndTimingRates() {
        val directory = dataset("JUMPSCAN_TWENTE_DIR", "/work/jumpscan-data/twente-jumps9")
        val lines = File(directory, "labels.csv").readLines()
        val header = lines.first().split(',')
        val labels = lines.drop(1).filter(String::isNotBlank).map { header.zip(it.split(',')).toMap() }
        assertEquals(182, labels.size)
        assertEquals(182, labels.map { it.getValue("file") }.distinct().size)
        assertEquals((4..12).map { "Subj%02d".format(it) }.toSet(), labels.map { it.getValue("subject") }.toSet())
        for (rules in listOf(false, true)) for (view in listOf("front", "side")) {
            var spurious = 0
            var errors = 0
            var missed = 0
            val flightErrors = mutableListOf<Double>()
            val selected = labels.filter { it.getValue("view") == view }
            assertEquals(91, selected.size)
            for (label in selected) {
                val result = analyze(File(directory, label.getValue("file")), rules)
                if (result == null) errors++
                val flight = result?.metrics?.flightTimeMs
                if (flight == null) { missed++; continue }
                val contact = result.contactMs!!.toDouble()
                if (abs(contact - label.getValue("force_landing_ms").toDouble()) > 300 ||
                    abs(contact - flight - label.getValue("force_takeoff_ms").toDouble()) > 300) {
                    spurious++
                } else {
                    flightErrors += abs(flight - label.getValue("force_flight_ms").toDouble())
                }
            }
            val withinFrame = flightErrors.count { it <= 1000.0 / 33 }
            val summary = "${if (rules) "rules" else "learned proposal + cross-check"} in-training consistency $view " +
                "right=${flightErrors.size}/91 spurious=$spurious missed=$missed " +
                "MAE=${flightErrors.average()} within_frame=$withinFrame errors=$errors"
            println(summary)
            // Production subjects are in training; these assertions check the port, not held-out accuracy.
            val front = view == "front"
            assertTrue(flightErrors.size >= if (rules) { if (front) 69 else 58 } else { if (front) 86 else 84 }, summary)
            assertTrue(spurious <= if (rules) 0 else if (front) 2 else 1, summary)
            assertTrue(missed <= if (rules) { if (front) 22 else 33 } else { if (front) 4 else 7 }, summary)
            assertTrue(flightErrors.average() <= if (rules) { if (front) 35.0 else 90.0 }
                else { if (front) 30.0 else 20.0 }, summary)
            assertTrue(withinFrame >= if (rules) { if (front) 43 else 8 } else { if (front) 64 else 66 }, summary)
            assertTrue(errors <= if (front) 0 else if (rules) 3 else 6, summary)
        }
    }

    @Test
    fun webcamNegativesAndPositiveRecallKeepTheirMeasuredRates() {
        val directory = dataset("JUMPSCAN_KAGGLE_DIR", "/work/jumpscan-data/kaggle-landmarks")
        // Two-clip detection margins and one-clip error margins around the fixed-model TEST measurements.
        val minimumFlights = mapOf("jump_in_place" to 86, "jump_left" to 79, "jump_right" to 90)
        val minimumClassFlights = mapOf("jump_in_place" to 86, "jump_left" to 79, "jump_right" to 89)
        val maximumAlarms = mapOf("looking_down" to 34, "still_pose" to 27)
        val rulesMinimum = mapOf("jump_in_place" to 62, "jump_left" to 32, "jump_right" to 47)
        val rulesMaximum = mapOf("looking_down" to 9, "still_pose" to 3)
        val maximumErrors = mapOf("looking_down" to 10, "still_pose" to 1,
            "jump_in_place" to 10, "jump_left" to 13, "jump_right" to 8)
        for (cls in listOf("looking_down", "still_pose", "jump_in_place", "jump_left", "jump_right")) {
            val files = File(directory, cls).listFiles()!!.filter { it.name.endsWith(".csv.gz") || it.name.endsWith(".csv") }
            assertEquals(200, files.size, cls)
            // PROTOCOL2: hash the full basename, sort, then hold out the second half unchanged.
            val test = files.sortedBy { file -> MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) } }.drop(100)
            assertEquals(100, test.size)
            for (rules in listOf(false, true)) {
                val results = test.map { analyze(it, rules) }
                val flights = results.count { it?.metrics?.flightTimeMs != null }
                val classFlights = results.count { it?.metrics?.flightTimeMs?.let { t -> t in 100.0..1000.0 } == true }
                val errors = results.count { it == null }
                val uncertain = results.count { it?.metrics?.warnings?.contains("uncertain_timing") == true }
                val summary = "${if (rules) "rules" else "learned proposal + cross-check"} TEST $cls " +
                    "flights=$flights class_flights=$classFlights no_flight=${100 - flights - errors} " +
                    "errors=$errors uncertain=$uncertain"
                println(summary)
                assertTrue(errors <= maximumErrors.getValue(cls), summary)
                (if (rules) rulesMinimum else minimumFlights)[cls]?.let { assertTrue(flights >= it, summary) }
                if (!rules) minimumClassFlights[cls]?.let { assertTrue(classFlights >= it, summary) }
                (if (rules) rulesMaximum else maximumAlarms)[cls]?.let { assertTrue(flights <= it, summary) }
            }
        }
    }
}
