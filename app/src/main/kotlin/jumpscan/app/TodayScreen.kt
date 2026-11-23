package jumpscan.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Today: the home screen (SPEC 10.2). One request to /api/me/today fills every card; the last good answer is cached
// on the phone with its time, so the screen works signed out and offline. Card order and hidden cards stay on the phone.

internal val TODAY_CARDS = listOf("readiness", "plan", "checkin", "nutrition", "training", "weight", "records",
    "next_session", "messages", "jump")
/** Server events that change a Today card; the whole aggregate is reloaded and only changed cards are redrawn. */
internal val TODAY_EVENTS = setOf("checkin", "jump", "form", "comment", "record", "measurement")

private fun Context.todayPrefs() = getSharedPreferences("today", Context.MODE_PRIVATE)

internal fun Context.todayOrder(): List<String> {
    val saved = todayPrefs().getString("order", "").orEmpty().split(',').filter { it in TODAY_CARDS }
    return (saved + TODAY_CARDS).distinct()
}

internal fun Context.hiddenCards(): Set<String> =
    todayPrefs().getString("hidden", "").orEmpty().split(',').filter { it in TODAY_CARDS }.toSet()

/** Swap the card with its visible neighbour (`by` is -1 or 1); hidden cards keep their places. */
internal fun Context.moveCard(key: String, by: Int) {
    val order = todayOrder().toMutableList()
    val visible = order.filter { it !in hiddenCards() }
    val neighbour = visible.getOrNull(visible.indexOf(key) + by) ?: return
    val (a, b) = order.indexOf(key) to order.indexOf(neighbour)
    order[a] = neighbour; order[b] = key
    todayPrefs().edit().putString("order", order.joinToString(",")).apply()
}

internal fun Context.hideCard(key: String, hidden: Boolean) {
    val next = if (hidden) hiddenCards() + key else hiddenCards() - key
    todayPrefs().edit().putString("hidden", next.joinToString(",")).apply()
}

/** The last good `today` answer for this account and server, with the wall-clock time it arrived. */
internal fun Context.cachedToday(): Pair<JSONObject, Long>? {
    val account = getSharedPreferences("account", Context.MODE_PRIVATE)
    val prefs = todayPrefs()
    if (prefs.getLong("user", -1) != account.getLong("user_id", 0) || prefs.getString("server", null) != account.getString("server", null))
        return null
    val body = prefs.getString("body", null) ?: return null
    return runCatching { JSONObject(body) to prefs.getLong("at", 0) }.getOrNull()
}

internal fun Context.saveToday(body: JSONObject) {
    val account = getSharedPreferences("account", Context.MODE_PRIVATE)
    todayPrefs().edit().putString("body", body.toString()).putLong("at", System.currentTimeMillis())
        .putLong("user", account.getLong("user_id", 0)).putString("server", account.getString("server", null)).apply()
    TodayWidget.refresh(this)
}

private fun clockOf(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun MainActivity.home(message: String? = null) {
    layout(getString(R.string.ui_today), "home", headline = false)
    val signedIn = token().isNotEmpty()
    val hour = LocalTime.now().hour
    val greeting = getString(when { hour < 12 -> R.string.today_morning; hour < 18 -> R.string.today_afternoon; else -> R.string.today_evening })
    val name = getSharedPreferences("account", Context.MODE_PRIVATE).getString("name", "").orEmpty().substringBefore(' ')
    eyebrow(localDate(todayLocal(), locale)).setPadding(0, dp(16), 0, 0)
    text(if (signedIn && name.isNotEmpty()) getString(R.string.today_greeting_named, greeting, name) else greeting, 30).apply {
        instrumentType(display = true)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    if (message != null) notice(message, caution = true)
    if (!signedIn) {
        card().apply {
            section(R.string.today_signed_out_title, this)
            text(getString(R.string.today_signed_out_text), 14, parent = this)
            button(getString(R.string.sign_in), primary = true, parent = this) { login() }
        }
        todayCard("jump", null, page)
        phoneTools()
        return
    }
    val status = text(getString(R.string.today_loading), 12).apply { tag = "today-status" }
    val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "today-cards" }
    page.addView(cards)
    val cached = cachedToday()
    drawToday(cards, cached?.first)
    if (cached != null) status.text = getString(R.string.today_updated, clockOf(cached.second))
    val hidden = todayOrder().filter { it in hiddenCards() }
    if (hidden.isNotEmpty()) {
        eyebrow(getString(R.string.today_hidden))
        val group = settingsGroup()
        hidden.forEachIndexed { index, key ->
            if (index > 0) divider(group)
            settingRow(cardIcon(key), getString(cardTitle(key)), getString(R.string.today_show), group) { hideCard(key, false); home() }
        }
    }
    pendingControls()
    refreshToday()
}

/** Reload the aggregate; redraw only the cards whose section changed. Offline keeps the cached cards and says so. */
internal fun MainActivity.refreshToday() {
    val cards = page.findViewWithTag<LinearLayout>("today-cards") ?: return
    val status = page.findViewWithTag<android.widget.TextView>("today-status")
    val api = featureApi()
    later(cards, { api.obj("/api/me/today?day=${todayLocal()}") }) { fresh ->
        if (fresh == null) {
            val cached = cachedToday()
            status?.text = if (cached == null) getString(R.string.today_offline_empty) else getString(R.string.today_offline, clockOf(cached.second))
            if (cached == null && page.findViewWithTag<View>("phone-tools") == null) phoneTools()
            return@later
        }
        saveToday(fresh)
        status?.text = getString(R.string.today_updated, clockOf(System.currentTimeMillis()))
        drawToday(cards, fresh)
    }
}

private fun sectionOf(key: String, data: JSONObject?): String? = when (key) {
    "jump" -> ""
    "messages" -> data?.opt("unread")?.toString()
    else -> data?.opt(key)?.toString()
}

private fun MainActivity.drawToday(cards: LinearLayout, data: JSONObject?) {
    @Suppress("UNCHECKED_CAST")
    val shown = (cards.getTag(R.id.today_sections) as? MutableMap<String, String?>) ?: mutableMapOf<String, String?>().also { cards.setTag(R.id.today_sections, it) }
    val keys = todayOrder().filter { it !in hiddenCards() }
    keys.forEachIndexed { index, key ->
        val section = sectionOf(key, data)
        val old = cards.findViewWithTag<View>("card-$key")
        if (old != null && shown[key] == section) return@forEachIndexed
        shown[key] = section
        val view = todayCard(key, data, cards)
        cards.removeView(view)
        if (old != null) { val at = cards.indexOfChild(old); cards.removeView(old); cards.addView(view, at) }
        else cards.addView(view, minOf(index, cards.childCount))
        view.arrive(if (old == null) index * 18L else 0L)
    }
}

private fun cardTitle(key: String) = when (key) {
    "readiness" -> R.string.rd_title
    "plan" -> R.string.today_plan
    "checkin" -> R.string.today_checkin
    "nutrition" -> R.string.nutrition_title
    "training" -> R.string.today_training
    "weight" -> R.string.tool_weight
    "records" -> R.string.today_records
    "next_session" -> R.string.today_next_session
    "messages" -> R.string.today_messages
    else -> R.string.tool_jump
}

private fun cardIcon(key: String) = when (key) {
    "readiness", "jump" -> "legs"
    "plan", "training" -> "training"
    "checkin" -> "checkin"
    "nutrition" -> "food"
    "weight", "records" -> "insights"
    "next_session" -> "club"
    else -> "assistant"
}

private fun MainActivity.openCard(key: String) = when (key) {
    "readiness" -> readinessCheck()
    "plan" -> trainingHome()
    "checkin" -> checkin()
    "nutrition" -> nutritionHome()
    "training" -> sessionsScreen()
    "weight" -> insights(days = 30)
    "records" -> records()
    "next_session" -> clubHome()
    "messages" -> feedback()
    else -> jumpHome()
}

/** One Today card: a tappable surface with its title, an arrange button and the section's numbers. */
internal fun MainActivity.todayCard(key: String, data: JSONObject?, parent: LinearLayout): LinearLayout {
    val hero = key == "readiness"
    val panel = card(parent, hero = hero).apply {
        tag = "card-$key"
        isClickable = true; isFocusable = true
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.hover)), null,
            shape(Color.WHITE, 14))
        setOnClickListener { openCard(key) }
    }
    val ink = getColor(if (hero) R.color.on_hero else R.color.ink)
    val head = row(panel, center = true)
    text(getString(cardTitle(key)).uppercase(locale), 12, getColor(if (hero) R.color.signal else R.color.accent), head).apply {
        instrumentType(display = true); letterSpacing = .12f
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    if (token().isNotEmpty()) head.addView(ImageView(this).apply {
        setImageDrawable(lineIcon("more", getColor(if (hero) R.color.on_hero else R.color.muted)))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = getString(R.string.today_arrange, getString(cardTitle(key)))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.hover)), null, null)
        setOnClickListener { arrangeSheet(key) }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    val section = data?.opt(if (key == "messages") "unread" else key)?.takeIf { it != JSONObject.NULL }
    fun line(value: String, size: Int = 14, color: Int = getColor(if (hero) R.color.on_hero else R.color.muted)) = text(value, size, color, panel)
    fun big(value: String, color: Int = ink) = panel.addView(Measurement(this).apply {
        text = value; textSize = if (hero) 34f else 28f; setTextColor(color); instrumentType(numeric = true)
    })
    fun actions(vararg entries: Pair<Int, () -> Unit>) {
        val strip = row(panel)
        entries.forEachIndexed { index, (label, action) ->
            button(getString(label), primary = index == 0, parent = strip, look = if (index == 0) ActionTone.TONAL else null) { action() }
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); if (index > 0) marginStart = dp(8) }
        }
    }
    if (key == "jump") {
        line(getString(R.string.today_jump_note))
        actions(R.string.today_jump_record to { jumpHome() })
        return panel
    }
    if (section == null && key !in setOf("weight", "records", "next_session", "messages")) { line(getString(R.string.today_no_data)); return panel }
    when (key) {
        "readiness" -> (section as? JSONObject)?.let { readiness ->
            val state = readiness.getString("status")
            text(readinessLabel(state), 30, Color.WHITE, panel).instrumentType(display = true)
            line(getString(when (state) {
                "good" -> R.string.rd_summary_good; "take_care" -> R.string.rd_summary_take_care
                "still_learning" -> R.string.rd_summary_still_learning; "no_data" -> R.string.rd_summary_no_data
                else -> R.string.rd_summary_normal
            }))
            val strip = row(panel)
            readiness.getJSONArray("factors").objects().filter { !it.isNull("value") }.take(4).forEach { factor ->
                val (fill, ink) = when (factor.getString("state")) {
                    "worse" -> R.color.notice to R.color.warning; "better" -> R.color.ready_surface to R.color.ready
                    else -> R.color.segment_selected to R.color.on_hero
                }
                statusChip(factorValue(factor) ?: "—", fill, ink, strip).apply {
                    contentDescription = getString(factorLabels.getValue(factor.getString("key"))) + ": " + text + ", " + factorState(factor)
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(6); topMargin = dp(8) }
                }
            }
        } ?: line(getString(R.string.today_no_data))
        "plan" -> {
            val plan = section as? JSONObject
            val items = plan?.getJSONArray("items")?.objects().orEmpty()
            if (items.isEmpty()) line(getString(R.string.today_plan_empty))
            items.take(4).forEach { item ->
                val reps = item.getInt("reps").let { low -> item.optInt("reps_max", low).let { if (it > low) "$low–$it" else "$low" } }
                val load = if (item.isNull("load_kg")) "" else " · " + getString(R.string.feature_kg, short(item.getDouble("load_kg")))
                valueRow(item.getString("exercise"), getString(R.string.today_sets_reps, item.getInt("sets"), reps) + load, panel)
            }
            val more = (plan?.optInt("more") ?: 0) + (items.size - 4).coerceAtLeast(0)
            if (more > 0) line(getString(R.string.today_plan_more, more), 13)
            if (plan?.optBoolean("logged") == true) chip(getString(R.string.today_plan_logged), panel)
            actions(R.string.today_start_workout to { openTool(toolGroups().flatMap { it.second }.first { it.key == "workout" }) })
        }
        "checkin" -> {
            val checkin = section as JSONObject
            if (checkin.getBoolean("done")) {
                big(getString(R.string.today_checkin_done))
                line(getString(R.string.today_wellbeing, metricNumber(checkin.getDouble("wellbeing"), "training_minutes", locale), checkin.getInt("out_of")))
            } else {
                line(getString(R.string.today_checkin_missing), 15, ink)
                actions(R.string.today_check_in to { checkin() })
            }
        }
        "nutrition" -> {
            val food = section as JSONObject
            val eaten = food.getJSONObject("eaten")
            val target = food.optJSONObject("target")
            val line = row(panel, center = true)
            val kcal = eaten.getDouble("kcal")
            line.addView(Dial(this).apply {
                caption = metricNumber(kcal, "kcal", locale); unit = getString(R.string.unit_kcal)
                show(if (target == null) 0 else (kcal / target.getDouble("kcal") * 100).toInt())
            }, LinearLayout.LayoutParams(dp(112), dp(104)))
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            line.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val protein = eaten.getDouble("protein_g")
            if (target != null) {
                text(getString(R.string.today_kcal_of, metricNumber(target.getDouble("kcal"), "kcal", locale)), 14, parent = column)
                // A label and a bar; the empty reading line under the label is not needed here.
                targetBar(getString(R.string.today_protein, short(protein), short(target.getDouble("protein_g"))), "",
                    protein / target.getDouble("protein_g"), column).visibility = View.GONE
            } else text(getString(R.string.today_protein_plain, short(protein)), 14, parent = column)
            text(getString(R.string.today_food_logs, food.getInt("logs")), 12, parent = column)
            actions(R.string.today_scan_food to { scanBarcode() }, R.string.today_add_food to { foods() })
        }
        "training" -> {
            val training = section as JSONObject
            big(metricNumber(training.getDouble("total_load"), "kcal", locale) + " " + getString(R.string.today_load_unit))
            line(getString(R.string.today_sessions, training.getJSONArray("sessions").length()))
            val ratio = training.getJSONObject("acute_chronic")
            line(if (ratio.optBoolean("still_learning") || ratio.isNull("band")) getString(R.string.today_ratio_learning)
                else getString(R.string.rd_ratio) + ": " + String.format(locale, "%.2f", ratio.getDouble("ratio")) + " · " + getString(when (ratio.getString("band")) {
                    "well_above" -> R.string.rd_band_well_above; "above" -> R.string.rd_band_above
                    "below" -> R.string.rd_band_below; else -> R.string.rd_band_similar
                }))
        }
        "weight" -> {
            val weight = section as? JSONObject
            if (weight == null) line(getString(R.string.today_weight_empty)) else {
                big(getString(R.string.feature_kg, short(weight.getDouble("kg"))))
                val trend = if (weight.isNull("trend_kg")) "—" else short(weight.getDouble("trend_kg"))
                line(getString(R.string.today_weight_trend, trend, localDate(weight.getString("day"), locale)))
            }
        }
        "records" -> {
            val found = (section as? org.json.JSONArray)?.objects().orEmpty()
            if (found.isEmpty()) line(getString(R.string.today_records_empty))
            found.take(3).forEach { record -> valueRow(recordLabel(record), recordValue(record), panel) }
        }
        "next_session" -> {
            val session = section as? JSONObject
            if (session == null) line(getString(R.string.today_session_empty)) else {
                text(session.getString("title"), 18, ink, panel).instrumentType(display = true)
                line(localDateTime(session.getString("starts_at"), locale) + (session.optString("place").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            }
        }
        "messages" -> {
            val unread = (section as? Number)?.toInt() ?: 0
            if (unread == 0) line(getString(R.string.today_messages_none)) else {
                big(unread.toString(), getColor(R.color.accent))
                line(getString(R.string.today_messages_unread, unread))
            }
        }
    }
    return panel
}

private fun MainActivity.recordLabel(record: JSONObject): String = when (record.getString("measure")) {
    "jump_height" -> getString(R.string.records_height)
    "flight_time" -> getString(R.string.records_flight)
    else -> record.optString(if (locale.language == "bg") "name_bg" else "name_en").takeIf { it.isNotBlank() && it != "null" }
        ?: record.optString("exercise").takeIf { it.isNotBlank() && it != "null" } ?: getString(R.string.ft_records)
}

/** Phone-only tools for signed-out or offline use: each works without an account. */
private fun MainActivity.phoneTools() {
    eyebrow(getString(R.string.today_phone_tools)).tag = "phone-tools"
    val all = toolGroups().flatMap { it.second }.associateBy { it.key }
    toolRow(*listOf("timer", "calculators", "cardio", "form").mapNotNull { all[it] }
        .map { tool -> UiTile(tool.label, tool.icon) { openTool(tool) } }.toTypedArray())
}

private fun MainActivity.arrangeSheet(key: String) {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
    }
    section(cardTitle(key), panel)
    val order = todayOrder().filter { it !in hiddenCards() }
    val at = order.indexOf(key)
    fun choice(label: Int, enabled: Boolean, action: () -> Unit) = button(getString(label), parent = panel) {
        dialog.dismiss(); action(); home()
    }.apply { isEnabled = enabled }
    choice(R.string.today_move_up, at > 0) { moveCard(key, -1) }
    choice(R.string.today_move_down, at < order.lastIndex) { moveCard(key, 1) }
    choice(R.string.today_hide, true) { hideCard(key, true) }
    dialog.setContentView(android.widget.ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
}
