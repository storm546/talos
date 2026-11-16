package jumpscan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
    private var uploadedId: Long? = null
    private var test = "cmj"
    private lateinit var page: LinearLayout
    private var screen = "login"
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    private val green = Color.rgb(22, 102, 91)
    private val ink = Color.rgb(23, 42, 37)
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
        if (!openVideo(intent)) {
            if (token().isEmpty()) login() else home()
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
        } else startAnalysis(uri)
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun token() = prefs.getString("token", "").orEmpty()
    private fun base() = prefs.getString("server", if (BuildConfig.DEBUG) "http://10.0.2.2:8000" else "").orEmpty()

    private fun layout(title: String, name: String) {
        screen = name
        progress = null
        progressText = null
        val scroll = ScrollView(this).apply { isFillViewport = true }
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
        text(title, 28).apply { setPadding(0, dp(10), 0, dp(14)) }
    }

    private fun text(value: String, size: Int = 16, color: Int = ink): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
        page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(green)
        setOnClickListener { action() }
        page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val caption = text(label, 14)
        return EditText(this).apply {
            id = View.generateViewId()
            caption.labelFor = id
            inputType = type
            setSingleLine(true)
            setText(value)
            minHeight = dp(48)
            page.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun login(message: String? = null) {
        layout(getString(R.string.sign_in), "login")
        text(getString(R.string.login_intro))
        if (message != null) text(message, 15, Color.rgb(157, 45, 36))
        val url = field(getString(R.string.server_url), base(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val email = field(getString(R.string.email), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field(getString(R.string.password), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        button(getString(R.string.sign_in)) {
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
            }, { login(it) })
        }
        button(getString(R.string.continue_offline)) { if (analysis != null) result() else home() }
        text(getString(R.string.privacy), 14)
    }

    private fun home(message: String? = null) {
        layout(getString(R.string.home_title), "home")
        if (message != null) text(message, 15, Color.rgb(157, 45, 36))
        text(getString(R.string.guide_short))
        text(getString(R.string.choose_test), 18)
        val types = RadioGroup(this)
        listOf("cmj" to R.string.cmj, "drop_jump" to R.string.drop_jump).forEach { (value, label) ->
            types.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                minHeight = dp(48)
                isChecked = test == value
                setOnClickListener { test = value }
            })
        }
        page.addView(types)
        button(getString(R.string.record)) {
            launchVideo(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1), 10)
        }
        button(getString(R.string.pick_video)) {
            val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            launchVideo(pick.setType("video/*"), 11)
        }
        text(getString(R.string.analysis_hint), 14)
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

    private fun startAnalysis(uri: Uri) {
        analysis = null
        uploadedId = null
        val expectedGeneration = generation + 1
        work(getString(R.string.analysing), { analyzeVideo(this, uri, test) { value ->
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
        metricRows(JSONObject(current.metrics.toJson()))
        if (uploadedId == null) button(if (token().isEmpty()) getString(R.string.sign_in_upload) else getString(R.string.upload)) {
            if (token().isEmpty()) login() else {
                val savedToken = token()
                val savedBase = base()
                work(getString(R.string.uploading), {
                    JSONObject(api(savedBase, "/api/jumps", "POST", savedToken, JSONObject(current.metrics.toJson()))).getLong("id")
                }, { uploadedId = it; result() }, { result(); text(it, 15, Color.rgb(157, 45, 36)) })
            }
        } else text(getString(R.string.uploaded, uploadedId), 16, green)
        if (token().isNotEmpty()) button(getString(R.string.history)) { history() }
        button(getString(R.string.home)) { home() }
        text(getString(R.string.no_video_upload), 13)
    }

    private fun metricRows(json: JSONObject) {
        labels.forEach { (key, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
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
        val warnings = json.optJSONArray("warnings") ?: JSONArray()
        if (warnings.length() == 0) text(getString(R.string.no_warnings), 14, green)
        else for (i in 0 until warnings.length()) text(when (warnings.getString(i)) {
            "low_fps" -> getString(R.string.low_fps)
            "uncertain_timing" -> getString(R.string.uncertain_timing)
            "low_visibility" -> getString(R.string.low_visibility)
            "no_flight_detected" -> getString(R.string.no_flight_detected)
            else -> getString(R.string.unknown_warning)
        }, 14, Color.rgb(133, 80, 12))
    }

    private fun number(json: JSONObject, key: String): String = if (json.isNull(key)) getString(R.string.not_available)
        else String.format(Locale.getDefault(), "%.2f", json.getDouble(key))

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
