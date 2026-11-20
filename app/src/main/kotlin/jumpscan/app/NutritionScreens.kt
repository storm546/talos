package jumpscan.app

import android.text.InputFilter
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
    layout(getString(R.string.nutrition_title), "nutrition")
    if (message != null) notice(message)
    val panel = hero(R.string.nutr_summary, R.string.ui_food_intro, localDate(day, locale))
    button(getString(R.string.nutr_summary), primary = true, parent = panel) { diary(day) }
    tiles(UiTile(R.string.nutr_food, "food") { foods(day) }, UiTile(R.string.nutr_weight, "history") { weight(day) },
        UiTile(R.string.nutr_program, "training") { nutritionProgram(day) })
    text(getString(R.string.feature_day), 14).setPadding(0, dp(14), 0, 0)
    dayPicker(day) { nutritionHome(it) }
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
    for (food in rows.orEmpty()) button(foodName(food)) { foodLog(day, food) { foods(day, query, rows, offset, it) } }
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
    val group = android.widget.RadioGroup(this)
    page.addView(group)
    meals.forEach { (key, label) ->
        group.addView(android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = getString(label)
            segment()
            setOnClickListener { selected = key }
        })
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

private fun MainActivity.diary(day: String, message: String? = null) {
    val api = featureApi()
    val path = "/api/athletes/${athleteId()}/nutrition/summary?day=$day"
    work(getString(R.string.feature_loading), {
        api.obj(path) to api.array("/api/food-logs?from=$day&to=$day")
    }, { (summary, foods) ->
        layout(getString(R.string.nutr_summary), "nutrition_summary")
        if (message != null) text(message, 15)
        text(getString(R.string.feature_day), 14)
        dayPicker(day) { diary(it) }
        val totals = summary.getJSONObject("totals")
        val target = summary.optJSONObject("target") ?: JSONObject()
        val panel = card()
        section(R.string.nutr_total_target, panel)
        for ((key, label) in nutrients) {
            val goal = target.optDouble(key)
            targetBar(getString(label), if (target.isNull(key)) number(totals, key) else
                "${number(totals, key)} / ${number(target, key)}",
                if (goal > 0) totals.optDouble(key, 0.0) / goal else null, panel)
        }
        if (target.length() == 0) text(getString(R.string.nutr_no_targets), 14, parent = panel)
        val byMeal = summary.getJSONObject("by_meal_kcal")
        for ((meal, label) in meals) {
            val mealCard = card()
            val kcal = metricNumber(byMeal.getDouble(meal), "kcal", locale)
            text(getString(R.string.feature_value, getString(label), "$kcal ${getString(R.string.unit_kcal)}"), 18, green, mealCard)
            for (food in foods.filter { it.getString("meal") == meal }) {
                text(getString(R.string.feature_value, foodName(food), "${number(food, "grams")} ${getString(R.string.unit_grams)}"), 14, parent = mealCard)
            }
        }
        text(getString(R.string.nutr_complete_note), 14)
        button(getString(R.string.nutr_mark_complete)) {
            saveFeature(FeatureWrite.COMPLETE, JSONObject(), day) { _, message -> nutritionHome(day, message) }
        }
        button(getString(R.string.nutrition_title)) { nutritionHome(day) }
    }, { nutritionHome(day, it) })
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
