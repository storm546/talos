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
    work(getString(R.string.feature_loading), { api.array("/api/plans") }, { plans ->
        trainingWeek(week, plans, message)
    }, { trainingWeek(week, null, it) })
}

private fun MainActivity.trainingWeek(week: LocalDate, plans: List<JSONObject>?, message: String? = null) {
    layout(getString(R.string.training_title), "training")
    if (message != null) notice(message, caution = plans == null)
    val overview = card(hero = true)
    text(getString(R.string.training_week, localDate(week.toString(), locale), localDate(week.plusDays(6).toString(), locale)),
        16, getColor(R.color.on_hero), overview)
    button(getString(R.string.training_choose_exercise), primary = true, parent = overview) { exercises(week, plans) }
    button(getString(R.string.training_other_week), parent = overview) { dayPickerWeek(week, plans) }
    val scheduled = plans.orEmpty().map { it to planDays(it, week) }.filter { it.second.isNotEmpty() }
    val today = LocalDate.now()
    if (plans != null && week == weekStart(today)) {
        val items = scheduled.flatMap { it.second }.filter { it.first == today }
        if (items.isEmpty()) notice(getString(R.string.ui_rest_day))
        else section(R.string.ui_today_plan).setPadding(0, dp(14), 0, 0)
    }
    if (plans != null && scheduled.isEmpty()) notice(getString(R.string.training_no_plan))
    if (plans == null) button(getString(R.string.training_load_plan)) { trainingHome(week) }
    for ((plan, items) in scheduled) {
        val panel = card()
        text(plan.getString("title"), 21, green, panel)
        plan.optString("notes").takeIf { it.isNotBlank() }?.let { text(it, 14, parent = panel) }
        for ((day, item) in items.sortedBy { it.first != today }) {
            val dayLabel = if (day == today) getString(R.string.ui_today) else day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            text(dayLabel + " · " + item.getString("exercise"), 17, parent = panel).setPadding(0, dp(14), 0, 0)
            val reps = item.getInt("reps")
            val max = item.optInt("reps_max", reps)
            val repRange = if (max == reps) "$reps" else "$reps–$max"
            val load = number(item, "load_kg").let { if (item.isNull("load_kg")) it else getString(R.string.feature_kg, it) }
            text(getString(R.string.training_prescribed, item.getInt("sets"), repRange, load, number(item, "rpe_target")), 14, parent = panel)
            button(getString(R.string.training_log_exercise, item.getString("exercise")), parent = panel) {
                setForm(week, plans, day.toString(), item.getLong("exercise_id"), item.getString("exercise"), plan.getLong("id"))
            }
        }
    }
    pendingControls()
}

private fun MainActivity.dayPickerWeek(week: LocalDate, plans: List<JSONObject>?) {
    android.app.DatePickerDialog(this, { _, year, month, day ->
        val selected = weekStart(LocalDate.of(year, month + 1, day))
        if (plans == null) trainingHome(selected) else trainingWeek(selected, plans)
    }, week.year, week.monthValue - 1, week.dayOfMonth).show()
}

private fun MainActivity.exercises(week: LocalDate, plans: List<JSONObject>?) {
    val api = featureApi()
    work(getString(R.string.feature_loading), { api.array("/api/exercises") }, { rows ->
        layout(getString(R.string.training_choose_exercise), "training_exercises")
        button(getString(R.string.training_title)) { trainingWeek(week, plans) }
        if (rows.isEmpty()) text(getString(R.string.training_no_exercises))
        for (item in rows) button(item.getString("name")) {
            setForm(week, plans, todayLocal(), item.getLong("id"), item.getString("name"), null)
        }
    }, { trainingWeek(week, plans, it) })
}

private fun MainActivity.setForm(week: LocalDate, plans: List<JSONObject>?, day: String, exercise: Long,
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
    dayPicker(day) { setForm(week, plans, it, exercise, name, plan, values()) }
    button(getString(R.string.training_save), primary = true) {
        val input = values()
        val payload = setBody(day, exercise, plan, input)
        if (payload == null) setForm(week, plans, day, exercise, name, plan, input, getString(R.string.training_invalid))
        else saveFeature(FeatureWrite.SET, payload) { status, message ->
            if (status in listOf(R.string.feature_saved, R.string.saved_pending)) trainingWeek(week, plans, message)
            else setForm(week, plans, day, exercise, name, plan, input, message)
        }
    }
    button(getString(R.string.training_title)) { trainingWeek(week, plans) }
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
