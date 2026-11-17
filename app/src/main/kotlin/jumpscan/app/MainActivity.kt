package jumpscan.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var generation = 0
    private val prefs by lazy { getSharedPreferences("account", MODE_PRIVATE) }
    private var analysis: Analysis? = null
    private data class FrameEdits(var index: Int, var takeoffMs: Long?, var landingMs: Long?)
    private var edits: FrameEdits? = null
    private var uploadedId: Long? = null
    private var queuedId: String? = null
    private var queueSending = false
    private val pendingRoot by lazy { java.io.File(filesDir, "pending") }
    private fun pending() = PendingUploads(pendingRoot, base(), prefs.getLong("user_id", 0))
    private var test = "cmj"
    private lateinit var page: LinearLayout
    private var screen = "login"
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    private val green by lazy { getColor(R.color.accent) }
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
        if (retained != null) { analysis = retained.analysis; uploadedId = retained.uploadedId; test = retained.test; queuedId = retained.queuedId; edits = retained.edits }
        if (analysis != null) { if (edits != null) frameEditor() else result() } else if (!openVideo(intent)) {
            if (token().isEmpty()) login() else home()
        }
        sendPending(automatic = true)
    }

    private data class RetainedResult(val analysis: Analysis?, val uploadedId: Long?, val test: String, val queuedId: String?, val edits: FrameEdits?)

    @Deprecated("Uses the platform non-configuration state API")
    override fun onRetainNonConfigurationInstance(): Any = RetainedResult(analysis, uploadedId, test, queuedId, edits)

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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun token() = prefs.getString("token", "").orEmpty()
    private fun base() = prefs.getString("server", if (BuildConfig.DEBUG) "http://10.0.2.2:8000" else "").orEmpty()

    private fun layout(title: String, name: String) {
        screen = name
        progress = null
        progressText = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.paper))
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
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
        text(getString(R.string.brand), 12, green).apply { letterSpacing = 0.16f }
        text(title, 28).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(18)) }
    }

    private fun text(value: String, size: Int = 16, color: Int = if (size <= 14) muted else ink): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
        page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(label: String, primary: Boolean = false, parent: LinearLayout = page,
                       action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = 0
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTypeface(null, Typeface.BOLD)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) Color.WHITE else green)))
        fun shape(fill: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(5).toFloat()
            setStroke(dp(1), if (primary) fill else getColor(R.color.line))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(getColor(R.color.hover)))
            addState(intArrayOf(), shape(if (primary) green else Color.WHITE))
        }
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(if (primary) getColor(R.color.accent_pressed)
            else getColor(R.color.hover)), states, null)
        stateListAnimator = null
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val caption = text(label, 14).apply { setPadding(0, dp(14), 0, dp(6)) }
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
                setColor(Color.WHITE)
                cornerRadius = dp(5).toFloat()
                setStroke(dp(1), ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(green, getColor(R.color.line))))
            }
            minHeight = dp(48)
            page.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun login(message: String? = null) {
        layout(getString(R.string.sign_in), "login")
        text(getString(R.string.login_intro))
        if (message != null) text(message, 15, getColor(R.color.error))
        val url = field(getString(R.string.server_url), base(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val email = field(getString(R.string.email), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field(getString(R.string.password), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
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
                prefs.edit().putString("server", address).putString("token", newToken)
                    .putString("name", name).putLong("user_id", id).apply()
                if (analysis != null) result() else home()
                sendPending(automatic = true)
            }, { login(it) })
        }
        button(getString(R.string.continue_offline)) { if (analysis != null) result() else home() }
        text(getString(R.string.privacy), 14)
    }

    private fun home(message: String? = null) {
        layout(getString(R.string.home_title), "home")
        if (message != null) text(message, 15, getColor(R.color.error))
        text(getString(R.string.guide_short))
        text(getString(R.string.choose_test), 18).setPadding(0, dp(20), 0, dp(8))
        val types = RadioGroup(this)
        listOf("cmj" to R.string.cmj, "drop_jump" to R.string.drop_jump).forEach { (value, label) ->
            types.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                setTextColor(ink)
                buttonTintList = ColorStateList.valueOf(green)
                minHeight = dp(48)
                isChecked = test == value
                setOnClickListener { test = value }
            })
        }
        page.addView(types)
        button(getString(R.string.record), primary = true) {
            filmingGuide { launchVideo(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1), 10) }
        }
        button(getString(R.string.pick_video)) {
            val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            filmingGuide { launchVideo(pick.setType("video/*"), 11) }
        }
        text(getString(R.string.analysis_hint), 14)
        pendingControls()
        if (analysis != null) button(getString(R.string.last_result)) { result() }
        if (token().isNotEmpty()) {
            text(getString(R.string.signed_in, prefs.getString("name", "")), 14)
            button(getString(R.string.history)) { history() }
            button(getString(R.string.sign_out)) {
                val savedToken = token()
                val savedBase = base()
                work(getString(R.string.signing_out), { api(savedBase, "/api/auth/logout", "POST", savedToken) }, {
                    prefs.edit().remove("token").remove("name").remove("user_id").apply()
                    login()
                }, { error -> home(getString(R.string.signout_error, error)) })
            }
        } else button(getString(R.string.sign_in)) { login() }
        text(getString(R.string.coach_note), 14)
    }

    private fun filmingGuide(continueAction: () -> Unit) {
        layout(getString(R.string.filming_guide), "guide")
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.filming_guide)
            contentDescription = getString(R.string.guide_diagram)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(-1, dp(140)))
        for (line in listOf(R.string.guide_phone, R.string.guide_body, R.string.guide_face,
                R.string.guide_fps, R.string.guide_slow_motion, R.string.guide_still, R.string.guide_light)) {
            text(getString(line)).setPadding(0, dp(8), 0, dp(8))
        }
        button(getString(R.string.continue_video), action = continueAction)
        button(getString(R.string.cancel)) { home() }
    }

    @Suppress("DEPRECATION")
    private fun launchVideo(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request) }
        catch (_: Exception) { home(getString(R.string.no_video_app)) }
    }

    @Deprecated("Uses the platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode in 10..11 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) startAnalysis(uri) else home(getString(R.string.no_video_returned))
        }
    }

    private fun startAnalysis(uri: Uri, mode: String = "full") {
        analysis = null
        edits = null
        uploadedId = null
        queuedId = null
        val expectedGeneration = generation + 1
        work(getString(R.string.analysing), { analyzeVideo(this, uri, test, mode) { value ->
            runOnUiThread {
                if (screen == "busy" && !isDestroyed && generation == expectedGeneration) {
                    progress?.isIndeterminate = false
                    progress?.progress = value
                    progressText?.text = getString(R.string.progress, value)
                }
            }
        } }, { analysis = it; result() }, { home(it) })
        progress?.isIndeterminate = false
        text(getString(R.string.first_ten), 14)
    }

    private fun result() {
        val current = analysis ?: return home()
        layout(getString(R.string.result), "result")
        text(if (current.metrics.test == "cmj") getString(R.string.cmj) else getString(R.string.drop_jump), 18)
        if (current.truncated) text(getString(R.string.truncated), 14)
        text(getString(R.string.depth_note), 14)
        text(edgeTimes(current.landing.takeoffMs, current.landing.contactMs), 16)
        if ("manual_edges" in current.metrics.warnings) text(getString(R.string.manual_edges), 16, green)
        if ("no_flight_detected" in current.metrics.warnings) {
            text(getString(R.string.no_flight_help), 16)
            button(getString(R.string.film_again)) { home() }
        }
        if ("low_visibility" in current.metrics.warnings) text(getString(R.string.visibility_help), 16)
        button(getString(R.string.check_frames)) {
            val start = current.landing.takeoffMs ?: peakFlexionFrame(current.frames).tMs
            edits = FrameEdits(current.timesUs.indexOfFirst { it / 1000 == start }.coerceAtLeast(0),
                current.landing.takeoffMs, current.landing.contactMs)
            frameEditor()
        }
        text(getString(if (current.contactFound) R.string.landing_frame else R.string.peak_frame), 18)
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
        metricRows(JSONObject(current.metrics.toJson()), showNoWarnings = !current.previewUnclear)
        if (uploadedId == null && queuedId == null) button(if (token().isEmpty()) getString(R.string.sign_in_upload) else getString(R.string.upload), primary = true) {
            if (token().isEmpty()) login() else {
                val savedToken = token()
                val savedBase = base()
                val queue = pending()
                work(getString(R.string.uploading), {
                    synchronized(uploadLock) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        try {
                            val id = JSONObject(api(savedBase, "/api/jumps", "POST", savedToken,
                                JSONObject(current.metrics.toJson()))).getLong("id")
                            id to null
                        } catch (e: IOException) {
                            if (e is ApiError || e is AppError || Thread.currentThread().isInterrupted) throw e
                            try { null to queue.enqueue(current.metrics) }
                            catch (_: IOException) { throw AppError(R.string.pending_save_failed) }
                        }
                    }
                }, { (id, pendingId) -> uploadedId = id; queuedId = pendingId; result() },
                    { result(); text(it, 15, getColor(R.color.error)) })
            }
        }
        if (uploadedId != null) text(getString(R.string.uploaded, uploadedId), 16, green)
        if (queuedId != null) text(getString(R.string.saved_pending), 16, green)
        pendingControls()
        if (token().isNotEmpty()) button(getString(R.string.history)) { history() }
        button(getString(R.string.home)) { home() }
        text(getString(R.string.no_video_upload), 13)
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

    private fun pendingControls() {
        val count = if (token().isNotEmpty()) pending().count() else
            pendingRoot.walkTopDown().count { it.isFile && it.name.endsWith(".json") }
        text(getString(R.string.pending_count, count), 14).setPadding(0, dp(18), 0, dp(8))
        if (count > 0) {
            if (token().isEmpty()) text(getString(R.string.pending_sign_in), 14)
            else button(getString(if (queueSending) R.string.sending_pending else R.string.send_pending)) {
                sendPending(automatic = false)
            }.isEnabled = !queueSending
        }
    }

    private fun sendPending(automatic: Boolean) {
        if (queueSending || token().isEmpty() || pending().count() == 0) return
        val savedToken = token()
        val savedBase = base()
        val savedOwner = prefs.getLong("user_id", 0)
        val queue = pending()
        queueSending = true
        if (screen == "result") result() else if (screen == "home") home()
        executor.submit {
            var error: Exception? = null
            val sent = linkedMapOf<String, Long>()
            try {
                synchronized(uploadLock) {
                    queue.send(sent) { metrics ->
                        JSONObject(api(savedBase, "/api/jumps", "POST", savedToken, metrics)).getLong("id")
                    }
                }
            } catch (e: Exception) { error = e }
            runOnUiThread {
                queueSending = false
                if (!isDestroyed && token() == savedToken && base() == savedBase && prefs.getLong("user_id", 0) == savedOwner) {
                    queuedId?.let { id -> sent[id]?.let { uploadedId = it; queuedId = null } }
                    if (error is ApiError && (error as ApiError).status == 401) {
                        prefs.edit().remove("token").apply()
                        if (screen != "busy") login(getString(R.string.session_expired))
                    } else {
                        if (screen == "result") result() else if (screen == "home") home()
                        if (error != null && error !is InterruptedException) {
                            Toast.makeText(this, errorMessage(error!!), Toast.LENGTH_LONG).show()
                        } else if (!automatic) Toast.makeText(this, getString(R.string.pending_sent), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun metricRows(json: JSONObject, showNoWarnings: Boolean = true) {
        val groups = listOf(
            Triple(R.string.group_jump, R.string.explain_jump, listOf("flight_time_ms", "jump_height_cm", "fps")),
            Triple(R.string.group_knees, R.string.explain_knees, listOf("knee_flexion_contact_l", "knee_flexion_contact_r", "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r")),
            Triple(R.string.group_sides, R.string.explain_sides, listOf("knee_ankle_ratio", "flexion_asymmetry_pct", "quality"))
        )
        for ((heading, explanation, keys) in groups) {
            text(getString(heading), 21).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(24), 0, dp(6)) }
            text(getString(explanation), 14)
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
            page.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        }
        val warnings = json.optJSONArray("warnings") ?: JSONArray()
        if (warnings.length() == 0) { if (showNoWarnings) text(getString(R.string.no_warnings), 14, green) }
        else for (i in 0 until warnings.length()) text(when (warnings.getString(i)) {
            "low_fps" -> getString(R.string.low_fps)
            "uncertain_timing" -> getString(R.string.uncertain_timing)
            "single_leg_view" -> getString(R.string.single_leg_view)
            "manual_edges" -> getString(R.string.manual_edges)
            "low_visibility" -> getString(R.string.low_visibility)
            "no_flight_detected" -> getString(R.string.no_flight_detected)
            else -> getString(R.string.unknown_warning)
        }, 14, warning)
    }

    private fun number(json: JSONObject, key: String): String = if (json.isNull(key)) getString(R.string.not_available)
        else metricNumber(json.getDouble(key), key, resources.configuration.locales[0])

    private fun history() {
        if (token().isEmpty()) return login()
        val savedToken = token()
        val savedBase = base()
        work(getString(R.string.loading_history), { JSONArray(api(savedBase, "/api/jumps", token = savedToken)) }, { rows ->
            layout(getString(R.string.your_history), "history")
            text(getString(R.string.history_summary, rows.length()), 14)
            button(getString(R.string.home)) { home() }
            if (rows.length() == 0) text(getString(R.string.empty_history))
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                // The API returns metadata and metric fields in the same object.
                val metrics = item.optJSONObject("metrics") ?: item
                text(getString(R.string.history_item, item.getLong("id"), item.getString("created_at").replace('T', ' ').take(19)), 16, green)
                text(getString(R.string.history_numbers,
                    getString(if (metrics.getString("test") == "cmj") R.string.cmj else R.string.drop_jump),
                    number(metrics, "knee_flexion_peak_l"), number(metrics, "knee_flexion_peak_r"),
                    number(metrics, "knee_ankle_ratio")), 14)
                button(getString(R.string.view_jump, item.getLong("id"))) {
                    layout(getString(R.string.jump_title, item.getLong("id")), "history_detail")
                    metricRows(metrics)
                    button(getString(R.string.history)) { history() }
                    button(getString(R.string.home)) { home() }
                }
            }
        }, { home(it) })
    }

    private fun <T> work(title: String, action: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        task?.cancel(true)
        val currentGeneration = ++generation
        layout(title, "busy")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(green)
            indeterminateTintList = ColorStateList.valueOf(green)
            page.addView(this, LinearLayout.LayoutParams(-1, dp(16)))
        }
        progressText = text(getString(R.string.please_wait), 14)
        button(getString(R.string.cancel)) { cancelWork(); home() }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            "home" -> super.onBackPressed()
            "busy" -> { cancelWork(); home() }
            "frames" -> { cancelWork(); edits = null; result() }
            "login" -> if (analysis != null) result() else home()
            else -> home()
        }
    }

    override fun onDestroy() {
        cancelWork()
        executor.shutdownNow()
        super.onDestroy()
    }
}

/** Dashboard DIGITS precision, without a rounded negative zero. */
internal fun metricNumber(value: Double, key: String, locale: Locale): String {
    val digits = when (key) {
        "flight_time_ms", "fps" -> 0
        "knee_ankle_ratio", "quality" -> 2
        else -> 1
    }
    val format = "%.${digits}f"
    val result = String.format(locale, format, value)
    return if (result == String.format(locale, format, -0.0)) String.format(locale, format, 0.0) else result
}
