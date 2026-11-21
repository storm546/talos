package jumpscan.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import jumpscan.core.Frame
import jumpscan.core.FormMetrics
import jumpscan.core.FormRepetition
import jumpscan.core.FormSummary
import jumpscan.core.JumpMetrics
import jumpscan.core.JumpAnalyzer
import jumpscan.core.Landmark
import jumpscan.core.toJson
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.time.LocalDate

/** Small platform-only checks; no test library is needed. */
class AppChecks : Instrumentation() {
    private var fixtureServer: String? = null
    private var themeName = "dark"
    private var movies = false
    private var only: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments)
        only = arguments?.getString("only")
        fixtureServer = arguments?.getString("fixtureServer")
        themeName = arguments?.getString("theme") ?: "dark"
        movies = arguments?.getString("movies") == "yes"
        start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val capture = CaptureChecks(this)
            if (only == "studio") {
                targetContext.getSharedPreferences("instrument", 0).edit().putString("appearance", themeName).commit()
                val studio = StudioChecks(this, movies)
                studio.screens(requireNotNull(fixtureServer))
                capture.visual(studio)
            } else if (only == "nutrition") {
                targetContext.getSharedPreferences("instrument", 0).edit().putString("appearance", themeName).commit()
                NutritionStudio(this).screens()
            } else if (only == "records") {
                targetContext.getSharedPreferences("instrument", 0).edit().putString("appearance", themeName).commit()
                RecordsStudio(this).screens(requireNotNull(fixtureServer))
            } else if (only == "capture-parity") output.putString("parity", capture.parity())
            else if (only == "capture-visual") capture.visual()
            else if (only == "cutoff") videoCutoffCheck()
            else if (only == "timestamp") videoPreviewCheck()
            else {
                clubChecks()
                assistantConfirmationCheck()
                queueChecks()
                featureChecks()
                previewChecks()
                videoPreviewCheck()
                videoCutoffCheck()
                numberChecks()
                recordChecks()
                capture.policies()
                diagnosticChecks()
            }
            output.putString("stream", "App checks passed.\n" + output.getString("parity").orEmpty())
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "App checks failed: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun assistantConfirmationCheck() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            runOnMainSync {
                val summary = "Cancel Thursday for the under-18 group; note: bring water."
                activity.assistantChat.proposal = JSONObject().put("proposal_id", "test").put("actions",
                    org.json.JSONArray().put(JSONObject().put("tool", "future_action").put("human_summary", summary)))
                activity.assistantScreen()
                fun labels(view: View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val review = labels(activity.page)
                val confirm = review.indexOf(activity.getString(R.string.assistant_confirm))
                check(review.indexOf(summary) in 0 until confirm)
            }
        } finally { runOnMainSync { activity.finish() } }
    }

    private fun diagnosticChecks() {
        check(p95Ms(emptyList()) == null && p95Ms((20 downTo 1).map { it * 1_000_000L }) == 19.0)
        val at30 = JSONObject().put("kind", "jump").put("status", "no_flight").put("clip_fps", 29.97)
            .put("trial", 11).put("trial_target_fps", 60)
        check(!at30.completedJump())
        check(at30.put("trial_target_fps", 30).completedJump())
        check(PhoneTrial(5).view == "front" && PhoneTrial(6).view == "side" && PhoneTrial(16).plannedFps == 60)
        val csv = diagnosticsCsv(listOf(at30.put("device_model", "=Model, \"test\"")))
        check("\"'=Model, \"\"test\"\"\"" in csv && ",29.970," in csv && csv.endsWith("\r\n"))
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$name-checks", mode)
            override fun getCacheDir() = File(super.getCacheDir(), "diagnostic-checks")
        }
        val log = DiagnosticLog(isolated)
        log.clear()
        try {
            repeat(201) { index -> log.append(JSONObject().put("index", index)) }
            val restored = DiagnosticLog(isolated)
            check(restored.read().size == 200 && restored.read().first().getInt("index") == 1)
            restored.append(JSONObject().put("trial", 21).put("status", "ok").put("app_reps", 4))
            restored.saveCount(21, 5)
            restored.saveCount(21, 9)
            check(log.read().last().getInt("human_reps") == 5)
        } finally { log.clear() }
        check(log.read().isEmpty())
    }

    private fun featureChecks() {
        check(decimal("80,25") == 80.25 && decimal("80.25") == 80.25 && decimal("NaN") == null)
        val day = "2026-11-18"
        val set = setBody(day, 9, null, listOf("1", "6", "10,5", "7"))!!
        check(set.getDouble("load_kg") == 10.5 && set.isNull("plan_id"))
        check(setBody(day, 9, null, listOf("1", "6", "", "11")) == null)
        val week = weekStart(LocalDate.parse(day))
        val plan = JSONObject("""{"starts_on":"$day","weeks":1,"items":[{"weekday":0},{"weekday":2}]}""")
        check(planDays(plan, week).single().first.toString() == day)
        check(planDays(plan, week.plusWeeks(1)).single().first.toString() == "2026-11-23")
        val root = targetContext.cacheDir.resolve("feature-checks")
        try {
            val queue = PendingUploads(root, "https://club.example", 1)
            val form = JSONObject(FormMetrics("squat", null, "front", 30.0, 1.0, emptyList(),
                FormSummary(0, 0, 0, null, null, null, null, null, null, null, null, null, null), emptyList()).toJson())
            val food = JSONObject().put("day", day).put("meal", "lunch").put("food_id", 2).put("grams", 123.5)
            val uuid = UUID.randomUUID().toString()
            val formName = queue.enqueueFeature(FeatureWrite.FORM, "", form, uuid)
            queue.enqueueFeature(FeatureWrite.FOOD, "", food, UUID.randomUUID().toString())
            queue.enqueueFeature(FeatureWrite.WEIGHT, day, JSONObject().put("kg", 81.5), UUID.randomUUID().toString())
            queue.enqueueFeature(FeatureWrite.SET, "", set, UUID.randomUUID().toString())
            queue.enqueueFeature(FeatureWrite.COMPLETE, day, JSONObject(), UUID.randomUUID().toString())
            // Version 2: quick add without a food, a catalogue food with a serving, and copying yesterday.
            queue.enqueueFeature(FeatureWrite.FOOD, "", JSONObject().put("day", day).put("meal", "snack").put("label", "Bar")
                .put("kcal", 210).put("protein_g", 10).put("carbs_g", 25).put("fat_g", 8), UUID.randomUUID().toString())
            queue.enqueueFeature(FeatureWrite.FOOD, "", JSONObject().put("day", day).put("meal", "lunch").put("catalog_id", 171287)
                .put("grams", 60).put("serving_label", "slice").put("quantity", 2), UUID.randomUUID().toString())
            queue.enqueueFeature(FeatureWrite.COPY, "", JSONObject().put("from_day", "2026-11-20").put("to_day", day)
                .put("from_meal", "lunch").put("to_meal", "lunch"), UUID.randomUUID().toString())
            check(runCatching { queue.enqueueFeature(FeatureWrite.FOOD, "", JSONObject().put("day", day).put("meal", "lunch")
                .put("photo", "local only"), UUID.randomUUID().toString()) }.isFailure)
            check(validBarcode("3800123456789") && validBarcode("12345670") && !validBarcode("1234567") && !validBarcode("38001234x6789"))
            check(servingsOf(JSONObject("""{"servings":[{"label":"slice","grams":30}]}""")).map { it.first } == listOf("slice", "100 g", "g"))
            check(PendingUploads(root, "https://club.example", 2).count() == 0)
            check(runCatching { queue.enqueueFeature(FeatureWrite.FORM, "", JSONObject(form.toString())
                .put("video", "local only"), uuid) }.isFailure)
            liveFormChecks(root)
            val restored = PendingUploads(root, "https://club.example", 1)
            val firstAttempt = runCatching {
                restored.send(linkedMapOf(), featureSend = { _, path, json ->
                    check(path == "/api/form" && json.getString("client_id") == uuid)
                    throw IOException("Response lost")
                }) { error("Not a jump") }
            }
            check(firstAttempt.exceptionOrNull() is IOException && restored.count() == 8)
            val routes = mutableListOf<String>()
            val sent = linkedMapOf<String, Long>()
            restored.send(sent, featureSend = { kind, path, json ->
                routes.add("${kind.method} $path")
                when (kind) {
                    FeatureWrite.FORM -> check(json.getString("client_id") == uuid && json.isNull("side"))
                    FeatureWrite.FOOD -> check((json.optDouble("grams") in listOf(123.5, 60.0) || json.getString("label") == "Bar") && json.has("client_id"))
                    FeatureWrite.COPY -> check(json.getString("to_day") == day && json.getString("to_meal") == "lunch" && json.has("client_id"))
                    FeatureWrite.WEIGHT -> check(json.getDouble("kg") == 81.5 && !json.has("client_id"))
                    FeatureWrite.SET -> check(json.getInt("reps") == 6 && json.has("client_id"))
                    FeatureWrite.COMPLETE -> check(json.length() == 0)
                }
                17L
            }) { error("Not a jump") }
            check(routes == listOf("POST /api/form", "POST /api/food-logs", "PUT /api/me/weights/$day",
                "POST /api/set-logs", "PUT /api/me/nutrition/days/$day/complete", "POST /api/food-logs", "POST /api/food-logs",
                "POST /api/food-logs/copy"))
            check(sent[formName] == 17L && restored.count() == 0)
            // SPEC 7.4: a copy from an empty day is dropped (not set aside) with a receipt the screen turns into a message.
            val empty = restored.enqueueFeature(FeatureWrite.COPY, "", JSONObject().put("from_day", "2026-11-01").put("to_day", day),
                UUID.randomUUID().toString())
            val emptySent = linkedMapOf<String, Long>()
            restored.send(emptySent, featureSend = { _, _, _ ->
                throw ApiError(422, """{"detail":"Nothing to copy from that day","code":"nothing_to_copy"}""")
            }) { error("Not a jump") }
            check(emptySent[empty] == NOTHING_COPIED && restored.count() == 0 && restored.rejectedCount() == 0)
        } finally { root.deleteRecursively() }
    }

    /** Live sets carry mode, source, per-repetition checks and hold times, and are queued like recorded sets. */
    private fun liveFormChecks(root: File) {
        val queue = PendingUploads(root.resolve("live"), "https://club.example", 1)
        val rep = FormRepetition(1, "complete", emptyList(), 0, 400, 900, null, null, null, null, null, null,
            400, 500, null, mapOf("elbow_angle" to 82.5, "body_line" to null))
        val pushUp = JSONObject(FormMetrics("push_up", null, "side", 14.0, .9, listOf(rep),
            FormSummary(1, 0, 0, null, null, null, null, null, null, null, null, null, null), emptyList(),
            mode = "reps", source = "live").toJson())
        val plank = JSONObject(FormMetrics("plank", null, "side", 14.0, .9, emptyList(),
            FormSummary(0, 0, 0, null, null, null, null, null, null, null, null, null, null, 31_000, 27_500),
            emptyList(), mode = "hold", source = "live").toJson())
        val names = listOf(pushUp, plank).map { queue.enqueueFeature(FeatureWrite.FORM, "", it, UUID.randomUUID().toString()) }
        check(listOf(pushUp to "reps", plank to "hold").all { (body, mode) -> body.getString("mode") == mode })
        for (bad in listOf(JSONObject(plank.toString()).put("mode", "reps"), JSONObject(plank.toString()).put("source", "camera"),
            JSONObject(pushUp.toString()).apply { getJSONArray("repetitions").getJSONObject(0)
                .getJSONObject("checks").put("hip_angle", 1) })) {
            check(runCatching { queue.enqueueFeature(FeatureWrite.FORM, "", bad, UUID.randomUUID().toString()) }.isFailure)
        }
        val sent = linkedMapOf<String, Long>()
        val bodies = mutableListOf<JSONObject>()
        PendingUploads(root.resolve("live"), "https://club.example", 1).send(sent, featureSend = { kind, path, json ->
            check(kind == FeatureWrite.FORM && path == "/api/form")
            bodies += json
            bodies.size.toLong()
        }) { error("Not a jump") }
        check(sent.keys == names.toSet() && bodies.all { it.getString("source") == "live" && it.has("client_id") })
        check(bodies[1].getJSONObject("summary").getLong("in_band_ms") == 27_500L)
        check(bodies[0].getJSONArray("repetitions").getJSONObject(0).getJSONObject("checks").getDouble("elbow_angle") == 82.5)
    }

    private fun clubChecks() {
        val sessions = parseSessions("""[
            {"id":2,"title":"Cancelled","starts_at":"2026-11-19T12:30:00Z","place":"Hall","test":"cmj","trials":3,"jump_count":1,"cancelled":1},
            {"id":1,"title":"Next","starts_at":"2026-11-18T12:30:00Z","place":"Hall","test":"drop_jump","trials":5,"jump_count":0,"cancelled":false}]
        """)
        check(sessions.map { it.id } == listOf(1L, 2L) && sessions[1].cancelled && sessions[1].jumpCount == 1)
        check(localDateTime(sessions[0].startsAt, Locale.UK, java.time.ZoneId.of("Europe/Sofia")).contains("14:30"))
        // The real server emits offsets (+00:00), not Z; both must parse on Android 8–13 libcore.
        val offsetSessions = parseSessions("""[
            {"id":1,"title":"Offset","starts_at":"2026-11-18T12:30:00+00:00","place":"Hall","test":"cmj","trials":3,"jump_count":0,"cancelled":false}]
        """)
        check(offsetSessions.size == 1)
        check(localDateTime("2026-11-18T12:30:00+00:00", Locale.UK, java.time.ZoneId.of("UTC")).contains("12:30"))
        val offsetComments = parseComments("""[
            {"id":1,"author_name":"Coach","author_role":"coach","body":"Hi","created_at":"2026-11-18T10:00:00+00:00","jump_id":null,"session_id":null}]
        """)
        check(offsetComments.size == 1)
        check(localDateTime(offsetComments.single().createdAt, Locale.UK, java.time.ZoneId.of("Europe/Sofia")).contains("12:00"))
        check(localDateTime("2026-11-18T12:30:00+03:00", Locale.UK, java.time.ZoneId.of("Europe/Sofia")).contains("11:30"))
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

    private fun recordChecks() {
        // A 400 ms flight is a 19.6 cm jump (h = g t² / 8), both ways.
        check(kotlin.math.abs(recordJump(19.6133).getDouble("flight_time_ms") - 400) < .01)
        check(kotlin.math.abs(heightFromFlight(400.0) - 19.6133) < .001)
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
            for (status in listOf(401, 408, 429, 500)) {
                try { restored.send(sent) { throw ApiError(status) }; error("Expected HTTP failure") }
                catch (e: ApiError) { check(e.status == status && restored.count() == 1 && restored.rejectedCount() == 0) }
            }
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
            // A permanent rejection is quarantined and does not block the rest of the queue.
            val staleDay = java.time.LocalDate.parse(todayLocal()).minusDays(30).toString()
            queue.enqueueCheckin(staleDay, checkinBody(List(5) { 3 }, "", ""))
            queue.enqueue(metrics)
            queue.send(linkedMapOf(), put = { date, _ ->
                if (date == staleDay) throw ApiError(422)
            }) { 9L }
            check(queue.count() == 0 && queue.rejectedCount() == 1)
            // An unreadable file is quarantined, not sent, and the queue continues.
            queue.enqueue(metrics)
            val file = root.walkTopDown().first { it.isFile && it.extension == "json" }
            file.writeText(JSONObject().put("video", "must never be sent").toString())
            queue.enqueue(metrics, clientId = java.util.UUID.randomUUID().toString())
            val healthyName = root.walkTopDown().map { it.name }.first { it != file.name && it.endsWith(".json") }
            val sentMap = linkedMapOf<String, Long>()
            queue.send(sentMap) { 5L }
            check(queue.rejectedCount() == 2 && sentMap[healthyName] == 5L)
            // A jump rejected only for its session is retried once without it, same client_id.
            val rejectedSessionName = queue.enqueue(metrics, sessionId = 999)
            val rejectedSessionId = rejectedSessionName.removeSuffix(".json")
            var attempts = 0
            queue.sendJump(rejectedSessionName) { body ->
                attempts++
                check(body.getString("client_id") == rejectedSessionId)
                if (body.has("session_id")) throw ApiError(422,
                    """{"detail":"Session not found","code":"jump_session_unavailable"}""")
                6L
            }
            check(queue.count() == 0 && attempts == 2)
            val invalidMetricsName = queue.enqueue(metrics, sessionId = 999)
            attempts = 0
            val invalid = ApiError(422, """{"detail":[{"loc":["body","fps"],"type":"less_than_equal"}]}""")
            check(runCatching { queue.sendJump(invalidMetricsName) {
                attempts++
                throw invalid
            } }.exceptionOrNull() === invalid && attempts == 1 && queue.count() == 1)
            queue.sendJump(invalidMetricsName) { 7L }
            // Every permanent 4xx is set aside, including a failed session fallback.
            val permanentNames = listOf(400, 403, 404, 422).associate { status ->
                queue.enqueue(metrics, sessionId = 999).removeSuffix(".json") to status
            }
            val healthy = queue.enqueue(metrics)
            val permanentSent = linkedMapOf<String, Long>()
            queue.send(permanentSent) { body ->
                permanentNames[body.getString("client_id")]?.let { throw ApiError(it) }
                10L
            }
            check(queue.count() == 0 && queue.rejectedCount() == 6 && permanentSent[healthy] == 10L)
            // A corrupt backup and a malformed check-in filename cannot block later work.
            val backup = root.walkTopDown().first { it.isDirectory && it != root }
            File(backup, "broken.json.bak").writeText("{")
            File(backup, "checkin-invalid.json").writeText("{}")
            queue.enqueue(metrics)
            queue.send(linkedMapOf()) { 11L }
            check(queue.count() == 0 && queue.rejectedCount() == 8)
            // A missing file is already sent, not an error.
            check(queue.sendJump(java.util.UUID.randomUUID().toString() + ".json") { error("Must not post") } == null)
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

    private fun videoPreviewCheck() {
        val file = File.createTempFile("reordered", ".mp4", targetContext.cacheDir)
        try {
            context.assets.open("reordered.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val timeUs = 2_000_000L
                val expected = checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                val actual = videoFrame(targetContext, Uri.fromFile(file), listOf(timeUs), 0, 128f / 96f)
                check((0 until expected.height).all { row -> (0 until expected.width).all { column ->
                    actual.getPixel(column, row) == expected.getPixel(column, row)
                } }) { "A preview must use its presentation timestamp, not its list index" }
                actual.recycle(); expected.recycle()
            } finally { retriever.release() }
        } finally { file.delete() }
    }

    private fun videoCutoffCheck() {
        val file = File.createTempFile("reordered", ".mp4", targetContext.cacheDir)
        try {
            context.assets.open("reordered.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
            val uri = Uri.fromFile(file)
            val cutoff = 10_000_000L
            val extractor = MediaExtractor()
            val decodeOrder = try {
                extractor.setDataSource(file.path)
                extractor.selectTrack(0)
                buildList {
                    while (extractor.sampleTime >= 0) {
                        add(extractor.sampleTime)
                        if (!extractor.advance()) break
                    }
                }
            } finally { extractor.release() }
            val prefix = decodeOrder.takeWhile { it < cutoff }
            val expected = decodeOrder.filter { it < cutoff }.distinct().sorted()
            check(expected.size == 120 && prefix.size == 118) { "Fixture must cross the cutoff in decode order" }
            val actual = videoSampleTimes(targetContext, uri, cutoff)
            check(actual == expected) { "Expected ${expected.size} presentations before cutoff, got ${actual.size}" }
        } finally { file.delete() }
    }
}
