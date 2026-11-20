package jumpscan.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import jumpscan.core.toJson
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val clubExecutor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var generation = 0
    private val prefs by lazy { getSharedPreferences("account", MODE_PRIVATE) }
    private var analysis: Analysis? = null
    private data class FrameEdits(var index: Int, var takeoffMs: Long?, var landingMs: Long?,
        val suggested: Boolean = false)
    private var edits: FrameEdits? = null
    private var uploadedId: Long? = null
    private var queuedId: String? = null
    private var clientId: String? = null
    private var selectedSession: ClubSession? = null
    private var resultSession: ClubSession? = null
    private var queueSending = false
    private val pendingRoot by lazy { java.io.File(filesDir, "pending") }
    private fun pending() = PendingUploads(pendingRoot, base(), athleteId())
    internal var formChoice = FormChoice("squat", null)
    internal var lastForm: FormResult? = null
    private var pendingTrial: PhoneTrial? = null
    private var guiding = false
    internal var assistantChat = AssistantChat()
    private var test = "cmj"
    internal lateinit var page: LinearLayout
        private set
    private var screen = "login"
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    internal val green by lazy { getColor(R.color.accent) }
    private val ink by lazy { getColor(R.color.ink) }
    private val muted by lazy { getColor(R.color.muted) }
    private val warning by lazy { getColor(R.color.warning) }
    private val labels = linkedMapOf(
        "fps" to R.string.fps,
        "flight_time_ms" to R.string.flight_time_ms,
        "jump_height_cm" to R.string.jump_height_cm,
        "knee_flexion_contact_l" to R.string.knee_flexion_contact_l,
        "knee_flexion_contact_r" to R.string.knee_flexion_contact_r,
        "knee_flexion_peak_l" to R.string.knee_flexion_peak_l,
        "knee_flexion_peak_r" to R.string.knee_flexion_peak_r,
        "fppa_peak_l" to R.string.fppa_peak_l,
        "fppa_peak_r" to R.string.fppa_peak_r,
        "knee_ankle_ratio" to R.string.knee_ankle_ratio,
        "flexion_asymmetry_pct" to R.string.flexion_asymmetry_pct,
        "quality" to R.string.quality
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RetainedResult
        if (retained != null) { analysis = retained.analysis; uploadedId = retained.uploadedId; test = retained.test; queuedId = retained.queuedId; edits = retained.edits; clientId = retained.clientId; selectedSession = retained.selectedSession; resultSession = retained.resultSession }
        lastForm = retained?.form
        assistantChat = retained?.chat ?: AssistantChat()
        savedInstanceState?.getString("form_exercise")?.let { formChoice = FormChoice(it, savedInstanceState.getString("form_side")) }
        savedInstanceState?.getInt("phone_trial")?.takeIf { it in 1..23 }?.let {
            pendingTrial = PhoneTrial(it, savedInstanceState.getInt("phone_fps").takeIf { fps -> fps > 0 })
        }
        if (savedInstanceState?.getString("screen") == "diagnostics" || pendingTrial != null) diagnostics()
        else if (lastForm != null && retained?.screen == "form_result") formResult(lastForm!!.metrics, lastForm)
        else if (analysis != null && retained?.screen in listOf("frames", "result")) { if (edits != null) frameEditor() else result() } else if (!openVideo(intent)) {
            if (token().isEmpty()) login() else home()
        }
        sendPending(automatic = true)
    }

    private data class RetainedResult(val analysis: Analysis?, val uploadedId: Long?, val test: String, val queuedId: String?, val edits: FrameEdits?, val clientId: String?, val selectedSession: ClubSession?, val resultSession: ClubSession?, val form: FormResult?, val chat: AssistantChat, val screen: String)

    @Deprecated("Uses the platform non-configuration state API")
    override fun onRetainNonConfigurationInstance(): Any = RetainedResult(analysis, uploadedId, test, queuedId, edits, clientId, selectedSession, resultSession, lastForm, assistantChat, screen)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("form_exercise", formChoice.exercise)
        outState.putString("form_side", formChoice.side)
        outState.putString("screen", screen)
        pendingTrial?.let {
            outState.putInt("phone_trial", it.number)
            outState.putInt("phone_fps", it.targetFps ?: 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openVideo(intent)
    }

    @Suppress("DEPRECATION")
    private fun openVideo(intent: Intent): Boolean {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return false
        if (uri.scheme !in listOf("content", "file")) {
            home(getString(R.string.local_video))
        } else startAnalysis(uri, if (BuildConfig.DEBUG) intent.getStringExtra("analysis_mode").orEmpty() else "full")
        return true
    }

    internal fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    internal fun token() = prefs.getString("token", "").orEmpty()
    internal fun base() = prefs.getString("server", if (BuildConfig.DEBUG) "http://10.0.2.2:8000"
        else "https://jumpscan.stormlabs.cloud").orEmpty()
    internal fun athleteId() = prefs.getLong("user_id", 0)

    internal fun layout(title: String, name: String) {
        screen = name
        progress = null
        progressText = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.paper))
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        scroll.addView(page)
        // Target 35 draws edge-to-edge. Keep controls outside system bars and the keyboard.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        text("↗  " + getString(R.string.brand), 13, green).apply {
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
        }
        text(title, 34).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(0, dp(9), 0, dp(6))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    internal fun text(value: String, size: Int = 16, color: Int = if (size <= 14) muted else ink, parent: LinearLayout = page): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(5), 0, dp(5))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun button(label: String, primary: Boolean = false, parent: LinearLayout = page,
                       action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = 0
        setPadding(dp(12), dp(10), dp(12), dp(10))
        styleButton(this, primary)
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    internal fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT,
                       parent: LinearLayout = page): EditText {
        val caption = text(label, 14, parent = parent).apply { setPadding(0, dp(14), 0, dp(6)) }
        return EditText(this).apply {
            id = View.generateViewId()
            caption.labelFor = id
            inputType = type
            setSingleLine(true)
            setText(value)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            backgroundTintList = null
            background = GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(green, getColor(R.color.line))))
            }
            minHeight = dp(48)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    internal fun login(message: String? = null, showServer: Boolean = false, server: String = base()) {
        layout(getString(R.string.sign_in), "login")
        hero(R.string.ui_workbench, R.string.privacy)
        text(getString(R.string.login_intro))
        if (message != null) notice(message, caution = true)
        val email = field(getString(R.string.email), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field(getString(R.string.password), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        lateinit var connection: LinearLayout
        lateinit var url: EditText
        button(getString(R.string.sign_in), primary = true) {
            val address = try { serverUrl(url.text.toString()) } catch (e: IOException) {
                Toast.makeText(this, errorMessage(e), Toast.LENGTH_LONG).show()
                return@button
            }
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            if (emailValue.isBlank() || passwordValue.isBlank()) {
                Toast.makeText(this, getString(R.string.credentials_required), Toast.LENGTH_LONG).show()
                return@button
            }
            password.text.clear()
            work(getString(R.string.signing_in), {
                val response = JSONObject(api(address, "/api/auth/login", "POST", body =
                    JSONObject().put("email", emailValue).put("password", passwordValue)))
                val newToken = response.getString("token")
                val user = response.getJSONObject("user")
                if (user.getString("role") != "athlete") {
                    runCatching { api(address, "/api/auth/logout", "POST", newToken) }
                    throw AppError(R.string.athlete_account)
                }
                Triple(newToken, user.getString("name"), user.getLong("id"))
            }, { (newToken, name, id) ->
                val prior = prefs.getLong("user_id", 0)
                if (prior != id || base() != address) {
                    selectedSession = null
                    resultSession = null
                    if (prior != 0L) lastForm = null
                    assistantChat = AssistantChat()
                    if (prior != 0L) {
                        analysis = null; edits = null; uploadedId = null; queuedId = null; clientId = null
                    }
                }
                prefs.edit().putString("server", address).putString("token", newToken)
                    .putString("name", name).putLong("user_id", id).apply()
                if (analysis != null) result() else home()
                sendPending(automatic = true)
            }, { login(it, showServer = connection.visibility == View.VISIBLE, server = address) })
        }
        button(getString(R.string.continue_offline)) { if (analysis != null) result() else home() }
        button(getString(R.string.ui_server_settings)) {
            connection.visibility = if (connection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (connection.visibility == View.VISIBLE) connection.post {
                connection.requestRectangleOnScreen(android.graphics.Rect(0, 0, connection.width, connection.height))
            }
        }.apply {
            val style = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, style, true)
            setBackgroundResource(style.resourceId)
            setTextColor(green)
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        connection = card().apply { visibility = if (showServer) View.VISIBLE else View.GONE }
        url = field(getString(R.string.server_url), server, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, connection)
    }

    internal fun home(message: String? = null) {
        layout(getString(R.string.home), "home")
        val signedIn = token().isNotEmpty()
        val name = prefs.getString("name", "").orEmpty()
        val day = localDate(todayLocal(), locale)
        text(if (signedIn) "$day  ·  $name" else day, 14)
        if (message != null) notice(message, caution = true)
        val jump = hero(R.string.ui_jump, R.string.ui_jump_intro)
        selectedSession?.let { text(getString(R.string.chosen_session, sessionLabel(it)), 14, getColor(R.color.on_hero), jump) }
        val types = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(Triple("cmj", R.string.ui_cmj_short, R.string.cmj),
            Triple("drop_jump", R.string.ui_drop_short, R.string.drop_jump)).forEach { (value, label, fullLabel) ->
            types.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                contentDescription = getString(fullLabel)
                textSize = 13f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(getColor(R.color.signal))
                minHeight = dp(48)
                isChecked = test == value
                isEnabled = selectedSession == null
                setOnClickListener { test = value }
            }, RadioGroup.LayoutParams(0, -2, 1f))
        }
        jump.addView(types)
        button(getString(R.string.record), primary = true, parent = jump) { recordJump() }
        button(getString(R.string.pick_video), parent = jump) {
            filmingGuide { pickJumpVideo() }
        }
        section(R.string.ui_tools).setPadding(0, dp(14), 0, dp(3))
        if (signedIn) {
            tiles(UiTile(R.string.ui_technique, "form") { formHome() }, UiTile(R.string.history, "history") { history() },
                UiTile(R.string.ui_club, "club") { clubHome() }, UiTile(R.string.checkin_title, "checkin") { checkin() },
                UiTile(R.string.ui_profile, "profile") { profile() })
            button(getString(R.string.ui_more)) { more() }
        } else {
            tiles(UiTile(R.string.ui_technique, "form") { formHome() }, UiTile(R.string.ui_profile, "profile") { profile() })
        }
        if (analysis != null) button(getString(R.string.last_result)) { result() }
        if (signedIn) pendingControls()
        text(getString(R.string.coach_note), 14).setPadding(0, dp(18), 0, 0)
    }

    private fun more() {
        layout(getString(R.string.ui_more), "more")
        text(getString(R.string.ui_extras))
        tiles(UiTile(R.string.training_title, "training") { trainingHome() },
            UiTile(R.string.nutrition_title, "food") { nutritionHome() },
            UiTile(R.string.assistant_title, "assistant") { assistantScreen() })
        button(getString(R.string.home)) { home() }
    }

    internal fun profile() {
        layout(getString(R.string.ui_profile), "profile")
        if (token().isEmpty()) button(getString(R.string.sign_in)) { login() }
        else {
            card().also {
                text(prefs.getString("name", "").orEmpty(), 21, parent = it).setTypeface(null, Typeface.BOLD)
                text(getString(R.string.server_url), 13, parent = it)
                text(base(), 14, parent = it)
            }
        }
        button(getString(R.string.diagnostics)) { diagnostics() }
        if (token().isNotEmpty()) {
            pendingControls()
            button(getString(R.string.ui_change_server)) { leaveProfile(showServer = true) }
            button(getString(R.string.sign_out)) { leaveProfile(showServer = false) }
        }
        button(getString(R.string.home)) { home() }
    }

    private fun leaveProfile(showServer: Boolean) {
        val savedToken = token()
        val savedBase = base()
        work(getString(R.string.signing_out), { runCatching { api(savedBase, "/api/auth/logout", "POST", savedToken) } }, {
            signOutLocal(); login(showServer = showServer)
        }, {
            signOutLocal(); login(showServer = showServer)
        })
    }

    private fun signOutLocal() {
        prefs.edit().remove("token").remove("name").remove("user_id").apply()
        selectedSession = null
        resultSession = null
        analysis = null
        edits = null
        uploadedId = null
        queuedId = null
        clientId = null
        lastForm = null
        assistantChat = AssistantChat()
    }

    private fun sessionLabel(session: ClubSession): String = buildString {
        append(session.title)
        append("\n" + localDateTime(session.startsAt, resources.configuration.locales[0]))
        if (session.place.isNotBlank()) append(" · " + session.place)
        append("\n" + getString(if (session.test == "cmj") R.string.cmj else R.string.drop_jump))
        if (session.jumpCount > 0) append(" · " + getString(R.string.session_attempt, session.jumpCount + 1, session.trials))
        if (session.cancelled) append(" · " + getString(R.string.session_cancelled))
    }

    private fun clubHome() {
        layout(getString(R.string.ui_club), "club")
        val host = page
        val day = todayLocal()
        val actions = card()
        val checkin = button(getString(R.string.checkin_title), primary = true, parent = actions) { checkin() }
        val feedback = button(getString(R.string.feedback_title), parent = actions) { feedback() }
        checkin.isEnabled = token().isNotEmpty()
        feedback.isEnabled = token().isNotEmpty()
        val sessions = card()
        section(R.string.sessions_title, sessions)
        val status = text(getString(if (token().isEmpty()) R.string.club_sign_in else R.string.club_loading), 14, parent = sessions)
        selectedSession?.let { text(getString(R.string.chosen_session, sessionLabel(it)), 14, parent = sessions) }
        if (selectedSession != null) button(getString(R.string.session_clear), parent = sessions) {
            selectedSession = null; clubHome()
        }
        button(getString(R.string.home)) { home() }
        if (token().isEmpty()) return
        val savedToken = token(); val savedBase = base(); val owner = prefs.getLong("user_id", 0)
        fun current() = !isDestroyed && page === host && token() == savedToken && base() == savedBase
        clubExecutor.submit {
            try {
                val rows = parseSessions(api(savedBase, "/api/sessions?from=${Instant.now()}&limit=5", token = savedToken))
                runOnUiThread {
                    if (current()) {
                        status.text = if (rows.isEmpty()) getString(R.string.sessions_empty) else ""
                        selectedSession?.let { chosen ->
                            rows.firstOrNull { it.id == chosen.id }?.let { fresh ->
                                if (fresh.cancelled) { selectedSession = null; home(); return@runOnUiThread }
                                selectedSession = fresh
                            } ?: run { selectedSession = null; home(); return@runOnUiThread }
                        }
                        rows.forEach { session ->
                            button(sessionLabel(session), parent = sessions) {
                                selectedSession = session; test = session.test; home()
                            }.isEnabled = !session.cancelled
                        }
                    }
                }
            } catch (_: Exception) { runOnUiThread { if (current()) status.text = getString(R.string.sessions_offline) } }
        }
        clubExecutor.submit {
            val queued = synchronized(uploadLock) { runCatching { pending().checkin(day) }.getOrNull() }
            val done = if (queued != null) true else runCatching {
                JSONArray(api(savedBase, "/api/checkins?athlete_id=$owner&from=$day&to=$day", token = savedToken)).length() > 0
            }.getOrNull()
            runOnUiThread { if (current()) checkin.text = getString(when {
                queued != null -> R.string.checkin_pending
                done == true -> R.string.checkin_done
                done == false -> R.string.checkin_not_done
                else -> R.string.checkin_unknown
            }) }
            val unread = runCatching { JSONObject(api(savedBase, "/api/me/unread", token = savedToken)).getInt("comments") }.getOrNull()
            runOnUiThread { if (current()) feedback.text = if (unread == null) getString(R.string.feedback_unknown)
                else getString(R.string.feedback_unread, unread) }
        }
    }

    private fun checkin() {
        val day = todayLocal()
        val savedToken = token(); val savedBase = base(); val owner = prefs.getLong("user_id", 0)
        work(getString(R.string.checkin_title), {
            synchronized(uploadLock) { pending().checkin(day) } ?: try {
                val rows = JSONArray(api(savedBase, "/api/checkins?athlete_id=$owner&from=$day&to=$day", token = savedToken))
                if (rows.length() == 0) null else rows.getJSONObject(0)
            } catch (e: IOException) { if (e is ApiError) throw e else null }
        }, { checkinForm(day, it) }, { home(it) })
    }

    private fun checkinForm(day: String, saved: JSONObject?, message: String? = null) {
        layout(getString(R.string.checkin_title), "checkin")
        text(getString(R.string.checkin_day, localDate(day, resources.configuration.locales[0])), 14)
        text(getString(R.string.checkin_scale), 14)
        if (message != null) notice(message, caution = true)
        val keys = listOf("sleep", "fatigue", "soreness", "stress", "mood")
        val groups = listOf(R.string.checkin_sleep, R.string.checkin_fatigue, R.string.checkin_soreness,
            R.string.checkin_stress, R.string.checkin_mood).mapIndexed { index, label ->
            text(getString(label), 16)
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                for (score in 1..5) addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = score.toString(); tag = score
                    setTextColor(ink); buttonTintList = ColorStateList.valueOf(green)
                    minHeight = dp(48); setPadding(0, 0, 0, 0)
                    contentDescription = getString(label) + ": " + score
                    isChecked = score == (saved?.optInt(keys[index], 3) ?: 3)
                }, RadioGroup.LayoutParams(0, -2, 1f))
                page.addView(this)
            }
        }
        fun optional(key: String) = if (saved == null || saved.isNull(key)) "" else saved.getString(key)
        val minutes = field(getString(R.string.checkin_minutes), optional("training_minutes"), InputType.TYPE_CLASS_NUMBER)
        val rpe = field(getString(R.string.checkin_rpe), optional("rpe"), InputType.TYPE_CLASS_NUMBER)
        text(getString(R.string.checkin_optional), 14)
        button(getString(R.string.checkin_save), primary = true) {
            val body = try { checkinBody(
                groups.map { group ->
                    // A group can be empty when nothing is selected; the validator reports it, no crash.
                    val id = group.checkedRadioButtonId
                    if (id == -1) throw AppError(R.string.checkin_invalid)
                    group.findViewById<RadioButton>(id).tag as Int
                },
                minutes.text.toString(), rpe.text.toString()) } catch (e: AppError) {
                Toast.makeText(this, errorMessage(e), Toast.LENGTH_LONG).show(); return@button
            }
            val queue = pending(); val savedToken = token(); val savedBase = base()
            work(getString(R.string.checkin_saving), {
                synchronized(uploadLock) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    try { queue.enqueueCheckin(day, body) }
                    catch (_: Exception) { throw AppError(R.string.pending_save_failed) }
                    queue.sendCheckin(day) { date, value ->
                        // Verify the success body, or a captive portal 200 would drop the queued check-in.
                        require(JSONObject(api(savedBase, "/api/me/checkins/$date", "PUT", savedToken, value)).getString("day") == date)
                    }
                }
            }, {
                home()
                Toast.makeText(this, getString(R.string.checkin_saved), Toast.LENGTH_LONG).show()
            }, { checkinForm(day, body, getString(R.string.checkin_queued) + "\n" + it) })
        }
        button(getString(R.string.home)) { home() }
    }

    private fun feedback(offset: Int = 0) {
        val savedToken = token(); val savedBase = base(); val owner = prefs.getLong("user_id", 0)
        work(getString(R.string.feedback_title), {
            parseComments(api(savedBase, "/api/comments?athlete_id=$owner&limit=50&offset=$offset", token = savedToken))
        }, { rows ->
            feedbackForm(rows, offset)
            // Mark read only after the page is actually shown; the newest page clears the badge.
            if (offset == 0) clubExecutor.submit {
                runCatching { api(savedBase, "/api/me/read", "POST", savedToken) }
            }
        }, { home(it) })
    }

    private fun feedbackForm(rows: List<ClubComment>, offset: Int, draft: String = "", message: String? = null) {
        layout(getString(R.string.feedback_title), "feedback")
        if (message != null) text(message, 15, getColor(R.color.error))
        if (rows.isEmpty()) text(getString(R.string.feedback_empty))
        val reply = field(getString(R.string.feedback_reply), draft).apply {
            setSingleLine(false); minLines = 2; maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
        }
        button(getString(R.string.feedback_send), primary = true) {
            val body = reply.text.toString().trim()
            if (body.isEmpty()) { reply.error = getString(R.string.feedback_required); return@button }
            val savedToken = token(); val savedBase = base(); val owner = prefs.getLong("user_id", 0)
            work(getString(R.string.feedback_sending), {
                api(savedBase, "/api/comments", "POST", savedToken, JSONObject().put("athlete_id", owner).put("body", body))
            }, { feedback() }, { feedbackForm(rows, offset, body, it) })
        }
        button(getString(R.string.home)) { home() }
        for (comment in rows) {
            text(getString(R.string.feedback_author, comment.authorName, getString(when (comment.authorRole) {
                "coach" -> R.string.role_coach
                "admin" -> R.string.role_admin
                else -> R.string.role_athlete
            })), 18, green)
            text(localDateTime(comment.createdAt, resources.configuration.locales[0]), 14)
            comment.jumpId?.let { text(getString(R.string.feedback_jump, it), 14) }
            comment.sessionId?.let { text(getString(R.string.feedback_session, it), 14) }
            text(comment.body)
        }
        if (rows.size == 50) button(getString(R.string.feedback_older)) { feedback(offset + 50) }
        if (offset > 0) button(getString(R.string.feedback_newest)) { feedback() }
    }

    private fun recordJump() {
        launchVideo(Intent(this, JumpCaptureActivity::class.java).putExtra("test", test), 10)
    }

    private fun pickJumpVideo() {
        val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        launchVideo(pick.setType("video/*"), 11)
    }

    private fun filmingGuide(continueAction: () -> Unit) {
        layout(getString(R.string.filming_guide), "guide")
        text(getString(if (test == "cmj") R.string.film_cmj else R.string.film_drop), 16)
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.filming_guide)
            contentDescription = getString(R.string.guide_diagram)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(-1, dp(140)))
        for (line in listOf(R.string.guide_phone, R.string.guide_body, R.string.guide_face,
                R.string.guide_fps, R.string.guide_slow_motion, R.string.guide_still, R.string.guide_light)) {
            text(getString(line)).setPadding(0, dp(8), 0, dp(8))
        }
        text(getString(R.string.analysis_hint), 14)
        button(getString(R.string.continue_video), primary = true, action = continueAction)
        button(getString(R.string.cancel)) { home() }
    }

    @Suppress("DEPRECATION")
    internal fun launchVideo(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request) }
        catch (_: Exception) {
            val error = getString(R.string.no_video_app)
            if (request == 30) { pendingTrial = null; diagnostics(error) }
            else if (request in 12..13) formHome(error) else home(error)
        }
    }

    internal fun pickPhoneTrial(trial: PhoneTrial) {
        pendingTrial = trial
        val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        launchVideo(pick.setType("video/*"), 30)
    }

    @Deprecated("Uses the platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_FIRST_USER) pickJumpVideo()
        if (requestCode == 10 && resultCode == RESULT_OK) {
            val name = data?.getStringExtra("clip")?.takeIf { it.matches(Regex("jump-[0-9]+\\.mp4")) }
            val fps = data?.getIntExtra("fps", 0)
            val file = name?.let { java.io.File(cacheDir, "jump-capture/$it") }
            if (file?.isFile == true && fps in listOf(60, 120, 240)) {
                startAnalysis(Uri.fromFile(file), captureFps = fps)
            } else home(getString(R.string.no_video_returned))
        }
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) startAnalysis(uri) else home(getString(R.string.no_video_returned))
        }
        if (requestCode == 30) {
            val trial = pendingTrial
            pendingTrial = null
            val uri = data?.data
            if (trial == null || resultCode != RESULT_OK) diagnostics()
            else if (uri == null) diagnostics(getString(R.string.no_video_returned))
            else if (trial.number <= 20) startAnalysis(uri, trial = trial)
            else startRecordedForm(uri, FormChoice("squat", null), trial)
        } else if (requestCode in 12..13) {
            val uri = data?.data
            if (resultCode != RESULT_OK) formHome()
            else if (uri == null) formHome(getString(R.string.no_video_returned))
            else startRecordedForm(uri, formChoice)
        }
    }

    private fun startRecordedForm(uri: Uri, choice: FormChoice, trial: PhoneTrial? = null) {
        val expectedGeneration = generation + 1
        work(getString(R.string.analysing), { analyzeFormVideo(this, uri, choice, trial) { value ->
            runOnUiThread {
                if (!isDestroyed && generation == expectedGeneration) {
                    progress?.isIndeterminate = false
                    progress?.progress = value
                    progressText?.text = getString(R.string.progress, value)
                }
            }
        } }, { metrics ->
            val result = FormResult(JSONObject(metrics.toJson()))
            lastForm = result
            if (trial != null) diagnostics() else formResult(result.metrics, result)
        }, { if (trial != null) diagnostics(it) else formHome(it) }, phoneTest = trial != null)
    }

    private fun startAnalysis(uri: Uri, mode: String = "full", trial: PhoneTrial? = null, captureFps: Int? = null) {
        analysis = null
        edits = null
        uploadedId = null
        queuedId = null
        clientId = null
        resultSession = if (trial == null) selectedSession else null
        val expectedGeneration = generation + 1
        val jumpTest = if (trial == null) test else "cmj"
        work(getString(R.string.analysing), { analyzeVideo(this, uri, jumpTest, mode, trial, captureFps) { value ->
            runOnUiThread {
                if (screen == "busy" && !isDestroyed && generation == expectedGeneration) {
                    progress?.isIndeterminate = false
                    progress?.progress = value
                    progressText?.text = getString(R.string.progress, value)
                }
            }
        } }, { analysis = it; if (trial == null) result() else diagnostics() },
            { if (trial == null) home(it) else diagnostics(it) }, phoneTest = trial != null)
        progress?.isIndeterminate = false
        text(getString(R.string.first_ten), 14)
    }

    private fun result(message: String? = null) {
        val current = analysis ?: return home()
        layout(getString(R.string.result), "result")
        val metrics = JSONObject(current.metrics.toJson())
        val hasFlight = hasFlight(metrics)
        if (!hasFlight) {
            notice(getString(R.string.no_flight_help), caution = true)
            if (uploadedId == null && queuedId == null) {
                button(getString(R.string.check_frames), primary = true) { editFrames(current) }
            }
            button(getString(R.string.film_again)) {
                test = current.metrics.test
                selectedSession = resultSession
                recordJump()
            }
        }
        text(if (current.metrics.test == "cmj") getString(R.string.cmj) else getString(R.string.drop_jump), 14)
        if (hasFlight) {
            metricWarnings(metrics)
            if (uploadedId == null && queuedId == null) button(getString(R.string.check_frames)) { editFrames(current) }
            jumpHighlights(metrics, uploadedId, clientId)
        }
        if (message != null) notice(message, caution = true)
        resultSession?.let { text(getString(R.string.chosen_session, sessionLabel(it)), 16, green) }
        if (current.truncated) text(getString(R.string.truncated), 14)
        if (hasFlight) text(edgeTimes(current.landing.takeoffMs, current.landing.contactMs), 16)
        if ("low_visibility" in current.metrics.warnings) text(getString(R.string.visibility_help), 16)
        // After upload (or once queued) the result is read-only: editing frames would create a second jump.
        text(getString(when {
            !hasFlight -> R.string.preview_check
            current.contactFound -> R.string.landing_frame
            else -> R.string.peak_frame
        }), 18)
        page.addView(ImageView(this).apply {
            setImageBitmap(current.preview)
            adjustViewBounds = true
            maxHeight = dp(330)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.skeleton_description)
        }, LinearLayout.LayoutParams(-1, -2))
        val legend = LinearLayout(this)
        for ((label, color) in listOf(R.string.left_blue to LEFT_COLOR, R.string.right_orange to RIGHT_COLOR)) {
            legend.addView(TextView(this).apply {
                text = getString(label); textSize = 14f
                setTextColor(if (color == LEFT_COLOR) Color.rgb(0, 78, 162) else Color.rgb(139, 76, 0))
                setPadding(0, dp(8), dp(16), dp(8))
            })
        }
        page.addView(legend)
        if (current.previewUnclear) text(getString(R.string.preview_unclear), 14, warning)
        if (hasFlight && uploadedId == null && queuedId == null) button(if (token().isEmpty()) getString(R.string.sign_in_upload) else getString(R.string.upload), primary = true) {
            if (token().isEmpty()) login() else {
                val savedToken = token()
                val savedBase = base()
                val queue = pending()
                val sessionId = resultSession?.id
                val uuid = clientId ?: UUID.randomUUID().toString().also { clientId = it }
                work(getString(R.string.uploading), {
                    synchronized(uploadLock) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val name = try { queue.enqueue(current.metrics, uuid, sessionId) }
                            catch (_: Exception) { throw AppError(R.string.pending_save_failed) }
                        queuedId = name // Shown as "saved" even if the send below fails.
                        queue.sendJump(name) { body ->
                            JSONObject(api(savedBase, "/api/jumps", "POST", savedToken, body)).getLong("id")
                        }
                    }
                }, { id -> if (id != null) { uploadedId = id; queuedId = null }; result() },
                    { result(it) })
            }
        }
        if (uploadedId != null) text(getString(R.string.uploaded, uploadedId), 16, green)
        if (queuedId != null) text(getString(R.string.saved_pending), 16, green)
        if (uploadedId != null || queuedId != null) text(getString(R.string.uploaded_readonly), 14)
        if (hasFlight) {
            metricRows(metrics)
            text(getString(R.string.depth_note), 14)
        }
        pendingControls()
        if (token().isNotEmpty()) button(getString(R.string.history)) { history() }
        button(getString(R.string.home)) { home() }
        text(getString(R.string.no_video_upload), 13)
    }

    private fun editFrames(current: Analysis) {
        val suggestion = current.landing.learnedSuggestion?.takeIf { "no_flight_detected" in current.metrics.warnings }
        val takeoff = suggestion?.takeoffMs ?: current.landing.takeoffMs
        val landing = suggestion?.landingMs ?: current.landing.contactMs
        val start = takeoff ?: peakFlexionFrame(current.frames).tMs
        edits = FrameEdits(current.timesUs.indexOfFirst { it / 1000 == start }.coerceAtLeast(0),
            takeoff, landing, suggested = suggestion != null)
        frameEditor()
    }

    private fun edgeTimes(takeoff: Long?, landing: Long?): String {
        fun time(value: Long?) = value?.let { getString(R.string.frame_time, it) } ?: getString(R.string.not_available)
        return getString(R.string.edge_times, time(takeoff), time(landing))
    }

    private fun frameEditor(message: String? = null) {
        val current = analysis ?: return home()
        val selection = edits ?: return result()
        layout(getString(R.string.check_frames), "frames")
        text(getString(R.string.frame_instructions), 14)
        if (selection.suggested) text(getString(R.string.suggested_edges), 14)
        if (message != null) text(message, 15, getColor(R.color.error)).apply {
            post { announceForAccessibility(message) }
        }
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.skeleton_description)
        }
        page.addView(image, LinearLayout.LayoutParams(-1, dp(230)))
        val timestamp = text("", 18)
        timestamp.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val loading = text("", 14)
        val steps = LinearLayout(this)
        page.addView(steps)
        val stepButtons = mutableListOf<Pair<Int, Button>>()
        val edges = text(edgeTimes(selection.takeoffMs, selection.landingMs), 16)
        val flight = text("", 16)
        flight.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        lateinit var apply: Button
        fun updateEdges() {
            edges.text = edgeTimes(selection.takeoffMs, selection.landingMs)
            val duration = selection.landingMs?.let { end -> selection.takeoffMs?.let { end - it } }
            val valid = duration != null && duration in 1..1000
            flight.text = if (valid) getString(R.string.manual_flight, duration,
                9.81 * duration!!.toDouble() * duration / 1_000_000 / 8 * 100) else getString(R.string.edge_error)
            apply.isEnabled = valid
        }
        val takeoff = button(getString(R.string.set_takeoff)) {
            selection.takeoffMs = current.timesUs[selection.index] / 1000
            updateEdges()
        }
        val landing = button(getString(R.string.set_landing)) {
            selection.landingMs = current.timesUs[selection.index] / 1000
            updateEdges()
        }
        apply = button(getString(R.string.apply_frames), primary = true) {
            work(getString(R.string.applying_frames), {
                try { applyEdges(this, current, selection.takeoffMs!!, selection.landingMs!!) }
                catch (_: IllegalArgumentException) { throw AppError(R.string.manual_analysis_error) }
            }, {
                analysis = it
                edits = null
                uploadedId = null
                queuedId = null
                clientId = null
                result()
            }, { frameEditor(it) })
        }
        button(getString(R.string.cancel)) { cancelWork(); edits = null; result() }
        fun showFrame() {
            task?.cancel(true)
            val expected = ++generation
            val index = selection.index
            takeoff.isEnabled = false
            landing.isEnabled = false
            image.setImageDrawable(null)
            timestamp.text = getString(R.string.frame_time, current.timesUs[index] / 1000)
            loading.text = getString(R.string.loading_frame)
            stepButtons.forEach { (step, button) -> button.isEnabled = index + step in current.timesUs.indices }
            task = executor.submit {
                try {
                    val bitmap = framePreview(this, current, index)
                    runOnUiThread {
                        if (!isDestroyed && screen == "frames" && generation == expected) {
                            image.setImageBitmap(bitmap)
                            takeoff.isEnabled = true
                            landing.isEnabled = true
                            loading.text = ""
                        } else bitmap.recycle()
                    }
                } catch (e: Exception) {
                    runOnUiThread { if (!isDestroyed && screen == "frames" && generation == expected)
                        loading.text = errorMessage(e) }
                }
            }
        }
        for (step in listOf(-5, -1, 1, 5)) {
            val control = button(if (step > 0) "+$step" else step.toString(), parent = steps) {
                selection.index += step; showFrame()
            }.apply {
                contentDescription = getString(if (step > 0) R.string.frames_forward else R.string.frames_back, kotlin.math.abs(step))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = dp(10)
                    if (step != -5) marginStart = dp(8)
                }
            }
            stepButtons += step to control
        }
        updateEdges()
        showFrame()
    }

    internal fun pendingControls() {
        val count = if (token().isNotEmpty()) pending().count() else
            pendingRoot.walkTopDown().count { it.isFile && it.name.endsWith(".json") }
        text(getString(R.string.pending_count, count), 14).setPadding(0, dp(18), 0, dp(8))
        if (token().isNotEmpty()) {
            val rejected = pending().rejectedCount()
            if (rejected > 0) text(getString(R.string.pending_rejected, rejected), 14, warning)
        }
        if (count > 0) {
            if (token().isEmpty()) text(getString(R.string.pending_sign_in), 14)
            else button(getString(if (queueSending) R.string.sending_pending else R.string.send_pending)) {
                sendPending(automatic = false)
            }.isEnabled = !queueSending
        }
    }

    internal fun saveFeature(kind: FeatureWrite, body: JSONObject, day: String = "",
        clientId: String = UUID.randomUUID().toString(), done: (Int?, String) -> Unit) {
        if (token().isEmpty()) return login()
        val queue = pending()
        val savedBase = base()
        val savedToken = token()
        work(getString(R.string.feature_saving), {
            synchronized(uploadLock) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val name = queue.enqueueFeature(kind, day, body, clientId)
                val sent = linkedMapOf<String, Long>()
                try { flush(queue, sent, savedBase, savedToken) }
                catch (error: ApiError) { if (error.status == 401) throw error }
                catch (error: IOException) { if (error is AppError) throw error }
                val status = when {
                    sent.containsKey(name) -> R.string.feature_saved
                    queue.featurePending(name) -> R.string.saved_pending
                    else -> R.string.feature_rejected
                }
                status to sent
            }
        }, { (status, sent) ->
            applyUploadReceipts(sent)
            done(status, getString(status))
        }, { done(null, it) })
    }

    private fun flush(queue: PendingUploads, sent: MutableMap<String, Long>, address: String, auth: String) {
        queue.send(sent, put = { day, body ->
            require(JSONObject(api(address, "/api/me/checkins/$day", "PUT", auth, body)).getString("day") == day)
        }, featureSend = { kind, path, body ->
            val response = JSONObject(api(address, path, kind.method, auth, body))
        if (kind.method == "POST") response.getLong("id") else {
            require(response.getString("day") == path.split('/').last { it != "complete" })
            0L
        }
        }) { metrics -> JSONObject(api(address, "/api/jumps", "POST", auth, metrics)).getLong("id") }
    }

    private fun applyUploadReceipts(sent: Map<String, Long>) {
        queuedId?.let { name -> sent[name]?.let { uploadedId = it; queuedId = null } }
        lastForm?.let { form ->
            if (sent.keys.any { it.endsWith("-${form.clientId}.json") }) form.status = R.string.feature_saved
            else if (form.status == R.string.saved_pending && pending().featureRejected(form.clientId))
                form.status = R.string.feature_rejected
        }
    }

    private fun sendPending(automatic: Boolean) {
        if (queueSending || token().isEmpty() || pending().count() == 0) return
        val savedToken = token()
        val savedBase = base()
        val savedOwner = prefs.getLong("user_id", 0)
        val queue = pending()
        queueSending = true
        if (screen == "result") result() else if (screen == "home") home() else if (screen == "profile") profile()
        else if (screen == "form_result") lastForm?.let { formResult(it.metrics, it) }
        executor.submit {
            var error: Exception? = null
            val sent = linkedMapOf<String, Long>()
            try {
                synchronized(uploadLock) {
                    flush(queue, sent, savedBase, savedToken)
                }
            } catch (e: Exception) { error = e }
            runOnUiThread {
                queueSending = false
                if (!isDestroyed && token() == savedToken && base() == savedBase && prefs.getLong("user_id", 0) == savedOwner) {
                    applyUploadReceipts(sent)
                    if (error is ApiError && (error as ApiError).status == 401) {
                        prefs.edit().remove("token").apply()
                        if (screen != "busy") login(getString(R.string.session_expired))
                    } else {
                        if (screen == "result") result() else if (screen == "home") home() else if (screen == "profile") profile()
                        else if (screen == "form_result") lastForm?.let { formResult(it.metrics, it) }
                        if (error != null && error !is InterruptedException) {
                            Toast.makeText(this, errorMessage(error!!), Toast.LENGTH_LONG).show()
                        } else if (!automatic) Toast.makeText(this, getString(R.string.pending_sent), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun hasFlight(json: JSONObject) = json.optDouble("flight_time_ms", 0.0) > 0 &&
        json.optJSONArray("warnings")?.let { warnings ->
            (0 until warnings.length()).none { warnings.optString(it) == "no_flight_detected" }
        } != false

    private fun jumpHighlights(json: JSONObject, beforeId: Long? = null, excludeClient: String? = null) {
        val highlights = listOf(
            JumpHighlight("jump_height_cm", R.string.ui_height, R.string.jump_height_meaning, getString(R.string.ui_cm)),
            JumpHighlight("flight_time_ms", R.string.ui_flight, R.string.jump_flight_meaning, getString(R.string.ui_ms)),
            JumpHighlight("knee_flexion_peak_l", R.string.ui_flex_left, R.string.jump_flex_meaning, "°"),
            JumpHighlight("knee_flexion_peak_r", R.string.ui_flex_right, R.string.jump_flex_meaning, "°"),
            JumpHighlight("flexion_asymmetry_pct", R.string.ui_asymmetry, R.string.jump_asymmetry_meaning, "%")
        ).filterNot { json.isNull(it.key) }
        val auth = token()
        val host = page
        val address = base()
        val warnings = json.optJSONArray("warnings")
        val side = warnings != null && (0 until warnings.length()).any { warnings.optString(it) == "single_leg_view" }
        val errorMs = if (side) 87.9 else 32.1
        val durationMs = json.optDouble("flight_time_ms")
        val textLocale = resources.configuration.locales[0]
        if (listOf("knee_flexion_contact_l", "knee_flexion_contact_r").any { json.optDouble(it) >= 30 }) {
            notice(getString(R.string.jump_bent_landing), caution = true)
        }
        section(R.string.jump_comparison, host)
        text(getString(if (side) R.string.jump_error_side else R.string.jump_error_front), 14, parent = host)
        text(getString(if (auth.isEmpty()) R.string.jump_baseline_sign_in else R.string.jump_baseline_info), 14, parent = host)
        val comparisons = highlights.associateWith { metric ->
            val panel = stat(getString(metric.label), number(json, metric.key), metric.unit, host)
            val band = when (metric.key) {
                "flight_time_ms" -> errorMs
                "jump_height_cm" -> 9.81 / 4 * (durationMs / 1000) * (errorMs / 1000) * 100
                else -> null
            }
            if (band != null && band.isFinite()) {
                text(getString(R.string.jump_error_band, String.format(textLocale, "%.1f", band), metric.unit), 14, parent = panel)
            }
            val comparison = text(getString(if (auth.isEmpty()) R.string.jump_baseline_offline else R.string.jump_baseline_loading),
                14, parent = panel)
            text(getString(metric.meaning), 14, parent = panel)
            comparison
        }
        if (auth.isEmpty()) return
        val path = buildString {
            append("/api/jumps/baseline?test=").append(if (json.optString("test") == "cmj") "cmj" else "drop_jump")
            beforeId?.let { append("&before_id=").append(it) }
            excludeClient?.let { append("&exclude_client_id=").append(Uri.encode(it)) }
        }
        clubExecutor.submit {
            val baseline = runCatching { JSONObject(api(address, path, token = auth)) }.getOrNull()
            runOnUiThread {
                if (host !== page || auth != token() || address != base()) return@runOnUiThread
                for ((metric, caption) in comparisons) {
                    caption.text = when {
                        baseline == null -> getString(R.string.jump_baseline_unavailable)
                        baseline.isNull(metric.key) -> getString(R.string.jump_baseline_pending)
                        else -> {
                            val change = json.getDouble(metric.key) - baseline.getDouble(metric.key)
                            val value = metricNumber(kotlin.math.abs(change), metric.key, locale)
                            val unit = if (metric.unit == "%") getString(R.string.jump_percentage_points) else metric.unit
                            val comparison = when {
                                value == metricNumber(0.0, metric.key, locale) -> getString(R.string.jump_baseline_equal)
                                change > 0 -> getString(R.string.jump_baseline_higher, value, unit)
                                else -> getString(R.string.jump_baseline_lower, value, unit)
                            }
                            val reference = metricNumber(baseline.getDouble(metric.key), metric.key, locale)
                            getString(R.string.jump_baseline_value, reference, metric.unit, comparison)
                        }
                    }
                }
            }
        }
    }

    private data class JumpHighlight(val key: String, val label: Int, val meaning: Int, val unit: String)

    private fun metricRows(json: JSONObject) {
        val groups = listOf(
            Triple(R.string.group_jump, R.string.explain_jump, listOf("flight_time_ms", "jump_height_cm", "fps")),
            Triple(R.string.group_knees, R.string.explain_knees, listOf("knee_flexion_contact_l", "knee_flexion_contact_r", "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r")),
            Triple(R.string.group_sides, R.string.explain_sides, listOf("knee_ankle_ratio", "flexion_asymmetry_pct", "quality"))
        )
        for ((heading, explanation, keys) in groups) {
            val panel = card()
            section(heading, panel)
            text(getString(explanation), 14, parent = panel)
            for (key in keys) {
            val label = labels.getValue(key)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val caption = TextView(this).apply { text = getString(label); textSize = 14f; setTextColor(ink) }
            row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = number(json, key)
                textSize = 16f
                setTextColor(ink)
                gravity = Gravity.END
                setPadding(dp(12), 0, 0, 0)
            })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        }
    }

    private fun metricWarnings(json: JSONObject) {
        val warnings = json.optJSONArray("warnings") ?: JSONArray()
        if (warnings.length() == 0) return
        val lines = (0 until warnings.length()).map { index -> when (warnings.getString(index)) {
            "low_fps" -> getString(R.string.low_fps)
            "uncertain_timing" -> getString(R.string.uncertain_timing)
            "single_leg_view" -> getString(R.string.single_leg_view)
            "manual_edges" -> getString(R.string.manual_edges)
            "low_visibility" -> getString(R.string.low_visibility)
            "no_flight_detected" -> getString(R.string.no_flight_detected)
            else -> getString(R.string.unknown_warning)
        } }
        notice(getString(R.string.ui_reviews) + "\n" + lines.joinToString("\n"), caution = true)
    }

    internal fun number(json: JSONObject, key: String): String = if (json.isNull(key)) getString(R.string.not_available)
        else metricNumber(json.getDouble(key), key, resources.configuration.locales[0])

    private fun history() {
        if (token().isEmpty()) return login()
        val savedToken = token()
        val savedBase = base()
        work(getString(R.string.loading_history), { JSONArray(api(savedBase, "/api/jumps", token = savedToken)) }, { rows ->
            layout(getString(R.string.ui_jump_history), "history")
            text(getString(R.string.history_summary, rows.length()), 14)
            button(getString(R.string.home)) { home() }
            button(getString(R.string.ui_form_history)) { techniqueHistory() }
            if (rows.length() == 0) notice(getString(R.string.empty_history))
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                // The API returns metadata and metric fields in the same object.
                val metrics = item.optJSONObject("metrics") ?: item
                val panel = card()
                text(getString(R.string.history_item, item.getLong("id"), localDateTime(item.getString("created_at"), locale)), 16, green, panel)
                val hasFlight = hasFlight(metrics)
                text(getString(if (metrics.getString("test") == "cmj") R.string.cmj else R.string.drop_jump), 14, parent = panel)
                if (!hasFlight) text(getString(R.string.no_flight_detected), 14, parent = panel)
                else if (!metrics.isNull("jump_height_cm")) {
                    text(getString(R.string.jump_history_height, number(metrics, "jump_height_cm")), 14, parent = panel)
                }
                button(getString(R.string.view_jump, item.getLong("id")), parent = panel) {
                    layout(getString(R.string.jump_title, item.getLong("id")), "history_detail")
                    if (hasFlight) {
                        jumpHighlights(metrics, item.getLong("id"))
                        metricWarnings(metrics)
                        metricRows(metrics)
                    } else {
                        notice(getString(R.string.no_flight_detected), caution = true)
                        button(getString(R.string.film_again)) {
                            test = metrics.getString("test")
                            selectedSession = null
                            recordJump()
                        }
                    }
                    button(getString(R.string.history)) { history() }
                    button(getString(R.string.home)) { home() }
                }
            }
        }, { home(it) })
    }

    internal fun <T> work(title: String, action: () -> T, success: (T) -> Unit, failure: (String) -> Unit,
        phoneTest: Boolean = false) {
        task?.cancel(true)
        val currentGeneration = ++generation
        layout(title, "busy")
        guiding = phoneTest
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(green)
            indeterminateTintList = ColorStateList.valueOf(green)
            page.addView(this, LinearLayout.LayoutParams(-1, dp(16)))
        }
        progressText = text(getString(R.string.please_wait), 14)
        button(getString(R.string.cancel)) { leaveWork() }
        task = executor.submit {
            try {
                val value = action()
                runOnUiThread { if (!isDestroyed && currentGeneration == generation) {
                    try { success(value) } catch (_: Exception) { failure(getString(R.string.unexpected_response)) }
                } }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed && currentGeneration == generation) {
                    if (e is ApiError && e.status == 401 && token().isNotEmpty()) {
                        prefs.edit().remove("token").apply()
                        login(getString(R.string.session_expired))
                    } else failure(errorMessage(e))
                } }
            }
        }
    }

    private fun errorMessage(e: Exception): String = getString(when (e) {
        is AppError -> e.resource
        is ApiError -> when (e.status) {
            401 -> R.string.unauthorized
            403 -> R.string.forbidden
            429 -> R.string.rate_limit
            else -> R.string.server_failed
        }
        is java.net.SocketTimeoutException -> R.string.timeout
        is IOException -> R.string.unreachable
        is SecurityException -> R.string.video_permission
        else -> R.string.action_failed
    })

    private fun cancelWork() { generation++; task?.cancel(true) }

    private fun leaveWork() { cancelWork(); if (guiding) diagnostics() else home() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            "home" -> super.onBackPressed()
            "busy" -> leaveWork()
            "diagnostics" -> profile()
            "frames" -> { cancelWork(); edits = null; result() }
            "login" -> if (analysis != null) result() else super.onBackPressed()
            else -> home()
        }
    }

    override fun onDestroy() {
        cancelWork()
        executor.shutdownNow()
        clubExecutor.shutdownNow()
        super.onDestroy()
    }
}

/** Dashboard DIGITS precision, without a rounded negative zero. */
internal fun metricNumber(value: Double, key: String, locale: Locale): String {
    val digits = when (key) {
        "flight_time_ms", "fps", "kcal", "calories", "value_kcal", "training_minutes", "descent_time_ms", "ascent_time_ms",
        "median_descent_time_ms", "median_ascent_time_ms" -> 0
        "knee_ankle_ratio", "quality" -> 2
        else -> 1
    }
    val format = "%.${digits}f"
    val result = String.format(locale, format, value)
    return if (result == String.format(locale, format, -0.0)) String.format(locale, format, 0.0) else result
}
