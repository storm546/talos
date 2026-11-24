package jumpscan.app

import android.view.View
import android.widget.LinearLayout

/** One tool (SPEC 10.9): it lives in its section's screen, and `talos://tool/<key>` opens it. [local] tools work without an account. */
internal class Tool(val key: String, val label: Int, val note: Int, val icon: String, val local: Boolean = false, val open: MainActivity.() -> Unit)

/** The app's three parts and the personal and club extras. Keys are stable: launcher shortcuts and links use them. */
internal object Tools {
    val cameraTests = listOf(
        Tool("jump", R.string.tool_jump, R.string.tool_jump_note, "legs", local = true) { jumpHome() },
        Tool("readiness", R.string.rd_title, R.string.rd_check, "legs") { readinessCheck() },
        Tool("rom", R.string.ft_rom, R.string.tool_rom_note, "hinge", local = true) { romSetup() },
        Tool("balance", R.string.ft_balance, R.string.tool_balance_note, "core", local = true) { balanceSetup() },
        Tool("speed", R.string.ft_speed, R.string.tool_speed_note, "conditioning", local = true) { speedSetup() })
    val formHistory = listOf(
        Tool("form_history", R.string.tool_form_history, R.string.tool_form_history_note, "history") { techniqueHistory() },
        Tool("jump_history", R.string.tool_jump_history, R.string.tool_jump_history_note, "history") { history() },
        Tool("diagnostics", R.string.diagnostics, R.string.tool_diagnostics_note, "diagnostics", local = true) { diagnostics() })
    val food = listOf(
        Tool("cook", R.string.cook_title, R.string.tool_cook_note, "recipe") { cook() },
        Tool("recipes", R.string.recipes_title, R.string.tool_recipes_note, "recipe") { recipes() },
        Tool("weight", R.string.tool_weight, R.string.tool_weight_note, "insights") { insights(days = 30) })
    val training = listOf(
        Tool("plans", R.string.tool_plans, R.string.tool_plans_note, "index") { trainingHome() },
        Tool("cardio", R.string.ft_cardio, R.string.tool_cardio_note, "route", local = true) { cardioHome() },
        Tool("timer", R.string.timer_title, R.string.tool_timer_note, "timer", local = true) { intervalTimer() },
        Tool("calculators", R.string.calc_title, R.string.tool_calc_note, "plates", local = true) { calculators() },
        Tool("records", R.string.records_title, R.string.tool_records_note, "insights") { records() },
        Tool("load", R.string.rd_sessions_title, R.string.rd_load_chart, "training") { sessionsScreen() })
    val you = listOf(
        Tool("checkin", R.string.checkin_title, R.string.tool_checkin_note, "checkin") { checkin() },
        Tool("body", R.string.body_title, R.string.tool_body_note, "insights") { body() },
        Tool("assistant", R.string.assistant_title, R.string.tool_assistant_note, "assistant") { assistantScreen() })
    val club = listOf(
        Tool("sessions", R.string.tool_sessions, R.string.tool_sessions_note, "club") { clubHome() },
        Tool("feedback", R.string.feedback_title, R.string.tool_feedback_note, "assistant") { feedback() },
        Tool("leaderboard", R.string.leaderboard_title, R.string.tool_leaderboard_note, "index") { leaderboard() })
    private val extra = listOf(
        Tool("photo", R.string.food_photo_title, R.string.tool_photo_note, "camera") { foodPhoto() },
        Tool("scan_food", R.string.tool_scan_food, R.string.tool_scan_food_note, "scan") { scanBarcode() },
        Tool("form", R.string.ui_technique, R.string.tool_form_note, "form", local = true) { formHome() },
        Tool("workout", R.string.workout_title, R.string.tool_workout_note, "training") { workoutStart() },
        Tool("nutrition", R.string.nutrition_title, R.string.tool_nutrition_note, "food") { nutritionHome() })
    val all get() = cameraTests + formHistory + food + training + you + club + extra
    operator fun get(key: String) = all.firstOrNull { it.key == key }
}

internal fun MainActivity.openTool(tool: Tool) {
    if (!tool.local && token().isEmpty()) login() else tool.open(this)
}

internal fun MainActivity.openTool(key: String) = Tools[key]?.let { openTool(it) } ?: home()

/** "Ask the assistant" with a prepared question about what is on screen (SPEC 10.9). */
internal fun MainActivity.askButton(draft: String) =
    button(getString(R.string.ask_assistant), look = ActionTone.TONAL) { assistantScreen(draft = draft) }.apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(lineIcon("assistant", green), null, null, null)
        compoundDrawablePadding = dp(8)
    }

/** A section's tools as one list of rows under an eyebrow. */
internal fun MainActivity.toolList(title: Int, tools: List<Tool>, parent: LinearLayout = page) {
    eyebrow(getString(title), parent)
    val list = settingsGroup(parent).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) }
    tools.forEachIndexed { index, tool ->
        if (index > 0) divider(list)
        settingRow(tool.icon, getString(tool.label), getString(tool.note), list) { openTool(tool) }
    }
}

/** A small letter-spaced label over a group, in the accent colour. */
internal fun MainActivity.eyebrow(value: String, parent: LinearLayout = page) = text(value.uppercase(locale), 12, green, parent).apply {
    instrumentType(display = true)
    letterSpacing = .12f
    setPadding(0, dp(20), 0, dp(2))
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}
