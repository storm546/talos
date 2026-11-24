package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import jumpscan.core.Balance
import jumpscan.core.GpsTrack
import jumpscan.core.LineCrossing
import jumpscan.core.RangeOfMotion
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal val FIELD_SAMPLES = listOf(
    RangeOfMotion.Result(RangeOfMotion.Joint.KNEE_FLEXION, "side", 134.0, 121.0, .9, emptyList()).toJson(),
    Balance.Result("L", 18_440, 2.7, "touch_down", 15.0, .92).toJson(),
    LineCrossing.json("sprint", 3_420, 33.3, 30.0, listOf("distance_m" to 20.0, "start" to "cue")),
    LineCrossing.json("agility", 2_610, 16.7, 60.0, listOf("test" to "505")),
    LineCrossing.json("reaction", 233, 33.3, 30.0),
)

/** A 2.6 km out-and-back run along a street, one fix every 4 s at about 3 m/s. */
internal fun sampleRun(start: Long): GpsTrack.Recorder = GpsTrack.Recorder("run", start).apply {
    val metre = 1 / 111_194.93
    for (i in 0..216) {
        val along = if (i <= 108) i * 12.0 else (216 - i) * 12.0
        add(GpsTrack.Fix(start + i * 4_000L, 42.6977 + along * metre, 23.3219 + (if (i <= 108) 0.0 else 30.0) * metre / 0.735, 6f))
    }
}

/**
 * Offline queue contract of the field tools (SPEC 9.3), the slow-motion guard on a real clip and the cue tone's
 * output timestamp on this device. Returns a line describing how the cue time was taken here.
 */
internal fun fieldChecks(test: Instrumentation): String {
    val root = test.targetContext.cacheDir.resolve("field-checks")
    try {
        val queue = PendingUploads(root, "https://club.example", 1)
        val bodies = FIELD_SAMPLES + GpsTrack.Recorder("run", 0).summary(60_000).toJson()
        val names = bodies.map { queue.enqueueFeature(FeatureWrite.MEASUREMENT, "", JSONObject(it), UUID.randomUUID().toString()) }
        check(runCatching { queue.enqueueFeature(FeatureWrite.MEASUREMENT, "", JSONObject(FIELD_SAMPLES[0]).put("route", "[]"),
            UUID.randomUUID().toString()) }.isFailure) // a route is never queued
        val sent = mutableListOf<JSONObject>()
        queue.send(mutableMapOf(), featureSend = { kind, path, body -> check(kind == FeatureWrite.MEASUREMENT && path == "/api/measurements"); sent += body; 1L }) { 0L }
        check(sent.size == names.size && sent.all { UUID.fromString(it.getString("client_id")) != null })
        check(sent.map { it.getString("kind") } == listOf("rom", "balance", "sprint", "agility", "reaction", "cardio"))
    } finally { root.deleteRecursively() }
    slowMotionCheck(test)
    return cueToneCheck()
}

/** A clip captured at 120 fps but stored with 30 fps timestamps is refused before any pose work. */
private fun slowMotionCheck(test: Instrumentation) {
    check(!captureRateMismatch(null, 30.0) && !captureRateMismatch(Double.NaN, 30.0) && !captureRateMismatch(30.0, 29.2) &&
        captureRateMismatch(30.0, 31.0) && captureRateMismatch(120.0, 30.0) && captureRateMismatch(1.0, 30.0))
    val file = File.createTempFile("slowmotion", ".mp4", test.targetContext.cacheDir)
    try {
        test.context.assets.open("slowmotion.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        val retriever = android.media.MediaMetadataRetriever()
        val capture = try { retriever.setDataSource(file.path)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) } finally { retriever.release() }
        check(capture?.toDoubleOrNull() == 120.0) { "Fixture capture rate must read as 120, got $capture" }
        val refused = runCatching { analyzeVideo(test.targetContext, android.net.Uri.fromFile(file), "cmj") {} }.exceptionOrNull()
        check(refused is AppError && refused.resource == R.string.slow_motion_error) { "Slow-motion clip must be refused, got $refused" }
    } finally { file.delete() }
}

/** The start tone reports when its first sample was presented, on the boot-time clock, within a second of the request. */
private fun cueToneCheck(): String {
    val done = java.util.concurrent.CountDownLatch(1)
    var reported: Pair<Long, Boolean>? = null
    val requested = SystemClock.elapsedRealtime()
    playCueTone(100, boottime = true) { at, audio -> reported = at to audio; done.countDown() }
    check(done.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "The cue tone never reported its start" }
    val (at, audio) = reported!!
    check(at in requested - 5..requested + 1000) { "Cue time $at is not near the request $requested" }
    return "Cue tone: ${if (audio) "AudioTrack output timestamp" else "request-time fallback (no timestamp)"}, ${at - requested} ms after the request."
}

/** Setup, live and result screens of every field tool against a seeded server, saved as files/<language>-field-<name>.png. */
internal class FieldStudio(private val test: Instrumentation, private val server: String) {
    private val context get() = test.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    fun screens() {
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
            .putString("name", login.getJSONObject("user").getString("name")).putLong("user_id", login.getJSONObject("user").getLong("id")).commit()
        context.getSharedPreferences("field", 0).edit().clear().commit()
        context.getSharedPreferences("tools", 0).edit().clear().putString("pinned", "rom,balance,speed,cardio").commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
        fun save(name: String) {
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$language-field-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun shot(name: String, expected: String) {
            val end = SystemClock.uptimeMillis() + 20000
            var actual = ""
            do {
                test.runOnMainSync { actual = state.get(host).toString() }
                if (actual == expected) break
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < end)
            check(actual == expected) { "$name: expected $expected, got $actual" }
            SystemClock.sleep(800); save(name)
        }
        fun scrollDown() = test.runOnMainSync { (host.page.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        fun live(name: String, mode: String, extras: Intent.() -> Unit = {}) {
            val tool = test.startActivitySync(Intent(context, CameraToolActivity::class.java).putExtra("mode", mode).putExtra("replay", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras))
            SystemClock.sleep(4000); save(name)
            test.runOnMainSync { tool.finish() }
            SystemClock.sleep(600)
        }
        fun result(name: String, json: String, local: JSONObject = JSONObject()) {
            test.runOnMainSync { host.fieldResult(FieldRun(JSONObject(json), local = local)) }; shot(name, "field_result")
        }
        try {
            test.runOnMainSync { host.formHome() }; shot("hub", "form")
            test.runOnMainSync { host.romSetup("knee_flexion") }; shot("rom-setup", "field_rom")
            test.runOnMainSync { host.romSetup("shoulder_abduction") }; shot("rom-setup-front", "field_rom")
            live("rom-live", "rom") { putExtra("joint", "knee_flexion") }
            result("rom-result", FIELD_SAMPLES[0])
            // Save through the app's own button, then show the saved state.
            test.runOnMainSync { views(host.page).filterIsInstance<Button>().first { it.text == host.getString(R.string.ft_save) }.performClick() }
            SystemClock.sleep(2500); shot("rom-saved", "field_result")
            test.runOnMainSync { host.balanceSetup() }; shot("balance-setup", "field_balance")
            live("balance-live", "balance")
            result("balance-result", FIELD_SAMPLES[1], JSONObject().put("sway_velocity_pct", 9.4).put("noise_floor_pct", 3.1))
            test.runOnMainSync { host.speedSetup("sprint") }; shot("sprint-setup", "field_speed")
            live("sprint-live", "sprint") { putExtra("distance", 20.0) }
            result("sprint-result", FIELD_SAMPLES[2], JSONObject().put("cue_clock", "audio"))
            test.runOnMainSync { host.speedSetup("agility") }; shot("agility-setup", "field_speed")
            result("agility-result", FIELD_SAMPLES[3])
            test.runOnMainSync { host.speedSetup("reaction") }; shot("reaction-setup", "field_speed")
            live("reaction-live", "reaction")
            result("reaction-result", FIELD_SAMPLES[4], JSONObject().put("cue_clock", "audio"))
            test.runOnMainSync { CardioState.running = false; CardioState.recorder = null; host.cardioHome() }; shot("cardio-setup", "cardio")
            // A recorded run injected into the service state (GPS needs a real phone outdoors).
            val start = SystemClock.elapsedRealtime() - 217 * 4_000L
            test.runOnMainSync { CardioState.recorder = sampleRun(start); CardioState.running = true; CardioState.lastAccuracy = 6f; host.cardioLive() }
            shot("cardio-live", "cardio")
            test.runOnMainSync {
                CardioState.running = false; CardioState.endedAt = start + 216 * 4_000L
                host.fieldResult(FieldRun(JSONObject(CardioState.recorder!!.summary(CardioState.endedAt).toJson())))
            }
            shot("cardio-result", "field_result")
            scrollDown(); shot("cardio-result-end", "field_result")
            test.runOnMainSync { host.records() }; shot("records", "records")
            scrollDown(); shot("records-end", "records")
        } finally {
            test.runOnMainSync { CardioState.running = false; CardioState.recorder = null; host.finish() }
        }
    }
}
