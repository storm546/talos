package jumpscan.app

import android.text.InputFilter
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate

private val nutrients = linkedMapOf("kcal" to R.string.nutr_energy, "protein_g" to R.string.nutr_protein,
    "carbs_g" to R.string.nutr_carbs, "fat_g" to R.string.nutr_fat, "fibre_g" to R.string.nutr_fibre)
private val meals = linkedMapOf("breakfast" to R.string.meal_breakfast, "lunch" to R.string.meal_lunch,
    "dinner" to R.string.meal_dinner, "snack" to R.string.meal_snack)

internal fun MainActivity.mealName(meal: String) = getString(meals.getValue(meal))
internal fun MainActivity.goalName(goal: String) = getString(when (goal) {
    "lose" -> R.string.goal_lose
    "gain" -> R.string.goal_gain
    else -> R.string.goal_maintain
})

internal fun MainActivity.nutritionHome(day: String = todayLocal(), message: String? = null) {
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

private fun MainActivity.nutritionDay(day: String, message: String?, summary: JSONObject?, foods: List<JSONObject>) {
    layout(getString(R.string.nutrition_title), "nutrition")
    if (message != null) notice(message, caution = summary == null)
    dayPicker(day) { nutritionHome(it) }
    if (summary != null) {
        val totals = summary.getJSONObject("totals")
        val target = summary.optJSONObject("target") ?: JSONObject()
        val energy = row(center = true)
        val goal = target.optDouble("kcal")
        if (goal > 0) energy.addView(Dial(this).apply {
            show((100 * totals.getDouble("kcal") / goal).toInt())
        }, dp(112), dp(106))
        val values = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        energy.addView(values, LinearLayout.LayoutParams(0, -2, 1f))
        text(getString(R.string.nutr_energy), 14, parent = values)
        text("${number(totals, "kcal")} ${getString(R.string.unit_kcal)}", 28, green, values).instrumentType(numeric = true)
        text(if (goal > 0) getString(R.string.energy_target, number(target, "kcal")) else getString(R.string.nutr_no_targets), 14, parent = values)
        divider(page, inset = false)
        section(R.string.nutr_total_target)
        val macros = row()
        for ((key, label) in nutrients.entries.drop(1).take(3)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(6), 0) }
            macros.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            val goalGrams = target.optDouble(key)
            val grams = number(totals, key) + (if (goalGrams > 0) " / ${number(target, key)}" else "")
            targetBar(getString(label), "$grams ${getString(R.string.unit_grams)}", if (goalGrams > 0) totals.getDouble(key) / goalGrams else null, column)
        }
        text(getString(R.string.feature_value, getString(R.string.nutr_fibre), "${number(totals, "fibre_g")} ${getString(R.string.unit_grams)}"), 14)
    }
    button(getString(R.string.nutr_food), primary = true) { foods(day) }
    if (summary != null) {
        section(R.string.meal_timeline)
        val byMeal = summary.getJSONObject("by_meal_kcal")
        meals.entries.forEachIndexed { index, (meal, label) ->
            val entry = timelineEntry(index, index == meals.size - 1)
            valueRow(getString(label), "${number(byMeal, meal)} ${getString(R.string.unit_kcal)}", entry)
            for (food in foods.filter { it.getString("meal") == meal }) {
                text(getString(R.string.feature_value, foodName(food), "${number(food, "grams")} ${getString(R.string.unit_grams)}"), 14, parent = entry)
            }
        }
        text(getString(R.string.nutr_complete_note), 14)
        button(getString(R.string.nutr_mark_complete)) {
            saveFeature(FeatureWrite.COMPLETE, JSONObject(), day) { _, result -> nutritionHome(day, result) }
        }
    } else button(getString(R.string.nutr_summary)) { nutritionHome(day) }
    button(getString(R.string.nutr_weight)) { weight(day) }
    button(getString(R.string.nutr_program)) { nutritionProgram(day) }
    pendingControls()
}

private fun MainActivity.foodName(food: JSONObject): String =
    if (locale.language == "bg") food.getString("name_bg") else food.optString("name_en").ifBlank { food.getString("name_bg") }

private fun MainActivity.foods(day: String, query: String = "", rows: List<JSONObject>? = null, offset: Int = 0,
    message: String? = null) {
    layout(getString(R.string.nutr_food), "food_search")
    featureError(message)
    text(localDate(day, locale), 14)
    val search = field(getString(R.string.nutr_search_label), query).apply { filters = arrayOf(InputFilter.LengthFilter(100)) }
    fun load(value: String, start: Int = 0) {
        val api = featureApi()
        val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        work(getString(R.string.nutr_searching), { api.array("/api/foods?q=$encoded&limit=20&offset=$start") },
            { foods(day, value, it, start) }, { foods(day, value, rows, offset, it) })
    }
    button(getString(R.string.nutr_search), primary = true) { load(search.value()) }
    if (rows?.isEmpty() == true) text(getString(R.string.nutr_no_foods), 14)
    for (food in rows.orEmpty()) button(foodName(food), look = ActionTone.TEXT) { foodLog(day, food) { foods(day, query, rows, offset, it) } }.apply {
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        setTextColor(getColor(R.color.ink)); typeface = resources.getFont(R.font.spacegrotesk_regular)
        (layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = 0
        divider(page, inset = false)
    }
    if (offset > 0) button(getString(R.string.feature_previous)) { load(query, offset - 20) }
    if (rows?.size == 20) button(getString(R.string.feature_more)) { load(query, offset + 20) }
    button(getString(R.string.nutrition_title)) { nutritionHome(day) }
}

private fun MainActivity.foodLog(day: String, food: JSONObject, message: String? = null, gramsText: String = "",
    meal: String = "breakfast", back: (String?) -> Unit) {
    layout(getString(R.string.nutr_food), "food_log")
    featureError(message)
    text(foodName(food), 18, green)
    text(localDate(day, locale), 14)
    val grams = decimalField(R.string.nutr_grams, gramsText)
    text(getString(R.string.nutr_meal), 14)
    var selected = meal
    val group = android.widget.RadioGroup(this).apply {
        orientation = android.widget.RadioGroup.VERTICAL
        background = shape(getColor(R.color.hover), 10)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    page.addView(group)
    meals.forEach { (key, label) ->
        group.addView(android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = getString(label)
            textSize = 13f
            segment()
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { selected = key }
        }, android.widget.RadioGroup.LayoutParams(-1, -2))
    }
    group.check(group.getChildAt(meals.keys.indexOf(meal)).id)
    button(getString(R.string.nutr_food_save), primary = true) {
        val value = decimal(grams.value())
        if (value == null || value <= 0 || value > 5000) grams.error = getString(R.string.nutr_invalid_grams)
        else saveFeature(FeatureWrite.FOOD, JSONObject().put("day", day).put("food_id", food.getLong("id"))
            .put("meal", selected).put("grams", value)) { status, message ->
            if (status in listOf(R.string.feature_saved, R.string.saved_pending)) nutritionHome(day, message)
            else foodLog(day, food, message, grams.value(), selected, back)
        }
    }
    button(getString(R.string.nutr_search)) { back(null) }
}

private fun MainActivity.weight(day: String, rows: List<JSONObject>? = null, message: String? = null, draft: String? = null) {
    layout(getString(R.string.nutr_weight), "weight")
    if (message != null) text(message, 15)
    text(getString(R.string.feature_day), 14)
    dayPicker(day) { weight(it, rows) }
    val kg = decimalField(R.string.nutr_weight_kg, draft ?: rows?.firstOrNull { it.getString("day") == day }
        ?.let { number(it, "kg") }.orEmpty())
    button(getString(R.string.nutr_weight_save), primary = true) {
        val value = decimal(kg.value())
        if (value == null || value !in 20.0..400.0) kg.error = getString(R.string.nutr_invalid_weight)
        else saveFeature(FeatureWrite.WEIGHT, JSONObject().put("kg", value), day) { _, message -> weight(day, rows, message, kg.value()) }
    }
    button(getString(R.string.nutr_load_trend)) {
        val api = featureApi()
        val first = LocalDate.parse(day).minusDays(29)
        val path = "/api/athletes/${athleteId()}/weights?from=$first&to=$day"
        work(getString(R.string.feature_loading), { api.obj(path).getJSONArray("weights").objects().asReversed() },
            { weight(day, it, draft = kg.value().ifBlank { null }) }, { weight(day, rows, it, kg.value()) })
    }
    if (rows != null) {
        text(getString(R.string.nutr_trend), 18, green)
        if (rows.isEmpty()) text(getString(R.string.nutr_no_weights), 14)
        for (item in rows) {
            text(getString(R.string.nutr_weight_row, localDate(item.getString("day"), locale),
                number(item, "kg"), number(item, "trend_kg")), 14)
        }
    }
    button(getString(R.string.nutrition_title)) { nutritionHome(day) }
}

private fun MainActivity.nutritionProgram(day: String) {
    val api = featureApi()
    val path = "/api/athletes/${athleteId()}/nutrition"
    work(getString(R.string.feature_loading), { api.obj("$path/program") to api.obj("$path/expenditure") }, { (response, expenditure) ->
        layout(getString(R.string.nutr_program), "nutrition_program")
        text(getString(R.string.nutr_coach_note), 14)
        val program = response.optJSONObject("program")
        if (program == null) text(getString(R.string.nutr_no_program))
        else {
            text(getString(R.string.feature_value, getString(R.string.nutr_goal), goalName(program.getString("goal"))))
            text(getString(R.string.nutr_rate_row, number(program, "rate_kg_week")))
            text(getString(if (response.optBoolean("applied")) R.string.nutr_applied else R.string.nutr_not_applied), 14)
            val recommendation = program.getJSONObject("recommended_target")
            text(getString(R.string.nutr_recommended), 18, green)
            for ((key, label) in nutrients.entries.take(4)) {
                text(getString(R.string.feature_value, getString(label), number(recommendation, if (key == "kcal") "calories" else key)))
            }
        }
        text(getString(R.string.nutr_expenditure), 18, green)
        val estimate = expenditure.optJSONObject("expenditure")
        if (estimate == null) text(getString(R.string.nutr_no_expenditure), 14)
        else {
            text(getString(R.string.nutr_expenditure_row, number(estimate, "value_kcal")))
            text(getString(if (estimate.optString("status") == "updating") R.string.nutr_updating else R.string.nutr_holding), 14)
            for (item in expenditure.getJSONArray("history").objects().takeLast(7).asReversed()) {
                text(getString(R.string.feature_value, localDate(item.getString("date"), locale), number(item, "value_kcal")), 14)
            }
        }
        button(getString(R.string.nutrition_title)) { nutritionHome(day) }
    }, { nutritionHome(day, it) })
}
