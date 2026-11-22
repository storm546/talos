package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import jumpscan.core.JumpMetrics
import org.json.JSONObject
import java.io.File

/** Wave 2 checks without a server: check-in fields, legacy queued check-ins, sessions and readiness jumps. */
internal fun Instrumentation.readinessChecks() {
    check(clockTime("7:05") == "07:05" && clockTime("23:59") == "23:59" && clockTime("24:00") == null)
    val full = checkinBody(List(5) { 3 }, "", "", "52", "85,5", "23:10", "7:05")
    check(full.getInt("resting_hr") == 52 && full.getDouble("hrv_rmssd") == 85.5 && full.getString("wake_time") == "07:05")
    check(checkinBody(List(5) { 3 }, "", "").isNull("bedtime"))
    for ((hr, hrv, bed, wake) in listOf(listOf("10", "", "", ""), listOf("", "0", "", ""), listOf("", "", "23:00", ""),
            listOf("", "", "25:00", "07:00"))) {
        try { checkinBody(List(5) { 3 }, "", "", hr, hrv, bed, wake); error("Invalid readiness field accepted") }
        catch (e: AppError) { check(e.resource == R.string.rd_checkin_invalid) }
    }
    val root = targetContext.cacheDir.resolve("readiness-checks").apply { deleteRecursively() }
    try {
        val queue = PendingUploads(root, "https://club.example", 1)
        val day = todayLocal()
        // A check-in queued by the previous version has only the base keys and must still send.
        val legacy = JSONObject().put("sleep", 3).put("fatigue", 3).put("soreness", 3).put("stress", 3).put("mood", 3)
            .put("training_minutes", 40).put("rpe", 6)
        queue.enqueueCheckin(day, legacy)
        queue.enqueueCheckin(todayLocal(java.time.Clock.offset(java.time.Clock.systemDefaultZone(), java.time.Duration.ofDays(-1))), full)
        var sent = 0
        queue.send(linkedMapOf(), put = { _, body -> check(body.has("sleep")); sent++ }) { error("Not a jump") }
        check(sent == 2 && queue.count() == 0)
        val name = queue.enqueueFeature(FeatureWrite.SESSION, day, JSONObject().put("day", day).put("kind", "gym")
            .put("minutes", 45).put("rpe", 7), java.util.UUID.randomUUID().toString())
        queue.send(linkedMapOf(), featureSend = { kind, path, body ->
            check(kind == FeatureWrite.SESSION && path == "/api/training-sessions" && body.getString("kind") == "gym"); 5L
        }) { error("Not a jump") }
        check(!queue.featurePending(name))
        val metrics = JumpMetrics("cmj", 60.0, 400.0, 19.6, 20.0, 21.0, 60.0, 61.0, null, null, null, null, .9, emptyList())
        val jump = queue.enqueue(metrics, readiness = true)
        queue.sendJump(jump) { check(it.getString("test") == "readiness"); 9L }
    } finally { root.deleteRecursively() }
    check(testLabel("readiness") == R.string.rd_test && testLabel("cmj") == R.string.cmj)
}

/** Readiness, morning check, check-in and sessions screens against a seeded server, saved as files/readiness-<language>-<name>.png. */
internal class ReadinessStudio(private val test: Instrumentation) {
    fun screens(server: String) {
        val context = test.targetContext
        fun signIn(account: String) {
            val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "$account@demo.test").put("password", "demo1234")))
            val user = login.getJSONObject("user")
            context.getSharedPreferences("account", 0).edit().clear().putString("server", server)
                .putString("token", login.getString("token")).putString("name", user.getString("name"))
                .putLong("user_id", user.getLong("id")).commit()
        }
        signIn("athlete1")
        context.getSharedPreferences("reminder", 0).edit().putInt("minutes", 7 * 60 + 30).commit()
        var host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
        fun shot(name: String, expected: String) {
            val end = SystemClock.uptimeMillis() + 20000
            var actual = ""
            do {
                test.runOnMainSync { actual = state.get(host).toString() }
                if (actual == expected) break
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < end)
            check(actual == expected) { "$name: expected $expected, got $actual" }
            SystemClock.sleep(1500) // the cards load in the background
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "readiness-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun scrollDown() = test.runOnMainSync { (host.page.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        fun scrollTo(fraction: Float) = test.runOnMainSync {
            val scroll = host.page.parent as ScrollView
            scroll.scrollTo(0, ((host.page.height - scroll.height) * fraction).toInt())
        }
        try {
            test.runOnMainSync { host.home() }; SystemClock.sleep(1500); scrollDown(); shot("home-card", "home")
            test.runOnMainSync { host.readinessCheck() }; shot("check", "readiness")
            test.runOnMainSync { host.checkin() }; shot("checkin", "checkin")
            scrollTo(.62f); shot("checkin-morning", "checkin")
            scrollDown(); shot("checkin-end", "checkin")
            test.runOnMainSync { host.sessionsScreen() }; shot("sessions", "sessions")
            scrollDown(); shot("sessions-load", "sessions")
            test.runOnMainSync { host.finish() }
            signIn("athlete2")
            host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.runOnMainSync { host.home() }; SystemClock.sleep(1500); scrollDown(); shot("home-card-take-care", "home")
            test.runOnMainSync { host.readinessCheck() }; shot("check-take-care", "readiness")
            test.runOnMainSync { host.finish() }
            notifications(server, ::signIn, ::shot) { host = it }
        } finally {
            test.runOnMainSync { host.finish() }
        }
    }

    /** The daily reminder and, while the app is open, the coach's nudge arrive as notifications. */
    private fun notifications(server: String, signIn: (String) -> Unit, shot: (String, String) -> Unit, opened: (MainActivity) -> Unit) {
        val context = test.targetContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        ReminderReceiver().onReceive(context, Intent("jumpscan.app.REMINDER"))
        check(manager.activeNotifications.any { it.id == 1 }) { "Reminder notification missing" }
        signIn("athlete7") // has not checked in today in the seed
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        opened(host)
        SystemClock.sleep(3000) // the events stream connects in onResume
        val coach = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "coach@demo.test").put("password", "demo1234"))).getString("token")
        val athlete = context.getSharedPreferences("account", 0).getLong("user_id", 0)
        val result = JSONObject(api(server, "/api/team/nudge", "POST", coach, JSONObject().put("athlete_ids", org.json.JSONArray().put(athlete))))
        if (result.getJSONArray("sent").length() == 1) { // a nudge is sent at most once a day; later runs skip this part
            val end = SystemClock.uptimeMillis() + 15000
            while (manager.activeNotifications.none { it.id == 2 } && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
            check(manager.activeNotifications.any { it.id == 2 }) { "Nudge notification missing" }
            test.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
            SystemClock.sleep(1500)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "readiness-${host.resources.configuration.locales[0].language}-notifications.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            test.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        }
        manager.cancelAll()
    }
}
