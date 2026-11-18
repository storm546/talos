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
            clubChecks()
            queueChecks()
            previewChecks()
            numberChecks()
            output.putString("stream", "Club parsing, check-in validation, queue migration, retry identity, isolation, durability, preview and number-format checks passed.\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "App checks failed: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun clubChecks() {
        val sessions = parseSessions("""[
            {"id":2,"title":"Cancelled","starts_at":"2026-11-19T12:30:00Z","place":"Hall","test":"cmj","trials":3,"jump_count":1,"cancelled":1},
            {"id":1,"title":"Next","starts_at":"2026-11-18T12:30:00Z","place":"Hall","test":"drop_jump","trials":5,"jump_count":0,"cancelled":false}]
        """)
        check(sessions.map { it.id } == listOf(1L, 2L) && sessions[1].cancelled && sessions[1].jumpCount == 1)
        check(localDateTime(sessions[0].startsAt, Locale.UK, java.time.ZoneId.of("Europe/Sofia")).contains("14:30"))
        for (instant in listOf("2026-11-17T22:30:00Z", "2026-06-17T21:30:00Z")) {
            val clock = java.time.Clock.fixed(java.time.Instant.parse(instant), java.time.ZoneId.of("Europe/Sofia"))
            check(todayLocal(clock) == instant.substring(0, 8) + "18")
        }
        check(localDate("2026-11-18", Locale.UK) == "18 Nov 2026")
        check(localDate("2026-11-18", Locale.US) == "Nov 18, 2026")
        check(!targetContext.getString(R.string.checkin_day, localDate(todayLocal(), Locale.getDefault())).contains("UTC"))
        val comments = parseComments("""[
            {"id":1,"author_name":"Coach","author_role":"coach","body":"First","created_at":"2026-11-18T10:00:00Z","jump_id":3,"session_id":null},
            {"id":2,"author_name":"Athlete","author_role":"athlete","body":"Reply","created_at":"2026-11-18T11:00:00Z","jump_id":null,"session_id":4}]
        """)
        check(comments[0].id == 2L && comments[0].sessionId == 4L && comments[1].jumpId == 3L)
        check(checkinBody(List(5) { 5 }, "0", "10").getInt("rpe") == 10)
        check(checkinBody(List(5) { 1 }, "", "").isNull("rpe"))
        for ((scores, minutes, rpe) in listOf(Triple(List(5) { 0 }, "", ""), Triple(List(4) { 3 }, "", ""),
                Triple(List(5) { 3 }, "20", ""), Triple(List(5) { 3 }, "601", "5"), Triple(List(5) { 3 }, "20", "11"))) {
            try { checkinBody(scores, minutes, rpe); error("Invalid check-in accepted") }
            catch (e: AppError) { check(e.resource == R.string.checkin_invalid) }
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
                check(json.length() == 15 && json.getDouble("flight_time_ms") == 360.0)
                check(!json.has("video") && !json.has("landmarks"))
                7L
            }
            check(sent[name] == 7L && restored.count() == 0)
            // Older app files gain a durable UUID before the first network attempt.
            queue.enqueue(metrics)
            val legacy = root.walkTopDown().first { it.isFile && it.extension == "json" }
            legacy.writeText(JSONObject(legacy.readText()).apply { remove("client_id") }.toString())
            var uuid = ""
            try { queue.send(linkedMapOf()) { json ->
                uuid = json.getString("client_id"); java.util.UUID.fromString(uuid)
                throw IOException("Response lost after storage")
            }; error("Expected lost response") } catch (_: IOException) { }
            check(JSONObject(legacy.readText()).getString("client_id") == uuid)
            queue.send(linkedMapOf()) { check(it.getString("client_id") == uuid); 7L }
            check(queue.count() == 0)
            val sessionName = queue.enqueue(metrics, sessionId = 12)
            queue.sendJump(sessionName) { check(it.getLong("session_id") == 12L); 8L }
            val day = todayLocal()
            queue.enqueueCheckin(day, checkinBody(listOf(1, 2, 3, 4, 5), "", ""))
            queue.enqueueCheckin(day, checkinBody(listOf(5, 4, 3, 2, 1), "40", "6"))
            check(queue.count() == 1 && queue.checkin(day)!!.getInt("sleep") == 5)
            try { queue.send(linkedMapOf(), put = { _, _ -> throw IOException() }) { error("Not a jump") } }
            catch (_: IOException) { check(queue.count() == 1) }
            queue.send(linkedMapOf(), put = { date, body ->
                check(date == day && body.getInt("training_minutes") == 40 && body.getInt("rpe") == 6)
            }) { error("Not a jump") }
            check(queue.count() == 0 && queue.checkin(day) == null)
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
