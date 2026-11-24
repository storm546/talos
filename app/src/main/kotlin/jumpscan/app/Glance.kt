package jumpscan.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

// Today at a glance (SPEC 10.9): a grid of small tiles, each a number, a tiny picture and the finding's title.
// Tapping a tile explains the finding in a sheet and leads to the full screen.

/** A sparkline (line) or seven-day bars (bars); the last point is the accent. */
internal class Spark(context: Context, private val values: List<Double>, private val bars: Boolean = false) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty() || (!bars && values.size < 2)) return
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat() - 2 * d
        val accent = context.getColor(R.color.accent); val ink = context.getColor(R.color.ink)
        if (bars) {
            val high = values.max().takeIf { it > 0 } ?: return
            val slot = w / values.size
            values.forEachIndexed { i, v ->
                pen.color = if (i == values.lastIndex) accent else ink; pen.alpha = if (i == values.lastIndex) 255 else 70
                val top = h - (h * (v / high)).toFloat().coerceAtLeast(if (v > 0) 2 * d else 0f)
                canvas.drawRoundRect(i * slot + slot * .18f, top + d, (i + 1) * slot - slot * .18f, h + d, 2 * d, 2 * d, pen)
            }
            return
        }
        val low = values.min(); val span = (values.max() - low).takeIf { it > 1e-9 } ?: 1.0
        fun x(i: Int) = 3 * d + (w - 6 * d) * i / (values.size - 1)
        fun y(v: Double) = (3 * d + (h - 6 * d) * (1 - (v - low) / span)).toFloat()
        val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 2 * d; pen.strokeCap = Paint.Cap.ROUND; pen.color = accent
        canvas.drawPath(path, pen)
        pen.style = Paint.Style.FILL
        canvas.drawCircle(x(values.lastIndex), y(values.last()), 3 * d, pen)
    }
}

/** A thin progress meter. */
internal fun MainActivity.meter(fraction: Double, parent: LinearLayout, color: Int = getColor(R.color.accent)) = parent.addView(
    LinearLayout(this).apply {
        background = shape(getColor(R.color.hover), 3)
        addView(View(context).apply { background = shape(color, 3) },
            LinearLayout.LayoutParams(0, -1, fraction.coerceIn(0.02, 1.0).toFloat()))
        addView(View(context), LinearLayout.LayoutParams(0, -1, (1 - fraction.coerceIn(0.02, 1.0)).toFloat()))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10); bottomMargin = dp(4) })

internal fun MainActivity.toneColor(tone: String, hero: Boolean = false) = getColor(when {
    hero && tone == "good" -> R.color.hero_good; hero && tone == "watch" -> R.color.signal; hero -> R.color.on_hero
    tone == "good" -> R.color.ready; tone == "watch" -> R.color.warning; else -> R.color.ink
})

/** The finding sheet: every finding of the card in full, then the way to the full screen. */
internal fun MainActivity.findingSheet(title: String, notes: List<JSONObject>, open: () -> Unit) {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
    }
    text(title, 20, parent = panel).instrumentType(display = true)
    notes.forEach { note ->
        text(note.optString("title"), 16, toneColor(note.optString("tone")), panel).apply { instrumentType(display = true); setPadding(0, dp(12), 0, 0) }
        text(note.optString("text"), 14, parent = panel)
    }
    button(getString(R.string.today_open_details), primary = true, parent = panel) { dialog.dismiss(); open() }
        .layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    dialog.setContentView(android.widget.ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
}

/** Keys drawn as tiles, two per row. */
internal val GLANCE = setOf("checkin", "sleep", "nutrition", "training", "weight", "steps", "body", "form", "week", "records")

/** One tile, or null when the key has nothing to show today (a tile never says only "no data"). */
internal fun MainActivity.glanceTile(key: String, data: JSONObject?, notes: List<JSONObject>, title: String, open: () -> Unit): LinearLayout? {
    val tile = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = raisedSurface()
        elevation = resources.getDimension(R.dimen.lift_panel)
        tag = "card-$key"
        isClickable = true; isFocusable = true
        foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 14))
        setOnClickListener { if (notes.isEmpty()) open() else findingSheet(title, notes, open) }
    }
    val ink = getColor(R.color.ink)
    text(title.uppercase(locale), 11, getColor(R.color.accent), tile).apply {
        instrumentType(display = true); letterSpacing = .1f; maxLines = 1; setPadding(0, 0, 0, 0)
    }
    fun value(big: String, small: String? = null) = row(tile, center = true).also { line ->
        text(big, 24, ink, line).apply {
            instrumentType(numeric = true); setPadding(0, dp(4), 0, 0); maxLines = 1; layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        if (small != null) text(small, 12, parent = line).apply {
            setPadding(dp(6), dp(8), 0, 0); maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
    }
    fun picture(view: View) = tile.addView(view, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(8) })
    fun section(name: String) = data?.optJSONObject(name)
    when (key) {
        "checkin" -> {
            val c = section("checkin") ?: return null
            if (!c.optBoolean("done")) { value("—"); text(getString(R.string.today_checkin_missing), 13, parent = tile) }
            else {
                value(metricNumber(c.getDouble("wellbeing"), "training_minutes", locale), "/ ${c.getInt("out_of")}")
                meter(c.getDouble("wellbeing") / c.getInt("out_of"), tile)
            }
        }
        "sleep" -> {
            val sleep = section("readiness")?.optJSONArray("factors")?.objects()?.firstOrNull { it.optString("key") == "sleep" }
                ?.optJSONObject("sleep")?.takeIf { !it.isNull("hours") } ?: return null
            val hours = sleep.getDouble("hours")
            val need = sleep.optJSONArray("need_hours")
            value(short(hours), getString(R.string.today_hours_unit))
            meter(hours / (need?.optDouble(1)?.takeIf { it > 0 } ?: 9.0), tile, getColor(if (need != null && hours < need.optDouble(0)) R.color.warning else R.color.accent))
            if (need != null) text(getString(R.string.today_sleep_need, short(need.optDouble(0)), short(need.optDouble(1))), 12, parent = tile)
        }
        "nutrition" -> {
            val food = section("nutrition") ?: return null
            val kcal = food.getJSONObject("eaten").getDouble("kcal")
            val target = food.optJSONObject("target")
            value(metricNumber(kcal, "kcal", locale), target?.let { "/ ${metricNumber(it.getDouble("kcal"), "kcal", locale)} ${getString(R.string.unit_kcal)}" }
                ?: getString(R.string.unit_kcal))
            if (target != null) {
                meter(kcal / target.getDouble("kcal"), tile, getColor(if (kcal > target.getDouble("kcal") * 1.1) R.color.warning else R.color.accent))
                val protein = food.getJSONObject("eaten").getDouble("protein_g")
                text(getString(R.string.today_protein, short(protein), short(target.getDouble("protein_g"))), 12, parent = tile)
            }
        }
        "training" -> {
            val t = section("training") ?: return null
            val daily = t.optJSONArray("daily")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()
            val acute = t.optJSONObject("acute_load")?.optDouble("load") ?: daily.sum()
            value(metricNumber(acute, "kcal", locale), getString(R.string.today_load_7d))
            if (daily.any { it > 0 }) picture(Spark(this, daily, bars = true))
            t.optJSONObject("chronic_load")?.optDouble("weekly_load")?.takeIf { it.isFinite() && it > 0 }?.let {
                text(getString(R.string.today_load_usual, metricNumber(it, "kcal", locale)), 12, parent = tile)
            }
        }
        "weight" -> {
            val w = section("weight") ?: return null
            value(short(w.getDouble("kg")), getString(R.string.unit_kg))
            val series = w.optJSONArray("series")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()
            if (series.size >= 2) picture(Spark(this, series))
        }
        "steps" -> {
            val steps = healthSteps ?: return null
            value(String.format(locale, "%,d", steps), getString(R.string.today_steps_unit))
            meter(steps / 10000.0, tile)
        }
        "body" -> {
            val b = section("body") ?: return null
            value(short(b.getDouble("pct")) + " %", "± " + short(b.getDouble("error_pct")))
            val series = b.optJSONArray("series")?.let { a -> List(a.length()) { a.getDouble(it) } }.orEmpty()
            if (series.size >= 2) picture(Spark(this, series))
        }
        "form" -> {
            val f = section("form") ?: return null
            val exercise = runCatching { jumpscan.core.FormExercises[f.getString("exercise")].label(locale.language == "bg") }.getOrDefault(f.optString("exercise"))
            if (!f.isNull("hold_ms") && f.optDouble("hold_ms") > 0) value(short(f.getDouble("hold_ms") / 1000), getString(R.string.today_s_unit))
            else value(f.optInt("reps").toString(), getString(R.string.today_reps_unit))
            text(exercise + " · " + localDate(f.getString("day"), locale), 12, parent = tile).maxLines = 1
        }
        "week" -> {
            val c = section("week")?.optJSONObject("consistency")?.optJSONObject("checkin") ?: return null
            value("${c.getInt("done")}", "/ ${c.getInt("days")} " + getString(R.string.today_days_unit))
            meter(c.getInt("done").toDouble() / c.getInt("days").coerceAtLeast(1), tile)
        }
        "records" -> {
            val found = (data?.opt("records") as? JSONArray)?.objects().orEmpty()
            val newest = found.firstOrNull() ?: return null
            value(recordValue(newest))
            text(recordLabel(newest), 12, parent = tile).maxLines = 1
        }
        else -> return null
    }
    notes.firstOrNull()?.let { note ->
        text(note.optString("title"), 13, toneColor(note.optString("tone")), tile).apply {
            instrumentType(display = true); maxLines = 2; setPadding(0, dp(8), 0, 0)
        }
    }
    tile.contentDescription = (listOf(title) + notes.map { it.optString("title") + ". " + it.optString("text") }).joinToString(". ")
    return tile
}

/** Tiles two per row; a lone last tile keeps half the width. */
internal fun MainActivity.glanceGrid(tiles: List<LinearLayout>, parent: LinearLayout) {
    tiles.chunked(2).forEach { pair ->
        val line = LinearLayout(this).apply { isBaselineAligned = false }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        pair.forEachIndexed { index, tile ->
            line.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(10) })
        }
        if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(10) })
    }
}
