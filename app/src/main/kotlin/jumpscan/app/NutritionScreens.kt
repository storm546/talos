package jumpscan.app

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.abs

internal val nutrients = linkedMapOf("kcal" to R.string.nutr_energy, "protein_g" to R.string.nutr_protein,
    "carbs_g" to R.string.nutr_carbs, "fat_g" to R.string.nutr_fat, "fibre_g" to R.string.nutr_fibre)
private val meals = linkedMapOf("breakfast" to R.string.meal_breakfast, "lunch" to R.string.meal_lunch,
    "dinner" to R.string.meal_dinner, "snack" to R.string.meal_snack)

/** The diary day and meal the add-food flow returns to. */
internal var diaryDay = todayLocal()
internal var diaryMeal = "breakfast"

internal fun MainActivity.mealName(meal: String) = getString(meals.getValue(meal))
internal fun MainActivity.goalName(goal: String) = getString(when (goal) {
    "lose" -> R.string.goal_lose
    "gain" -> R.string.goal_gain
    else -> R.string.goal_maintain
})

internal fun MainActivity.kcalText(value: Double) = "${metricNumber(value, "kcal", locale)} ${getString(R.string.unit_kcal)}"
internal fun MainActivity.gramText(value: Double) = "${short(value)} ${getString(R.string.unit_grams)}"
/** One decimal, dropped when it is zero. */
internal fun MainActivity.short(value: Double) = metricNumber(value, "grams", locale).let {
    if (it.length > 2 && it[it.length - 2] in ".," && it.last() == '0') it.dropLast(2) else it
}

internal fun MainActivity.foodName(food: JSONObject): String = listOf(if (locale.language == "bg") "name_bg" else "name_en",
    "name_bg", "name_en", "name", "label").firstNotNullOfOrNull { key -> food.optString(key).takeIf { !food.isNull(key) && it.isNotBlank() } }
    ?: getString(R.string.nutr_quick)

/** Servings a food offers, its own first, always with 100 g and plain grams. */
internal fun servingsOf(food: JSONObject): List<Pair<String, Double>> {
    val listed = food.optJSONArray("servings")?.objects().orEmpty().mapNotNull { serving ->
        val grams = serving.optDouble("grams")
        val label = serving.optString("label")
        if (grams.isFinite() && grams > 0 && label.isNotBlank()) label to grams else null
    }
    return (listed + ("100 g" to 100.0) + ("g" to 1.0)).distinctBy { it.first }
}

/** Recipe portions come as a count and unit (SPEC 7.4); the label is built here in the app's language. Other labels show as typed. */
internal fun MainActivity.servingName(label: String, food: JSONObject? = null): String {
    val serving = food?.optJSONArray("servings")?.objects()?.firstOrNull { it.optString("label") == label }
    val unit = serving?.optString("unit")?.takeIf { it.isNotBlank() } ?: label.takeIf { it == "serving" || it == "recipe" } ?: return label
    val count = serving?.optDouble("count", 1.0)?.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val name = getString(if (unit == "recipe") R.string.recipe_unit_recipe else R.string.recipe_serving)
    return if (count == 1.0) name else "${short(count)} $name"
}

/** The reference a log carries: a stored food (recipes are backed by one) or a catalogue food, stored by the server on first use. */
private fun foodReference(food: JSONObject, body: JSONObject): JSONObject = when {
    food.has("id") && !food.isNull("id") -> body.put("food_id", food.getLong("id"))
    else -> body.put("catalog_id", food.get("catalog_id"))
}

internal fun MainActivity.nutritionBack() = nutritionHome(diaryDay)

internal fun MainActivity.nutritionHome(day: String = diaryDay, message: String? = null) {
    diaryDay = day
    val api = featureApi()
    work(getString(R.string.feature_loading), {
        val summary = api.obj("/api/athletes/${athleteId()}/nutrition/summary?day=$day")
        val foods = mutableListOf<JSONObject>()
        do {
            val batch = api.array("/api/food-logs?from=$day&to=$day&limit=100&offset=${foods.size}")
            foods.addAll(batch)
        } while (batch.size == 100)
        summary to foods
    }, { (summary, foods) -> nutritionDay(day, message, summary, foods) }, {
        nutritionDay(day, listOfNotNull(message, it).joinToString("\n"), null, emptyList())
    })
}

private fun MainActivity.dayStepper(day: String, picked: (String) -> Unit) {
    val line = row(top = 2, center = true)
    val date = LocalDate.parse(day)
    fun step(label: String, description: Int, days: Long) = button(label, parent = line, look = ActionTone.TEXT) {
        picked(date.plusDays(days).toString())
    }.apply { contentDescription = getString(description); textSize = 20f; layoutParams = LinearLayout.LayoutParams(dp(48), dp(44)) }
    step("‹", R.string.nutr_previous_day, -1)
    // The date is a quiet tappable line (DESIGN.md), not a big button.
    text(localDate(day, locale), 15, getColor(R.color.ink), line).apply {
        gravity = Gravity.CENTER; instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        isClickable = true; isFocusable = true
        setOnClickListener {
            DatePickerDialog(this@dayStepper, { _, year, month, dayOfMonth -> picked(LocalDate.of(year, month + 1, dayOfMonth).toString()) },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
    }
    step("›", R.string.nutr_next_day, 1)
}

internal fun MainActivity.iconButton(icon: String, description: String, parent: LinearLayout, action: () -> Unit) = ImageView(this).apply {
    setImageDrawable(lineIcon(icon, green))
    scaleType = ImageView.ScaleType.CENTER
    contentDescription = description
    isClickable = true; isFocusable = true
    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 20))
    setOnClickListener { action() }
    parent.addView(this, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
}

private fun MainActivity.nutritionDay(day: String, message: String?, summary: JSONObject?, foods: List<JSONObject>) {
    layout(getString(R.string.nutrition_title), "nutrition")
    if (message != null) notice(message, caution = summary == null)
    dayStepper(day) { nutritionHome(it) }
    val totals = summary?.optJSONObject("totals") ?: JSONObject()
    val target = summary?.optJSONObject("target") ?: JSONObject()
    val goal = target.optDouble("kcal")
    val eaten = totals.optDouble("kcal", 0.0)
    // The day at a glance (DESIGN.md): four rings against the targets, one line of what is left, then the actions.
    val rings = LinearLayout(this).apply { isBaselineAligned = false; setPadding(0, dp(10), 0, 0) }
    page.addView(rings, LinearLayout.LayoutParams(-1, -2))
    for ((key, label) in listOf("kcal" to R.string.unit_kcal, "protein_g" to R.string.macro_protein, "carbs_g" to R.string.macro_carbs,
            "fat_g" to R.string.macro_fat)) {
        val value = totals.optDouble(key, 0.0); val aim = target.optDouble(key).takeIf { it > 0 }
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        cell.addView(DayRing(this, aim?.let { value / it }, aim != null && key == "kcal" && value > aim * 1.1,
            metricNumber(value, "kcal", locale), value), LinearLayout.LayoutParams(dp(68), dp(68)))
        text(if (aim != null) getString(R.string.food_ring_of, getString(label).lowercase(locale), metricNumber(aim, "kcal", locale))
            else getString(label), 11, parent = cell).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(4), 0, 0) }
        rings.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
    }
    if (goal > 0) text(getString(if (goal - eaten >= 0) R.string.food_left else R.string.food_over, kcalText(abs(goal - eaten))), 13,
        getColor(if (goal - eaten >= 0) R.color.muted else R.color.warning)).apply { gravity = Gravity.CENTER; instrumentType(numeric = true); setPadding(0, dp(8), 0, 0) }
    else text(getString(R.string.nutr_no_targets), 13).gravity = Gravity.CENTER
    actionChips(UiTile(R.string.food_photo_short, "camera") { foodPhoto(day) }, UiTile(R.string.nutr_add, "plus") { foods(day) },
        UiTile(R.string.scan_short, "scan") { scanBarcode() }, UiTile(R.string.nutr_copy_day, "copy") { copyLogs(day, null) })
    section(R.string.meal_timeline).setPadding(0, dp(22), 0, dp(8))
    val byMeal = summary?.optJSONObject("by_meal_kcal") ?: JSONObject()
    meals.entries.forEachIndexed { index, (meal, label) ->
        val entry = timelineEntry(index, index == meals.size - 1)
        val head = row(entry, center = true)
        text(getString(label), 17, getColor(R.color.ink), head).apply { setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        text(if (byMeal.has(meal)) kcalText(byMeal.optDouble(meal)) else "—", 14, parent = head).apply {
            setPadding(0, 0, 0, 0); instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        iconButton("plus", getString(R.string.nutr_add_to, getString(label)), head) { diaryMeal = meal; foods(day) }
        val items = foods.filter { it.optString("meal") == meal }
        if (items.isEmpty() && summary != null) button(getString(R.string.nutr_copy_meal), parent = entry, look = ActionTone.TEXT) {
            copyLogs(day, meal)
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0); textSize = 13f;
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0 }
        for (log in items) logRow(day, log, entry)
    }
    if (foods.isNotEmpty()) text(getString(R.string.nutr_swipe_hint), 12).gravity = Gravity.CENTER
    if (summary != null) {
        text(getString(R.string.nutr_complete_note), 14).setPadding(0, dp(14), 0, 0)
        button(getString(R.string.nutr_mark_complete)) {
            saveFeature(FeatureWrite.COMPLETE, JSONObject(), day) { _, result -> nutritionHome(day, result) }
        }
    } else button(getString(R.string.nutr_summary)) { nutritionHome(day) }
    askButton(getString(R.string.ask_food_draft, localDate(day, locale)))
    toolList(R.string.section_food_more, Tools.food)
    pendingControls()
}

private fun MainActivity.servingText(log: JSONObject): String {
    if (log.isNull("grams") || !log.has("grams")) return getString(R.string.nutr_quick)
    val grams = gramText(log.getDouble("grams"))
    val label = log.optString("serving_label").takeIf { !log.isNull("serving_label") && it.isNotBlank() && it != "100 g" && it != "g" }
    val quantity = log.optDouble("quantity")
    return if (label != null && quantity.isFinite()) "${short(quantity)} × ${servingName(label)} · $grams" else grams
}

private fun MainActivity.logRow(day: String, log: JSONObject, parent: LinearLayout) {
    val line = row(parent, center = true).apply {
        setPadding(0, dp(8), 0, dp(8))
        minimumHeight = dp(52)
        isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 8))
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(foodName(log), 15, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
    text(servingText(log), 13, parent = column).setPadding(0, dp(2), 0, 0)
    text(number(log, "kcal"), 15, getColor(R.color.ink), line).apply {
        setPadding(0, 0, 0, 0); instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
    }
    line.contentDescription = "${foodName(log)}, ${servingText(log)}, ${kcalText(log.optDouble("kcal", 0.0))}"
    line.setOnClickListener { editLog(day, log) }
    swipeAway(line) { deleteLog(day, log) }
}

/** Drag a row sideways past a third of its width to remove it; a short drag springs back. */
@SuppressLint("ClickableViewAccessibility")
private fun MainActivity.swipeAway(view: View, removed: () -> Unit) {
    val slop = ViewConfiguration.get(this).scaledTouchSlop
    var start = 0f
    var dragging = false
    view.setOnTouchListener { row, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { start = event.rawX; dragging = false; false }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - start
                if (!dragging && abs(dx) > slop) {
                    dragging = true
                    row.parent.requestDisallowInterceptTouchEvent(true)
                    row.isPressed = false
                }
                if (dragging) { row.translationX = dx; row.alpha = 1f - minOf(.7f, abs(dx) / row.width) }
                dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) false else {
                    if (event.actionMasked == MotionEvent.ACTION_UP && abs(row.translationX) > row.width / 3f) {
                        row.animate().translationX(if (row.translationX > 0) row.width.toFloat() else -row.width.toFloat())
                            .alpha(0f).setDuration(160).withEndAction(removed).start()
                    } else row.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(settle).start()
                    dragging = false
                    true
                }
            }
            else -> false
        }
    }
}

private fun MainActivity.deleteLog(day: String, log: JSONObject) {
    val api = featureApi()
    work(getString(R.string.feature_saving), { api.send("DELETE", "/api/food-logs/${log.getLong("id")}") },
        { nutritionHome(day, getString(R.string.nutr_deleted)) }, { nutritionHome(day, it) })
}

private fun MainActivity.copyLogs(day: String, meal: String?) {
    val body = JSONObject().put("from_day", LocalDate.parse(day).minusDays(1).toString()).put("to_day", day)
    if (meal != null) body.put("from_meal", meal).put("to_meal", meal)
    saveFeature(FeatureWrite.COPY, body) { _, result -> nutritionHome(day, result) }
}

private fun MainActivity.editLog(day: String, log: JSONObject) {
    if (log.isNull("grams") || !log.has("grams")) return quickAdd(day, log)
    // The log carries the food's macros for its grams; derive per-100 g values for the picker.
    val grams = log.getDouble("grams")
    val food = JSONObject(log.toString())
    for (key in nutrients.keys) if (log.has(key) && !log.isNull(key)) food.put(key, log.getDouble(key) * 100 / grams)
    if (!food.has("servings") && log.has("serving_label") && !log.isNull("serving_label") && log.optDouble("quantity") > 0) {
        food.put("servings", org.json.JSONArray().put(JSONObject().put("label", log.getString("serving_label"))
            .put("grams", grams / log.getDouble("quantity"))))
    }
    foodLog(day, food, log)
}

/** A 2 x 2 meal choice; the long Bulgarian snack label wraps inside its half. */
/** The meal as one scrolling row of pills (DESIGN.md): one line instead of a 2×2 block of buttons. */
internal fun MainActivity.mealGrid(selected: String, picked: (String) -> Unit) {
    val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    page.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val line = LinearLayout(this)
    scroll.addView(line)
    val choices = mutableListOf<RadioButton>()
    meals.entries.forEachIndexed { index, (key, label) ->
        choices += RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(label)
            textSize = 14f
            segment()
            maxLines = 1
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), shape(getColor(R.color.accent), 22))
                addState(intArrayOf(), shape(getColor(R.color.hover), 22))
            }
            isChecked = key == selected
            contentDescription = getString(R.string.nutr_meal) + ": " + getString(label)
            setOnClickListener { choices.forEach { it.isChecked = it === this }; picked(key) }
            line.addView(this, LinearLayout.LayoutParams(-2, dp(44)).apply { if (index > 0) marginStart = dp(8) })
        }
    }
}

internal fun MainActivity.foods(day: String = diaryDay, query: String? = null, rows: List<JSONObject>? = null,
    offset: Int = 0, message: String? = null) {
    if (rows == null) return searchFoods(day, query.orEmpty(), 0)
    layout(getString(R.string.nutr_add), "food_search")
    featureError(message)
    text("${mealName(diaryMeal)} · ${localDate(day, locale)}", 14)
    val search = field(getString(R.string.nutr_search_label), query.orEmpty()).apply {
        filters = arrayOf(InputFilter.LengthFilter(100))
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = getString(R.string.nutr_search_hint)
        setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_SEARCH).also { if (it) searchFoods(day, value(), 0) } }
    }
    button(getString(R.string.nutr_search), primary = true) { searchFoods(day, search.value(), 0) }
    toolRow(UiTile(R.string.scan_short, "scan") { scanBarcode() }, UiTile(R.string.nutr_quick_add, "plus") { quickAdd(day) },
        UiTile(R.string.recipes_title, "recipe") { recipes(day) }, UiTile(R.string.ai_entry_short, "assistant") { aiEntry(day) })
    toolRow(UiTile(R.string.food_photo_short, "camera") { foodPhoto(day) }, UiTile(R.string.cook_short, "food") { startCook(day) })
    section(if (query.isNullOrBlank()) R.string.nutr_recent else R.string.nutr_results).setPadding(0, dp(20), 0, dp(4))
    if (rows.isEmpty()) text(getString(if (query.isNullOrBlank()) R.string.nutr_no_recent else R.string.nutr_no_foods), 14)
    for (food in rows) foodRow(food, sourceLabel(food)) { foodLog(day, food) }
    if (offset > 0) button(getString(R.string.feature_previous)) { searchFoods(day, query.orEmpty(), offset - 20) }
    if (rows.size == 20 && !query.isNullOrBlank()) button(getString(R.string.feature_more)) { searchFoods(day, query, offset + 20) }
    button(getString(R.string.food_create), look = ActionTone.TEXT) { foodForm(day) }
}

private fun MainActivity.searchFoods(day: String, query: String, offset: Int) {
    val api = featureApi()
    val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    work(getString(R.string.nutr_searching), {
        // Recipes are foods too, so one search covers recent, own, club, catalogue foods and recipes.
        if (query.isBlank()) runCatching { api.array("/api/foods/recent?meal=$diaryMeal") }.getOrDefault(emptyList())
        else api.array("/api/foods?q=$encoded&limit=20&offset=$offset")
    }, { found -> foods(day, query, found, offset) }, { foods(day, query, emptyList(), message = it) })
}

private fun MainActivity.sourceLabel(food: JSONObject): String = getString(when {
    food.optString("source") == "recipe" -> R.string.source_recipe
    !food.has("id") || food.isNull("id") -> R.string.source_catalogue
    !food.isNull("owner_id") && food.optLong("owner_id") == athleteId() -> R.string.source_mine
    !food.isNull("club_id") && food.has("club_id") -> R.string.source_club
    else -> R.string.source_catalogue
})

private fun MainActivity.foodRow(food: JSONObject, source: String, open: () -> Unit) {
    val line = row(center = true).apply {
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(getColor(R.color.hover)), null, shape(Color.WHITE, 8))
        setOnClickListener { open() }
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    text(foodName(food), 16, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
    val brand = food.optString("brand").takeIf { !food.isNull("brand") && it.isNotBlank() }
    text(listOfNotNull(brand, getString(R.string.per_100, kcalText(food.optDouble("kcal", 0.0)))).joinToString(" · "), 13, parent = column)
        .setPadding(0, dp(2), 0, 0)
    statusChip(source, R.color.hover, R.color.accent, line)
    line.contentDescription = "${foodName(food)}, $source"
    divider(page, inset = false)
}

/** The food backing a recipe, logged per serving or in grams. */
internal fun recipeAsFood(recipe: JSONObject): JSONObject {
    val food = JSONObject(recipe.optJSONObject("per_100g")?.toString() ?: "{}").put("id", recipe.getLong("food_id"))
        .put("name", recipe.optString("name")).put("source", "recipe")
    val serving = recipe.optDouble("serving_grams")
    if (serving > 0) food.put("servings", org.json.JSONArray().put(JSONObject().put("label", "serving").put("count", 1)
        .put("unit", "serving").put("grams", serving)))
    return food
}

/** Serving picker: a serving, a quantity and a meal, with the macros previewed live. Editing a log uses the same screen. */
internal fun MainActivity.foodLog(day: String, food: JSONObject, log: JSONObject? = null, message: String? = null) {
    layout(foodName(food), "food_log", headline = false)
    featureError(message)
    text(foodName(food), 26, getColor(R.color.ink)).instrumentType(display = true)
    val brand = food.optString("brand").takeIf { !food.isNull("brand") && it.isNotBlank() }
    // Named outside sources (for example the open product database, whose licence asks for attribution) are shown; internal codes are not.
    val source = food.optString("source").takeIf { !food.isNull("source") && ' ' in it }
    text(listOfNotNull(brand, source, getString(R.string.per_100, kcalText(food.optDouble("kcal", 0.0)))).joinToString(" · "), 13)
    val servings = servingsOf(food)
    // An edit starts from the logged serving (plain grams when it had none); a new log from the last one used.
    val startLabel = (if (log != null) log.optString("serving_label").takeIf { !log.isNull("serving_label") } ?: "g"
        else food.optString("last_serving_label").takeIf { !food.isNull("last_serving_label") })?.takeIf { label -> servings.any { it.first == label } }
    var serving = servings.firstOrNull { it.first == startLabel } ?: servings.firstOrNull { it.first != "100 g" && it.first != "g" } ?: servings.first()
    var meal = log?.optString("meal")?.takeIf { it in meals } ?: diaryMeal
    val readout = row(top = 10, center = true)
    val energy = text("", 44, getColor(R.color.ink), readout).apply { instrumentType(numeric = true); layoutParams = LinearLayout.LayoutParams(-2, -2) }
    text(getString(R.string.unit_kcal), 16, parent = readout).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(dp(6), dp(14), 0, 0) }
    val split = text("", 14)
    text(getString(R.string.serving_title), 14).setPadding(0, dp(12), 0, dp(4))
    val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    page.addView(group)
    val quantityLine = row(top = 10, center = true)
    val quantity = EditText(this)
    fun grams() = (decimal(quantity.value()) ?: 0.0) * serving.second
    fun refresh() {
        val grams = grams()
        energy.text = metricNumber(food.optDouble("kcal", 0.0) * grams / 100, "kcal", locale)
        split.text = listOf("protein_g" to R.string.macro_p, "carbs_g" to R.string.macro_c, "fat_g" to R.string.macro_f).joinToString("   ") { (key, label) ->
            "${getString(label)} ${gramText(food.optDouble(key, 0.0) * grams / 100)}"
        } + "   ·   " + gramText(grams)
    }
    servings.forEach { option ->
        group.addView(RadioButton(this).apply {
            id = View.generateViewId()
            text = when (option.first) { "g" -> getString(R.string.serving_grams); "100 g" -> "100 g"; else -> "${servingName(option.first, food)} · ${gramText(option.second)}" }
            textSize = 14f
            segment()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                val grams = grams()
                val previous = serving
                serving = option
                if (option.first == "g") quantity.setText(short(grams).replace(',', '.'))
                else if (previous.first == "g") quantity.setText("1")
                refresh()
            }
        }, RadioGroup.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    group.check(group.getChildAt(servings.indexOf(serving)).id)
    text(getString(R.string.serving_quantity), 14, parent = quantityLine).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    fun nudge(by: Double) {
        val step = if (serving.first == "g") by * 10 else by * .5
        quantity.setText(short(((decimal(quantity.value()) ?: 0.0) + step).coerceAtLeast(0.0)).replace(',', '.'))
    }
    button("−", parent = quantityLine) { nudge(-1.0) }.apply {
        textSize = 22f; contentDescription = getString(R.string.serving_less); layoutParams = LinearLayout.LayoutParams(dp(52), -2) }
    quantity.apply {
        val previous = if (serving.first == "g") (log ?: food).optDouble(if (log != null) "grams" else "last_grams")
            else (log ?: food).optDouble(if (log != null) "quantity" else "last_quantity").takeIf { startLabel != null }
        setText(previous?.takeIf { it.isFinite() && it > 0 }?.let { short(it).replace(',', '.') } ?: if (serving.first == "g") "100" else "1")
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
        gravity = Gravity.CENTER
        textSize = 20f
        instrumentType(numeric = true)
        setTextColor(getColor(R.color.ink))
        contentDescription = getString(R.string.serving_quantity)
        backgroundTintList = null
        background = shape(getColor(R.color.surface), 13, getColor(R.color.line))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        quantityLine.addView(this, LinearLayout.LayoutParams(dp(92), dp(48)).apply { marginStart = dp(6); marginEnd = dp(6) })
    }
    button("+", parent = quantityLine) { nudge(1.0) }.apply {
        textSize = 22f; contentDescription = getString(R.string.serving_more); layoutParams = LinearLayout.LayoutParams(dp(52), -2) }
    refresh()
    mealGrid(meal) { meal = it }
    button(getString(R.string.nutr_food_save), primary = true) {
        val grams = grams()
        if (grams <= 0 || grams > 5000) { quantity.error = getString(R.string.nutr_invalid_grams); return@button }
        // Plain grams carry no serving; a named serving lets the server keep label and quantity with the grams.
        val values = JSONObject().put("meal", meal).put("grams", grams)
        if (serving.first != "g") values.put("serving_label", serving.first).put("quantity", decimal(quantity.value()))
        if (log != null) {
            val api = featureApi()
            work(getString(R.string.feature_saving), { api.send("PATCH", "/api/food-logs/${log.getLong("id")}", values) },
                { nutritionHome(day, getString(R.string.feature_saved)) }, { foodLog(day, food, log, it) })
        } else saveFeature(FeatureWrite.FOOD, foodReference(food, values.put("day", day))) { status, result ->
            diaryMeal = meal
            if (status in listOf(R.string.feature_saved, R.string.saved_pending)) nutritionHome(day, result)
            else foodLog(day, food, null, result)
        }
    }
    if (log != null) button(getString(R.string.nutr_delete_entry)) { deleteLog(day, log) }.setTextColor(getColor(R.color.error))
    // Own foods, and club foods this athlete scanned or created (the server refuses once another athlete logged one).
    if (log == null && food.has("id") && !food.isNull("id") && food.optString("source") != "recipe" &&
        (food.optLong("owner_id") == athleteId() || food.optLong("created_by") == athleteId()))
        button(getString(R.string.food_edit), look = ActionTone.TEXT) { foodForm(day, food = food) }
}

/** Macros without a food, for a meal eaten out or a packet without a barcode. */
private fun MainActivity.quickAdd(day: String, log: JSONObject? = null, message: String? = null) {
    layout(getString(R.string.nutr_quick_add), "quick_add")
    featureError(message)
    text(getString(R.string.nutr_quick_note), 14)
    val label = field(getString(R.string.nutr_quick_label), log?.optString("label").orEmpty()).apply { filters = arrayOf(InputFilter.LengthFilter(100)) }
    fun initial(key: String) = log?.takeIf { it.has(key) && !it.isNull(key) }?.let { number(it, key) }.orEmpty()
    val kcal = decimalField(R.string.nutr_energy, initial("kcal"))
    val macros = fieldRow(listOf(R.string.macro_protein_short, R.string.macro_carbs_short, R.string.macro_fat_short),
        listOf(initial("protein_g"), initial("carbs_g"), initial("fat_g")))
    var meal = log?.optString("meal")?.takeIf { it in meals } ?: diaryMeal
    mealGrid(meal) { meal = it }
    button(getString(R.string.nutr_food_save), primary = true) {
        val energy = decimal(kcal.value())
        val grams = macros.map { if (it.value().isBlank()) 0.0 else decimal(it.value()) }
        if (energy == null || energy <= 0 || energy > 10000) { kcal.error = getString(R.string.nutr_invalid_kcal); return@button }
        grams.forEachIndexed { index, value -> if (value == null || value < 0 || value > 1000) { macros[index].error = getString(R.string.nutr_invalid_macro); return@button } }
        val body = JSONObject().put("meal", meal).put("label", label.value().ifBlank { getString(R.string.nutr_quick) }).put("kcal", energy)
            .put("protein_g", grams[0]).put("carbs_g", grams[1]).put("fat_g", grams[2])
        if (log != null) {
            val api = featureApi()
            work(getString(R.string.feature_saving), { api.send("PATCH", "/api/food-logs/${log.getLong("id")}", body) },
                { nutritionHome(day, getString(R.string.feature_saved)) }, { quickAdd(day, log, it) })
        } else saveFeature(FeatureWrite.FOOD, body.put("day", day)) { status, result ->
            if (status in listOf(R.string.feature_saved, R.string.saved_pending)) nutritionHome(day, result) else quickAdd(day, null, result)
        }
    }
    if (log != null) button(getString(R.string.nutr_delete_entry)) { deleteLog(day, log) }.setTextColor(getColor(R.color.error))
}

/** Equal columns of decimal fields on one line. */
internal fun MainActivity.fieldRow(labels: List<Int>, values: List<String>): List<EditText> {
    val line = row()
    return labels.mapIndexed { index, label ->
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(10) })
        decimalField(label, values[index], column)
    }
}

/** Create a club or personal food, or correct one (for example after a scan). Values are per 100 g. */
internal fun MainActivity.foodForm(day: String = diaryDay, barcode: String = "", food: JSONObject? = null, message: String? = null,
    caution: Boolean = false) {
    layout(getString(if (food == null) R.string.food_create else R.string.food_edit), "food_form")
    if (message != null) notice(message, caution)
    fun initial(key: String) = food?.takeIf { it.has(key) && !it.isNull(key) }?.optString(key).orEmpty()
    val nameBg = field(getString(R.string.food_name_bg), initial("name_bg")).apply { filters = arrayOf(InputFilter.LengthFilter(100)) }
    val nameEn = field(getString(R.string.food_name_en), initial("name_en")).apply { filters = arrayOf(InputFilter.LengthFilter(200)) }
    val brand = field(getString(R.string.food_brand), initial("brand")).apply { filters = arrayOf(InputFilter.LengthFilter(100)) }
    val code = field(getString(R.string.food_barcode), initial("barcode").ifBlank { barcode }, InputType.TYPE_CLASS_NUMBER)
        .apply { filters = arrayOf(InputFilter.LengthFilter(14)) }
    section(R.string.food_per_100).setPadding(0, dp(18), 0, 0)
    fun value(key: String) = food?.takeIf { it.has(key) && !it.isNull(key) }?.let { number(it, key) }.orEmpty()
    val kcal = decimalField(R.string.nutr_energy, value("kcal"))
    val macros = fieldRow(listOf(R.string.macro_protein_short, R.string.macro_carbs_short, R.string.macro_fat_short),
        listOf(value("protein_g"), value("carbs_g"), value("fat_g")))
    val fibre = decimalField(R.string.nutr_fibre, value("fibre_g"))
    val first = food?.optJSONArray("servings")?.objects()?.firstOrNull { it.optString("label") != "100 g" }
    val serving = field(getString(R.string.food_serving_label), first?.optString("label").orEmpty()).apply { filters = arrayOf(InputFilter.LengthFilter(60)) }
    val servingGrams = decimalField(R.string.food_serving_grams, first?.let { number(it, "grams") }.orEmpty())
    button(getString(R.string.food_save), primary = true) {
        val numbers = (listOf(kcal) + macros + fibre).map { if (it === fibre && it.value().isBlank()) 0.0 else decimal(it.value()) }
        val limits = listOf(1000.0, 100.0, 100.0, 100.0, 100.0)
        val fields = listOf(kcal) + macros + fibre
        if (nameBg.value().isBlank()) { nameBg.error = getString(R.string.food_name_needed); return@button }
        if (code.value().isNotEmpty() && !validBarcode(code.value())) { code.error = getString(R.string.scan_invalid); return@button }
        numbers.forEachIndexed { index, number ->
            if (number == null || number < 0 || number > limits[index]) { fields[index].error = getString(R.string.food_invalid_value); return@button }
        }
        val portion = decimal(servingGrams.value())
        if (serving.value().isNotBlank() && (portion == null || portion <= 0 || portion > 5000)) { servingGrams.error = getString(R.string.nutr_invalid_grams); return@button }
        val body = JSONObject().put("name_bg", nameBg.value()).put("name_en", nameEn.value()).put("brand", brand.value())
            .put("barcode", code.value().ifEmpty { JSONObject.NULL })
        listOf("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g").forEachIndexed { index, key -> body.put(key, numbers[index]) }
        val portions = org.json.JSONArray().put(JSONObject().put("label", "100 g").put("grams", 100))
        if (serving.value().isNotBlank()) portions.put(JSONObject().put("label", serving.value()).put("grams", portion))
        body.put("servings", portions)
        val api = featureApi()
        work(getString(R.string.feature_saving), {
            if (food == null) api.post("/api/foods", body) else JSONObject(api.send("PATCH", "/api/foods/${food.getLong("id")}", body))
        }, { saved -> foodLog(day, saved) }, { foodForm(day, code.value(), food, it, caution = true) })
    }
    button(getString(R.string.nutr_quick_add)) { quickAdd(day) }
}

internal fun MainActivity.scanBarcode() = launchVideo(Intent(this, BarcodeActivity::class.java), 40)

internal fun MainActivity.barcodeResult(resultCode: Int, code: String?) {
    when {
        resultCode == android.app.Activity.RESULT_OK && code != null && validBarcode(code) -> lookupBarcode(code)
        resultCode == android.app.Activity.RESULT_FIRST_USER -> barcodeManual()
    }
}

private fun MainActivity.lookupBarcode(code: String) {
    val api = featureApi()
    work(getString(R.string.scan_looking_up), {
        try { api.obj("/api/foods/barcode/$code") to 200 }
        catch (e: ApiError) { if (e.status == 404 || e.status == 503) null to e.status else throw e }
        catch (e: IOException) { if (e is AppError) throw e else null to 503 }
    }, { (food, status) ->
        when {
            food != null -> foodLog(diaryDay, food)
            status == 404 -> foodForm(diaryDay, code, message = getString(R.string.scan_not_found, code))
            else -> barcodeManual(code, getString(R.string.scan_offline))
        }
    }, { barcodeManual(code, it) })
}

private fun MainActivity.barcodeManual(code: String = "", message: String? = null) {
    layout(getString(R.string.scan_manual), "barcode_manual")
    if (message != null) notice(message, caution = true)
    val digits = field(getString(R.string.food_barcode), code, InputType.TYPE_CLASS_NUMBER).apply { filters = arrayOf(InputFilter.LengthFilter(14)) }
    button(getString(R.string.scan_lookup), primary = true) {
        if (validBarcode(digits.value())) lookupBarcode(digits.value()) else digits.error = getString(R.string.scan_invalid)
    }
    button(getString(R.string.scan_again)) { scanBarcode() }
    button(getString(R.string.food_create)) { foodForm(diaryDay, digits.value()) }
    button(getString(R.string.nutr_quick_add)) { quickAdd(diaryDay) }
}
