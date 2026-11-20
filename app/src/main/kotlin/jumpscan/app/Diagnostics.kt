package jumpscan.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal data class PhoneTrial(val number: Int, val targetFps: Int? = null) {
    val view get() = if (number in 1..5 || number in 11..15) "front" else if (number <= 20) "side" else null
    val plannedFps get() = if (number <= 10) 30 else 60
}

private fun Context.battery() = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)).let { state ->
    val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val temperature = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    (if (level >= 0 && scale > 0) 100.0 * level / scale else null) to
        temperature.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
}

internal class RunTiming(private val context: Context, private val kind: String, private val model: String?,
    private val trial: PhoneTrial? = null) {
    private val startBattery = context.battery()
    private val started = SystemClock.elapsedRealtimeNanos()
    private val decodeNs = mutableListOf<Long>()
    private val poseNs = mutableListOf<Long>()
    private var captured = 0
    private var ended = false
    var clipMs: Long? = null
    var clipFps: Double? = null
    var appReps: Int? = null

    val elapsedMs get() = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L

    inline fun <T> decode(action: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return action().also { if (it != null) decoded(SystemClock.elapsedRealtimeNanos() - start) }
    }

    @Synchronized fun decoded(nanos: Long) { if (!ended) decodeNs.add(nanos) }
    @Synchronized fun analysed(nanos: Long) { if (!ended) poseNs.add(nanos) }
    @Synchronized fun capture() { if (!ended) captured++ }

    @Synchronized fun save(status: String) {
        check(!ended)
        ended = true
        val wallMs = elapsedMs
        val endBattery = context.battery()
        val live = kind == "form_live" || kind == "form_replay"
        val setMs = clipMs ?: 0L
        val sourceFrames = if (kind == "form_replay") (setMs + 49) / 50 else captured.toLong()
        val row = JSONObject().put("kind", kind).put("status", status)
            .put("device_model", Build.MODEL).put("android_version", Build.VERSION.RELEASE)
            .put("model", model).put("delegate", model?.let { "CPU" })
            .put("clip_ms", clipMs).put("clip_fps", clipFps)
            .put("frames_decoded", decodeNs.size).put("frames_analysed", poseNs.size)
            .put("decode_mean_ms", meanMs(decodeNs)).put("decode_p95_ms", p95Ms(decodeNs))
            .put("pose_mean_ms", meanMs(poseNs)).put("pose_p95_ms", p95Ms(poseNs))
            .put("wall_ms", wallMs)
            .put("live_fps", if (live && setMs > 0) poseNs.size * 1000.0 / setMs else null)
            .put("dropped_frames", if (live) (sourceFrames - poseNs.size).coerceAtLeast(0) else null)
            .put("battery_start_pct", startBattery.first).put("battery_end_pct", endBattery.first)
            .put("battery_start_c", startBattery.second).put("battery_end_c", endBattery.second)
            .put("trial", trial?.number).put("trial_view", trial?.view)
            .put("trial_planned_fps", trial?.targetFps?.let { trial.plannedFps }).put("trial_target_fps", trial?.targetFps)
            .put("app_reps", appReps)
        DiagnosticLog(context).append(row)
    }
}

private fun meanMs(samples: List<Long>) = samples.takeIf { it.isNotEmpty() }?.average()?.div(1e6)

internal fun p95Ms(samples: List<Long>): Double? = if (samples.isEmpty()) null
    else samples.sorted()[ceil(samples.size * .95).toInt() - 1] / 1e6

internal fun JSONObject.completedJump(): Boolean = optString("kind") == "jump" &&
    optString("status") in listOf("ok", "no_flight") && has("trial_target_fps") &&
    abs(optDouble("clip_fps") - getInt("trial_target_fps")) <= 5.0

internal fun JSONObject.completedSquat(): Boolean = optInt("trial") in 21..23 && optString("status") == "ok" && has("app_reps")

internal class DiagnosticLog(private val context: Context) {
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val exportDir get() = File(context.cacheDir, "diagnostic-reports")

    fun read(): List<JSONObject> = JSONArray(prefs.getString("runs", "[]")).let { rows ->
        List(rows.length()) { rows.getJSONObject(it) }
    }

    private fun write(rows: List<JSONObject>) {
        check(prefs.edit().putString("runs", JSONArray(rows).toString()).commit())
    }

    fun append(row: JSONObject) = synchronized(lock) { write((read() + row).takeLast(200)) }

    fun saveCount(trial: Int, count: Int) = synchronized(lock) {
        require(count >= 0)
        val rows = read()
        val row = rows.lastOrNull { it.optInt("trial") == trial && it.completedSquat() && !it.has("human_reps") }
            ?: return@synchronized
        row.put("human_reps", count)
        write(rows)
    }

    fun clear() = synchronized(lock) {
        write(emptyList())
        exportDir.deleteRecursively()
    }

    fun share(rows: List<JSONObject>): Intent = synchronized(lock) {
        val summary = summary(rows)
        exportDir.mkdirs()
        exportDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            ?.forEach { it.deleteRecursively() }
        val folder = Files.createTempDirectory(exportDir.toPath(), "report-").toFile()
        val files = listOf(File(folder, "jumpscan-diagnostics.csv").apply { writeText(diagnosticsCsv(rows)) },
            File(folder, "jumpscan-summary.txt").apply { writeText(summary) })
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-files", it) })
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).apply {
                addItem(ClipData.Item(uris.last()))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun summary(rows: List<JSONObject>) = buildString {
        val last = rows.last()
        appendLine(context.getString(R.string.diag_summary_title, last.getString("device_model"), last.getString("android_version")))
        val jumps = rows.filter { it.completedJump() }.distinctBy { it.optInt("trial") }
        val squats = rows.filter { it.completedSquat() && it.has("human_reps") }.distinctBy { it.optInt("trial") }
        appendLine(context.getString(R.string.diag_summary_trials, jumps.size, jumps.count { it.optString("status") == "ok" }, squats.size))
        for ((kind, runs) in rows.groupBy { it.getString("kind") }) {
            val fps = runs.filter { it.has("live_fps") }.map { it.getDouble("live_fps") }
            val drops = runs.sumOf { it.optLong("dropped_frames") }
            val unavailable = runs.count { it.optString("status") !in listOf("ok", "no_flight") }
            append(context.getString(R.string.diag_summary_runs, context.getString(runTitle(kind)), runs.size, unavailable,
                runs.map { it.getLong("wall_ms") }.average() / 1000))
            if (fps.isNotEmpty()) append(context.getString(R.string.diag_summary_live, fps.average(), drops))
            appendLine()
        }
        for (row in squats) {
            val app = row.getInt("app_reps")
            val human = row.getInt("human_reps")
            appendLine(context.getString(R.string.diag_summary_reps, row.getInt("trial") - 20, app, human, app - human))
        }
    }

    private companion object { val lock = Any() }
}

internal fun runTitle(kind: String) = when (kind) {
    "jump" -> R.string.diag_jump
    "jump_manual" -> R.string.diag_jump_manual
    "jump_replay" -> R.string.diag_jump_replay
    "form_recorded" -> R.string.diag_recorded
    "form_replay" -> R.string.diag_replayed
    else -> R.string.diag_live
}

private val columns = ("kind,status,device_model,android_version,model,delegate,clip_ms,clip_fps,frames_decoded,frames_analysed," +
    "decode_mean_ms,decode_p95_ms,pose_mean_ms,pose_p95_ms,wall_ms,live_fps,dropped_frames,battery_start_pct," +
    "battery_end_pct,battery_start_c,battery_end_c,trial,trial_view,trial_planned_fps,trial_target_fps,app_reps,human_reps").split(',')

internal fun diagnosticsCsv(rows: List<JSONObject>) = buildString {
    append(columns.joinToString(",")).append("\r\n")
    for (row in rows) {
        append(columns.joinToString(",") { column ->
            when (val value = row.opt(column)) {
                null, JSONObject.NULL -> ""
                is Double -> String.format(Locale.ROOT, "%.3f", value)
                is Number -> value.toString()
                else -> "\"" + value.toString().let { text ->
                    if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$text" else text
                }.replace("\"", "\"\"") + "\""
            }
        }).append("\r\n")
    }
}
