package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File

/** Records and leaderboard screens against a seeded server (SPEC 8), saved as files/records-<language>-<name>.png. */
internal class RecordsStudio(private val test: Instrumentation) {
    fun screens(server: String) {
        val context = test.targetContext
        val credentials = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = credentials))
        val user = login.getJSONObject("user")
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server)
            .putString("token", login.getString("token")).putString("name", user.getString("name"))
            .putLong("user_id", user.getLong("id")).commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
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
            SystemClock.sleep(700)
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "records-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun scrollDown() = test.runOnMainSync { (host.page.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        try {
            test.runOnMainSync { host.records() }; shot("records", "records")
            scrollDown(); shot("records-end", "records")
            test.runOnMainSync { host.leaderboard() }; shot("leaderboard", "leaderboard")
            scrollDown(); shot("leaderboard-end", "leaderboard")
            test.runOnMainSync { host.leaderboard("flight_time", "30") }; shot("leaderboard-flight", "leaderboard")
        } finally {
            test.runOnMainSync { host.finish() }
        }
    }
}
