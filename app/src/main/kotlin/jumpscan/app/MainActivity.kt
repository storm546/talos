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
        "fps" to "Analysed frames / second",
        "flight_time_ms" to "Flight time (ms)",
        "jump_height_cm" to "Jump height (cm)",
        "knee_flexion_contact_l" to "Contact knee flexion · left (°)",
        "knee_flexion_contact_r" to "Contact knee flexion · right (°)",
        "knee_flexion_peak_l" to "Peak knee flexion · left (°)",
        "knee_flexion_peak_r" to "Peak knee flexion · right (°)",
        "fppa_peak_l" to "FPPA at peak · left (°)",
        "fppa_peak_r" to "FPPA at peak · right (°)",
        "knee_ankle_ratio" to "Knee / ankle separation",
        "flexion_asymmetry_pct" to "Peak flexion asymmetry (%)",
        "quality" to "Landmark visibility (0–1)"
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
            home("Open a local video from your camera or files.")
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
        text("JUMPSCAN", 12, green).apply { letterSpacing = 0.16f }
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
        layout("Sign in", "login")
        text("Sign in to upload numbers and see your history. Video analysis works offline.")
        if (message != null) text(message, 15, Color.rgb(157, 45, 36))
        val url = field("Server URL", base(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val email = field("Email", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("Password", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        button("Sign in") {
            val address = try { serverUrl(url.text.toString()) } catch (e: IOException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return@button
            }
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            if (emailValue.isBlank() || passwordValue.isBlank()) {
                Toast.makeText(this, "Enter your email and password.", Toast.LENGTH_LONG).show()
                return@button
            }
            password.text.clear()
            work("Signing in…", {
                val response = JSONObject(api(address, "/api/auth/login", "POST", body =
                    JSONObject().put("email", emailValue).put("password", passwordValue)))
                val newToken = response.getString("token")
                val user = response.getJSONObject("user")
                if (user.getString("role") != "athlete") {
                    runCatching { api(address, "/api/auth/logout", "POST", newToken) }
                    throw IOException("Use an athlete account here. Coaches can use the web dashboard.")
                }
                Triple(newToken, user.getString("name"), user.getLong("id"))
            }, { (newToken, name, id) ->
                prefs.edit().putString("server", address).putString("token", newToken)
                    .putString("name", name).putLong("user_id", id).apply()
                if (analysis != null) result() else home()
            }, { login(it) })
        }
        button("Continue offline") { if (analysis != null) result() else home() }
        text("Only numbers are uploaded. Your video and pose landmarks stay on this device.", 14)
    }

    private fun home(message: String? = null) {
        layout("Landing mechanics", "home")
        if (message != null) text(message, 15, Color.rgb(157, 45, 36))
        text("Camera at knee height, facing the athlete, whole body in frame.")
        text("Choose a test", 18)
        val types = RadioGroup(this)
        listOf("cmj" to "Countermovement jump", "drop_jump" to "Drop jump").forEach { (value, label) ->
            types.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                minHeight = dp(48)
                isChecked = test == value
                setOnClickListener { test = value }
            })
        }
        page.addView(types)
        button("Record") {
            launchVideo(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1), 10)
        }
        button("Pick video") {
            val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            launchVideo(pick.setType("video/*"), 11)
        }
        text("Analysis uses the first 10 seconds. Keep still briefly before and after the jump.", 14)
        if (analysis != null) button("Last result") { result() }
        if (token().isNotEmpty()) {
            text("Signed in as ${prefs.getString("name", "athlete")}", 14)
            button("History") { history() }
            button("Sign out") {
                val savedToken = token()
                val savedBase = base()
                work("Signing out…", { api(savedBase, "/api/auth/logout", "POST", savedToken) }, {
                    prefs.edit().remove("token").remove("name").remove("user_id").apply()
                    login()
                }, { error -> home("Could not sign out: $error") })
            }
        } else button("Sign in") { login() }
        text("Numbers to discuss with a coach. Single-camera depth is an estimate; compare your own jumps over time.", 14)
    }

    @Suppress("DEPRECATION")
    private fun launchVideo(intent: Intent, request: Int) {
        try { startActivityForResult(intent, request) }
        catch (_: Exception) { home("No app can open this action. Use Pick video or open a video with JumpScan.") }
    }

    @Deprecated("Uses the platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode in 10..11 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) startAnalysis(uri) else home("No video returned. Try Pick video.")
        }
    }

    private fun startAnalysis(uri: Uri) {
        analysis = null
        uploadedId = null
        val expectedGeneration = generation + 1
        work("Analysing video…", { analyzeVideo(this, uri, test) { value ->
            runOnUiThread {
                if (screen == "busy" && !isDestroyed && generation == expectedGeneration) {
                    progress?.isIndeterminate = false
                    progress?.progress = value
                    progressText?.text = "$value% · Video stays on this device"
                }
            }
        } }, { analysis = it; result() }, { home(it) })
        progress?.isIndeterminate = false
        text("Only the first 10 seconds are analysed.", 14)
    }

    private fun result() {
        val current = analysis ?: return home()
        layout("Your result", "result")
        text(if (current.metrics.test == "cmj") "Countermovement jump" else "Drop jump", 18)
        if (current.truncated) text("Clip longer than 10 seconds. Only the first 10 seconds were analysed.", 14)
        text("Numbers to discuss with a coach. Depth is an estimate.", 14)
        metricRows(JSONObject(current.metrics.toJson()))
        if (uploadedId == null) button(if (token().isEmpty()) "Sign in to upload" else "Upload numbers") {
            if (token().isEmpty()) login() else {
                val savedToken = token()
                val savedBase = base()
                work("Uploading numbers…", {
                    JSONObject(api(savedBase, "/api/jumps", "POST", savedToken, JSONObject(current.metrics.toJson()))).getLong("id")
                }, { uploadedId = it; result() }, { result(); text(it, 15, Color.rgb(157, 45, 36)) })
            }
        } else text("Uploaded · jump #$uploadedId", 16, green)
        if (token().isNotEmpty()) button("History") { history() }
        button("Home") { home() }
        text("No video or landmarks are uploaded.", 13)
    }

    private fun metricRows(json: JSONObject) {
        labels.forEach { (key, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val caption = TextView(this).apply { text = label; textSize = 14f; setTextColor(ink) }
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
        if (warnings.length() == 0) text("Warnings: none", 14, green)
        else for (i in 0 until warnings.length()) text(when (warnings.getString(i)) {
            "low_fps" -> "Low frame rate: timing may be less precise."
            "low_visibility" -> "Low visibility: keep hips, knees and feet in view."
            "no_flight_detected" -> "No flight detected. Numbers use the start of the clip."
            else -> "Analysis warning: ${warnings.getString(i)}"
        }, 14, Color.rgb(133, 80, 12))
    }

    private fun number(json: JSONObject, key: String): String = if (json.isNull(key)) "Not available"
        else String.format(Locale.US, "%.2f", json.getDouble(key))

    private fun history() {
        if (token().isEmpty()) return login()
        val savedToken = token()
        val savedBase = base()
        work("Loading history…", { JSONArray(api(savedBase, "/api/jumps", token = savedToken)) }, { rows ->
            layout("Your history", "history")
            text("${rows.length()} jumps · newest first", 14)
            button("Home") { home() }
            if (rows.length() == 0) text("No uploaded jumps yet. Analyse a video, then upload your numbers.")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                // The API returns metadata and metric fields in the same object.
                val metrics = item.optJSONObject("metrics") ?: item
                text("#${item.getLong("id")} · ${item.getString("created_at").replace('T', ' ').take(19)}", 16, green)
                text("${if (metrics.getString("test") == "cmj") "Countermovement jump" else "Drop jump"}\n" +
                    "Peak flexion L / R: ${number(metrics, "knee_flexion_peak_l")}° / ${number(metrics, "knee_flexion_peak_r")}°\n" +
                    "Knee / ankle separation: ${number(metrics, "knee_ankle_ratio")}", 14)
                button("View numbers for jump #${item.getLong("id")}") {
                    layout("Jump #${item.getLong("id")}", "history_detail")
                    metricRows(metrics)
                    button("History") { history() }
                    button("Home") { home() }
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
        progressText = text("Please wait…", 14)
        button("Cancel") { cancelWork(); home() }
        task = executor.submit {
            try {
                val value = action()
                runOnUiThread { if (!isDestroyed && currentGeneration == generation) {
                    try { success(value) } catch (_: Exception) { failure("Unexpected server response. Please try again.") }
                } }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed && currentGeneration == generation) {
                    if (e is ApiError && e.status == 401 && token().isNotEmpty()) {
                        prefs.edit().remove("token").apply()
                        login("Your session expired. Sign in again.")
                    } else failure(when (e) {
                        is java.net.SocketTimeoutException -> "Connection timed out. Check the server URL and try again."
                        is java.net.ConnectException, is java.net.UnknownHostException -> "Cannot reach the server. Check the server URL and your connection."
                        is SecurityException -> "Cannot read this video. Open it again from Pick video."
                        else -> e.message?.take(300) ?: "Could not complete this action. Please try again."
                    })
                } }
            }
        }
    }

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
