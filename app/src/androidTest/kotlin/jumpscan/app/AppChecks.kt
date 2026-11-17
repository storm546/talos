package jumpscan.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import jumpscan.core.Frame
import jumpscan.core.JumpMetrics
import jumpscan.core.JumpAnalyzer
import jumpscan.core.Landmark
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/** Small platform-only checks; no test library is needed. */
class AppChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            queueChecks()
            previewChecks()
            numberChecks()
            output.putString("stream", "Queue isolation, durability, failure retention, payload boundaries, preview and number-format checks passed.\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "App checks failed: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun numberChecks() {
        for ((locale, decimal) in listOf(Locale.ENGLISH to ".", Locale.forLanguageTag("bg") to ",")) {
            check(metricNumber(280.0, "flight_time_ms", locale) == "280")
            check(metricNumber(24.91, "fps", locale) == "25")
            for (key in listOf("knee_ankle_ratio", "quality")) {
                check(metricNumber(0.876, key, locale) == "0${decimal}88")
                check(metricNumber(-0.001, key, locale) == "0${decimal}00")
            }
            for (key in listOf("jump_height_cm", "knee_flexion_contact_l", "knee_flexion_contact_r",
                    "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r", "flexion_asymmetry_pct")) {
                check(metricNumber(15.89, key, locale) == "15${decimal}9")
                check(metricNumber(-0.01, key, locale) == "0${decimal}0")
                check(metricNumber(-0.16, key, locale) == "-0${decimal}2")
            }
            check(metricNumber(-0.0, "jump_height_cm", locale) == "0${decimal}0")
            check(metricNumber(-0.1, "fps", locale) == "0")
        }
    }

    private fun queueChecks() {
        val root = File(targetContext.cacheDir, "pending-checks")
        root.deleteRecursively()
        val metrics = JumpMetrics("cmj", 25.0, 360.0, 15.89, 20.0, 21.0, 60.0, 61.0,
            2.0, 3.0, 0.9, 1.64, 0.9, emptyList())
        try {
            val queue = PendingUploads(root, "https://club.example", 1)
            val name = queue.enqueue(metrics)
            check(queue.count() == 1)
            check(PendingUploads(root, "https://club.example", 2).count() == 0)
            check(PendingUploads(root, "https://other.example", 1).count() == 0)
            val restored = PendingUploads(root, "https://club.example", 1)
            val sent = linkedMapOf<String, Long>()
            try { restored.send(sent) { throw IOException("Simulated network failure") }; error("Expected network failure") }
            catch (_: IOException) { check(restored.count() == 1 && sent.isEmpty()) }
            try { restored.send(sent) { throw ApiError(401) }; error("Expected HTTP failure") }
            catch (_: ApiError) { check(restored.count() == 1) }
            restored.send(sent) { json ->
                check(json.length() == 14 && json.getDouble("flight_time_ms") == 360.0)
                check(!json.has("video") && !json.has("landmarks"))
                7L
            }
            check(sent[name] == 7L && restored.count() == 0)
            queue.enqueue(metrics)
            val file = root.walkTopDown().first { it.isFile && it.extension == "json" }
            file.writeText(JSONObject().put("video", "must never be sent").toString())
            try { queue.send(linkedMapOf()) { error("Invalid payload reached network") }; error("Expected invalid payload") }
            catch (e: AppError) { check(e.resource == R.string.pending_unreadable && queue.count() == 1) }
        } finally { root.deleteRecursively() }
    }

    private fun previewChecks() {
        val points = MutableList(33) { Landmark(0f, 0f, 0f, 1f) }
        for (side in 0..1) {
            val x = if (side == 0) 0.4f else 0.6f
            listOf(23 to 0.3f, 25 to 0.5f, 27 to 0.7f, 29 to 0.8f, 31 to 0.8f).forEach { (i, y) ->
                points[i + side] = Landmark(x, y, 0f, 1f)
            }
        }
        val frame = Frame(0, points.toList(), points.toList())
        val raw = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val preview = landingPreview(raw, frame)
        check(preview.getPixel(40, 30) == LEFT_COLOR && preview.getPixel(60, 30) == RIGHT_COLOR)
        check(raw.getPixel(40, 30) == Color.WHITE)
        val hidden = frame.copy(landmarks = points.mapIndexed { i, p -> if (i == 25) p.copy(visibility = 0f) else p })
        val hiddenPreview = landingPreview(raw, hidden)
        check(hiddenPreview.getPixel(40, 50) == Color.WHITE)
        val frames = List(15) { frame.copy(tMs = it * 40L) }
        check(JumpAnalyzer.analyzeWithLanding(frames, "cmj", 1f).contactMs == null)
        check(peakFlexionFrame(frames) in frames)
        val profile = frames.map { f -> f.copy(landmarks = f.landmarks.mapIndexed { i, point ->
            if (i in listOf(24, 26, 28, 30, 32)) point.copy(visibility = 0f) else point
        }) }
        check(peakFlexionFrame(profile) in profile)
        check(!unclearPreview(frame) && unclearPreview(hidden))
        check(hiddenPreview.getPixel(40, 70) == Color.WHITE) { "An unclear leg must not leave floating ankle markers" }
        raw.recycle(); preview.recycle(); hiddenPreview.recycle()
    }
}
