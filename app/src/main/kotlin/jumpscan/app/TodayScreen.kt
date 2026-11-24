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

/** Today's stored order; drawn as the readiness panel, the day's rings, then one row per metric (todayDrawOrder). */
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

private fun MainActivity.stamp(state: String) = localDate(todayLocal(), locale) + "  ·  " + state

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
    text(if (signedIn && name.isNotEmpty()) getString(R.string.today_greeting_named, greeting, name) else greeting, 22).apply {
        instrumentType(display = true); setPadding(0, dp(10), 0, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    val status = text(localDate(todayLocal(), locale), 12).apply { tag = "today-status"; instrumentType(numeric = true); setPadding(0, dp(2), 0, 0) }
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
    actionChips(UiTile(R.string.today_quick_photo, "camera") { openTool("photo") }, UiTile(R.string.today_check_in, "checkin") { checkin() },
        UiTile(R.string.workout_title, "training") { openTool("workout") }, UiTile(R.string.ui_technique, "form") { formHome() })
    briefingCard()
    val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "today-cards" }
    page.addView(cards)
    val cached = cachedToday()
    drawToday(cards, cached?.first)
    if (cached != null) status.text = stamp(getString(R.string.today_updated, clockOf(cached.second)))
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
    page.findViewWithTag<android.widget.TextView>("today-status")?.text = stamp(getString(R.string.today_loading))
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
            status?.text = stamp(if (cached == null) getString(R.string.today_offline_empty) else getString(R.string.today_offline, clockOf(cached.second)))
            if (cached == null && page.findViewWithTag<View>("phone-tools") == null) phoneTools()
            return@later
        }
        saveToday(fresh)
        status?.text = stamp(getString(R.string.today_updated, clockOf(System.currentTimeMillis())))
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

/** Drawing order: the readiness panel, the day's rings (food, load), then one row per metric (SPEC 10.9). */
internal fun todayDrawOrder(keys: List<String>) = keys.filter { it == "readiness" } + listOf("nutrition", "training").filter { it in keys } +
    keys.filter { it !in setOf("readiness", "nutrition", "training") }

/** The whole Today, redrawn when the aggregate or the phone's steps change. */
private fun MainActivity.drawToday(cards: LinearLayout, data: JSONObject?) {
    val section = TODAY_CARDS.joinToString { sectionOf(it, data).toString() } + healthSteps
    if (cards.getTag(R.id.today_sections) == section) return
    cards.setTag(R.id.today_sections, section)
    val briefing = page.findViewWithTag<View>("today-briefing")
    cards.removeAllViews()
    (briefing?.parent as? LinearLayout)?.removeView(briefing)
    val keys = todayOrder().filter { it !in hiddenCards() }
    if ("readiness" in keys) vitalsPanel(data, findingsFor("readiness", data), cards, { panel -> adjustment(panel, data) }) { openCard("readiness") }
    briefing?.let { cards.addView(it) }
    dayRings(data, { findingsFor(it, data) }, cards) { openCard(it) }
    metricRows(keys.filter { it !in setOf("readiness", "nutrition", "training") }, data, { findingsFor(it, data) },
        { getString(cardTitle(it)) }, cards) { openCard(it) }
    cards.arrive()
}

/** "Not feeling 100%?" and the flag when set: a quiet line at the foot of the readiness panel. */
private fun MainActivity.adjustment(panel: LinearLayout, data: JSONObject?) {
    val flag = data?.optJSONObject("adjustment")
    if (flag != null) text(getString(R.string.adj_flag, adjustLabel(flag.getString("reason")).lowercase(locale),
        adjustLabel(flag.getString("mode")).lowercase(locale)), 13, Color.WHITE, panel).setPadding(0, dp(8), 0, 0)
    button(getString(if (flag == null) R.string.adj_question else R.string.adj_change), parent = panel, look = ActionTone.TEXT) { adjustmentSheet(flag) }.apply {
        setTextColor(getColor(R.color.signal)); gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
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

/** The jump-test card shown signed out; signed in, the jump test lives in the Technique tab. */
internal fun MainActivity.todayCard(key: String, @Suppress("UNUSED_PARAMETER") data: JSONObject?, parent: LinearLayout): LinearLayout {
    val panel = card(parent).apply { tag = "card-$key" }
    text(getString(cardTitle(key)), 18, getColor(R.color.ink), panel).instrumentType(display = true)
    text(getString(R.string.today_jump_note), 14, parent = panel)
    button(getString(R.string.today_jump_record), parent = panel, look = ActionTone.TONAL) { jumpHome() }
        .layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
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

/** The day's findings phrased by the club's language model, as a quiet three-line note under the readiness panel;
 *  tap to read it all. Hidden when there is no model or no answer (SPEC 10.9). */
private fun MainActivity.briefingCard() {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; tag = "today-briefing"; setPadding(dp(4), dp(14), dp(4), dp(2))
    }
    page.addView(panel, LinearLayout.LayoutParams(-1, -2))
    val line = row(panel)
    line.addView(android.widget.ImageView(this).apply {
        setImageDrawable(lineIcon("assistant", getColor(R.color.accent))); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(2); marginEnd = dp(10) })
    val body = text(getString(R.string.today_briefing_loading), 14, getColor(R.color.muted), line).apply {
        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    val api = featureApi()
    val host = page
    // Its own thread: the model can take half a minute, and the panel must not wait behind it.
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
    body.contentDescription = getString(R.string.today_briefing) + ": " + said
    body.setOnClickListener { body.maxLines = if (body.maxLines == 3) 20 else 3 }
    val foot = row(panel, center = true).apply { setPadding(dp(28), dp(2), 0, 0) }
    text(getString(R.string.today_briefing_by), 11, parent = foot).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setPadding(0, 0, 0, 0) }
    button(getString(R.string.today_ask), parent = foot, look = ActionTone.TEXT) { assistantScreen(draft = getString(R.string.today_ask_draft)) }
        .apply { textSize = 13f; setPadding(dp(8), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(-2, dp(40)) }
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
