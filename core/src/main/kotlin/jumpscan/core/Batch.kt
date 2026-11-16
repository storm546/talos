package jumpscan.core

import java.io.BufferedReader
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Parses the app's debug landmark export: a header row, then one row per frame with
 * `tMs, aspect`, 33 image landmarks and 33 world landmarks (x, y, z, visibility each) = 266 columns.
 * Returns the clip aspect (identical on every row) and the frames. Throws IllegalArgumentException
 * (or NumberFormatException) on malformed input.
 */
fun readLandmarkCsv(reader: BufferedReader): Pair<Float, List<Frame>> {
    val rows = reader.readLines().drop(1).filter(String::isNotBlank).map { it.split(',') }
    require(rows.isNotEmpty()) { "no frame rows" }
    rows.forEachIndexed { i, row -> require(row.size == 266) { "row ${i + 2}: expected 266 columns, got ${row.size}" } }
    val aspect = rows.first()[1].toFloat()
    val frames = rows.mapIndexed { i, row ->
        require(row[1].toFloat() == aspect) { "row ${i + 2}: aspect differs from the first row" }
        fun landmarks(start: Int) = List(33) { index ->
            val offset = start + index * 4
            Landmark(row[offset].toFloat(), row[offset + 1].toFloat(),
                row[offset + 2].toFloat(), row[offset + 3].toFloat())
        }
        Frame(row[0].toLong(), landmarks(2), landmarks(134))
    }
    return aspect to frames
}

private class Scored(
    val cls: String, val clip: String, val frames: Int, val durationMs: Long,
    val metrics: JumpMetrics?, val error: String?,
) {
    val detected = metrics != null && metrics.flightTimeMs != null && "no_flight_detected" !in metrics.warnings
}

private fun score(file: File): Scored {
    var frames = 0
    var durationMs = 0L
    return try {
        val (aspect, parsed) = file.inputStream().use { raw ->
            (if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().use(::readLandmarkCsv)
        }
        frames = parsed.size
        durationMs = parsed.last().tMs - parsed.first().tMs
        Scored(file.parentFile.name, file.name, frames, durationMs, JumpAnalyzer.analyze(parsed, "cmj", aspect), null)
    } catch (e: Exception) {
        Scored(file.parentFile.name, file.name, frames, durationMs, null, e.message ?: e.javaClass.simpleName)
    }
}

private fun clean(s: String) = s.replace(',', ';').replace(Regex("[\\r\\n]+"), " ")

private fun num(v: Double?) = if (v == null) "" else (Math.round(v * 100) / 100.0).toString()

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Scores every *.csv / *.csv.gz under [inDir] (test type cmj), writes one row per file to [outFile], returns the summary. */
fun runBatch(inDir: File, outFile: File): String {
    require(inDir.isDirectory) { "input directory not found: $inDir" }
    val results = inDir.walkTopDown().filter { it.isFile && (it.name.endsWith(".csv") || it.name.endsWith(".csv.gz")) }
        .sortedBy { it.path }.map(::score).toList()
    val header = "class,clip,frames,duration_ms,status,error,flight_time_ms,jump_height_cm," +
        "knee_flexion_contact_l,knee_flexion_contact_r,knee_flexion_peak_l,knee_flexion_peak_r," +
        "fppa_peak_l,fppa_peak_r,knee_ankle_ratio,flexion_asymmetry_pct,quality,fps,warnings,flight_detected"
    outFile.absoluteFile.parentFile?.mkdirs()
    outFile.bufferedWriter().use { w ->
        w.write(header + "\n")
        for (r in results) {
            val m = r.metrics
            val fields = listOf(clean(r.cls), clean(r.clip), r.frames.toString(), r.durationMs.toString(),
                if (r.error == null) "ok" else "error", clean(r.error ?: ""),
                num(m?.flightTimeMs), num(m?.jumpHeightCm), num(m?.kneeFlexionContactL), num(m?.kneeFlexionContactR),
                num(m?.kneeFlexionPeakL), num(m?.kneeFlexionPeakR), num(m?.fppaPeakL), num(m?.fppaPeakR),
                num(m?.kneeAnkleRatio), num(m?.flexionAsymmetryPct), num(m?.quality), num(m?.fps),
                clean(m?.warnings?.joinToString("|") ?: ""), r.detected.toString())
            w.write(fields.joinToString(",") + "\n")
        }
    }
    val lines = results.groupBy { it.cls }.toSortedMap().map { (cls, rs) ->
        val detected = rs.filter { it.detected }
        "$cls: clips=${rs.size} errors=${rs.count { it.error != null }} detected=${detected.size} " +
            "share=${num(100.0 * detected.size / rs.size)}% median_flight_ms=${num(median(detected.map { it.metrics!!.flightTimeMs!! })).ifEmpty { "-" }}"
    }
    return (listOf("share = detected / clips (errors count as not detected)") + lines).joinToString("\n")
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: batch <input dir> <output csv>" }
    println(runBatch(File(args[0]), File(args[1])))
}
