package jumpscan.app

import android.view.View
import android.widget.LinearLayout

/** One tool of the hub (SPEC 9). [local] tools work without an account. */
internal class Tool(val key: String, val label: Int, val note: Int, val icon: String, val local: Boolean = false, val open: MainActivity.() -> Unit)

internal fun toolGroups(): List<Pair<Int, List<Tool>>> = listOf(
    R.string.tools_measure to listOf(
        Tool("jump", R.string.tool_jump, R.string.tool_jump_note, "legs", local = true) { home() },
        Tool("jump_history", R.string.tool_jump_history, R.string.tool_jump_history_note, "history") { history() },
        Tool("form", R.string.ui_technique, R.string.tool_form_note, "form", local = true) { formHome() },
        Tool("form_history", R.string.tool_form_history, R.string.tool_form_history_note, "history") { techniqueHistory() },
        Tool("timer", R.string.timer_title, R.string.tool_timer_note, "timer", local = true) { intervalTimer() },
        Tool("diagnostics", R.string.diagnostics, R.string.tool_diagnostics_note, "diagnostics", local = true) { diagnostics() }),
    R.string.tools_train to listOf(
        Tool("workout", R.string.workout_title, R.string.tool_workout_note, "training") { workoutStart() },
        Tool("plans", R.string.tool_plans, R.string.tool_plans_note, "index") { trainingHome() },
        Tool("calculators", R.string.calc_title, R.string.tool_calc_note, "plates", local = true) { calculators() },
        Tool("records", R.string.records_title, R.string.tool_records_note, "insights") { records() }),
    R.string.tools_fuel to listOf(
        Tool("nutrition", R.string.nutrition_title, R.string.tool_nutrition_note, "food") { nutritionHome() },
        Tool("recipes", R.string.recipes_title, R.string.tool_recipes_note, "recipe") { recipes() }),
    R.string.tools_body to listOf(
        Tool("weight", R.string.tool_weight, R.string.tool_weight_note, "insights") { insights(days = 30) },
        Tool("checkin", R.string.checkin_title, R.string.tool_checkin_note, "checkin") { checkin() }),
    R.string.tools_club to listOf(
        Tool("sessions", R.string.tool_sessions, R.string.tool_sessions_note, "club") { clubHome() },
        Tool("feedback", R.string.feedback_title, R.string.tool_feedback_note, "assistant") { feedback() },
        Tool("leaderboard", R.string.leaderboard_title, R.string.tool_leaderboard_note, "index") { leaderboard() }),
    R.string.tools_ask to listOf(
        Tool("assistant", R.string.assistant_title, R.string.tool_assistant_note, "assistant") { assistantScreen() }),
)

private fun MainActivity.toolPrefs() = getSharedPreferences("tools", android.content.Context.MODE_PRIVATE)
private fun MainActivity.keys(name: String) = toolPrefs().getString(name, "").orEmpty().split(',').filter { it.isNotEmpty() }

internal fun MainActivity.openTool(tool: Tool) {
    toolPrefs().edit().putString("recent", (listOf(tool.key) + keys("recent")).distinct().take(4).joinToString(",")).apply()
    if (!tool.local && token().isEmpty()) login() else tool.open(this)
}

/** Home of every tool: pinned or recent keys on top, then the groups as instrument lists. Hold a row to pin it. */
internal fun MainActivity.toolsHub() {
    layout(getString(R.string.tools_title), "tools")
    val all = toolGroups().flatMap { it.second }.associateBy { it.key }
    val pinned = keys("pinned").mapNotNull { all[it] }
    val top = pinned.ifEmpty { keys("recent").mapNotNull { all[it] } }.take(4)
    if (top.isNotEmpty()) {
        eyebrow(getString(if (pinned.isEmpty()) R.string.tools_recent else R.string.tools_pinned))
        toolRow(*top.map { tool -> UiTile(tool.label, tool.icon) { openTool(tool) } }.toTypedArray())
        (page.getChildAt(page.childCount - 1).layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
    }
    for ((group, tools) in toolGroups()) {
        eyebrow(getString(group))
        val list = settingsGroup().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) }
        tools.forEachIndexed { index, tool ->
            if (index > 0) divider(list)
            settingRow(tool.icon, getString(tool.label), getString(tool.note), list) { openTool(tool) }
                .setOnLongClickListener { togglePin(tool); true }
        }
    }
    text(getString(R.string.tools_pin_hint), 13).setPadding(0, dp(18), 0, 0)
}

private fun MainActivity.togglePin(tool: Tool) {
    val pins = keys("pinned")
    val next = if (tool.key in pins) pins - tool.key else (pins + tool.key).takeLast(4)
    toolPrefs().edit().putString("pinned", next.joinToString(",")).apply()
    toolsHub()
    snack(getString(if (tool.key in next) R.string.tools_pinned_now else R.string.tools_unpinned_now, getString(tool.label)))
}

/** A small letter-spaced label over a group, in the accent colour. */
internal fun MainActivity.eyebrow(value: String, parent: LinearLayout = page) = text(value.uppercase(locale), 12, green, parent).apply {
    instrumentType(display = true)
    letterSpacing = .12f
    setPadding(0, dp(20), 0, dp(2))
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}
