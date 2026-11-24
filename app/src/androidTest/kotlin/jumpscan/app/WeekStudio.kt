package jumpscan.app

import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import kotlin.concurrent.thread

/** A `week` answer shaped like the server's (SPEC 10.3), for the current ISO week. */
internal fun weekFixture(): JSONObject {
    val monday = LocalDate.parse(todayLocal()).let { it.minusDays(it.dayOfWeek.value - 1L) }
    return JSONObject("""{"week":"${isoWeek(monday)}","from":"$monday","to":"${monday.plusDays(6)}",
 "training":{"days":4,"sessions":5,"minutes":290,"load":1910,"load_previous_week":1640,"sets":38,"volume_kg":9120.5,
  "top_exercises":[{"exercise":"Back squat","sets":12,"volume_kg":4320.0},{"exercise":"Bench press","sets":10,"volume_kg":3100.0}]},
 "records":[{"measure":"jump_height","value":38.6,"unit":"cm","date":"${monday}T07:10:00+00:00","previous":{"value":36.1}}],
 "checkins":{"days":6,"wellbeing_avg":17.5},"readiness":{"good":3,"normal":2,"take_care":1,"still_learning":0},
 "nutrition":{"days_logged":5,"kcal_avg":2140.0,"protein_avg":118.0,"kcal_target":2200,"protein_target":130},
 "weight":{"start_trend":64.6,"end_trend":64.3},
 "consistency":{"checkin":{"score":0.7412,"percent":74,"done":13,"days":14},"training":{"score":0.5,"percent":50,"done":8,"days":14}},
 "text":"A steady week: four training days and six check-ins."}""")
}

private fun views(view: View): List<View> =
    listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

private fun Instrumentation.signIn(server: String) {
    val login = JSONObject(api(server, "/api/auth/login", "POST", body = JSONObject().put("email", "athlete1@demo.test").put("password", "demo1234")))
    val user = login.getJSONObject("user")
    targetContext.getSharedPreferences("account", 0).edit().clear().putString("server", server).putString("token", login.getString("token"))
        .putString("name", user.getString("name")).putLong("user_id", user.getLong("id")).commit()
    targetContext.getSharedPreferences("today", 0).edit().clear().commit()
}

/** Pick a reason and a mode in the open sheet by tapping their segments. */
private fun Instrumentation.pick(dialog: Dialog, vararg labels: String) {
    for (label in labels) runOnMainSync {
        views(dialog.window!!.decorView).filterIsInstance<RadioButton>().first { it.text == label }.performClick()
    }
}

/** A tiny HTTP stand-in that records each request line and body and answers from [reply]. */
private class Recorder(private val reply: (String) -> String) {
    val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    val first = input.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length).also { buffer -> var read = 0; while (read < length) read += input.read(buffer, read, length - read) }
                    requests.add(first + " " + String(body))
                    val answer = reply(first).toByteArray()
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${answer.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray() + answer)
                }
            }
        }
    }
    val url get() = "http://127.0.0.1:${socket.localPort}"
}

/** Week screen, the adjustment flow, the share-in intent and the card renderer without the real server (SPEC 10.3). */
internal fun weekChecks(test: Instrumentation) {
    val context = test.targetContext
    val account = context.getSharedPreferences("account", 0)
    val adjusted = todayFixture().put("adjustment", JSONObject().put("reason", "tired").put("mode", "rest"))
        .put("plan", JSONObject().put("items", org.json.JSONArray()).put("more", 0).put("logged", false).put("adjusted", "rest"))
        .put("week", JSONObject().put("week", isoWeek(LocalDate.parse(todayLocal()))).put("consistency", weekFixture().getJSONObject("consistency")))
    val server = Recorder { line -> if (line.startsWith("GET /api/me/today")) adjusted.toString() else "{}" }
    account.edit().clear().putString("server", server.url).putString("token", "fixture").putString("name", "Ana Test").putLong("user_id", 7).commit()
    context.getSharedPreferences("today", 0).edit().clear().commit()
    context.saveToday(todayFixture())
    val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
        check(test.screenOf(host, "home") == "home")
        // Week screen from a fixture: consistency with its plain count, the summary and the bounded navigation.
        test.runOnMainSync { host.drawWeek(weekFixture()) }
        check(test.screenOf(host, "week") == "week")
        var shown = emptyList<String>()
        test.runOnMainSync { shown = texts(host.page) }
        for (expected in listOf("74 %", host.getString(R.string.week_of_days, 13, 14), "A steady week: four training days and six check-ins.",
                host.getString(R.string.records_height), "Back squat", host.getString(R.string.week_weight_change, host.short(64.6), host.short(64.3))))
            check(shown.any { expected in it }) { "week: missing $expected in $shown" }
        test.runOnMainSync {
            check(!host.page.findViewWithTag<Button>("week-next").isEnabled) { "next week must be off on the current week" }
            check(host.page.findViewWithTag<Button>("week-prev").isEnabled)
        }
        // Not feeling 100%: the sheet needs a reason and a mode, then PUTs them for today and Today redraws.
        test.runOnMainSync { host.home() }
        check(test.screenOf(host, "home") == "home")
        var dialog: Dialog? = null
        test.runOnMainSync { dialog = host.adjustmentSheet(null) }
        fun save() = views(dialog!!.window!!.decorView).first { it.tag == "adjustment-save" } as Button
        test.runOnMainSync { check(!save().isEnabled) { "save must wait for a reason and a mode" } }
        test.pick(dialog!!, host.getString(R.string.adj_tired), host.getString(R.string.adj_rest))
        val sent = server.requests.size
        test.runOnMainSync { check(save().isEnabled); save().performClick() }
        val end = SystemClock.uptimeMillis() + 10000
        while (server.requests.drop(sent).none { it.startsWith("GET /api/me/today") } && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        val put = server.requests.firstOrNull { it.startsWith("PUT ") }
        check(put != null && put.startsWith("PUT /api/me/days/${todayLocal()}/adjustment ") &&
            JSONObject(put.substringAfter("HTTP/1.1 ")).let { it.getString("reason") == "tired" && it.getString("mode") == "rest" }) { "requests: ${server.requests}" }
        val flag = host.getString(R.string.adj_flag, host.getString(R.string.adj_tired).lowercase(host.locale), host.getString(R.string.adj_rest).lowercase(host.locale))
        val shownEnd = SystemClock.uptimeMillis() + 10000
        do { test.runOnMainSync { shown = texts(host.page) }; if (shown.contains(flag)) break; SystemClock.sleep(150) } while (SystemClock.uptimeMillis() < shownEnd)
        check(flag in shown && host.getString(R.string.adj_rest_day) in shown) { "adjusted Today: $shown" }
        // The consistency tile (SPEC 10.9): the number and "/ 14 days".
        test.runOnMainSync { shown = host.page.findViewWithTag<android.view.View>("card-week")?.let { texts(it) }.orEmpty() }
        check("13" in shown && shown.any { it.startsWith("/ 14") }) { "week tile: $shown" }
        // Share into the assistant: the text becomes a draft and nothing is sent.
        val before = server.requests.size
        test.runOnMainSync {
            host.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  Колко протеин има в 200 г кисело мляко?  ")
                .setClass(context, MainActivity::class.java))
        }
        check(test.screenOf(host, "assistant") == "assistant")
        test.runOnMainSync {
            val draft = views(host.page).filterIsInstance<EditText>().single()
            check(draft.text.toString() == "Колко протеин има в 200 г кисело мляко?") { "draft: ${draft.text}" }
            check(host.assistantChat.turns.isEmpty())
        }
        SystemClock.sleep(500)
        check(server.requests.drop(before).none { it.contains("/api/assistant") }) { "sent without Send: ${server.requests}" }
        // The share card renderer: full size, graphite ground, the flight arc in the signal colour for a jump.
        val signal = context.getColor(R.color.signal)
        fun near(a: Int, b: Int) = listOf(16, 8, 0).all { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) < 24 }
        val jump = RecordCard.render(context, RecordShare("Jump height", "38.6 cm", "36.1 cm", "23/11/2026", 38.6))
        check(jump.width == RecordCard.WIDTH && jump.height == RecordCard.HEIGHT)
        check(near(jump.getPixel(5, 5), context.getColor(R.color.hero_top))) { "ground" }
        val lit = (0 until jump.height step 4).count { y -> near(jump.getPixel(jump.width / 2, y), signal) }
        check(lit > 0) { "no flight arc at the centre line" }
        val lift = RecordCard.render(context, RecordShare("Back squat", "120 kg × 3", null, "23/11/2026", null))
        check((0 until lift.height step 4).count { y -> near(lift.getPixel(lift.width / 2, y), signal) } == 0) { "arc drawn for a lift" }
        jump.recycle(); lift.recycle()
    } finally {
        test.runOnMainSync { host.finish() }
        server.socket.close()
        account.edit().clear().commit()
        context.getSharedPreferences("today", 0).edit().clear().commit()
    }
}

/** Week screens against a seeded server, saved as files/week-<language>-<name>.png. */
internal class WeekStudio(private val test: Instrumentation, private val server: String) {
    fun screens() {
        val context = test.targetContext
        test.signIn(server)
        val token = context.getSharedPreferences("account", 0).getString("token", "")!!
        runCatching { api(server, "/api/me/days/${todayLocal()}/adjustment", "DELETE", token) }
        val host = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val language = host.resources.configuration.locales[0].language
        fun save(name: String, bitmap: Bitmap) =
            File(context.filesDir, "week-$language-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun shot(name: String, screen: String) {
            check(test.screenOf(host, screen) == screen) { "$name: expected $screen" }
            SystemClock.sleep(900)
            test.requireAppWindow(name)
            val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
            save(name, bitmap); bitmap.recycle()
        }
        fun scrollTo(tag: String) = test.runOnMainSync {
            val card = host.page.findViewWithTag<View>(tag) ?: return@runOnMainSync
            var top = 0; var view: View = card
            while (view !== host.page) { top += view.top; view = view.parent as View }
            (host.page.parent as ScrollView).scrollTo(0, top - host.dp(12))
        }
        try {
            val end = SystemClock.uptimeMillis() + 20000
            while (context.cachedToday() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
            check(context.cachedToday()?.first?.optJSONObject("week") != null) { "today has no week from $server" }
            scrollTo("card-plan"); shot("today-week", "home")
            var dialog: Dialog? = null
            test.runOnMainSync { dialog = host.adjustmentSheet(null) }
            test.pick(dialog!!, host.getString(R.string.adj_tired), host.getString(R.string.adj_lighter))
            shot("adjustment-sheet", "home")
            test.runOnMainSync { (views(dialog!!.window!!.decorView).first { it.tag == "adjustment-save" } as Button).performClick() }
            val saved = SystemClock.uptimeMillis() + 20000
            while (context.cachedToday()?.first?.optJSONObject("adjustment") == null && SystemClock.uptimeMillis() < saved) SystemClock.sleep(200)
            check(context.cachedToday()?.first?.optJSONObject("adjustment") != null) { "adjustment did not reach $server" }
            SystemClock.sleep(600)
            test.runOnMainSync { (host.page.parent as ScrollView).scrollTo(0, 0) }
            shot("today-adjusted", "home")
            scrollTo("card-plan"); shot("today-adjusted-plan", "home")
            test.runOnMainSync { host.weekScreen() }
            shot("week", "week")
            test.runOnMainSync { (host.page.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
            shot("week-end", "week")
            val records = JSONObject(api(server, "/api/athletes/${context.getSharedPreferences("account", 0).getLong("user_id", 0)}/records", token = token))
                .getJSONArray("records").objects()
            val record = records.firstOrNull { it.getString("measure") == "jump_height" } ?: records.first()
            var card: Bitmap? = null
            test.runOnMainSync { card = RecordCard.render(host, host.recordShare(record)) }
            save("share-card", card!!); card!!.recycle()
        } finally {
            runCatching { api(server, "/api/me/days/${todayLocal()}/adjustment", "DELETE", token) }
            test.runOnMainSync { host.finish() }
        }
    }
}
