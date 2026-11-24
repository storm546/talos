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

/** Today's order: the readiness hero, the tiles (GLANCE, two per row), then today's plan, next session and messages. */
internal val TODAY_CARDS = listOf("readiness", "plan", "checkin", "sleep", "nutrition", "training", "weight", "steps", "body", "form",
    "week", "records", "next_session", "messages")
/** Server events that change a Today card; the whole aggregate is reloaded and only changed cards are redrawn. */
internal val TODAY_EVENTS = setOf("checkin", "jump", "form", "comment", "record", "measurement", "adjustment")

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
    val signedIn = token().isNotEmpty()
    layout(getString(R.string.ui_today), "home", headline = false, menu = if (!signedIn) null else { anchor ->
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(R.string.today_refresh).setOnMenuItemClickListener { pullRefresh(); true }
            menu.add(R.string.today_arrange_all).setOnMenuItemClickListener { arrangeAll(); true }
        }.show()
    })
    val hour = LocalTime.now().hour
    val greeting = getString(when { hour < 12 -> R.string.today_morning; hour < 18 -> R.string.today_afternoon; else -> R.string.today_evening })
    val name = getSharedPreferences("account", Context.MODE_PRIVATE).getString("name", "").orEmpty().substringBefore(' ')
    eyebrow(localDate(todayLocal(), locale)).setPadding(0, dp(16), 0, 0)
    text(if (signedIn && name.isNotEmpty()) getString(R.string.today_greeting_named, greeting, name) else greeting, 26).apply {
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
    // The four things done most often, one tap each (SPEC 10.9).
    toolRow(UiTile(R.string.today_quick_photo, "camera") { openTool("photo") }, UiTile(R.string.today_check_in, "checkin") { checkin() },
        UiTile(R.string.workout_title, "training") { openTool("workout") }, UiTile(R.string.ui_technique, "form") { formHome() })
    val status = text(getString(R.string.today_loading), 12).apply { tag = "today-status"; setPadding(0, dp(12), 0, 0) }
    briefingCard()
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
    healthToday(cards)
    pendingControls()
    refreshToday()
    // Pull down at the top of the page to reload; the system overscroll stretch is the visual, the status line the answer.
    val scroll = page.parent as android.widget.ScrollView
    var lastY = Float.NaN
    var pulled = 0f
    scroll.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                pulled = if (!lastY.isNaN() && scroll.scrollY == 0) pulled + event.rawY - lastY else 0f
                lastY = event.rawY
                if (pulled > dp(96)) { pulled = Float.NEGATIVE_INFINITY; pullRefresh() }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { lastY = Float.NaN; pulled = 0f }
        }
        false
    }
}

private fun MainActivity.pullRefresh() {
    page.findViewWithTag<android.widget.TextView>("today-status")?.text = getString(R.string.today_loading)
    page.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    refreshToday()
}

/** Reload the aggregate; redraw only the cards whose section changed. Offline keeps the cached cards and says so. */
internal fun MainActivity.refreshToday() {
    val cards = page.findViewWithTag<LinearLayout>("today-cards") ?: return
    val status = page.findViewWithTag<android.widget.TextView>("today-status")
    val api = featureApi()
    later(cards, { api.obj("/api/me/today?day=${todayLocal()}&language=${language()}") }) { fresh ->
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

private fun MainActivity.language() = if (locale.language == "bg") "bg" else "en"

/** The findings for one card, in the server's order. */
private fun findingsFor(key: String, data: JSONObject?) = data?.optJSONArray("findings")?.objects().orEmpty().filter { it.optString("card") == key }

private fun sectionOf(key: String, data: JSONObject?): String? = when (key) {
    "jump" -> ""
    "messages" -> data?.opt("unread")?.toString()
    // The flag changes the readiness and plan cards too.
    "readiness", "plan" -> data?.opt(key)?.toString() + data?.opt("adjustment")
    else -> data?.opt(key)?.toString()
} + findingsFor(key, data).joinToString()

/** Draw Today again, for phone-only additions such as Health Connect steps. */
internal fun MainActivity.redrawCard(cards: LinearLayout, @Suppress("UNUSED_PARAMETER") key: String) = drawToday(cards, cachedToday()?.first)

/** The whole Today: hero, tiles two per row, then the plan, next session and messages when they have something. */
private fun MainActivity.drawToday(cards: LinearLayout, data: JSONObject?) {
    val section = TODAY_CARDS.joinToString { sectionOf(it, data).toString() } + healthSteps
    if (cards.getTag(R.id.today_sections) == section) return
    cards.setTag(R.id.today_sections, section)
    cards.removeAllViews()
    val keys = todayOrder().filter { it !in hiddenCards() }
    if ("readiness" in keys) todayCard("readiness", data, cards)
    glanceGrid(keys.filter { it in GLANCE }.mapNotNull { key ->
        glanceTile(key, data, findingsFor(key, data), getString(cardTitle(key))) { openCard(key) }
    }, cards)
    for (key in keys.filter { it !in GLANCE && it != "readiness" }) {
        val plan = data?.optJSONObject("plan")
        val show = when (key) {
            "plan" -> plan != null && (plan.optJSONArray("items")?.length() ?: 0) + plan.optInt("more") > 0 || plan?.isNull("adjusted") == false
            "next_session" -> data?.optJSONObject("next_session") != null
            "messages" -> (data?.optInt("unread") ?: 0) > 0
            else -> false
        }
        if (show) todayCard(key, data, cards)
    }
    cards.arrive()
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
    "week" -> R.string.week_title
    "sleep" -> R.string.today_sleep
    "steps" -> R.string.today_steps
    "body" -> R.string.body_title
    "form" -> R.string.ui_technique
    else -> R.string.tool_jump
}

private fun cardIcon(key: String) = when (key) {
    "readiness", "jump" -> "legs"
    "plan", "training" -> "training"
    "checkin" -> "checkin"
    "nutrition" -> "food"
    "weight", "records", "week", "body" -> "insights"
    "sleep" -> "checkin"
    "steps" -> "conditioning"
    "form" -> "form"
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
    "week" -> weekScreen()
    "sleep" -> readinessCheck()
    "steps" -> profile()
    "body" -> body()
    "form" -> techniqueHistory()
    else -> jumpHome()
}

/** One Today card: a tappable surface with its title and the section's numbers; arranging lives in the header menu. */
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
    val section = data?.opt(if (key == "messages") "unread" else key)?.takeIf { it != JSONObject.NULL }
    fun line(value: String, size: Int = 14, color: Int = getColor(if (hero) R.color.on_hero else R.color.muted)) = text(value, size, color, panel)
    fun big(value: String, color: Int = ink) = panel.addView(Measurement(this).apply {
        text = value; textSize = if (hero) 34f else 28f; setTextColor(color); instrumentType(numeric = true)
    })
    var deferred: (() -> Unit)? = null
    fun strip(entries: Array<out Pair<Int, () -> Unit>>) {
        val strip = row(panel)
        entries.forEachIndexed { index, (label, action) ->
            button(getString(label), primary = index == 0, parent = strip, look = if (index == 0) ActionTone.TONAL else null) { action() }
                .layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); if (index > 0) marginStart = dp(8) }
        }
    }
    // Actions go last, under the card's finding.
    fun actions(vararg entries: Pair<Int, () -> Unit>) { deferred = { strip(entries) } }
    if (key == "jump") {
        line(getString(R.string.today_jump_note))
        strip(arrayOf(R.string.today_jump_record to { jumpHome() }))
        return panel
    }
    val notes = findingsFor(key, data)
    if (section == null && notes.isEmpty() && key !in setOf("weight", "records", "next_session", "messages")) {
        line(getString(R.string.today_no_data)); return panel
    }
    when (key) {
        "readiness" -> (section as? JSONObject)?.let { readiness ->
            val state = readiness.getString("status")
            text(readinessLabel(state), 30, Color.WHITE, panel).instrumentType(display = true)
            if (notes.isEmpty()) line(getString(when (state) {
                "good" -> R.string.rd_summary_good; "take_care" -> R.string.rd_summary_take_care
                "still_learning" -> R.string.rd_summary_still_learning; "no_data" -> R.string.rd_summary_no_data
                else -> R.string.rd_summary_normal
            }))
            val shown = readiness.getJSONArray("factors").objects().filter { !it.isNull("value") && it.getString("key") != "adjustment" }.take(4)
            // The chips show numbers only; one caption names them in the same order so nothing is unlabelled.
            if (shown.isNotEmpty()) line(shown.joinToString(" · ") { getString(factorLabels.getValue(it.getString("key"))) }, 12)
                .setPadding(0, dp(10), 0, 0)
            val strip = row(panel)
            shown.forEach { factor ->
                val (fill, ink) = when (factor.getString("state")) {
                    "worse" -> R.color.notice to R.color.warning; "better" -> R.color.ready_surface to R.color.ready
                    else -> R.color.segment_selected to R.color.on_hero
                }
                statusChip(factorValue(factor) ?: "—", fill, ink, strip).apply {
                    contentDescription = getString(factorLabels.getValue(factor.getString("key"))) + ": " + text + ", " + factorState(factor)
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = dp(6); topMargin = dp(8) }
                }
            }
            val flag = data?.optJSONObject("adjustment")
            if (flag != null) line(getString(R.string.adj_flag, adjustLabel(flag.getString("reason")).lowercase(locale),
                adjustLabel(flag.getString("mode")).lowercase(locale)), 14, Color.WHITE)
            // A quiet text action: the hero stays short (SPEC 10.9).
            deferred = { button(getString(if (flag == null) R.string.adj_question else R.string.adj_change), parent = panel,
                look = ActionTone.TEXT) { adjustmentSheet(flag) }.apply { setTextColor(getColor(R.color.signal)); gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0) } }
        } ?: line(getString(R.string.today_no_data))
        "plan" -> {
            val plan = section as? JSONObject
            val items = plan?.getJSONArray("items")?.objects().orEmpty()
            when (plan?.optString("adjusted")) {
                "rest" -> { text(getString(R.string.adj_rest_day), 18, ink, panel).instrumentType(display = true); line(getString(R.string.adj_rest_note)) }
                "lighter" -> chip(getString(R.string.adj_lighter_note), panel)
                else -> if (items.isEmpty()) line(getString(R.string.today_plan_empty))
            }
            items.take(4).forEach { item ->
                val reps = item.getInt("reps").let { low -> item.optInt("reps_max", low).let { if (it > low) "$low–$it" else "$low" } }
                val load = if (item.isNull("load_kg")) "" else " · " + getString(R.string.feature_kg, short(item.getDouble("load_kg")))
                // A single repetition per set is a hold (e.g. plank): "2 sets" reads better than "2 × 1".
                val volume = if (reps == "1") getString(R.string.today_sets_only, item.getInt("sets"))
                    else getString(R.string.today_sets_reps, item.getInt("sets"), reps)
                valueRow(item.getString("exercise"), volume + load, panel)
            }
            val more = (plan?.optInt("more") ?: 0) + (items.size - 4).coerceAtLeast(0)
            if (more > 0) line(getString(R.string.today_plan_more, more), 13)
            if (plan?.optBoolean("logged") == true) chip(getString(R.string.today_plan_logged), panel)
            actions(R.string.today_start_workout to { openTool("workout") })
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
    // The conclusion under the numbers, as a title; tapping the card explains it (SPEC 10.9).
    notes.forEach { note ->
        text(note.optString("title"), 16, toneColor(note.optString("tone"), hero), panel).apply { instrumentType(display = true); setPadding(0, dp(10), 0, 0) }
    }
    if (notes.isNotEmpty()) panel.setOnClickListener { findingSheet(getString(cardTitle(key)), notes) { openCard(key) } }
    deferred?.invoke()
    return panel
}

internal fun MainActivity.recordLabel(record: JSONObject): String = when (record.getString("measure")) {
    "jump_height" -> getString(R.string.records_height) + recordQualifier(record)
    "flight_time" -> getString(R.string.records_flight) + recordQualifier(record)
    "balance_stance" -> getString(R.string.ft_balance)
    "sprint_time" -> getString(R.string.ft_speed)
    "agility_time" -> getString(R.string.ft_agility)
    else -> record.optString(if (locale.language == "bg") "name_bg" else "name_en").takeIf { it.isNotBlank() && it != "null" }
        ?: record.optString("exercise").takeIf { it.isNotBlank() && it != "null" } ?: getString(R.string.ft_records)
}

/** The day's findings phrased by the club's language model; hidden when there is no model or no answer (SPEC 10.9). */
private fun MainActivity.briefingCard() {
    val panel = card().apply { tag = "today-briefing" }
    val head = row(panel, center = true)
    text(getString(R.string.today_briefing).uppercase(locale), 12, getColor(R.color.accent), head).apply {
        instrumentType(display = true); letterSpacing = .12f
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    val body = text(getString(R.string.today_briefing_loading), 15, getColor(R.color.muted), panel)
    val api = featureApi()
    val host = page
    // Its own thread: the model can take half a minute, and the cards must not wait behind it.
    briefingWork.submit {
        val answer = runCatching { api.obj("/api/me/briefing?day=${todayLocal()}&language=${language()}", readTimeoutMs = 75_000) }.getOrNull()
        runOnUiThread { if (!isDestroyed && page === host && panel.isAttachedToWindow) showBriefing(panel, body, answer) }
    }
}

private val briefingWork = java.util.concurrent.Executors.newSingleThreadExecutor()

private fun MainActivity.showBriefing(panel: LinearLayout, body: android.widget.TextView, answer: JSONObject?) {
    val said = answer?.optString("text")?.takeIf { !answer.isNull("text") && it.isNotBlank() }
    if (said == null) { (panel.parent as? LinearLayout)?.removeView(panel); return }
    body.text = said
    body.setTextColor(getColor(R.color.ink))
    text(getString(R.string.today_briefing_note), 12, parent = panel).setPadding(0, dp(8), 0, 0)
    button(getString(R.string.today_ask), parent = panel, look = ActionTone.TONAL) { assistantScreen(draft = getString(R.string.today_ask_draft)) }
        .layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    panel.arrive()
}

/** Phone-only tools for signed-out or offline use: each works without an account. */
private fun MainActivity.phoneTools() {
    eyebrow(getString(R.string.today_phone_tools)).tag = "phone-tools"
    toolRow(*listOf("timer", "calculators", "cardio", "form").mapNotNull { Tools[it] }
        .map { tool -> UiTile(tool.label, tool.icon) { openTool(tool) } }.toTypedArray())
}

/** The header menu's "Arrange cards": pick a visible card, then move or hide it in its own sheet. */
private fun MainActivity.arrangeAll() {
    val dialog = Dialog(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(24))
        background = raisedSurface()
    }
    section(R.string.today_arrange_all, panel)
    val group = settingsGroup(panel)
    todayOrder().filter { it !in hiddenCards() }.forEachIndexed { index, key ->
        if (index > 0) divider(group)
        settingRow(cardIcon(key), getString(cardTitle(key)), null, group) { dialog.dismiss(); arrangeSheet(key) }
            .contentDescription = getString(R.string.today_arrange, getString(cardTitle(key)))
    }
    dialog.setContentView(android.widget.ScrollView(this).apply { addView(panel) })
    dialog.window?.apply { setGravity(Gravity.BOTTOM); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2) }
    dialog.show()
    panel.arrive()
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
