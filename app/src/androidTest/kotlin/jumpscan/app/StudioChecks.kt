package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class StudioChecks(private val test: Instrumentation, private val movies: Boolean) {
    private val context get() = test.targetContext

    fun record(name: String, action: () -> Unit) {
        if (!movies) return action()
        val recording = test.uiAutomation.executeShellCommand("screenrecord --size 480x1040 --bit-rate 3000000 --time-limit 3 /sdcard/jumpscan-$name.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(recording).use { stream ->
            SystemClock.sleep(450)
            action()
            stream.readBytes()
        }
    }

    fun screens(server: String) {
        val account = context.getSharedPreferences("account", 0)
        account.edit().clear().commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
        fun shot(name: String, expected: String = name) {
            val end = SystemClock.uptimeMillis() + 20000
            var actual = ""
            do {
                test.runOnMainSync { actual = state.get(host).toString() }
                if (actual == expected) break
                SystemClock.sleep(150)
            } while (SystemClock.uptimeMillis() < end)
            check(actual == expected) { "$name: expected $expected, got $actual" }
            SystemClock.sleep(550)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun open(method: String) = MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(host)
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        try {
            shot("login")
            val credentials = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")
            val login = JSONObject(api(server, "/api/auth/login", "POST", body = credentials))
            val user = login.getJSONObject("user")
            account.edit().putString("server", server).putString("token", login.getString("token"))
                .putString("name", user.getString("name")).putLong("user_id", user.getLong("id")).commit()
            record("press-transition") {
                val control = buttons(host.page).first { it.text == host.getString(R.string.continue_offline) }
                val start = SystemClock.uptimeMillis()
                for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    test.runOnMainSync {
                        val touch = MotionEvent.obtain(start, SystemClock.uptimeMillis(), event, control.width / 2f, control.height / 2f, 0)
                        control.dispatchTouchEvent(touch); touch.recycle()
                    }
                    SystemClock.sleep(180)
                }
            }
            shot("home")
            test.runOnMainSync { host.formHome() }; shot("technique", "form")
            test.runOnMainSync { host.techniqueHistory() }; shot("technique-history", "technique_history")
            val sets = JSONArray(api(server, "/api/form?limit=20", token = login.getString("token")))
            val set = sets.getJSONObject(0)
            test.runOnMainSync { host.formResult(set, FormResult(set)) }; shot("set-summary", "form_result")
            record("history-rulers") { test.runOnMainSync { open("history") }; shot("history") }
            test.runOnMainSync { host.trainingHome() }; shot("training")
            test.runOnMainSync { host.nutritionHome() }; shot("nutrition")
            test.runOnMainSync { open("clubHome") }; shot("club")
            test.runOnMainSync { open("checkin") }; shot("checkin")
            test.runOnMainSync { host.assistantScreen() }; shot("assistant")
            test.runOnMainSync { host.profile() }; shot("profile")
            test.runOnMainSync { host.appearanceSheet() }
            shot("appearance", "profile")
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            test.runOnMainSync { host.diagnostics() }; shot("guided-phone-test", "diagnostics")
            test.runOnMainSync { (host.page.parent as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN) }
            shot("diagnostics")
            test.runOnMainSync { buttons(host.page).firstOrNull { it.text == host.getString(R.string.diag_clear) }?.performClick() }
            shot("diagnostic-actions", "diagnostics")
        } finally {
            test.runOnMainSync { host.finish() }
        }
    }
}
