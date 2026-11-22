package jumpscan.app

import android.app.TimePickerDialog
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.exp

// Readiness, sessions per day and load (SPEC 9 wave 2). The server computes every number; these views show them.

private val readinessWork = Executors.newSingleThreadExecutor()
internal val sessionKinds = listOf("practice" to R.string.rd_kind_practice, "gym" to R.string.rd_kind_gym,
    "match" to R.string.rd_kind_match, "conditioning" to R.string.rd_kind_conditioning, "other" to R.string.rd_kind_other)

/** Load in the background and show the result only while `view` is still on screen. */
private fun <T> MainActivity.later(view: android.view.View, load: () -> T, show: (T?) -> Unit) {
    val host = page
    readinessWork.submit {
        val result = runCatching(load).getOrNull()
        runOnUiThread { if (!isDestroyed && page === host && view.isAttachedToWindow) show(result) }
    }
}

internal fun MainActivity.readinessLabel(status: String) = getString(when (status) {
    "good" -> R.string.rd_good
    "take_care" -> R.string.rd_take_care
    "still_learning" -> R.string.rd_still_learning
    "no_data" -> R.string.rd_no_data
    else -> R.string.rd_normal
})

private fun MainActivity.readinessChip(status: String, parent: LinearLayout) = when (status) {
    "good" -> statusChip(readinessLabel(status), R.color.ready_surface, R.color.ready, parent)
    "take_care" -> statusChip(readinessLabel(status), R.color.notice, R.color.warning, parent)
    "normal" -> statusChip(readinessLabel(status), R.color.hover, R.color.ink, parent)
    else -> statusChip(readinessLabel(status), R.color.hover, R.color.muted, parent)
}

private fun MainActivity.factorValue(factor: JSONObject): String? {
    if (factor.isNull("value")) return null
    val value = factor.getDouble("value")
    return when (factor.getString("key")) {
        "wellbeing" -> getString(R.string.rd_value_points, metricNumber(value, "training_minutes", locale))
        "resting_hr" -> getString(R.string.rd_value_bpm, metricNumber(value, "training_minutes", locale))
        "hrv" -> getString(R.string.rd_value_ms, metricNumber(exp(value), "training_minutes", locale))
        "jump" -> getString(R.string.rd_value_cm, metricNumber(value, "jump_height_cm", locale))
        else -> getString(R.string.rd_value_sleep, metricNumber(value, "training_minutes", locale),
            metricNumber(factor.getJSONObject("sleep").getDouble("hours"), "hours", locale))
    }
}

private fun MainActivity.factorState(factor: JSONObject): String = when (factor.getString("state")) {
    "better" -> getString(R.string.rd_state_better)
    "worse" -> getString(R.string.rd_state_worse)
    "usual" -> getString(R.string.rd_state_usual)
    "learning" -> factor.getJSONObject("baseline").let { getString(R.string.rd_state_learning, it.getInt("n"), it.getInt("needed")) }
    else -> getString(R.string.rd_state_missing)
}

private val factorLabels = mapOf("wellbeing" to R.string.rd_wellbeing, "resting_hr" to R.string.rd_resting_hr,
    "hrv" to R.string.rd_hrv, "jump" to R.string.rd_jump, "sleep" to R.string.rd_sleep)

/** Today's readiness with the factors behind it: on Today and, from wave 1, on the tools hub. */
internal fun MainActivity.readinessCard(parent: LinearLayout = page): LinearLayout = card(parent).also { panel ->
    val head = row(panel, center = true)
    section(R.string.rd_title, head).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    val summary = text(getString(R.string.rd_loading), 14, parent = panel)
    val factors = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    panel.addView(factors)
    val actions = row(panel)
    button(getString(R.string.rd_check), parent = actions, look = ActionTone.TONAL) { readinessCheck() }
        .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10) }
    button(getString(R.string.rd_sessions_title), parent = actions) { sessionsScreen() }
        .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); marginStart = dp(8) }
    val api = featureApi(); val id = athleteId()
    later(panel, { api.obj("/api/athletes/$id/readiness?days=1&day=${todayLocal()}").getJSONArray("days").getJSONObject(0) }) { day ->
        if (day == null) { summary.text = getString(R.string.rd_offline); return@later }
        val status = day.getString("status")
        readinessChip(status, head)
        summary.text = getString(when (status) {
            "good" -> R.string.rd_summary_good
            "take_care" -> R.string.rd_summary_take_care
            "still_learning" -> R.string.rd_summary_still_learning
            "no_data" -> R.string.rd_summary_no_data
            else -> R.string.rd_summary_normal
        })
        day.getJSONArray("factors").objects().forEachIndexed { index, factor ->
            val value = factorValue(factor)
            if (value == null && factor.getString("state") != "learning") return@forEachIndexed
            if (factors.childCount > 0) divider(factors, inset = false)
            val line = valueRow(getString(factorLabels.getValue(factor.getString("key"))), value ?: "—", factors)
            text(factorState(factor), 12, getColor(when (factor.getString("state")) {
                "worse" -> R.color.warning
                "better" -> R.color.ready
                else -> R.color.muted
            }), factors).setPadding(0, 0, 0, dp(4))
            line.arrive(index * 40L)
        }
        text(getString(R.string.rd_not_advice), 12, parent = factors).setPadding(0, dp(8), 0, 0)
    }
}

/** The morning jump check: today's trials, the best valid one against the usual, and the record button. */
internal fun MainActivity.readinessCheck() {
    layout(getString(R.string.rd_check), "readiness")
    text(getString(R.string.rd_check_intro), 14)
    val status = text(getString(R.string.rd_loading), 14)
    val ruler = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(ruler)
    button(getString(R.string.rd_record), primary = true) { startReadinessJump() }.apply { minHeight = dp(56) }
    val api = featureApi(); val id = athleteId()
    later(status, { api.obj("/api/athletes/$id/readiness?days=1&day=${todayLocal()}").getJSONArray("days").getJSONObject(0) }) { day ->
        if (day == null) { status.text = getString(R.string.rd_offline); return@later }
        val jump = day.getJSONArray("factors").objects().first { it.getString("key") == "jump" }
        val base = jump.getJSONObject("baseline")
        val usual = if (base.getBoolean("ready")) base.getDouble("mean") else null
        status.text = if (jump.isNull("value")) getString(R.string.rd_no_jump_today) else factorState(jump).replaceFirstChar { it.titlecase(locale) }
        if (!jump.isNull("value")) ruler.addView(HeightRule(this, recordJump(jump.getDouble("value")), listOfNotNull(usual)),
            LinearLayout.LayoutParams(-1, dp(138)))
        val group = settingsGroup(ruler)
        valueRow(getString(R.string.rd_jump), factorValue(jump) ?: "—", group).setPadding(dp(16), dp(10), dp(16), dp(4))
        text(getString(R.string.rd_today_trials, jump.getInt("trials")) + "  ·  " + getString(R.string.rd_today_excluded, jump.getInt("excluded")),
            13, parent = group).setPadding(dp(16), 0, dp(16), dp(4))
        text(if (usual != null) getString(R.string.rd_usual, metricNumber(usual, "jump_height_cm", locale),
                metricNumber(jump.optDouble("threshold", 0.0), "jump_height_cm", locale))
            else getString(R.string.rd_usual_learning, base.getInt("n"), base.getInt("needed")), 13, parent = group)
            .setPadding(dp(16), 0, dp(16), dp(12))
        readinessChip(day.getString("status"), row(ruler, top = 10, center = true))
    }
    text(getString(R.string.rd_not_advice), 12)
}

/** Sessions of one day with type, minutes and RPE; adding one goes through the offline queue. */
internal fun MainActivity.sessionsScreen(day: String = todayLocal(), message: String? = null) {
    layout(getString(R.string.rd_sessions_title), "sessions")
    dayPicker(day) { sessionsScreen(it) }
    featureError(message)
    val list = card()
    section(R.string.rd_sessions_day, list)
    val loading = text(getString(R.string.feature_loading), 14, parent = list)
    val api = featureApi()
    later(list, { api.array("/api/training-sessions?from=$day&to=$day") }) { rows ->
        loading.text = when { rows == null -> getString(R.string.rd_offline); rows.isEmpty() -> getString(R.string.rd_sessions_empty); else -> "" }
        if (rows.isNullOrEmpty()) return@later
        list.removeView(loading)
        rows.forEach { session ->
            val line = row(list, center = true).apply { setPadding(0, dp(6), 0, dp(6)) }
            val label = getString(sessionKinds.firstOrNull { it.first == session.getString("kind") }?.second ?: R.string.rd_kind_other)
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            text(if (session.getString("source") == "checkin") label + " · " + getString(R.string.rd_from_checkin) else label, 16, parent = column).setPadding(0, 0, 0, 0)
            text(getString(R.string.rd_session_line, session.getInt("minutes"), session.getInt("rpe"), session.getInt("load")), 13, parent = column)
                .apply { instrumentType(numeric = true); setPadding(0, 0, 0, 0) }
            if (session.getString("source") != "checkin") button(getString(R.string.rd_delete), parent = line, look = ActionTone.TEXT) {
                work(getString(R.string.feature_saving), { api.send("DELETE", "/api/training-sessions/${session.getLong("id")}") },
                    { sessionsScreen(day) }, { sessionsScreen(day, it) })
            }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
    }
    val form = card()
    section(R.string.rd_add_session, form)
    var kind = "practice"
    val choices = mutableListOf<RadioButton>()
    sessionKinds.chunked(3).forEach { chunk ->
        val line = row(form).apply { setPadding(0, dp(4), 0, 0) }
        chunk.forEach { (key, label) ->
            val choice = RadioButton(this).apply {
                text = getString(label); textSize = 14f; isChecked = key == kind; segment()
                setOnClickListener { kind = key; choices.forEach { other -> other.isChecked = other === this } }
            }
            choices += choice
            line.addView(choice, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        repeat(3 - chunk.size) { line.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
    }
    val minutes = field(getString(R.string.rd_minutes), "", InputType.TYPE_CLASS_NUMBER, form)
    val rpe = field(getString(R.string.rd_rpe), "", InputType.TYPE_CLASS_NUMBER, form)
    button(getString(R.string.rd_save_session), primary = true, parent = form) {
        val m = minutes.value().toIntOrNull(); val r = rpe.value().toIntOrNull()
        if (m == null || m !in 0..600 || r == null || r !in 0..10) { snack(getString(R.string.rd_session_invalid)); return@button }
        saveFeature(FeatureWrite.SESSION, JSONObject().put("day", day).put("kind", kind).put("minutes", m).put("rpe", r), day) { _, note ->
            sessionsScreen(day, note)
        }
    }
    loadChart(day)
}

private fun MainActivity.loadChart(day: String) {
    val panel = card()
    section(R.string.rd_load_chart, panel)
    val api = featureApi(); val id = athleteId()
    val status = text(getString(R.string.feature_loading), 14, parent = panel)
    later(panel, { api.obj("/api/athletes/$id/load?days=28&day=$day") }) { load ->
        if (load == null) { status.text = getString(R.string.rd_offline); return@later }
        panel.removeView(status)
        val daily = load.getJSONArray("daily").objects()
        val short = java.time.format.DateTimeFormatter.ofPattern(if (locale.language == "bg") "dd.MM" else "d MMM", locale)
        fun label(row: JSONObject) = java.time.LocalDate.parse(row.getString("day")).format(short)
        panel.addView(SeriesPlot(this, daily.map { it.getDouble("load") }, first = label(daily.first()), last = label(daily.last())).apply {
            contentDescription = getString(R.string.rd_load_chart)
        }, LinearLayout.LayoutParams(-1, dp(170)))
        val week = load.getJSONArray("weeks").objects().lastOrNull()
        val ratio = load.getJSONObject("acute_chronic")
        fun number(value: Any?, key: String) = (value as? Number)?.let { metricNumber(it.toDouble(), key, locale) } ?: "—"
        statGrid(listOf(
            Triple(getString(R.string.rd_week_load), number(week?.opt("load"), "training_minutes"), ""),
            Triple(getString(R.string.rd_monotony), number(week?.opt("monotony")?.takeIf { it != JSONObject.NULL }, "knee_ankle_ratio"), ""),
            Triple(getString(R.string.rd_strain), number(week?.opt("strain")?.takeIf { it != JSONObject.NULL }, "training_minutes"), ""),
            Triple(getString(R.string.rd_ratio), if (ratio.getBoolean("ready")) number(ratio.opt("ratio")?.takeIf { it != JSONObject.NULL }, "knee_ankle_ratio")
                else getString(R.string.rd_ratio_learning, ratio.getInt("days")), "")), parent = panel)
        if (ratio.getBoolean("ready") && !ratio.isNull("band")) text(getString(R.string.rd_ratio) + ": " + getString(when (ratio.getString("band")) {
            "well_above" -> R.string.rd_band_well_above
            "above" -> R.string.rd_band_above
            "below" -> R.string.rd_band_below
            else -> R.string.rd_band_similar
        }), 14, getColor(R.color.ink), panel)
        text(getString(R.string.rd_load_note), 12, parent = panel)
    }
}

/** Daily reminder time, kept on the phone; the alarm posts a local notification (no push service). */
internal fun MainActivity.reminderControl(parent: LinearLayout = page) {
    val settings = reminderSettings(this)
    val minutes = settings.getInt("minutes", -1)
    val group = settingsGroup(parent)
    val value = if (minutes < 0) getString(R.string.rd_reminder_off) else getString(R.string.rd_reminder_at, "%02d:%02d".format(minutes / 60, minutes % 60))
    settingRow("checkin", getString(R.string.rd_reminder), value, group) {
        val start = if (minutes < 0) 7 * 60 + 30 else minutes
        TimePickerDialog(this, { _, hour, minute ->
            settings.edit().putInt("minutes", hour * 60 + minute).apply()
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            scheduleReminder(this)
            checkin()
        }, start / 60, start % 60, true).show()
    }
    if (minutes >= 0) {
        divider(group)
        settingRow("signout", getString(R.string.rd_reminder_stop), null, group, subtle = true) {
            settings.edit().remove("minutes").apply(); scheduleReminder(this); checkin()
        }
    }
}
