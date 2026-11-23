package jumpscan.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.IsoFields

// The personal loop (SPEC 10.3): the week card and screen, "not feeling 100%" and the record share card.
// The server computes every number; these views only show them.

internal val ADJUST_REASONS = listOf("ill" to R.string.adj_ill, "aches" to R.string.adj_aches, "tired" to R.string.adj_tired,
    "busy" to R.string.adj_busy)
internal val ADJUST_MODES = listOf("rest" to R.string.adj_rest, "lighter" to R.string.adj_lighter)

internal fun MainActivity.adjustLabel(value: String) =
    getString((ADJUST_REASONS + ADJUST_MODES).firstOrNull { it.first == value }?.second ?: R.string.adj_title)

internal fun isoWeek(day: LocalDate) = "%d-W%02d".format(day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

private fun mondayOf(day: LocalDate) = day.minusDays(day.dayOfWeek.value - 1L)

/** The two consistency habits as a percentage with the plain count beside it. */
internal fun MainActivity.consistencyRows(consistency: JSONObject?, parent: LinearLayout) {
    val line = row(parent)
    for ((index, entry) in listOf("checkin" to R.string.week_checkin, "training" to R.string.week_training_days).withIndex()) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(14) })
        val habit = consistency?.optJSONObject(entry.first)
        val percent = habit?.getInt("percent")
        targetBar(getString(entry.second), if (percent == null) "—" else "$percent %", percent?.div(100.0), column)
            .contentDescription = getString(entry.second) + ": " + (percent?.let { "$it %" } ?: "—")
        text(if (habit == null) getString(R.string.week_learning)
            else getString(R.string.week_of_days, habit.getInt("done"), habit.getInt("days")), 12, parent = column)
    }
}

internal fun MainActivity.weekScreen(week: String? = null) {
    val api = featureApi()
    val language = if (locale.language == "bg") "bg" else "en"
    work(getString(R.string.feature_loading), { api.obj("/api/me/week?language=$language" + (week?.let { "&week=$it" } ?: "")) },
        { drawWeek(it) }, { home(getString(R.string.week_failed)) })
}

internal fun MainActivity.drawWeek(data: JSONObject) {
    layout(getString(R.string.week_title), "week")
    val from = LocalDate.parse(data.getString("from"))
    text(getString(R.string.week_range, localDate(data.getString("from"), locale), localDate(data.getString("to"), locale)), 14)
        .instrumentType(numeric = true)
    val current = mondayOf(LocalDate.parse(todayLocal()))
    val nav = row()
    for ((index, step) in listOf(-1L, 1L).withIndex()) {
        val target = from.plusWeeks(step)
        button(getString(if (step < 0) R.string.week_prev else R.string.week_next), parent = nav, look = ActionTone.TONAL) {
            weekScreen(isoWeek(target))
        }.apply {
            tag = if (step < 0) "week-prev" else "week-next"
            isEnabled = !target.isAfter(current) && !target.isBefore(current.minusWeeks(52))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); if (index > 0) marginStart = dp(8) }
        }
    }
    val training = data.getJSONObject("training")
    val checkins = data.getJSONObject("checkins")
    val food = data.getJSONObject("nutrition")
    if (training.getInt("days") == 0 && checkins.getInt("days") == 0 && food.getInt("days_logged") == 0)
        notice(getString(R.string.week_no_data))
    if (!data.isNull("text")) card(hero = true).apply {
        text(data.getString("text"), 17, getColor(R.color.on_hero), this)
        text(getString(R.string.week_summary_note), 12, getColor(R.color.on_hero), this).alpha = .75f
    }
    card().apply {
        section(R.string.week_consistency, this)
        consistencyRows(data.optJSONObject("consistency"), this)
    }
    card().apply {
        section(R.string.week_training, this)
        statGrid(listOf(
            Triple(getString(R.string.week_days), "${training.getInt("days")} / 7", ""),
            Triple(getString(R.string.week_sessions), training.getInt("sessions").toString(), ""),
            Triple(getString(R.string.week_minutes), training.getInt("minutes").toString(), ""),
            Triple(getString(R.string.week_load), metricNumber(training.getDouble("load"), "kcal", locale), "AU"),
            Triple(getString(R.string.week_sets), training.getInt("sets").toString(), ""),
            Triple(getString(R.string.week_volume), metricNumber(training.getDouble("volume_kg"), "kcal", locale), getString(R.string.feature_kg, "").trim())), parent = this)
        text(getString(R.string.week_load_previous, metricNumber(training.getDouble("load_previous_week"), "kcal", locale)), 13, parent = this)
        val top = training.getJSONArray("top_exercises").objects()
        if (top.isNotEmpty()) text(getString(R.string.week_top), 13, green, this).setPadding(0, dp(12), 0, 0)
        top.forEach { valueRow(it.optString("exercise"), getString(R.string.week_top_row, it.getInt("sets"),
            getString(R.string.feature_kg, short(it.getDouble("volume_kg")))), this) }
    }
    card().apply {
        section(R.string.week_records, this)
        val records = data.getJSONArray("records").objects()
        if (records.isEmpty()) text(getString(R.string.week_records_empty), 14, parent = this)
        records.forEach { valueRow(recordLabel(it), recordValue(it), this) }
    }
    card().apply {
        section(R.string.week_body, this)
        valueRow(getString(R.string.week_checkin_days), "${checkins.getInt("days")} / 7", this)
        valueRow(getString(R.string.week_wellbeing), if (checkins.isNull("wellbeing_avg")) "—"
            else metricNumber(checkins.getDouble("wellbeing_avg"), "hours", locale) + " / 25", this)
        val counts = data.getJSONObject("readiness")
        val strip = row(this)
        listOf("good", "normal", "take_care", "still_learning").filter { counts.optInt(it) > 0 }.forEach { status ->
            val (fill, ink) = when (status) {
                "good" -> R.color.ready_surface to R.color.ready; "take_care" -> R.color.notice to R.color.warning
                else -> R.color.hover to R.color.ink
            }
            statusChip(readinessLabel(status) + " · " + counts.getInt(status), fill, ink, strip).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(6); topMargin = dp(8) }
            }
        }
    }
    card().apply {
        section(R.string.week_food, this)
        valueRow(getString(R.string.week_food_days), "${food.getInt("days_logged")} / 7", this)
        fun ofTarget(value: String, target: String) = if (food.isNull(value)) "—" else metricNumber(food.getDouble(value), "kcal", locale)
            .let { if (food.isNull(target)) it else getString(R.string.week_of_target, it, metricNumber(food.getDouble(target), "kcal", locale)) }
        valueRow(getString(R.string.week_kcal), ofTarget("kcal_avg", "kcal_target"), this)
        valueRow(getString(R.string.week_protein), getString(R.string.week_grams, ofTarget("protein_avg", "protein_target")), this)
        val weight = data.getJSONObject("weight")
        valueRow(getString(R.string.week_weight), if (weight.isNull("end_trend")) "—"
            else getString(R.string.week_weight_change, short(weight.getDouble("start_trend")), short(weight.getDouble("end_trend"))), this)
    }
}

/** The "not feeling 100%" sheet: a reason and rest or lighter, saved for today; the coach sees only a neutral mark. */
internal fun MainActivity.adjustmentSheet(current: JSONObject?): Dialog {
    val dialog = Dialog(this)
    var reason = current?.optString("reason")
    var mode = current?.optString("mode")
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
        tag = "adjustment-sheet"
    }
    fun save(method: String, body: JSONObject?) {
        dialog.dismiss()
        val api = featureApi()
        val view = page
        later(view, { api.send(method, "/api/me/days/${todayLocal()}/adjustment", body); true }) { saved ->
            if (saved == true) refreshToday() else snack(getString(R.string.adj_failed))
        }
    }
    fun draw() {
        panel.removeAllViews()
        section(R.string.adj_title, panel)
        text(getString(R.string.adj_intro), 14, parent = panel)
        text(getString(R.string.adj_reason), 13, green, panel).setPadding(0, dp(12), 0, 0)
        for (pair in ADJUST_REASONS.chunked(2)) segments(pair.map { getString(it.second) }, pair.indexOfFirst { it.first == reason }, panel) {
            reason = pair[it].first; draw()
        }
        text(getString(R.string.adj_mode), 13, green, panel).setPadding(0, dp(12), 0, 0)
        segments(ADJUST_MODES.map { getString(it.second) }, ADJUST_MODES.indexOfFirst { it.first == mode }, panel) {
            mode = ADJUST_MODES[it].first; draw()
        }
        button(getString(R.string.adj_save), primary = true, parent = panel) {
            save("PUT", JSONObject().put("reason", reason).put("mode", mode))
        }.apply { isEnabled = reason != null && mode != null; tag = "adjustment-save" }
        if (current != null) button(getString(R.string.adj_clear), parent = panel) { save("DELETE", null) }
        button(getString(R.string.cancel), parent = panel) { dialog.dismiss() }
    }
    draw()
    dialog.setContentView(ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
    return dialog
}

internal fun MainActivity.recordShare(record: JSONObject): RecordShare {
    val value = record.getDouble("value")
    return RecordShare(recordLabel(record), recordValue(record), record.optJSONObject("previous")?.let { recordValue(record, it) },
        localDate(record.getString("date").take(10), locale), when (record.getString("measure")) {
            "jump_height" -> value; "flight_time" -> heightFromFlight(value); else -> null
        })
}

/** A compact Share action for a new record; the card is drawn on the phone and only handed to the share sheet. */
internal fun MainActivity.shareButton(record: JSONObject, parent: LinearLayout): Button = Button(this).apply {
    text = getString(R.string.share_record)
    isAllCaps = false
    minHeight = dp(48)
    minimumWidth = 0
    setPadding(dp(14), 0, dp(14), 0)
    styleButton(this, false, ActionTone.TONAL)
    contentDescription = getString(R.string.share_record_label, recordLabel(record))
    tag = "share-record"
    setOnClickListener { RecordCard.share(this@shareButton, recordShare(record)) }
    parent.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
}
