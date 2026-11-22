package jumpscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import jumpscan.core.GymMath

private val PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
private val BARS = listOf(20.0, 15.0, 10.0)

/** A loaded bar seen from the side: one sleeve with its plates, heavier plates taller. */
internal class PlateView(context: Context) : View(context) {
    var plates: List<Double> = emptyList()
        set(value) { field = value; invalidate(); contentDescription = value.joinToString(" + ") { kg(it) } }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun kg(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val mid = height / 2f
        pen.color = context.getColor(R.color.on_hero); pen.alpha = 110
        canvas.drawRoundRect(RectF(0f, mid - 4 * d, width.toFloat(), mid + 4 * d), 3 * d, 3 * d, pen)
        canvas.drawRoundRect(RectF(14 * d, mid - 12 * d, 22 * d, mid + 12 * d), 2 * d, 2 * d, pen) // collar
        var x = 26 * d
        pen.textAlign = Paint.Align.CENTER
        pen.typeface = resources.getFont(R.font.jetbrainsmono_regular)
        pen.textSize = 10 * d
        for (plate in plates) {
            val h = (height - 26 * d) * (0.34f + 0.66f * (plate / 25.0).toFloat().coerceAtMost(1f))
            val w = (if (plate >= 10) 16 else 11) * d
            // Drawn on the dark hero panel in both themes.
            pen.color = context.getColor(if (plate >= 20) R.color.signal else R.color.on_hero)
            pen.alpha = if (plate >= 5) 255 else 150
            canvas.drawRoundRect(RectF(x, mid - h / 2, x + w, mid + h / 2), 3 * d, 3 * d, pen)
            pen.color = context.getColor(R.color.on_hero); pen.alpha = 200
            canvas.drawText(kg(plate), x + w / 2, height - 2 * d, pen)
            x += w + 4 * d
            if (x > width - 20 * d) break
        }
    }
}

internal fun EditText.onChange(action: () -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = action()
})

/** A load with up to two decimals (1.25 kg plates stay exact), in the locale's format. */
internal fun MainActivity.kgNumber(value: Double): String =
    java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; isGroupingUsed = false }.format(value)

internal fun MainActivity.kgText(value: Double) = getString(R.string.feature_kg, kgNumber(value))

/** A segmented control; returns nothing, calls [pick] with the chosen index. */
internal fun MainActivity.segments(labels: List<String>, selected: Int, parent: LinearLayout = page, pick: (Int) -> Unit) {
    val group = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        background = shape(getColor(R.color.hover), 10)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    labels.forEachIndexed { index, label ->
        group.addView(RadioButton(this).apply {
            id = View.generateViewId(); text = label; textSize = 14f; minHeight = dp(44)
            isChecked = index == selected
            segment()
            setOnClickListener { pick(index) }
        }, RadioGroup.LayoutParams(0, -2, 1f))
    }
    parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
}

/** Plate loading, one-repetition maximum and warm-up sets (SPEC 9), all on the phone. */
internal fun MainActivity.calculators(tab: Int = 0) {
    layout(getString(R.string.calc_title), "calculators")
    val prefs = getSharedPreferences("calculators", android.content.Context.MODE_PRIVATE)
    segments(listOf(getString(R.string.calc_plates), getString(R.string.calc_one_rm), getString(R.string.calc_warmup)), tab) { calculators(it) }
    when (tab) {
        0 -> plateCalculator(prefs)
        1 -> oneRepMax(prefs)
        else -> warmupCalculator(prefs)
    }
}

private fun MainActivity.barChoice(prefs: android.content.SharedPreferences, changed: () -> Unit) {
    eyebrow(getString(R.string.calc_bar))
    segments(BARS.map(::kgText), BARS.indexOf(prefs.getFloat("bar", 20f).toDouble()).coerceAtLeast(0)) {
        prefs.edit().putFloat("bar", BARS[it].toFloat()).apply(); changed()
    }
}

private fun available(prefs: android.content.SharedPreferences) =
    prefs.getString("plates", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: PLATES.take(7)

private fun MainActivity.plateCalculator(prefs: android.content.SharedPreferences) {
    val target = decimalField(R.string.calc_target, prefs.getString("target", "100").orEmpty())
    val panel = card(hero = true)
    val total = Measurement(this).apply {
        textSize = 44f; setTextColor(getColor(R.color.signal)); instrumentType(numeric = true)
    }
    panel.addView(total)
    val side = text("", 14, getColor(R.color.on_hero), panel)
    val bar = PlateView(this)
    panel.addView(bar, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(8) })
    val warning = text("", 14, getColor(R.color.signal), panel)
    fun update() {
        prefs.edit().putString("target", target.value()).apply()
        val kg = decimal(target.value())
        val barKg = prefs.getFloat("bar", 20f).toDouble()
        if (kg == null || kg !in 0.0..1000.0) {
            total.text = "—"; side.text = getString(R.string.calc_enter_load); bar.plates = emptyList(); warning.visibility = View.GONE; return
        }
        val load = GymMath.plates(kg, barKg, available(prefs))
        total.text = kgText(load.total)
        side.text = if (load.plates.isEmpty()) getString(R.string.calc_empty_bar)
            else getString(R.string.calc_per_side, load.plates.joinToString(" + ") { kgNumber(it) })
        bar.plates = load.plates
        warning.visibility = if (load.exact) View.GONE else View.VISIBLE
        warning.text = load.above?.let { getString(R.string.calc_impossible, kgText(load.total), kgText(it)) }
            ?: getString(R.string.calc_impossible_low, kgText(load.total))
    }
    target.onChange(::update)
    barChoice(prefs) { update() }
    eyebrow(getString(R.string.calc_available))
    val chips = row()
    val second = row()
    PLATES.forEachIndexed { index, plate ->
        RadioButton(this).apply {
            text = kgNumber(plate)
            textSize = 14f; minHeight = dp(44)
            isChecked = plate in available(prefs)
            segment()
            contentDescription = kgText(plate)
            setOnClickListener {
                val now = available(prefs).toMutableSet().apply { if (plate in this) remove(plate) else add(plate) }
                prefs.edit().putString("plates", now.sortedDescending().joinToString(",")).apply()
                isChecked = plate in now
                update()
            }
            (if (index < 4) chips else second).addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(6); topMargin = dp(6)
            })
        }
    }
    text(getString(R.string.calc_plates_hint), 13)
    update()
}

private fun MainActivity.oneRepMax(prefs: android.content.SharedPreferences) {
    val (left, right) = columns()
    val load = decimalField(R.string.calc_lifted, prefs.getString("rm_load", "100").orEmpty(), left)
    val reps = field(getString(R.string.calc_reps), prefs.getString("rm_reps", "5").orEmpty(), android.text.InputType.TYPE_CLASS_NUMBER, right)
    val readout = settingsGroup()
    val table = settingsGroup()
    val note = text(getString(R.string.calc_rm_note), 13)
    fun update() {
        prefs.edit().putString("rm_load", load.value()).putString("rm_reps", reps.value()).apply()
        readout.removeAllViews(); table.removeAllViews()
        val kg = decimal(load.value()); val n = reps.value().toIntOrNull()
        if (kg == null || kg !in 0.0..1000.0 || n == null || n !in 1..30) {
            readoutRow(getString(R.string.calc_epley), "—", "", readout); return
        }
        val epley = GymMath.epley(kg, n)
        val brzycki = GymMath.brzycki(kg, n)
        readoutRow(getString(R.string.calc_epley), metricNumber(epley, "load_kg", locale), getString(R.string.unit_kg), readout)
        divider(readout, inset = false)
        readoutRow(getString(R.string.calc_brzycki), brzycki?.let { metricNumber(it, "load_kg", locale) } ?: "—", getString(R.string.unit_kg), readout)
        val base = if (brzycki == null) epley else (epley + brzycki) / 2
        eyebrowRow(getString(R.string.calc_percent_of, kgText(GymMath.percentTable(base, .5).first().second)), table)
        GymMath.percentTable(base).forEachIndexed { index, (percent, value) ->
            if (index > 0) divider(table, inset = false)
            valueRow("$percent %", kgText(value), table).apply { setPadding(dp(16), dp(7), dp(16), dp(7)) }
        }
        note.visibility = if (n > 10) View.VISIBLE else View.GONE
    }
    load.onChange(::update); reps.onChange(::update)
    update()
}

/** Two equal columns side by side, for short fields. */
internal fun MainActivity.columns(parent: LinearLayout = page): Pair<LinearLayout, LinearLayout> {
    val line = row(parent)
    fun column(start: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        line.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = start })
    }
    return column(0) to column(dp(10))
}

private fun MainActivity.eyebrowRow(value: String, parent: LinearLayout) = text(value, 13, getColor(R.color.muted), parent).apply {
    setPadding(dp(16), dp(12), dp(16), dp(4))
}

private fun MainActivity.warmupCalculator(prefs: android.content.SharedPreferences) {
    val working = decimalField(R.string.calc_working, prefs.getString("working", "100").orEmpty())
    val list = settingsGroup()
    fun update() {
        prefs.edit().putString("working", working.value()).apply()
        list.removeAllViews()
        val kg = decimal(working.value())
        val barKg = prefs.getFloat("bar", 20f).toDouble()
        val sets = if (kg == null || kg !in 0.0..1000.0) emptyList() else GymMath.warmup(kg, barKg)
        if (sets.isEmpty()) { eyebrowRow(getString(R.string.calc_no_warmup), list); return }
        sets.forEachIndexed { index, set ->
            if (index > 0) divider(list, inset = false)
            val plates = GymMath.plates(set.load, barKg, available(prefs)).plates
            readoutRow(getString(R.string.calc_warmup_set, index + 1, set.reps), kgNumber(set.load), getString(R.string.unit_kg), list)
            text(if (plates.isEmpty()) getString(R.string.calc_empty_bar) else getString(R.string.calc_per_side,
                plates.joinToString(" + ") { kgNumber(it) }), 13, parent = list)
                .setPadding(dp(16), 0, dp(16), dp(10))
        }
        divider(list, inset = false)
        readoutRow(getString(R.string.calc_work_set), kgNumber(kg!!),
            getString(R.string.unit_kg), list)
    }
    working.onChange(::update)
    barChoice(prefs) { update() }
    update()
}
