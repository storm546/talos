package jumpscan.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** A `today` answer shaped like the server's (SPEC 10.2), for checks that must not depend on a network. */
internal fun todayFixture(): JSONObject = JSONObject("""{"day":"${todayLocal()}","role":"athlete",
 "readiness":{"status":"good","still_learning":false,"checked_in":true,"note":"","factors":[
  {"key":"wellbeing","value":18,"unit":"points","state":"better","baseline":{"n":20,"needed":7}},
  {"key":"sleep","value":83,"unit":"score","state":"usual","sleep":{"score":83,"hours":8.3}}]},
 "checkin":{"done":true,"wellbeing":18,"out_of":25},
 "plan":{"items":[{"plan_id":1,"plan":"Strength","exercise":"Back squat","sets":3,"reps":6,"reps_max":8,"load_kg":60}],"more":0,"logged":false},
 "training":{"sessions":[{"id":1,"kind":"gym","minutes":60,"rpe":7,"load":420,"source":"session"}],"total_load":420,
  "acute_chronic":{"ready":true,"ratio":1.07,"band":"similar","still_learning":false}},
 "nutrition":{"eaten":{"kcal":1480,"protein_g":92,"carbs_g":180,"fat_g":40},"target":{"kcal":2200,"protein_g":130,"carbs_g":275,"fat_g":73},"logs":5},
 "weight":{"day":"${todayLocal()}","kg":64.3,"trend_kg":64.2,"trend_day":"${todayLocal()}"},
 "records":[{"measure":"jump_height","value":38.6,"unit":"cm","date":"${todayLocal()}T07:10:00+00:00"}],
 "next_session":null,"unread":2}""")

private fun texts(view: View): List<String> = when (view) {
    is TextView -> listOf(view.text.toString())
    is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    else -> emptyList()
}

private fun Instrumentation.screenOf(host: MainActivity, expected: String): String {
    val state = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
    var actual = ""
    val end = SystemClock.uptimeMillis() + 15000
    do {
        runOnMainSync { actual = state.get(host).toString() }
        if (actual == expected) break
        SystemClock.sleep(150)
    } while (SystemClock.uptimeMillis() < end)
    return actual
}

/** Today from a fixture with no reachable server: cards, hide and reorder, deep links and the widget (SPEC 10.2). */
internal fun todayChecks(test: Instrumentation) {
    val context = test.targetContext
    val account = context.getSharedPreferences("account", 0)
    val today = context.getSharedPreferences("today", 0)
    account.edit().clear().putString("server", "http://127.0.0.1:9").putString("token", "fixture")
        .putString("name", "Ana Test").putLong("user_id", 7).commit()
    today.edit().clear().commit()
    context.saveToday(todayFixture())
    var host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
        fun cardOrder(): List<String> {
            var keys = emptyList<String>()
            test.runOnMainSync {
                val cards = host.page.findViewWithTag<LinearLayout>("today-cards")
                keys = (0 until cards.childCount).map { cards.getChildAt(it).tag.toString().removePrefix("card-") }
            }
            return keys
        }
        check(test.screenOf(host, "home") == "home")
        check(cardOrder() == TODAY_CARDS) { "default order: ${cardOrder()}" }
        var shown = emptyList<String>()
        test.runOnMainSync { shown = texts(host.page) }
        for (expected in listOf(host.getString(R.string.rd_good), host.getString(R.string.today_checkin_done), "Back squat",
                host.getString(R.string.feature_kg, host.short(64.3)), host.getString(R.string.today_messages_unread, 2)))
            check(shown.any { expected in it }) { "missing $expected in $shown" }
        // Hide and reorder are stored on the phone and survive a restart of the screen.
        context.moveCard("checkin", -1)
        context.hideCard("weight", true)
        test.runOnMainSync { host.finish() }
        host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        check(test.screenOf(host, "home") == "home")
        val expected = listOf("readiness", "checkin", "plan") + TODAY_CARDS.drop(3).filter { it != "weight" }
        check(cardOrder() == expected) { "stored order: ${cardOrder()}" }
        test.runOnMainSync { shown = texts(host.page) }
        check(shown.any { it.equals(host.getString(R.string.today_hidden), ignoreCase = true) }) { "no hidden list in $shown" }
        context.hideCard("weight", false)
        context.moveCard("checkin", 1)
        check(context.todayOrder() == TODAY_CARDS && context.hiddenCards().isEmpty())
        // Deep links open the tool with that hub key; an unknown key opens the hub.
        for ((link, screen) in listOf("talos://tool/calculators" to "calculators", "talos://tool/timer" to "interval_timer",
                "talos://tool/no-such-tool" to "tools")) {
            test.runOnMainSync { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)) }
            check(test.screenOf(host, screen) == screen) { "$link opened ${test.screenOf(host, screen)}" }
        }
        // The widget renders the cached answer.
        test.runOnMainSync {
            val widget = TodayWidget.views(context).apply(context, FrameLayout(context))
            val words = texts(widget)
            check(words.any { host.getString(R.string.rd_good) in it } && host.getString(R.string.widget_checkin_done) in words &&
                words.any { "1480 / 2200" in it }) { "widget: $words" }
        }
    } finally {
        test.runOnMainSync { host.finish() }
        account.edit().clear().commit()
        today.edit().clear().commit()
    }
}

/** Today screens against a seeded server, signed in and signed out, saved as files/today-<language>-<name>.png. */
internal class TodayStudio(private val test: Instrumentation, private val server: String) {
    fun screens() {
        val context = test.targetContext
        val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
        val user = login.getJSONObject("user")
        context.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
            .putString("name", user.getString("name")).putLong("user_id", user.getLong("id")).commit()
        context.getSharedPreferences("today", 0).edit().clear().commit()
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        fun shot(name: String, screen: String) {
            check(test.screenOf(host, screen) == screen) { "$name: expected $screen" }
            SystemClock.sleep(900)
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            File(context.filesDir, "today-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun scroll(to: Int) = test.runOnMainSync { (host.page.parent as ScrollView).smoothScrollTo(0, to) }
        fun scrollEnd() = test.runOnMainSync { (host.page.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        try {
            // Wait for the live answer, not the empty first draw.
            val end = SystemClock.uptimeMillis() + 20000
            while (context.cachedToday() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
            check(context.cachedToday() != null) { "today did not load from $server" }
            shot("signed-in", "home")
            scroll(1400); shot("signed-in-middle", "home")
            scrollEnd(); shot("signed-in-end", "home")
            test.runOnMainSync {
                host.layout(host.getString(R.string.widget_description), "widget_preview")
                host.page.addView(TodayWidget.views(context).apply(context, host.page),
                    LinearLayout.LayoutParams(host.dp(260), -2).apply { topMargin = host.dp(16) })
            }
            shot("widget", "widget_preview")
            test.runOnMainSync { host.intervalTimer("intervals") }; scrollEnd(); shot("timer-voice", "interval_timer")
            context.getSharedPreferences("account", 0).edit().remove("token").commit()
            test.runOnMainSync { host.home() }; shot("signed-out", "home")
            scrollEnd(); shot("signed-out-end", "home")
        } finally {
            test.runOnMainSync { host.finish() }
        }
    }
}
