package jumpscan.app

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

// Today as an instrument panel (SPEC 10.9): a factor gauge with the vitals, three rings for the day's food and load,
// then dense rows of number, sparkline and finding. Tapping anything explains its finding in a sheet.

private fun View.sweepIn(onFrame: (Float) -> Unit) {
    if (!ValueAnimator.areAnimatorsEnabled()) { onFrame(1f); return }
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600; interpolator = settle
        addUpdateListener { onFrame(it.animatedValue as Float); invalidate() }
        start()
    }
}

/** Readiness as its factors: one arc segment per factor, coloured by better / usual / worse than the athlete's own
 *  usual; the status word sits in the middle. No invented score. */
internal class FactorGauge(context: Context, private val states: List<String>, private val word: String, private val sub: String) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = minOf(width, height) / 2f - 7 * d
        val box = RectF(width / 2f - r, height / 2f - r + 4 * d, width / 2f + r, height / 2f + r + 4 * d)
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 9 * d; pen.strokeCap = Paint.Cap.ROUND
        val n = states.size.coerceAtLeast(1)
        val gap = 10f
        val each = (240f - gap * (n - 1)) / n
        for (i in 0 until n) {
            val state = states.getOrNull(i)
            pen.color = context.getColor(when (state) { "better" -> R.color.hero_good; "worse" -> R.color.signal; else -> R.color.on_hero })
            // Missing factors stay visible as a faint track, so an empty morning still reads as a gauge.
            pen.alpha = when (state) { "usual" -> 150; "better", "worse" -> 255; else -> 45 }
            canvas.drawArc(box, 150f + i * (each + gap), each * time, false, pen)
        }
        pen.style = Paint.Style.FILL; pen.textAlign = Paint.Align.CENTER; pen.alpha = 255
        pen.typeface = resources.getFont(R.font.spacegrotesk_bold); pen.color = Color.WHITE
        // The status word fits inside the arc whatever the language: shrink it until it does.
        pen.textSize = r * .34f
        while (pen.measureText(word) > r * 1.55f && pen.textSize > 9 * d) pen.textSize -= d
        canvas.drawText(word, width / 2f, box.centerY() + pen.textSize * .3f, pen)
        pen.typeface = resources.getFont(R.font.jetbrainsmono_regular); pen.color = context.getColor(R.color.on_hero)
        pen.textSize = 10.5f * resources.configuration.fontScale * d
        canvas.drawText(sub, width / 2f, box.centerY() + r * .42f, pen)
    }
}

/** A ring for one of the day's totals: fill against its target, the reading in the middle. */
internal class DayRing(context: Context, private val fraction: Double?, private val over: Boolean, private val reading: String,
                       private val count: Double? = null) : View(context) {
    // With [count], the number in the middle counts up with the sweep and lands on [reading].
    private val locale = resources.configuration.locales[0]
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = minOf(width, height) / 2f - 4 * d
        val box = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 6 * d; pen.strokeCap = Paint.Cap.ROUND
        pen.color = context.getColor(R.color.hover)
        canvas.drawArc(box, 0f, 360f, false, pen)
        if (fraction != null) {
            pen.color = context.getColor(if (over) R.color.warning else R.color.accent)
            canvas.drawArc(box, -90f, (360 * fraction.coerceIn(0.0, 1.0) * time).toFloat(), false, pen)
        }
        pen.style = Paint.Style.FILL; pen.textAlign = Paint.Align.CENTER
        pen.typeface = resources.getFont(R.font.jetbrainsmono_regular); pen.color = context.getColor(R.color.ink)
        pen.textSize = r * (if (reading.length > 4) .36f else .44f)
        val shown = if (count == null || time >= 1f) reading else metricNumber(count * time, "kcal", locale)
        canvas.drawText(shown, width / 2f, height / 2f + pen.textSize * .35f, pen)
    }
}

/** A sparkline (line) or seven-day bars; the last point is the accent. */
internal class Spark(context: Context, private val values: List<Double>, private val bars: Boolean = false) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 1f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); time = 0f; sweepIn { time = it } }
    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty() || (!bars && values.size < 2)) return
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat() - 2 * d
        val accent = context.getColor(R.color.accent); val ink = context.getColor(R.color.ink)
        if (bars) {
            val high = values.max().takeIf { it > 0 } ?: return
            val slot = w / values.size
            values.forEachIndexed { i, v ->
                pen.color = if (i == values.lastIndex) accent else ink; pen.alpha = if (i == values.lastIndex) 255 else 60
                val top = h - (h * (v / high) * time).toFloat().coerceAtLeast(if (v > 0) 2 * d else 0f)
                canvas.drawRoundRect(i * slot + slot * .2f, top + d, (i + 1) * slot - slot * .2f, h + d, 1.5f * d, 1.5f * d, pen)
            }
            return
        }
        val low = values.min(); val span = (values.max() - low).takeIf { it > 1e-9 } ?: 1.0
        fun x(i: Int) = 3 * d + (w - 6 * d) * i / (values.size - 1)
        fun y(v: Double) = (3 * d + (h - 6 * d) * (1 - (v - low) / span)).toFloat()
        val shown = ((values.size - 1) * time).toInt() + 1
        val path = Path().apply { values.take(shown).forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 1.8f * d; pen.strokeCap = Paint.Cap.ROUND; pen.strokeJoin = Paint.Join.ROUND
        pen.color = accent
        canvas.drawPath(path, pen)
        if (time >= 1f) { pen.style = Paint.Style.FILL; canvas.drawCircle(x(values.lastIndex), y(values.last()), 2.8f * d, pen) }
    }
}

internal fun MainActivity.toneColor(tone: String, hero: Boolean = false) = getColor(when {
    hero && tone == "good" -> R.color.hero_good; hero && tone == "watch" -> R.color.signal; hero -> R.color.on_hero
    tone == "good" -> R.color.ready; tone == "watch" -> R.color.warning; else -> R.color.muted
})

/** The finding sheet: every finding of the item in full, then the way to the full screen. */
internal fun MainActivity.findingSheet(title: String, notes: List<JSONObject>, open: () -> Unit) {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
    }
    text(title, 20, parent = panel).instrumentType(display = true)
    notes.forEach { note ->
        val tone = note.optString("tone")
        text(note.optString("title"), 16, if (tone == "info") getColor(R.color.ink) else toneColor(tone), panel)
            .apply { instrumentType(display = true); setPadding(0, dp(12), 0, 0) }
        text(note.optString("text"), 14, parent = panel)
    }
    button(getString(R.string.today_open_details), primary = true, parent = panel) { dialog.dismiss(); open() }
        .layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    dialog.setContentView(android.widget.ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
}

private fun JSONArray?.doubles() = this?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()

private fun View.tappable(ctx: MainActivity, radius: Int, action: () -> Unit) {
    isClickable = true; isFocusable = true
    foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(ctx.getColor(R.color.hover)), null, ctx.shape(Color.WHITE, radius))
    setOnClickListener { action() }
}

/** The readiness panel: factor gauge on the left, the vitals against the usual on the right, the finding under it. */
internal fun MainActivity.vitalsPanel(data: JSONObject?, notes: List<JSONObject>, parent: LinearLayout, extra: (LinearLayout) -> Unit,
                                      open: () -> Unit): LinearLayout? {
    val r = data?.optJSONObject("readiness") ?: return null
    val status = r.optString("status")
    val factors = r.optJSONArray("factors")?.objects().orEmpty().filter { it.optString("key") != "adjustment" }
    val panel = card(parent, hero = true).apply {
        tag = "card-readiness"
        setPadding(dp(16), dp(16), dp(16), dp(12))
        tappable(this@vitalsPanel, 20) { if (notes.isEmpty()) open() else findingSheet(getString(R.string.rd_title), notes, open) }
    }
    val top = row(panel, center = true)
    val gaugeStates = factors.filter { it.optString("key") != "sleep" }.map { if (it.isNull("value")) "missing" else it.optString("state") }
    // Before any of today's values arrive, a dash instead of a sentence squeezed into the arc.
    val word = if (gaugeStates.all { it == "missing" }) "—" else readinessLabel(status)
    top.addView(FactorGauge(this, gaugeStates, word,
        getString(R.string.today_factors_count, gaugeStates.count { it != "missing" }, gaugeStates.size)),
        LinearLayout.LayoutParams(dp(128), dp(122)))
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    top.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
    val shortNames = mapOf("wellbeing" to R.string.today_v_wellbeing, "resting_hr" to R.string.today_v_rhr, "hrv" to R.string.today_v_hrv,
        "jump" to R.string.today_v_jump, "sleep" to R.string.today_v_sleep)
    val order = listOf("resting_hr", "hrv", "sleep", "jump", "wellbeing")
    for (f in factors.filter { !it.isNull("value") }.sortedBy { order.indexOf(it.optString("key")) }.take(4)) {
        val line = row(column, center = true).apply { setPadding(0, dp(3), 0, dp(3)) }
        text(getString(shortNames[f.optString("key")] ?: R.string.rd_title), 12, getColor(R.color.on_hero), line).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(0, 0, 0, 0)
        }
        val reading = when (f.optString("key")) {
            "hrv" -> metricNumber(exp(f.getDouble("value")), "training_minutes", locale)
            "sleep" -> f.optJSONObject("sleep")?.optDouble("hours")?.takeIf { it.isFinite() }?.let { short(it) + " " + getString(R.string.today_hours_unit) } ?: "—"
            "resting_hr", "wellbeing" -> metricNumber(f.getDouble("value"), "training_minutes", locale)
            else -> short(f.getDouble("value"))
        }
        text(reading, 15, Color.WHITE, line).apply { instrumentType(numeric = true); setPadding(dp(8), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(-2, -2) }
        val (arrow, tone) = when (f.optString("state")) { "better" -> "▲" to "good"; "worse" -> "▼" to "watch"; else -> "•" to "info" }
        text(arrow, 10, toneColor(tone, hero = true), line).apply {
            setPadding(dp(6), 0, 0, 0); contentDescription = factorState(f); layoutParams = LinearLayout.LayoutParams(dp(18), -2)
        }
    }
    if (column.childCount == 0) text(getString(R.string.today_checkin_missing), 14, getColor(R.color.on_hero), column)
    notes.firstOrNull()?.let { note ->
        text(note.optString("title"), 15, toneColor(note.optString("tone"), hero = true), panel).apply {
            instrumentType(display = true); setPadding(0, dp(10), 0, 0)
        }
    }
    extra(panel)
    panel.contentDescription = getString(R.string.rd_title) + ": " + readinessLabel(status) + ". " + notes.joinToString(". ") { it.optString("title") }
    return panel
}

/** Food and load as three rings: kcal of target, protein of target, the last 7 days' load against the usual week. */
internal fun MainActivity.dayRings(data: JSONObject?, notes: (String) -> List<JSONObject>, parent: LinearLayout, open: (String) -> Unit) {
    val food = data?.optJSONObject("nutrition")
    val load = data?.optJSONObject("training")
    if (food == null && load == null) return
    val line = LinearLayout(this).apply { isBaselineAligned = false; setPadding(0, dp(12), 0, dp(2)) }
    parent.addView(line, LinearLayout.LayoutParams(-1, -2))
    fun ring(key: String, tag: String?, fraction: Double?, over: Boolean, reading: String, caption: String, count: Double? = null) {
        val found = notes(key)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
            if (tag != null) this.tag = tag
            tappable(this@dayRings, 16) { if (found.isEmpty()) open(key) else findingSheet(caption, found) { open(key) } }
            contentDescription = "$caption: $reading. " + found.joinToString(". ") { it.optString("title") }
        }
        cell.addView(DayRing(this, fraction, over, reading, count), LinearLayout.LayoutParams(dp(78), dp(78)))
        text(caption, 12, parent = cell).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(dp(2), dp(6), dp(2), 0) }
        line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
    }
    if (food != null) {
        val eaten = food.getJSONObject("eaten"); val target = food.optJSONObject("target")
        val kcal = eaten.getDouble("kcal"); val goal = target?.optDouble("kcal")?.takeIf { it > 0 }
        ring("nutrition", "card-nutrition", goal?.let { kcal / it }, goal != null && kcal > goal * 1.1, metricNumber(kcal, "kcal", locale),
            if (goal != null) getString(R.string.today_ring_kcal, metricNumber(goal, "kcal", locale)) else getString(R.string.unit_kcal), kcal)
        val protein = eaten.getDouble("protein_g"); val proteinGoal = target?.optDouble("protein_g")?.takeIf { it > 0 }
        ring("nutrition", null, proteinGoal?.let { protein / it }, false, metricNumber(protein, "kcal", locale),
            if (proteinGoal != null) getString(R.string.today_ring_protein, metricNumber(proteinGoal, "kcal", locale)) else getString(R.string.macro_protein), protein)
    }
    if (load != null) {
        val acute = load.optJSONObject("acute_load")?.optDouble("load") ?: load.optJSONArray("daily").doubles().sum()
        val usual = load.optJSONObject("chronic_load")?.optDouble("weekly_load")?.takeIf { it.isFinite() && it > 0 }
        ring("training", "card-training", usual?.let { acute / it / 1.5 }, usual != null && acute > usual * 1.3, metricNumber(acute, "kcal", locale),
            if (usual != null) getString(R.string.today_ring_load, metricNumber(usual, "kcal", locale)) else getString(R.string.today_load_7d), acute)
    }
}

/** One instrument row: tone dot, label and finding on the left, a small picture and the reading on the right. */
internal fun MainActivity.metricRow(key: String, label: String, reading: String?, unit: String?, picture: View?, notes: List<JSONObject>,
                                    parent: LinearLayout, sub: String? = null, open: () -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        tag = "card-$key"; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        tappable(this@metricRow, 0) { if (notes.isEmpty()) open() else findingSheet(label, notes, open) }
    }
    parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    val tone = notes.firstOrNull()?.optString("tone")
    row.addView(View(this).apply { background = shape(if (tone == null) Color.TRANSPARENT else if (tone == "info") getColor(R.color.line) else toneColor(tone), 4) },
        LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(12) })
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
    text(label, 15, getColor(R.color.ink), left).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0) }
    val second = notes.firstOrNull()?.optString("title") ?: sub
    if (second != null) text(second, 12, if (tone == null || tone == "info") getColor(R.color.muted) else toneColor(tone), left)
        .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1), 0, 0) }
    if (picture != null) row.addView(picture, LinearLayout.LayoutParams(dp(56), dp(26)).apply { marginStart = dp(10) })
    if (reading != null) {
        val right = LinearLayout(this).apply { gravity = Gravity.END or Gravity.BOTTOM }
        row.addView(right, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        text(reading, 17, getColor(R.color.ink), right).apply { instrumentType(numeric = true); setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(-2, -2) }
        if (unit != null) text(unit, 11, parent = right).apply { setPadding(dp(3), 0, 0, dp(2)); layoutParams = LinearLayout.LayoutParams(-2, -2) }
    }
    row.contentDescription = listOfNotNull(label, reading?.let { it + (unit?.let { u -> " $u" } ?: "") }, second).joinToString(", ")
    return row
}

/** Rows for every metric that has something to show today, in the stored order; null when none has. */
internal fun MainActivity.metricRows(keys: List<String>, data: JSONObject?, notes: (String) -> List<JSONObject>, title: (String) -> String,
                                     parent: LinearLayout, open: (String) -> Unit) {
    val group = settingsGroup(parent).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
    var first = true
    fun add(key: String, reading: String?, unit: String?, picture: View? = null, sub: String? = null) {
        if (!first) divider(group)
        first = false
        metricRow(key, title(key), reading, unit, picture, notes(key), group, sub) { open(key) }
    }
    for (key in keys) when (key) {
        "checkin" -> data?.optJSONObject("checkin")?.let { c ->
            if (c.optBoolean("done")) add(key, metricNumber(c.getDouble("wellbeing"), "training_minutes", locale), "/ ${c.getInt("out_of")}")
            else add(key, null, null, sub = getString(R.string.today_checkin_missing))
        }
        "weight" -> data?.optJSONObject("weight")?.let { w ->
            val series = w.optJSONArray("series").doubles()
            add(key, short(w.getDouble("kg")), getString(R.string.unit_kg), if (series.size >= 2) Spark(this, series) else null)
        }
        "steps" -> healthSteps?.let { add(key, String.format(locale, "%,d", it), null, sub = getString(R.string.today_steps_unit)) }
        "body" -> data?.optJSONObject("body")?.let { b ->
            val series = b.optJSONArray("series").doubles()
            add(key, short(b.getDouble("pct")), "% ± " + short(b.getDouble("error_pct")), if (series.size >= 2) Spark(this, series) else null)
        }
        "form" -> data?.optJSONObject("form")?.let { f ->
            val exercise = runCatching { jumpscan.core.FormExercises[f.getString("exercise")].label(locale.language == "bg") }
                .getOrDefault(f.optString("exercise"))
            if (!f.isNull("hold_ms") && f.optDouble("hold_ms") > 0) add(key, short(f.getDouble("hold_ms") / 1000), getString(R.string.today_s_unit), sub = exercise)
            else add(key, f.optInt("reps").toString(), getString(R.string.today_reps_unit), sub = exercise)
        }
        "week" -> data?.optJSONObject("week")?.optJSONObject("consistency")?.optJSONObject("checkin")?.let { c ->
            add(key, "${c.getInt("done")}", "/ ${c.getInt("days")}")
        }
        "records" -> (data?.opt("records") as? JSONArray)?.objects()?.firstOrNull()?.let { newest ->
            add(key, recordValue(newest), null, sub = recordLabel(newest))
        }
        "plan" -> data?.optJSONObject("plan")?.let { p ->
            val items = p.optJSONArray("items")?.objects().orEmpty()
            if (items.isEmpty() && p.isNull("adjusted")) return@let
            val sub = if (p.optString("adjusted") == "rest") getString(R.string.adj_rest_day)
                else items.take(3).joinToString(" · ") { it.getString("exercise") }
            add(key, items.size.toString(), getString(R.string.today_exercises_unit), sub = sub)
        }
        "next_session" -> data?.optJSONObject("next_session")?.let { s ->
            add(key, null, null, sub = s.getString("title") + " · " + localDateTime(s.getString("starts_at"), locale))
        }
        "messages" -> data?.optInt("unread")?.takeIf { it > 0 }?.let { n ->
            add(key, n.toString(), null, sub = getString(R.string.today_messages_unread, n))
        }
    }
    if (first) parent.removeView(group)
}

/** Compact one-tap actions in a scrolling row; the first is the primary one. */
internal fun MainActivity.actionChips(vararg entries: UiTile, parent: LinearLayout = page) {
    val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(40)) }
    parent.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    val line = LinearLayout(this)
    scroll.addView(line)
    entries.forEachIndexed { index, tile ->
        val primary = index == 0
        val ink = getColor(if (primary) R.color.on_action else R.color.ink)
        line.addView(TextView(this).apply {
            text = getString(tile.label); textSize = 14f; setTextColor(ink); instrumentType(display = true)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(16), 0)
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon(tile.icon, ink).apply { setBounds(0, 0, dp(18), dp(18)) }, null, null, null)
            background = shape(getColor(if (primary) R.color.accent else R.color.hover), 22)
            isClickable = true; isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.accent_pressed)), null, shape(Color.WHITE, 22))
            setOnClickListener { tile.action() }
            pressFeedback()
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { if (index > 0) marginStart = dp(8) })
    }
}
