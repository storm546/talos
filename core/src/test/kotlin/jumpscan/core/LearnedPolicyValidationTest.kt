package jumpscan.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Optional public-data measurements. Private fixtures stay in RealVideoTest, unchanged. */
class LearnedPolicyValidationTest {
    @Test
    fun measureFinalModelPolicyAndRulesOnIdenticalClips() {
        val webcam = File(System.getenv("JUMPSCAN_KAGGLE_DIR") ?: "/work/jumpscan-data/kaggle-landmarks")
        val twente = File("/work/jumpscan-data/twente-jumps9")
        assumeTrue(webcam.isDirectory && twente.isDirectory, "Public validation datasets are not installed")
        val output = File("build/learnedv2").apply { mkdirs() }
        File(output, "clips.csv").bufferedWriter().use { writer ->
            writer.appendLine("dataset,file,group,rules_flight_ms,rules_takeoff_ms,rules_contact_ms,rules_warnings,flight_ms,takeoff_ms,contact_ms,warnings,suggestion_takeoff_ms,suggestion_landing_ms,error")
            fun measure(dataset: String, id: String, group: String, file: File): Pair<JumpAnalyzer.Analysis?, JumpAnalyzer.Analysis?> {
                var rules: JumpAnalyzer.Analysis? = null
                var result: JumpAnalyzer.Analysis? = null
                var error = ""
                try {
                    val (aspect, frames) = file.inputStream().use { raw ->
                        (if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().use(::readLandmarkCsv)
                    }
                    rules = JumpAnalyzer.analyzeRulesWithLanding(frames, "cmj", aspect)
                    result = JumpAnalyzer.analyzeWithLanding(frames, "cmj", aspect)
                    assertTrue(result.metrics.flightTimeMs == null || rules.metrics.flightTimeMs != null, id)
                    result.learnedSuggestion?.let { suggestion ->
                        assertNull(result.metrics.flightTimeMs, id)
                        assertTrue("no_flight_detected" in result.metrics.warnings, id)
                        assertTrue(frames.any { it.tMs == suggestion.takeoffMs }, id)
                        assertTrue(frames.any { it.tMs == suggestion.landingMs }, id)
                        assertFalse(result.metrics.toJson().contains("suggestion"), id)
                    }
                } catch (e: IllegalArgumentException) { error = e.message.orEmpty().replace(',', ';') }
                writer.appendLine(listOf(dataset, id, group, rules?.metrics?.flightTimeMs, rules?.takeoffMs,
                    rules?.contactMs, rules?.metrics?.warnings?.joinToString("|"), result?.metrics?.flightTimeMs,
                    result?.takeoffMs, result?.contactMs, result?.metrics?.warnings?.joinToString("|"),
                    result?.learnedSuggestion?.takeoffMs, result?.learnedSuggestion?.landingMs, error)
                    .joinToString(",") { it?.toString().orEmpty() })
                return rules to result
            }
            for (cls in listOf("looking_down", "still_pose", "jump_in_place", "jump_left", "jump_right")) {
                val files = File(webcam, cls).listFiles()!!.filter { it.name.endsWith(".csv.gz") }
                    .sortedBy { file -> MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray())
                        .joinToString("") { "%02x".format(it) } }.drop(100)
                assertEquals(100, files.size, cls)
                val results = files.map { measure("webcam_test", "$cls/${it.name}", cls, it) }
                val rules = results.count { it.first?.metrics?.flightTimeMs != null }
                val automatic = results.count { it.second?.metrics?.flightTimeMs != null }
                val suggestions = results.count { it.second?.learnedSuggestion != null }
                println("webcam TEST $cls automatic=$automatic rules=$rules suggestions=$suggestions errors=${results.count { it.second == null }}")
                assertTrue(automatic <= rules, cls)
                if (cls == "looking_down") assertEquals(7, rules)
                if (cls == "still_pose") assertEquals(1, rules)
            }
            val lines = File(twente, "labels.csv").readLines()
            val header = lines.first().split(',')
            val labels = lines.drop(1).filter(String::isNotBlank).map { header.zip(it.split(',')).toMap() }
            assertEquals(182, labels.size)
            labels.forEach { measure("twente_final_in_sample", it.getValue("file"), it.getValue("view"),
                File(twente, it.getValue("file"))) }
            val negatives = File("/work/jumpscan-data/learnedv2-negatives")
            if (negatives.isDirectory) negatives.walkTopDown().filter { it.extension == "csv" }.forEach {
                val id = it.relativeTo(negatives).path.removeSuffix(".csv")
                measure("twente_negative", id, if ("_front/" in id) "front" else "side", it)
            }
        }
    }
}
