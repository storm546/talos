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
            val perServing = recipe.optJSONObject("per_serving")?.optDouble("kcal")
                ?: (recipe.optJSONArray("items")?.objects()?.sumOf { it.optDouble("kcal", 0.0) } ?: 0.0) / portions
            text(getString(R.string.recipe_summary, short(portions), kcalText(perServing)), 13, parent = column).setPadding(0, dp(2), 0, 0)
            iconButton("recipe", getString(R.string.recipe_edit_named, recipe.optString("name")), line) {
                recipeDraft = RecipeDraft(recipe.getLong("id"), recipe.optString("name"), short(portions).replace(',', '.'),
                    recipe.optJSONArray("items")?.objects().orEmpty().toMutableList())
                recipeEditor(day)
            }
            iconButton("plus", getString(R.string.nutr_add_to, recipe.optString("name")), line) { foodLog(day, recipeAsFood(recipe)) }
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

/** A nutrient's name in the app's language (SPEC 7.4: definitions carry name_bg and name_en). */
private fun MainActivity.nutrientName(item: JSONObject, names: Map<String, JSONObject>): String {
    val definition = names[item.optString("key")] ?: JSONObject()
    return listOf(if (locale.language == "bg") "name_bg" else "name_en", "name_en", "name", "key").firstNotNullOfOrNull { key ->
        definition.optString(key).takeIf { it.isNotBlank() } ?: item.optString(key).takeIf { it.isNotBlank() && !item.isNull(key) }
    }.orEmpty()
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
    // Each item carries the model's words, a matched food (or none, then a quick add from the estimate) and a ready entry.
    val chosen = proposal.map { it.optJSONObject("entry") != null }.toMutableList()
    proposal.forEachIndexed { index, item ->
        val match = item.optJSONObject("match")
        val entry = item.optJSONObject("entry") ?: JSONObject()
        val grams = item.optDouble("grams", 0.0)
        val line = row(center = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        val name = match?.let { foodName(it) } ?: item.optString("text")
        text(name, 15, getColor(R.color.ink), column).setPadding(0, 0, 0, 0)
        val kcal = match?.let { it.optDouble("kcal", 0.0) * grams / 100 } ?: entry.optDouble("kcal", 0.0)
        text("“${item.optString("text")}” · ${gramText(grams)} · ${kcalText(kcal)}" + (if (match == null) "\n" + getString(R.string.ai_entry_no_match) else ""),
            13, parent = column).setPadding(0, dp(2), 0, 0)
        line.addView(android.widget.Switch(this).apply {
            instrumentSwitch(); isChecked = chosen[index]; isEnabled = chosen[index]
            contentDescription = name
            setOnCheckedChangeListener { _, on -> chosen[index] = on }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        divider(page, inset = false)
    }
    if (chosen.any { it }) button(getString(R.string.ai_entry_confirm), primary = true) {
        val entries = JSONArray()
        proposal.forEachIndexed { index, item ->
            if (chosen[index]) entries.put(JSONObject(item.getJSONObject("entry").toString()).put("client_id", UUID.randomUUID().toString()))
        }
        val api = featureApi()
        // The batch route answers with an array, so read it as text (post() expects an object and failed after saving).
        work(getString(R.string.feature_saving), { api.send("POST", "/api/food-logs/batch", JSONObject().put("entries", entries)) },
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
        val result = Insight(get("$base/nutrition/analytics?days=$days"),
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
    val series = analytics?.optJSONArray("daily")?.objects().orEmpty()
    if (analytics == null) text(getString(R.string.insight_unavailable), 14)
    else {
        val averages = analytics.optJSONObject("averages") ?: JSONObject()
        val adherence = analytics.optJSONObject("adherence") ?: JSONObject()  // absent without a target
        val logged = averages.optInt("logged_days", series.count { !it.isNull("kcal") && it.optDouble("kcal", 0.0) > 0 })
        val target = analytics.optDouble("target_kcal").takeIf { it > 0 }
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
    val kg = decimalField(R.string.nutr_weight_kg, weights.firstOrNull { it.optString("day") == day }?.let { number(it, "kg") }.orEmpty())
    healthWeightButton(kg)
    button(getString(R.string.nutr_weight_save)) {
        val value = decimal(kg.value())
        if (value == null || value !in 20.0..400.0) kg.error = getString(R.string.nutr_invalid_weight)
        else saveFeature(FeatureWrite.WEIGHT, JSONObject().put("kg", value), day) { _, result -> insights(day, days, result) }
    }
    button(getString(R.string.body_open)) { body() }
    // Goal progress.
    section(R.string.insight_goal).setPadding(0, dp(22), 0, 0)
    val goal = data.goal?.optJSONObject("goal")
    if (goal == null) text(getString(R.string.insight_no_goal), 14)
    else {
        val progress = data.goal!!
        fun kg(json: JSONObject, key: String) = json.optDouble(key).takeIf { !json.isNull(key) && it.isFinite() }
        val current = kg(progress, "current_trend_kg")
        val target = kg(goal, "target_weight_kg")
        val line = row(center = true)
        text(goalName(goal.optString("phase")), 17, getColor(R.color.ink), line).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        progress.optBoolean("on_track").takeIf { !progress.isNull("on_track") && progress.has("on_track") }?.let {
            statusChip(getString(if (it) R.string.insight_on_track else R.string.insight_off_track),
                if (it) R.color.ready_surface else R.color.notice, if (it) R.color.ready else R.color.warning, line)
        }
        text(listOf(current, target).filterIndexed { index, value -> index == 0 || value != null }
            .joinToString(" → ") { value -> value?.let { "${short(it)} kg" } ?: "—" }, 28, green).instrumentType(numeric = true)
        kg(progress, "remaining_kg")?.let { valueRow(getString(R.string.insight_remaining), "${short(it)} kg", page) }
        kg(progress, "trend_kg_week")?.let { valueRow(getString(R.string.insight_trend_week), "${short(it)} kg", page) }
        progress.optString("projected_date").takeIf { it.length == 10 }?.let { valueRow(getString(R.string.insight_projected), localDate(it, locale), page) }
        goal.optString("end_date").takeIf { it.length == 10 }?.let { valueRow(getString(R.string.insight_end_date), localDate(it, locale), page) }
        kg(goal, "rate_kg_week")?.let { valueRow(getString(R.string.insight_rate), "${short(it)} kg", page) }
    }
    button(getString(if (goal == null) R.string.goal_set else R.string.goal_change)) { goalForm(day, days, data) }
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
            if (recommendation.has(field)) valueRow(getString(label), metricNumber(recommendation.optDouble(field), "kcal", locale), page)
        }
        text(getString(if (data.program!!.optBoolean("applied")) R.string.nutr_applied else R.string.nutr_not_applied), 13)
        button(getString(R.string.insight_checkin)) {
            val api = featureApi()
            // The program view carries protein and fat only inside its recommendation.
            val body = JSONObject().put("goal", program.optString("goal", "maintain")).put("rate_kg_week", program.optDouble("rate_kg_week", 0.0))
                .put("protein_g", recommendation.optDouble("protein_g", 0.0)).put("fat_g", recommendation.optDouble("fat_g", 0.0))
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
    val (known, missing) = list.partition { it.has("amount") && !it.isNull("amount") }
    if (known.isEmpty()) text(getString(R.string.insight_no_micros), 14)
    for (item in known) {
        val definition = names[item.optString("key")] ?: JSONObject()
        val label = nutrientName(item, names)
        val unit = item.optString("unit", definition.optString("unit"))
        val amount = item.optDouble("amount")
        val reference = item.optDouble("reference", definition.optDouble("reference"))
        val partial = item.optInt("known_entries", 0).let { part -> item.optInt("total_entries", part).takeIf { it > part }?.let { " · $part/$it" } }.orEmpty()
        fun fine(value: Double) = if (value < 10) String.format(locale, "%.2f", value).trimEnd('0').trimEnd('.', ',') else short(value)
        val shown = "${fine(amount)} $unit$partial"
        if (reference.isFinite() && reference > 0) targetBar(label, "$shown / ${fine(reference)} $unit", if (amount.isFinite()) amount / reference else 0.0, page)
        else valueRow(label, shown, page)
    }
    if (missing.isNotEmpty()) text(getString(R.string.insight_missing_list, missing.joinToString(", ") { nutrientName(it, names) }), 12)
    if (known.any { it.optInt("total_entries") > it.optInt("known_entries") }) text(getString(R.string.insight_partial_note), 12)
    button(getString(R.string.nutrition_title)) { nutritionHome(day) }
}

/** The athlete's own goal (SPEC 7.4): phase, target weight and end date, on the same endpoint and guardrails as the coach. */
private fun MainActivity.goalForm(day: String, days: Int, data: Insight, message: String? = null) {
    layout(getString(R.string.goal_form_title), "goal_form")
    if (message != null) notice(message, caution = true)
    text(getString(R.string.nutr_coach_note), 14)
    val goal = data.goal?.optJSONObject("goal")
    val trend = data.goal?.optDouble("current_trend_kg")?.takeIf { it.isFinite() }
    var phase = goal?.optString("phase")?.takeIf { it in listOf("maintain", "lose", "gain") } ?: "maintain"
    var end = goal?.optString("end_date")?.takeIf { it.length == 10 } ?: LocalDate.now().plusWeeks(8).toString()
    trend?.let { valueRow(getString(R.string.insight_trend_now), "${short(it)} kg", page) }
    val phases = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; background = shape(getColor(R.color.hover), 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }
    page.addView(phases, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val target = decimalField(R.string.goal_target_weight, goal?.takeIf { !it.isNull("target_weight_kg") }?.let { number(it, "target_weight_kg") }.orEmpty())
    val endButton = button(getString(R.string.goal_end_date, localDate(end, locale))) {}
    endButton.setOnClickListener {
        val date = LocalDate.parse(end)
        android.app.DatePickerDialog(this, { _, year, month, dayOfMonth ->
            end = LocalDate.of(year, month + 1, dayOfMonth).toString(); endButton.text = getString(R.string.goal_end_date, localDate(end, locale))
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    fun sync() { target.isEnabled = phase != "maintain"; endButton.isEnabled = phase != "maintain" }
    for (option in listOf("maintain", "lose", "gain")) phases.addView(RadioButton(this).apply {
        // Short labels on one line: "Decrease weight" wrapped and was clipped in the segment on a phone.
        id = android.view.View.generateViewId(); text = getString(when (option) { "lose" -> R.string.goal_short_lose
            "gain" -> R.string.goal_short_gain; else -> R.string.goal_short_maintain }); textSize = 14f; segment(); maxLines = 1
        contentDescription = goalName(option)
        isChecked = option == phase
        setOnClickListener { phase = option; sync() }
    }, RadioGroup.LayoutParams(0, -2, 1f))
    sync()
    // Protein and fat stay as agreed: the program's recommendation, else the current target.
    val recommended = data.program?.optJSONObject("program")?.optJSONObject("recommended_target")
    val current = data.summary?.optJSONObject("target")
    fun grams(key: String) = listOfNotNull(recommended, current).firstOrNull { it.has(key) && !it.isNull(key) }?.let { number(it, key) }.orEmpty()
    val macros = fieldRow(listOf(R.string.macro_protein_short, R.string.macro_fat_short), listOf(grams("protein_g"), grams("fat_g")))
    button(getString(R.string.goal_save), primary = true) {
        val body = JSONObject().put("phase", phase)
        if (phase != "maintain") {
            val kg = decimal(target.value())
            if (kg == null || kg !in 20.0..400.0) { target.error = getString(R.string.nutr_invalid_weight); return@button }
            if (trend != null && (kg < trend) != (phase == "lose")) { target.error = getString(R.string.goal_wrong_direction); return@button }
            if (LocalDate.parse(end) < LocalDate.now().plusWeeks(1)) { snack(getString(R.string.goal_too_soon)); return@button }
            body.put("target_weight_kg", kg).put("end_date", end)
        }
        val values = macros.map { decimal(it.value()) }
        values.forEachIndexed { index, value -> if (value == null || value < 0 || value > 500) { macros[index].error = getString(R.string.nutr_invalid_macro); return@button } }
        body.put("protein_g", values[0]).put("fat_g", values[1])
        val api = featureApi()
        work(getString(R.string.feature_saving), {
            try { api.send("PUT", "/api/athletes/${athleteId()}/nutrition/goal", body) }
            catch (e: ApiError) { if (e.status == 422) throw AppError(R.string.goal_rejected) else throw e }
        }, { insights(day, days, getString(R.string.goal_saved)) }, { goalForm(day, days, data, it) })
    }
}
