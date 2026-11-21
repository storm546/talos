package jumpscan.app

import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID

// Recipes, AI meal entry and the insights page (SPEC 7.2).

private class RecipeDraft(val id: Long?, var name: String, var portions: String, val items: MutableList<JSONObject>)
private var recipeDraft: RecipeDraft? = null

internal fun MainActivity.recipes(day: String = diaryDay) {
    val api = featureApi()
    work(getString(R.string.feature_loading), { api.array("/api/recipes") }, { rows ->
        layout(getString(R.string.recipes_title), "recipes")
        text(getString(R.string.recipes_note), 14)
        button(getString(R.string.recipe_new), primary = true) { recipeDraft = RecipeDraft(null, "", "1", mutableListOf()); recipeEditor(day) }
        if (rows.isEmpty()) notice(getString(R.string.recipes_empty))
        for (recipe in rows) {
            val line = row(center = true).apply { setPadding(0, dp(10), 0, dp(10)) }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            text(recipe.optString("name"), 16, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
            val portions = recipe.optDouble("servings_count", 1.0).coerceAtLeast(1.0)
            val total = recipe.optJSONObject("totals")?.optDouble("kcal")
                ?: recipe.optJSONArray("items")?.objects()?.sumOf { it.optDouble("kcal", 0.0) } ?: 0.0
            text(getString(R.string.recipe_summary, short(portions), kcalText(total / portions)), 13, parent = column).setPadding(0, dp(2), 0, 0)
            iconButton("recipe", getString(R.string.recipe_edit_named, recipe.optString("name")), line) {
                recipeDraft = RecipeDraft(recipe.getLong("id"), recipe.optString("name"), short(portions).replace(',', '.'),
                    recipe.optJSONArray("items")?.objects().orEmpty().toMutableList())
                recipeEditor(day)
            }
            iconButton("plus", getString(R.string.nutr_add_to, recipe.optString("name")), line) {
                foods(day, recipe.optString("name"))
            }
            divider(page, inset = false)
        }
    }, { foods(day, message = it) })
}

/** Per-100 g values of an ingredient, whether it came from a search result or from the server's recipe items. */
private fun ingredientMacros(item: JSONObject, key: String): Double {
    val food = item.optJSONObject("food") ?: item
    val grams = item.optDouble("grams", 0.0)
    return if (item.has("per_100")) item.getJSONObject("per_100").optDouble(key, 0.0) * grams / 100
    else if (item.has("food")) food.optDouble(key, 0.0) * grams / 100
    else item.optDouble(key, 0.0)
}

private fun MainActivity.recipeEditor(day: String, results: List<JSONObject> = emptyList(), message: String? = null) {
    val draft = recipeDraft ?: return recipes(day)
    layout(getString(if (draft.id == null) R.string.recipe_new else R.string.recipe_edit), "recipe_edit")
    featureError(message)
    val name = field(getString(R.string.recipe_name), draft.name).apply { filters = arrayOf(InputFilter.LengthFilter(100)) }
    val portions = decimalField(R.string.recipe_portions, draft.portions)
    fun keep() { draft.name = name.value(); draft.portions = portions.value() }
    val count = (decimal(draft.portions) ?: 1.0).coerceAtLeast(1.0)
    val hero = card(hero = true)
    text(getString(R.string.recipe_per_serving), 12, getColor(R.color.signal), hero).letterSpacing = .09f
    val kcal = draft.items.sumOf { ingredientMacros(it, "kcal") } / count
    text(kcalText(kcal), 32, android.graphics.Color.WHITE, hero).instrumentType(numeric = true)
    text(listOf("protein_g" to R.string.macro_p, "carbs_g" to R.string.macro_c, "fat_g" to R.string.macro_f).joinToString("   ") { (key, label) ->
        "${getString(label)} ${gramText(draft.items.sumOf { ingredientMacros(it, key) } / count)}"
    } + "   ·   " + getString(R.string.recipe_total, gramText(draft.items.sumOf { it.optDouble("grams", 0.0) })), 14, getColor(R.color.on_hero), hero)
    section(R.string.recipe_ingredients).setPadding(0, dp(18), 0, dp(4))
    if (draft.items.isEmpty()) text(getString(R.string.recipe_no_items), 14)
    draft.items.forEachIndexed { index, item ->
        val line = row(center = true).apply { setPadding(0, dp(6), 0, dp(6)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        text(foodName(item.optJSONObject("food") ?: item), 15, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
        text("${gramText(item.optDouble("grams", 0.0))} · ${kcalText(ingredientMacros(item, "kcal"))}", 13, parent = column).setPadding(0, dp(2), 0, 0)
        iconButton("plus", getString(R.string.recipe_remove, foodName(item.optJSONObject("food") ?: item)), line) {
            keep(); draft.items.removeAt(index); recipeEditor(day)
        }.rotation = 45f
        divider(page, inset = false)
    }
    section(R.string.recipe_add_ingredient).setPadding(0, dp(18), 0, 0)
    val ingredientRow = row(center = true)
    val search = field(getString(R.string.nutr_search_label), parent = ingredientRow.let { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
        ingredientRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f)) } })
    val grams = decimalField(R.string.nutr_grams, "100", ingredientRow.let { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
        ingredientRow.addView(this, LinearLayout.LayoutParams(dp(110), -2).apply { marginStart = dp(10) }) } })
    button(getString(R.string.nutr_search)) {
        keep()
        val api = featureApi()
        val query = URLEncoder.encode(search.value(), "UTF-8").replace("+", "%20")
        val amount = grams.value()
        work(getString(R.string.nutr_searching), { api.array("/api/foods?q=$query&limit=8") },
            { recipeEditor(day, it.map { food -> JSONObject().put("food", food).put("grams_hint", amount) }) }, { recipeEditor(day, message = it) })
    }
    for (result in results) {
        val food = result.getJSONObject("food")
        button("${foodName(food)} · ${getString(R.string.per_100, kcalText(food.optDouble("kcal", 0.0)))}", look = ActionTone.TEXT) {
            keep()
            val amount = decimal(result.optString("grams_hint"))?.takeIf { it > 0 && it <= 5000 } ?: 100.0
            draft.items += JSONObject().put("food", food).put("grams", amount)
            recipeEditor(day)
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(getColor(R.color.ink)) }
    }
    button(getString(R.string.recipe_save), primary = true) {
        keep()
        val portionCount = decimal(draft.portions)
        if (draft.name.isBlank()) { name.error = getString(R.string.food_name_needed); return@button }
        if (portionCount == null || portionCount < 1 || portionCount > 100) { portions.error = getString(R.string.recipe_invalid_portions); return@button }
        if (draft.items.isEmpty()) { search.error = getString(R.string.recipe_no_items); return@button }
        val items = JSONArray()
        for (item in draft.items) {
            val food = item.optJSONObject("food")
            val entry = JSONObject().put("grams", item.getDouble("grams"))
            when {
                food == null -> entry.put("food_id", item.getLong("food_id"))
                food.has("id") && !food.isNull("id") -> entry.put("food_id", food.getLong("id"))
                else -> entry.put("catalog_id", food.get("catalog_id"))
            }
            items.put(entry)
        }
        val body = JSONObject().put("name", draft.name).put("servings_count", portionCount).put("items", items)
        val api = featureApi()
        work(getString(R.string.feature_saving), {
            if (draft.id == null) api.send("POST", "/api/recipes", body) else api.send("PUT", "/api/recipes/${draft.id}", body)
        }, { recipeDraft = null; recipes(day) }, { recipeEditor(day, message = it) })
    }
    if (draft.id != null) button(getString(R.string.recipe_delete)) {
        val api = featureApi()
        work(getString(R.string.feature_saving), { api.send("DELETE", "/api/recipes/${draft.id}") },
            { recipeDraft = null; recipes(day) }, { recipeEditor(day, message = it) })
    }.setTextColor(getColor(R.color.error))
}

/** Free-text meal entry: the server proposes matches, nothing is logged until the athlete confirms. */
internal fun MainActivity.aiEntry(day: String = diaryDay, draft: String = "", proposal: List<JSONObject>? = null, message: String? = null) {
    layout(getString(R.string.ai_entry_title), "ai_entry")
    if (message != null) notice(message, caution = true)
    text(getString(R.string.ai_entry_note), 14)
    val input = field(getString(R.string.ai_entry_label), draft).apply {
        setSingleLine(false); minLines = 3; gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(InputFilter.LengthFilter(500))
        hint = getString(R.string.ai_entry_hint)
    }
    button(getString(R.string.ai_entry_parse), primary = proposal == null) {
        if (input.value().isBlank()) { input.error = getString(R.string.ai_entry_empty); return@button }
        val api = featureApi()
        val text = input.value()
        work(getString(R.string.ai_entry_working), {
            val response = api.post("/api/me/nutrition/ai-parse", JSONObject().put("text", text).put("day", day).put("meal", diaryMeal))
            (response.optJSONArray("items") ?: response.optJSONObject("proposal")?.optJSONArray("items") ?: JSONArray()).objects()
        }, { aiEntry(day, text, it) }, { aiEntry(day, text, null, getString(R.string.ai_entry_unavailable) + "\n" + it) })
    }
    if (proposal == null) return
    section(R.string.ai_entry_proposal).setPadding(0, dp(18), 0, dp(4))
    if (proposal.isEmpty()) text(getString(R.string.ai_entry_nothing), 14)
    val chosen = proposal.map { item -> (item.optJSONObject("food") ?: item).let { it.has("id") && !it.isNull("id") || it.has("catalog_id") } }.toMutableList()
    proposal.forEachIndexed { index, item ->
        val food = item.optJSONObject("food") ?: item
        val grams = item.optDouble("grams", 0.0)
        val line = row(center = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        text(if (chosen[index]) foodName(food) else item.optString("text", item.optString("name")), 15, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
        text(if (chosen[index]) "${gramText(grams)} · ${kcalText(item.optDouble("kcal", food.optDouble("kcal", 0.0) * grams / 100))}"
            else getString(R.string.ai_entry_no_match), 13, parent = column).setPadding(0, dp(2), 0, 0)
        line.addView(android.widget.Switch(this).apply {
            instrumentSwitch(); isChecked = chosen[index]; isEnabled = chosen[index]
            contentDescription = foodName(food)
            setOnCheckedChangeListener { _, on -> chosen[index] = on }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        divider(page, inset = false)
    }
    if (chosen.any { it }) button(getString(R.string.ai_entry_confirm), primary = true) {
        val entries = JSONArray()
        proposal.forEachIndexed { index, item ->
            if (!chosen[index]) return@forEachIndexed
            val food = item.optJSONObject("food") ?: item
            val entry = JSONObject().put("day", day).put("meal", item.optString("meal").takeIf { it.isNotBlank() } ?: diaryMeal)
                .put("grams", item.getDouble("grams")).put("client_id", UUID.randomUUID().toString())
            if (food.has("id") && !food.isNull("id")) entry.put("food_id", food.getLong("id")) else entry.put("catalog_id", food.get("catalog_id"))
            if (item.has("serving_label") && !item.isNull("serving_label")) entry.put("serving_label", item.getString("serving_label"))
            if (item.has("quantity") && !item.isNull("quantity")) entry.put("quantity", item.getDouble("quantity"))
            entries.put(entry)
        }
        val api = featureApi()
        work(getString(R.string.feature_saving), { api.post("/api/food-logs/batch", JSONObject().put("entries", entries)) },
            { nutritionHome(day, getString(R.string.ai_entry_logged, entries.length())) }, { aiEntry(day, input.value(), proposal, it) })
    }
}

private class Insight(val analytics: JSONObject?, val weights: List<JSONObject>?, val goal: JSONObject?, val program: JSONObject?,
                      val expenditure: JSONObject?, val summary: JSONObject?, val definitions: List<JSONObject>)

internal fun MainActivity.insights(day: String = diaryDay, days: Int = 7, message: String? = null) {
    val api = featureApi()
    val base = "/api/athletes/${athleteId()}"
    work(getString(R.string.feature_loading), {
        fun get(path: String) = runCatching { api.obj(path) }.getOrNull()
        val from = LocalDate.parse(day).minusDays(days - 1L)
        val result = Insight(get("$base/nutrition/analytics?days=$days&end=$day"),
            get("$base/weights?from=$from&to=$day")?.optJSONArray("weights")?.objects(),
            get("$base/nutrition/goal"), get("$base/nutrition/program"), get("$base/nutrition/expenditure"),
            get("$base/nutrition/summary?day=$day"), runCatching { api.array("/api/nutrient-definitions") }.getOrDefault(emptyList()))
        if (result.analytics == null && result.weights == null && result.summary == null) api.obj("$base/nutrition/summary?day=$day")
        result
    }, { insightPage(day, days, it, message) }, { nutritionHome(day, it) })
}

private fun MainActivity.insightPage(day: String, days: Int, data: Insight, message: String?) {
    layout(getString(R.string.nutr_insights), "insights")
    if (message != null) notice(message)
    val period = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; background = shape(getColor(R.color.hover), 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }
    page.addView(period, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    for (option in listOf(7, 30, 90)) period.addView(RadioButton(this).apply {
        id = android.view.View.generateViewId(); text = getString(R.string.insight_days, option); textSize = 13f; segment()
        isChecked = option == days
        setOnClickListener { if (option != days) insights(day, option) }
    }, RadioGroup.LayoutParams(0, -2, 1f))
    // Intake against target.
    section(R.string.insight_intake).setPadding(0, dp(18), 0, 0)
    val analytics = data.analytics
    val series = analytics?.optJSONArray("days")?.objects().orEmpty()
    if (analytics == null) text(getString(R.string.insight_unavailable), 14)
    else {
        val averages = analytics.optJSONObject("averages") ?: JSONObject()
        val adherence = analytics.optJSONObject("adherence") ?: JSONObject()
        val logged = averages.optInt("logged_days", series.count { !it.isNull("kcal") && it.optDouble("kcal", 0.0) > 0 })
        val target = adherence.optDouble("target_kcal").takeIf { it > 0 } ?: series.lastOrNull { it.optDouble("target_kcal") > 0 }?.optDouble("target_kcal")
        val readout = row(center = true)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        readout.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        text(getString(R.string.insight_average), 13, parent = left)
        text(if (averages.has("kcal") && !averages.isNull("kcal")) kcalText(averages.getDouble("kcal")) else "—", 26, getColor(R.color.ink), left).instrumentType(numeric = true)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        readout.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        text(getString(R.string.insight_adherence), 13, parent = right).gravity = Gravity.END
        text(if (adherence.has("days_within")) "${adherence.getInt("days_within")} / $logged" else "—", 26, green, right).apply {
            instrumentType(numeric = true); gravity = Gravity.END }
        page.addView(SeriesPlot(this, series.map { if (it.isNull("kcal") || !it.has("kcal")) null else it.getDouble("kcal").takeIf { v -> v > 0 } }, target,
            first = series.firstOrNull()?.optString("day")?.let { localDate(it, locale) }.orEmpty(),
            last = series.lastOrNull()?.optString("day")?.let { localDate(it, locale) }.orEmpty()), LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(6) })
        text(getString(R.string.insight_adherence_note), 12)
        val macros = row(top = 4)
        for ((key, label) in listOf("protein_g" to R.string.nutr_protein, "carbs_g" to R.string.nutr_carbs, "fat_g" to R.string.nutr_fat)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            macros.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            text(getString(label), 12, parent = column)
            text(if (averages.has(key) && !averages.isNull(key)) gramText(averages.getDouble(key)) else "—", 17, getColor(R.color.ink), column).instrumentType(numeric = true)
        }
    }
    // Weight log with the trend line.
    section(R.string.insight_weight).setPadding(0, dp(22), 0, 0)
    val weights = data.weights.orEmpty().sortedBy { it.optString("day") }
    if (weights.isEmpty()) text(getString(R.string.nutr_no_weights), 14)
    else {
        val latest = weights.last()
        valueRow(getString(R.string.insight_trend_now), if (latest.isNull("trend_kg")) "—" else "${number(latest, "trend_kg")} kg", page)
        page.addView(SeriesPlot(this, emptyList(), dots = weights.map { it.optDouble("kg").takeIf(Double::isFinite) },
            line = weights.map { if (it.isNull("trend_kg")) null else it.optDouble("trend_kg") },
            first = localDate(weights.first().getString("day"), locale), last = localDate(latest.getString("day"), locale)),
            LinearLayout.LayoutParams(-1, dp(160)).apply { topMargin = dp(4) })
        text(getString(R.string.insight_weight_legend), 12)
    }
    val entry = row(center = true)
    val kgColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    entry.addView(kgColumn, LinearLayout.LayoutParams(0, -2, 1f))
    val kg = decimalField(R.string.nutr_weight_kg, weights.firstOrNull { it.optString("day") == day }?.let { number(it, "kg") }.orEmpty(), kgColumn)
    button(getString(R.string.nutr_weight_save), parent = entry) {
        val value = decimal(kg.value())
        if (value == null || value !in 20.0..400.0) kg.error = getString(R.string.nutr_invalid_weight)
        else saveFeature(FeatureWrite.WEIGHT, JSONObject().put("kg", value), day) { _, result -> insights(day, days, result) }
    }.layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(10); topMargin = dp(36) }
    // Goal progress.
    section(R.string.insight_goal).setPadding(0, dp(22), 0, 0)
    val goal = data.goal?.optJSONObject("goal") ?: data.goal?.takeIf { it.has("phase") }
    if (goal == null) text(getString(R.string.insight_no_goal), 14)
    else {
        val progress = data.goal!!
        val phase = goal.optString("phase", goal.optString("goal", "maintain"))
        val start = goal.optDouble("start_kg", progress.optDouble("start_kg"))
        val current = progress.optDouble("current_trend_kg", progress.optDouble("trend_kg"))
        val targetKg = goal.optDouble("target_kg", progress.optDouble("target_kg"))
        val fraction = if (start.isFinite() && current.isFinite() && targetKg.isFinite() && start != targetKg)
            ((current - start) / (targetKg - start)).coerceIn(0.0, 1.0) else null
        targetBar(goalName(phase), listOf(current, targetKg).joinToString(" → ") { if (it.isFinite()) "${short(it)} kg" else "—" }, fraction, page)
        progress.optString("projected_date").takeIf { it.length == 10 }?.let { valueRow(getString(R.string.insight_projected), localDate(it, locale), page) }
        goal.optString("end_date").takeIf { it.length == 10 }?.let { valueRow(getString(R.string.insight_end_date), localDate(it, locale), page) }
        goal.optDouble("rate_kg_week").takeIf { it.isFinite() }?.let { valueRow(getString(R.string.nutr_goal), getString(R.string.nutr_rate_row, short(it)), page) }
    }
    // Expenditure and the weekly check-in.
    section(R.string.nutr_program).setPadding(0, dp(22), 0, 0)
    text(getString(R.string.nutr_coach_note), 13)
    val estimate = data.expenditure?.optJSONObject("expenditure")
    if (estimate == null) text(getString(R.string.nutr_no_expenditure), 14)
    else {
        valueRow(getString(R.string.nutr_expenditure), getString(R.string.nutr_expenditure_row, number(estimate, "value_kcal")), page)
        val history = data.expenditure!!.optJSONArray("history")?.objects().orEmpty()
        history.getOrNull(history.size - 8)?.let { week ->
            val change = estimate.optDouble("value_kcal") - week.optDouble("value_kcal")
            if (change.isFinite()) valueRow(getString(R.string.insight_week_change), (if (change >= 0) "+" else "") + kcalText(change), page)
        }
        text(getString(if (estimate.optString("status") == "updating") R.string.nutr_updating else R.string.nutr_holding), 13)
    }
    val program = data.program?.optJSONObject("program")
    if (program == null) text(getString(R.string.nutr_no_program), 14)
    else {
        valueRow(getString(R.string.nutr_goal), goalName(program.optString("goal")), page)
        val recommendation = program.optJSONObject("recommended_target") ?: JSONObject()
        for ((key, label) in nutrients.entries.take(4)) {
            val field = if (key == "kcal") "calories" else key
            if (recommendation.has(field)) valueRow(getString(label), number(recommendation, field), page)
        }
        text(getString(if (data.program!!.optBoolean("applied")) R.string.nutr_applied else R.string.nutr_not_applied), 13)
        button(getString(R.string.insight_checkin)) {
            val api = featureApi()
            val body = JSONObject().put("goal", program.optString("goal", "maintain")).put("rate_kg_week", program.optDouble("rate_kg_week", 0.0))
                .put("protein_g", program.optDouble("protein_g")).put("fat_g", program.optDouble("fat_g"))
            work(getString(R.string.feature_saving), { api.send("PUT", "/api/athletes/${athleteId()}/nutrition/program", body) },
                { insights(day, days, getString(R.string.insight_checked_in)) }, { insights(day, days, it) })
        }
    }
    // Micronutrients for the day.
    section(R.string.insight_micros).setPadding(0, dp(22), 0, 0)
    text(localDate(day, locale), 13)
    val names = data.definitions.associateBy { it.optString("key") }
    val list = data.summary?.opt("nutrients").let { value ->
        when (value) {
            is JSONArray -> value.objects()
            is JSONObject -> value.keys().asSequence().map { key -> value.getJSONObject(key).put("key", key) }.toList()
            else -> emptyList()
        }
    }
    if (list.isEmpty()) text(getString(R.string.insight_no_micros), 14)
    for (item in list) {
        val definition = names[item.optString("key")] ?: JSONObject()
        val label = listOf(if (locale.language == "bg") "name_bg" else "name_en", "name_en", "name", "key")
            .firstNotNullOfOrNull { key -> (definition.optString(key).takeIf { it.isNotBlank() } ?: item.optString(key).takeIf { it.isNotBlank() && !item.isNull(key) }) }.orEmpty()
        val unit = item.optString("unit", definition.optString("unit"))
        val amount = item.optDouble("amount")
        val reference = item.optDouble("reference", definition.optDouble("reference"))
        val shown = if (amount.isFinite()) "${short(amount)} $unit" else getString(R.string.insight_missing)
        if (reference.isFinite() && reference > 0) targetBar(label, "$shown / ${short(reference)} $unit", if (amount.isFinite()) amount / reference else 0.0, page)
        else valueRow(label, shown, page)
    }
    button(getString(R.string.nutrition_title)) { nutritionHome(day) }
}
