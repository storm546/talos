package jumpscan.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// Personal records and the club leaderboard (SPEC 8). The server computes both; these screens only show them.

/** A jump row for HeightRule; the flight time follows from h = g t² / 8. */
internal fun recordJump(heightCm: Double): JSONObject = JSONObject().put("jump_height_cm", heightCm)
    .put("flight_time_ms", sqrt(8 * heightCm / 100 / 9.80665) * 1000).put("warnings", JSONArray())

internal fun heightFromFlight(flightMs: Double) = 9.80665 * (flightMs / 1000) * (flightMs / 1000) / 8 * 100

internal fun MainActivity.recordValue(record: JSONObject, point: JSONObject = record): String {
    val value = point.getDouble("value")
    return when (record.getString("measure")) {
        "jump_height" -> metricNumber(value, "jump_height_cm", locale) + " " + getString(R.string.ui_cm)
        "flight_time" -> getString(R.string.records_ms, metricNumber(value, "flight_time_ms", locale))
        "form_hold" -> getString(R.string.records_seconds, metricNumber(value / 1000, "hold_s", locale))
        "balance_stance" -> getString(R.string.records_seconds, String.format(locale, "%.1f", value / 1000))
        "sprint_time", "agility_time" -> getString(R.string.records_seconds, String.format(locale, "%.2f", value / 1000))
        "heaviest_set" -> getString(R.string.records_set, getString(R.string.feature_kg, metricNumber(value, "load_kg", locale)), point.getInt("reps"))
        else -> getString(R.string.feature_kg, metricNumber(value, "load_kg", locale))
    }
}

internal fun MainActivity.recordNote(record: JSONObject): String {
    val day = localDate(record.getString("date").take(10), locale)
    val previous = record.optJSONObject("previous") ?: return getString(R.string.records_first, day)
    return getString(R.string.records_previous, day, recordValue(record, previous))
}

internal fun MainActivity.records() {
    val api = featureApi()
    work(getString(R.string.feature_loading), { api.obj("/api/athletes/${athleteId()}/records") }, { recordsScreen(it) }, { home(it) })
}

private fun MainActivity.newChip(record: JSONObject, parent: LinearLayout) {
    if (!record.optBoolean("new")) return
    statusChip(getString(R.string.records_new), R.color.signal, R.color.hero, parent)
    shareButton(record, parent)
}

/** A readout row with the record's date, the record it replaced and a "new record" mark. */
private fun MainActivity.recordRow(label: String, record: JSONObject, parent: LinearLayout): LinearLayout =
    readoutRow(label, recordValue(record), "", parent).apply {
        val foot = row(this, center = true)
        text(recordNote(record), 13, parent = foot).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        newChip(record, foot)
    }

private fun MainActivity.recordsScreen(data: JSONObject) {
    layout(getString(R.string.records_title), "records")
    text(getString(R.string.records_intro), 14)
    val all = data.getJSONArray("records").objects()
    if (all.isEmpty()) notice(getString(R.string.records_empty))
    all.filter { it.getString("measure") == "jump_height" }.forEach { height ->
        val head = row(top = 14, center = true)
        section(R.string.records_height, head).apply { text = getString(R.string.records_height) + recordQualifier(height) }
            .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        newChip(height, head)
        // The previous best is drawn as a tick on the same ruler.
        val previous = height.optJSONObject("previous")?.getDouble("value")
        page.addView(HeightRule(this, recordJump(height.getDouble("value")), listOfNotNull(previous)), LinearLayout.LayoutParams(-1, dp(138)))
        text(recordNote(height), 14)
    }
    val timed = all.filter { it.getString("measure") in listOf("flight_time", "form_hold") }
    if (timed.isNotEmpty()) {
        val group = settingsGroup()
        timed.forEachIndexed { index, record ->
            if (index > 0) divider(group, inset = false)
            val label = if (record.getString("measure") == "flight_time") getString(R.string.records_flight) + recordQualifier(record)
                else getString(R.string.records_hold, record.getString(if (locale.language == "bg") "name_bg" else "name_en"))
            recordRow(label, record, group)
        }
    }
    val field = all.filter { it.getString("source") == "measurement" }
    if (field.isNotEmpty()) {
        section(R.string.ft_records).setPadding(0, dp(22), 0, dp(4))
        val group = settingsGroup()
        field.forEachIndexed { index, record ->
            if (index > 0) divider(group, inset = false)
            val label = when (record.getString("measure")) {
                "balance_stance" -> getString(R.string.ft_record_balance, getString(if (record.getString("leg") == "L") R.string.ft_left else R.string.ft_right))
                "sprint_time" -> getString(R.string.ft_sprint_named, String.format(locale, "%.0f", record.getDouble("distance_m"))) +
                    " · " + getString(if (record.optString("start") == "crossing") R.string.ft_start_crossing else R.string.ft_start_cue)
                else -> getString(if (record.getString("test") == "t_test") R.string.ft_t_test else R.string.ft_505)
            }
            recordRow(label, record, group)
        }
    }
    val strength = all.filter { it.getString("source") == "set_log" }.groupBy { it.getLong("exercise_id") }
    if (strength.isNotEmpty()) {
        section(R.string.records_strength).setPadding(0, dp(22), 0, dp(4))
        val group = settingsGroup()
        strength.values.forEachIndexed { index, rows ->
            if (index > 0) divider(group, inset = false)
            val heaviest = rows.first { it.getString("measure") == "heaviest_set" }
            val panel = recordRow(heaviest.getString("exercise"), heaviest, group)
            rows.firstOrNull { it.getString("measure") == "estimated_1rm" }?.let { estimate ->
                val line = row(panel, center = true)
                text(getString(R.string.records_estimate, recordValue(estimate)), 14, getColor(R.color.ink), line)
                    .layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                newChip(estimate, line)
            }
        }
    }
    howMeasured(*listOfNotNull(getString(R.string.records_estimate_note).takeIf { strength.isNotEmpty() },
        getString(R.string.records_excluded, data.getInt("excluded_jumps"))).toTypedArray())
    button(getString(R.string.leaderboard_title), look = ActionTone.TONAL) { leaderboard() }
}

internal fun MainActivity.leaderboard(measure: String = "jump_height", period: String = "all", message: String? = null) {
    val api = featureApi()
    work(getString(R.string.feature_loading), { api.obj("/api/clubs/me/leaderboard?measure=$measure&period=$period") },
        { leaderboardScreen(it, message) }, { home(it) })
}

private fun MainActivity.segments(choices: List<Pair<String, Int>>, current: String, pick: (String) -> Unit) {
    val group = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        background = shape(getColor(R.color.hover), 10)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    for ((value, label) in choices) group.addView(RadioButton(this).apply {
        id = View.generateViewId()
        text = getString(label)
        textSize = 14f
        isChecked = value == current
        segment()
        setOnClickListener { if (value != current) pick(value) }
    }, RadioGroup.LayoutParams(0, -2, 1f))
    page.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
}

private fun MainActivity.leaderboardScreen(data: JSONObject, message: String?) {
    val measure = data.getString("measure")
    val period = data.getString("period")
    val kind = JSONObject().put("measure", measure)
    layout(getString(R.string.leaderboard_title), "leaderboard")
    if (message != null) notice(message, caution = true)
    segments(listOf("jump_height" to R.string.records_height, "flight_time" to R.string.records_flight), measure) { leaderboard(it, period) }
    segments(listOf("30" to R.string.leaderboard_period_30, "90" to R.string.leaderboard_period_90,
        "all" to R.string.leaderboard_period_all), period) { leaderboard(measure, it) }
    val rows = data.getJSONArray("rows").objects()
    val me = data.optJSONObject("me")
    // The athlete's own row is pinned above the list, drawn on the jump ruler.
    val own = rows.firstOrNull { it.getBoolean("you") }
    if (own != null) {
        text(getString(R.string.leaderboard_your_place), 13).setPadding(0, dp(18), 0, 0)
        val head = row(center = true)
        text(getString(R.string.leaderboard_rank, own.getInt("rank")), 40, green, head).apply {
            instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) }
        }
        text(own.getString("name") + "\n" + recordValue(kind, own), 18, parent = head).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val value = own.getDouble("value")
        page.addView(HeightRule(this, recordJump(if (measure == "flight_time") heightFromFlight(value) else value)),
            LinearLayout.LayoutParams(-1, dp(138)))
    } else if (me != null) notice(getString(R.string.leaderboard_not_ranked))
    if (me != null) {
        val panel = card()
        panel.addView(Switch(this).apply {
            text = getString(R.string.leaderboard_opt_in)
            setTextColor(getColor(R.color.ink))
            textSize = 16f
            instrumentSwitch()
            switchMinWidth = dp(42)
            switchPadding = dp(10)
            minHeight = dp(48)
            isChecked = me.getBoolean("leaderboard_opt_in")
            setOnCheckedChangeListener { _, checked ->
                val api = featureApi()
                work(getString(R.string.feature_loading), { api.send("PATCH", "/api/me/profile", JSONObject().put("leaderboard_opt_in", checked)) },
                    { leaderboard(measure, period) }, { leaderboard(measure, period, it) })
            }
        }, LinearLayout.LayoutParams(-1, -2))
        text(getString(R.string.leaderboard_opt_in_note), 13, parent = panel)
        if (me.getBoolean("under_18") && me.getBoolean("leaderboard_opt_in") && !me.getBoolean("leaderboard_allowed"))
            text(getString(R.string.leaderboard_await_coach), 14, getColor(R.color.warning), panel)
    }
    if (rows.isEmpty()) notice(getString(R.string.leaderboard_empty))
    else {
        val group = settingsGroup()
        rows.forEachIndexed { index, entry ->
            if (index > 0) divider(group, inset = false)
            val line = row(group, center = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (entry.getBoolean("you")) background = shape(getColor(R.color.tonal), 0)
            }
            text(getString(R.string.leaderboard_rank, entry.getInt("rank")), 18, if (entry.getInt("rank") <= 3) green else getColor(R.color.muted), line).apply {
                instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(dp(44), -2)
            }
            val name = entry.getString("name") + if (entry.getBoolean("you")) " · " + getString(R.string.leaderboard_you) else ""
            text(name, 16, parent = line).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            text(recordValue(kind, entry), 18, getColor(R.color.ink), line).apply {
                instrumentType(numeric = true); gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
            }
        }
    }
}

/** " · front · 30 fps" for jump records, which the server keeps apart per camera view and frame-rate class. */
internal fun MainActivity.recordQualifier(record: JSONObject): String {
    val view = record.optString("view").takeIf { it == "front" || it == "side" } ?: return ""
    val fps = record.optInt("fps_class", 0)
    return " · " + getString(if (view == "side") R.string.records_view_side else R.string.records_view_front) +
        (if (fps > 0) " · $fps fps" else "")
}
