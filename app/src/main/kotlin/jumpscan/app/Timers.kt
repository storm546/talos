package jumpscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import jumpscan.core.IntervalPlan
import org.json.JSONArray
import org.json.JSONObject

/** The whole plan as one strip: work segments in the accent, rest in a quiet tone, the elapsed part dimmed. */
internal class PhaseStrip(context: Context, private val seconds: IntArray, private val work: BooleanArray) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    var position = 0f // elapsed seconds
        set(value) { field = value; invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val total = seconds.sum().coerceAtLeast(1).toFloat()
        var x = 0f
        for (i in seconds.indices) {
            val w = width * seconds[i] / total
            pen.color = context.getColor(if (work[i]) R.color.signal else R.color.on_hero)
            pen.alpha = if (work[i]) 255 else 70
            canvas.drawRoundRect(RectF(x + d, 0f, x + w - d, height.toFloat()), 2 * d, 2 * d, pen)
            x += w
        }
        pen.color = context.getColor(R.color.hero); pen.alpha = 170
        canvas.drawRect(0f, 0f, width * position / total, height.toFloat(), pen)
        pen.color = context.getColor(R.color.on_hero); pen.alpha = 255
        val at = width * position / total
        canvas.drawRect(at - d, -0f, at + d, height.toFloat(), pen)
    }
}

private val MODES = listOf("intervals", "tabata", "emom", "amrap", "stopwatch")

private fun phases(mode: String, values: JSONObject): List<IntervalPlan.Phase> = when (mode) {
    "tabata" -> IntervalPlan.tabata()
    "emom" -> IntervalPlan.emom(values.optInt("minutes", 10).coerceIn(1, 120))
    "amrap" -> IntervalPlan.amrap(values.optInt("minutes", 12).coerceIn(1, 120))
    "stopwatch" -> emptyList()
    else -> IntervalPlan.intervals(values.optInt("work", 40).coerceIn(1, 3600), values.optInt("rest", 20).coerceIn(0, 3600),
        values.optInt("rounds", 8).coerceIn(1, 99))
}

internal fun MainActivity.askNotifications() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 61)
}

/** Runs [update] every 200 ms while [view] is attached to the current page. */
internal fun MainActivity.ticker(view: View, update: () -> Unit) {
    val host = page
    view.post(object : Runnable {
        override fun run() {
            if (page !== host || !view.isAttachedToWindow) return
            update()
            view.postDelayed(this, 200)
        }
    })
}

/** Intervals, Tabata, EMOM, AMRAP and a stopwatch (SPEC 9). */
internal fun MainActivity.intervalTimer(mode: String? = null) {
    val prefs = getSharedPreferences("timer", Context.MODE_PRIVATE)
    if (TimerState.running && TimerState.kind != "rest") return timerRunning()
    val chosen = mode ?: prefs.getString("mode", "intervals")!!
    prefs.edit().putString("mode", chosen).apply()
    val values = JSONObject(prefs.getString("values_$chosen", "{}")!!)
    layout(getString(R.string.timer_title), "interval_timer")
    val labels = listOf(R.string.timer_intervals, R.string.timer_tabata, R.string.timer_emom, R.string.timer_amrap, R.string.timer_stopwatch)
    segments(labels.take(3).map(::getString), MODES.indexOf(chosen)) { intervalTimer(MODES[it]) }
    segments(labels.drop(3).map(::getString), MODES.indexOf(chosen) - 3) { intervalTimer(MODES[it + 3]) }
    val panel = card(hero = true)
    val total = Measurement(this).apply { textSize = 56f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true) }
    panel.addView(total)
    val summary = text("", 14, getColor(R.color.on_hero), panel)
    val strip = LinearLayout(this).also { panel.addView(it, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(10) }) }
    val inputs = mutableMapOf<String, android.widget.EditText>()
    fun number(key: String, label: Int, default: Int, parent: LinearLayout) {
        inputs[key] = field(getString(label), values.optInt(key, default).toString(), android.text.InputType.TYPE_CLASS_NUMBER, parent)
    }
    when (chosen) {
        "intervals" -> {
            val (left, right) = columns()
            number("work", R.string.timer_work_s, 40, left); number("rest", R.string.timer_rest_s, 20, right)
            number("rounds", R.string.timer_rounds, 8, page)
        }
        "emom" -> number("minutes", R.string.timer_minutes, 10, page)
        "amrap" -> number("minutes", R.string.timer_minutes, 12, page)
    }
    text(getString(when (chosen) {
        "tabata" -> R.string.timer_tabata_note
        "emom" -> R.string.timer_emom_note
        "amrap" -> R.string.timer_amrap_note
        "stopwatch" -> R.string.timer_stopwatch_note
        else -> R.string.timer_intervals_note
    }), 13)
    fun current() = JSONObject().apply { inputs.forEach { (key, input) -> input.value().toIntOrNull()?.let { put(key, it) } } }
    fun update() {
        val plan = phases(chosen, current())
        prefs.edit().putString("values_$chosen", current().toString()).apply()
        total.text = if (plan.isEmpty()) "0:00" else clock(IntervalPlan.total(plan) * 1000L)
        summary.text = if (plan.isEmpty()) getString(R.string.timer_stopwatch_note)
            else getString(R.string.timer_plan_summary, plan.count { it.kind == IntervalPlan.Kind.WORK }, plan.last().rounds)
        strip.removeAllViews()
        if (plan.isNotEmpty()) strip.addView(PhaseStrip(this, plan.map { it.seconds }.toIntArray(),
            plan.map { it.kind == IntervalPlan.Kind.WORK }.toBooleanArray()), LinearLayout.LayoutParams(-1, -1))
    }
    inputs.values.forEach { it.onChange(::update) }
    update()
    button(getString(R.string.timer_start), primary = true) {
        val plan = phases(chosen, current())
        askNotifications()
        startTimer(if (plan.isEmpty()) "stopwatch" else "interval", plan.map { phaseLabel(it.kind) },
            plan.map { getString(R.string.timer_round, it.round, it.rounds) }, plan.map { it.seconds }.toIntArray(),
            plan.map { it.kind == IntervalPlan.Kind.WORK }.toBooleanArray())
        page.postDelayed({ timerRunning() }, 120)
    }.apply { minHeight = dp(56); textSize = 17f }
    presets(prefs, chosen, ::current)
}

private fun MainActivity.phaseLabel(kind: IntervalPlan.Kind) = getString(when (kind) {
    IntervalPlan.Kind.READY -> R.string.timer_ready
    IntervalPlan.Kind.WORK -> R.string.timer_work
    IntervalPlan.Kind.REST -> R.string.timer_rest
})

private fun MainActivity.presets(prefs: android.content.SharedPreferences, mode: String, current: () -> JSONObject) {
    val saved = JSONArray(prefs.getString("presets", "[]"))
    eyebrow(getString(R.string.timer_presets))
    val list = settingsGroup()
    for (i in 0 until saved.length()) {
        val preset = saved.getJSONObject(i)
        if (i > 0) divider(list)
        val plan = phases(preset.getString("mode"), preset.getJSONObject("values"))
        settingRow("timer", preset.getString("name"), if (plan.isEmpty()) getString(R.string.timer_stopwatch)
            else clock(IntervalPlan.total(plan) * 1000L), list) {
            prefs.edit().putString("values_${preset.getString("mode")}", preset.getJSONObject("values").toString()).apply()
            intervalTimer(preset.getString("mode"))
        }.setOnLongClickListener {
            saved.remove(i); prefs.edit().putString("presets", saved.toString()).apply(); intervalTimer(mode); true
        }
    }
    if (saved.length() > 0) text(getString(R.string.timer_presets_hint), 13)
    button(getString(R.string.timer_save_preset), look = ActionTone.TONAL) {
        val values = current()
        val name = when (mode) {
            "intervals" -> "${values.optInt("work", 40)}/${values.optInt("rest", 20)} × ${values.optInt("rounds", 8)}"
            "emom" -> "EMOM ${values.optInt("minutes", 10)}′"
            "amrap" -> "AMRAP ${values.optInt("minutes", 12)}′"
            "tabata" -> getString(R.string.timer_tabata)
            else -> getString(R.string.timer_stopwatch)
        }
        saved.put(JSONObject().put("name", name).put("mode", mode).put("values", values))
        prefs.edit().putString("presets", saved.toString()).apply()
        intervalTimer(mode)
    }
}

/** The running timer: phase, a large countdown, the round and the whole plan as a strip. */
internal fun MainActivity.timerRunning() {
    layout(getString(R.string.timer_title), "interval_timer", headline = false)
    val state = TimerState
    val stopwatch = state.seconds.isEmpty()
    val panel = card(hero = true).apply { gravity = Gravity.CENTER_HORIZONTAL; (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) }
    val phase = text("", 16, getColor(R.color.signal), panel).apply { gravity = Gravity.CENTER; letterSpacing = .14f; instrumentType(display = true) }
    val big = TextView(this).apply {
        textSize = 92f; gravity = Gravity.CENTER; instrumentType(numeric = true); setTextColor(android.graphics.Color.WHITE)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        panel.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    val detail = text("", 15, getColor(R.color.on_hero), panel).apply { gravity = Gravity.CENTER }
    val strip = if (stopwatch) null else PhaseStrip(this, state.seconds, state.work)
        .also { panel.addView(it, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(16); bottomMargin = dp(6) }) }
    val elapsed = text("", 13, getColor(R.color.on_hero), panel).apply { gravity = Gravity.CENTER; instrumentType(numeric = true) }
    val controls = row(center = false)
    button(getString(R.string.timer_stop), parent = controls) { timerCommand(TimerService.STOP) }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(12) } }
    if (!stopwatch) button(getString(R.string.timer_skip), parent = controls, look = ActionTone.TONAL) { timerCommand(TimerService.SKIP) }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(12); marginStart = dp(8) } }
    text(getString(R.string.timer_background_note), 13).setPadding(0, dp(14), 0, 0)
    ticker(big) {
        if (!state.running) return@ticker timerFinished()
        val now = SystemClock.elapsedRealtime()
        if (stopwatch) {
            phase.text = getString(R.string.timer_stopwatch).uppercase(locale)
            big.text = clock(state.elapsedMs(now))
            detail.text = ""
        } else {
            val work = state.work.getOrElse(state.index) { false }
            phase.text = state.labels.getOrNull(state.index)?.uppercase(locale)
            phase.setTextColor(getColor(if (work) R.color.signal else R.color.on_hero))
            big.text = clock(state.remainingMs(now))
            big.setTextColor(if (work) getColor(R.color.signal) else android.graphics.Color.WHITE)
            detail.text = state.details.getOrNull(state.index)
            strip?.position = (state.seconds.take(state.index + 1).sum() * 1000L - state.remainingMs(now)) / 1000f
        }
        elapsed.text = getString(R.string.timer_elapsed, clock(state.elapsedMs(now)))
    }
}

private fun MainActivity.timerFinished() {
    layout(getString(R.string.timer_done), "interval_timer")
    val minutes = ((TimerState.elapsedMs() + 30_000) / 60_000).toInt()
    statGrid(listOf(Triple(getString(R.string.timer_total), clock(TimerState.elapsedMs()), ""),
        Triple(getString(R.string.timer_rounds), if (TimerState.seconds.isEmpty()) "—" else TimerState.work.count { it }.toString(), "")))
    if (minutes > 0 && token().isNotEmpty()) {
        button(getString(R.string.timer_log, minutes), primary = true) { checkin(minutes) }
        text(getString(R.string.timer_log_note), 13)
    }
    button(getString(R.string.timer_again), look = ActionTone.TONAL) { intervalTimer() }
}
