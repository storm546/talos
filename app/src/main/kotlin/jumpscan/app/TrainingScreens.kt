package jumpscan.app

import android.text.InputType
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle

internal fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

internal fun planDays(plan: JSONObject, week: LocalDate): List<Pair<LocalDate, JSONObject>> {
    val start = LocalDate.parse(plan.getString("starts_on"))
    val end = start.plusWeeks(plan.getLong("weeks"))
    return plan.getJSONArray("items").objects().mapNotNull { item ->
        val day = week.plusDays(item.getLong("weekday"))
        if (day < start || day >= end) null else day to item
    }.sortedBy { it.first }
}

internal fun MainActivity.trainingHome(week: LocalDate = weekStart(LocalDate.now()), message: String? = null) {
    val api = featureApi()
    work(getString(R.string.feature_loading), {
        val plans = api.array("/api/plans")
        val logs = mutableListOf<JSONObject>()
        do {
            val batch = api.array("/api/set-logs?from=$week&to=${week.plusDays(6)}&limit=500&offset=${logs.size}")
            logs.addAll(batch)
        } while (batch.size == 500)
        plans to logs
    }, { (plans, logs) -> trainingWeek(week, plans, logs, message) }, { trainingWeek(week, null, emptyList(), it) })
}

private fun MainActivity.trainingWeek(week: LocalDate, plans: List<JSONObject>?, logs: List<JSONObject>, message: String?) {
    layout(getString(R.string.training_title), "training")
    if (message != null) notice(message, caution = plans == null)
    text(getString(R.string.training_week, localDate(week.toString(), locale), localDate(week.plusDays(6).toString(), locale)),
        14).instrumentType(numeric = true)
    val scheduled = plans.orEmpty().flatMap { plan -> planDays(plan, week).map { (day, item) -> Triple(day, plan, item) } }
    val today = LocalDate.now()
    if (plans != null && week == weekStart(today)) {
        if (scheduled.none { it.first == today }) notice(getString(R.string.ui_rest_day))
    }
    if (plans != null && scheduled.isEmpty()) notice(getString(R.string.training_no_plan))
    if (plans == null) button(getString(R.string.training_load_plan)) { trainingHome(week) }
    for ((day, entries) in scheduled.sortedWith(compareBy({ it.first != today }, { it.first })).groupBy { it.first }) {
        text(if (day == today) getString(R.string.ui_today_plan) else day.dayOfWeek.getDisplayName(TextStyle.FULL, locale), 24)
        for ((index, entry) in entries.withIndex()) {
            val (_, plan, item) = entry
            val panel = timelineEntry(index, index == entries.lastIndex)
            text(plan.getString("title"), 13, parent = panel)
            if (index == 0 || entries[index - 1].second != plan) plan.optString("notes").takeIf { it.isNotBlank() }?.let { text(it, 14, parent = panel) }
            text(item.getString("exercise"), 20, parent = panel)
            val reps = item.getInt("reps")
            val max = item.optInt("reps_max", reps)
            val repRange = if (max == reps) "$reps" else "$reps–$max"
            val load = if (item.isNull("load_kg")) null else getString(R.string.training_load_value, getString(R.string.feature_kg, number(item, "load_kg")))
            val effort = if (item.isNull("rpe_target")) null else getString(R.string.training_effort, number(item, "rpe_target"))
            text(listOfNotNull(getString(R.string.training_prescribed, item.getInt("sets"), repRange), load, effort).joinToString(" · "), 14, parent = panel)
            val sets = item.getInt("sets")
            val done = logs.filter { it.optLong("plan_id") == plan.getLong("id") && it.getLong("exercise_id") == item.getLong("exercise_id") && it.getString("day") == day.toString() }.map { it.getInt("set_no") }.toSet()
            val completed = (1..sets).count { it in done }
            targetBar(getString(R.string.session_logged), "$completed / $sets", completed.toDouble() / sets, panel)
            button(getString(R.string.training_log_exercise, item.getString("exercise")), parent = panel, look = ActionTone.TONAL) {
                val next = (1..50).firstOrNull { it !in done } ?: 50
                setForm(week, day.toString(), item.getLong("exercise_id"), item.getString("exercise"), plan.getLong("id"), listOf(next.toString(), "", "", ""))
            }
        }
    }
    button(getString(R.string.training_choose_exercise), primary = true) { exercises(week) }
    button(getString(R.string.training_other_week)) { dayPickerWeek(week) }
    pendingControls()
}

private fun MainActivity.dayPickerWeek(week: LocalDate) {
    android.app.DatePickerDialog(this, { _, year, month, day ->
        val selected = weekStart(LocalDate.of(year, month + 1, day))
        trainingHome(selected)
    }, week.year, week.monthValue - 1, week.dayOfMonth).show()
}

private fun MainActivity.exercises(week: LocalDate) {
    val api = featureApi()
    work(getString(R.string.feature_loading), { api.array("/api/exercises") }, { rows ->
        layout(getString(R.string.training_choose_exercise), "training_exercises")
        button(getString(R.string.training_title)) { trainingHome(week) }
        if (rows.isEmpty()) text(getString(R.string.training_no_exercises))
        for (item in rows) button(item.getString("name")) {
            setForm(week, todayLocal(), item.getLong("id"), item.getString("name"), null)
        }
    }, { trainingHome(week, it) })
}

private fun MainActivity.setForm(week: LocalDate, day: String, exercise: Long,
    name: String, plan: Long?, draft: List<String> = listOf("1", "", "", ""), message: String? = null) {
    layout(getString(R.string.training_log_set), "training_set")
    featureError(message)
    text(name, 18, green)
    val ordinal = field(getString(R.string.training_set_no), draft[0], InputType.TYPE_CLASS_NUMBER)
    val reps = field(getString(R.string.training_reps), draft[1], InputType.TYPE_CLASS_NUMBER)
    val load = decimalField(R.string.training_load, draft[2])
    val rpe = decimalField(R.string.training_rpe, draft[3])
    fun values() = listOf(ordinal.value(), reps.value(), load.value(), rpe.value())
    text(getString(R.string.feature_day), 14)
    dayPicker(day) { setForm(week, it, exercise, name, plan, values()) }
    button(getString(R.string.training_save), primary = true) {
        val input = values()
        val payload = setBody(day, exercise, plan, input)
        if (payload == null) setForm(week, day, exercise, name, plan, input, getString(R.string.training_invalid))
        else saveFeature(FeatureWrite.SET, payload) { status, message ->
            if (status in listOf(R.string.feature_saved, R.string.saved_pending)) trainingHome(week, message)
            else setForm(week, day, exercise, name, plan, input, message)
        }
    }
    button(getString(R.string.training_title)) { trainingHome(week) }
}

internal fun setBody(day: String, exercise: Long, plan: Long?, input: List<String>): JSONObject? {
    val ordinal = input[0].toIntOrNull()
    val reps = input[1].toIntOrNull()
    val load = decimal(input[2])
    val rpe = decimal(input[3])
    if (ordinal !in 1..50 || reps !in 1..200 ||
        (input[2].isNotEmpty() && (load == null || load !in 0.0..1000.0)) ||
        (input[3].isNotEmpty() && (rpe == null || rpe !in 0.0..10.0))) return null
    return JSONObject().put("day", day).put("exercise_id", exercise).put("plan_id", plan ?: JSONObject.NULL)
        .put("set_no", ordinal).put("reps", reps).put("load_kg", load ?: JSONObject.NULL).put("rpe", rpe ?: JSONObject.NULL)
}
