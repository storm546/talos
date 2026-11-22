package jumpscan.app

import android.util.AtomicFile
import jumpscan.core.JumpMetrics
import jumpscan.core.toJson
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

internal val uploadLock = Any()

internal enum class FeatureWrite(val method: String, val keys: Set<String>, val optional: Set<String> = emptySet()) {
    FORM("POST", setOf("exercise", "side", "view", "fps", "quality", "repetitions", "summary", "warnings"),
        setOf("mode", "source")),
    // A log names a food or a catalogue food, or carries quick-add macros with a label.
    FOOD("POST", setOf("day", "meal"), setOf("food_id", "catalog_id", "grams", "serving_label", "quantity",
        "label", "kcal", "protein_g", "carbs_g", "fat_g")),
    COPY("POST", setOf("from_day", "to_day"), setOf("from_meal", "to_meal")),
    WEIGHT("PUT", setOf("kg")),
    SET("POST", setOf("day", "exercise_id", "set_no", "reps", "load_kg", "rpe", "plan_id"), setOf("workout_client_id")),
    WORKOUT("POST", setOf("started_at", "ended_at", "title", "template_id", "notes")),
    SESSION("POST", setOf("day", "kind", "minutes", "rpe")),
    COMPLETE("PUT", emptySet());

    fun path(day: String): String = when (this) {
        FORM -> "/api/form"
        FOOD -> "/api/food-logs"
        COPY -> "/api/food-logs/copy"
        SET -> "/api/set-logs"
        WORKOUT -> "/api/workouts"
        SESSION -> "/api/training-sessions"
        WEIGHT -> "/api/me/weights/${LocalDate.parse(day)}"
        COMPLETE -> "/api/me/nutrition/days/${LocalDate.parse(day)}/complete"
    }

    fun validate(body: JSONObject) {
        val expected = if (method == "POST") keys + "client_id" else keys
        val present = body.keys().asSequence().toSet()
        require(present.containsAll(expected) && optional.containsAll(present - expected))
        if (method == "POST") UUID.fromString(body.getString("client_id"))
        if (this == FORM) validateFormFields(body)
    }
}

/** The receipt of a copy the server answered with nothing_to_copy. */
internal const val NOTHING_COPIED = -1L

/** Directory identity binds saved numbers to one athlete on one server. Tokens, images and poses are excluded. */
internal class PendingUploads(root: File, server: String, owner: Long) {
    private val directory = File(root, MessageDigest.getInstance("SHA-256")
        .digest("$server\n$owner".toByteArray()).joinToString("") { "%02x".format(it) })

    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(directory, it.name.removeSuffix(".bak")) }.distinctBy { it.name }.sortedBy { it.name }

    fun count(): Int = files().size

    /** Items set aside after a permanent rejection, kept here for diagnosis. */
    fun rejectedCount(): Int = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
        .filter { it.endsWith(".rejected") }.distinct().size

    private fun write(file: File, body: JSONObject) {
        if (!directory.isDirectory && !directory.mkdirs()) throw AppError(R.string.pending_save_failed)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(body.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun enqueue(metrics: JumpMetrics, clientId: String = UUID.randomUUID().toString(), sessionId: Long? = null,
                readiness: Boolean = false): String {
        UUID.fromString(clientId)
        val file = File(directory, "$clientId.json")
        val body = JSONObject(metrics.toJson()).put("client_id", clientId)
        if (readiness) body.put("test", "readiness") // a morning countermovement jump check (SPEC 9)
        if (sessionId != null) body.put("session_id", sessionId)
        write(file, body)
        return file.name
    }

    fun enqueueCheckin(day: String, body: JSONObject) {
        require(LocalDate.parse(day).toString() == day)
        validateCheckin(body)
        write(File(directory, "checkin-$day.json"), body)
    }

    fun checkin(day: String): JSONObject? = files().firstOrNull { it.name == "checkin-$day.json" }?.let { read(it) }

    fun enqueueFeature(kind: FeatureWrite, day: String, values: JSONObject, clientId: String): String {
        UUID.fromString(clientId)
        val body = JSONObject(values.toString())
        if (kind.method == "POST") body.put("client_id", clientId)
        kind.path(day)
        kind.validate(body)
        val stamp = maxOf(System.currentTimeMillis(), (files().lastOrNull { it.name.startsWith("feature-") }
            ?.name?.split('-', limit = 3)?.get(1)?.toLongOrNull() ?: 0L) + 1)
        val name = "feature-$stamp-$clientId.json"
        write(File(directory, name), JSONObject().put("kind", kind.name).put("day", day).put("body", body))
        return name
    }

    fun featurePending(name: String): Boolean = files().any { it.name == name }

    fun featureRejected(clientId: String): Boolean = directory.listFiles().orEmpty().any {
        it.name.startsWith("feature-") && it.name.removeSuffix(".bak").endsWith("-$clientId.json.rejected")
    }

    private fun feature(file: File): Triple<FeatureWrite, String, JSONObject> = try {
        val bytes = AtomicFile(file).readFully()
        require(bytes.size <= 66_000)
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val kind = FeatureWrite.valueOf(envelope.getString("kind"))
        Triple(kind, kind.path(envelope.getString("day")), envelope.getJSONObject("body").also(kind::validate))
    } catch (_: Exception) { throw AppError(R.string.pending_unreadable) }

    private fun read(file: File): JSONObject? = if (!file.exists() && !File(file.path + ".bak").exists()) null else try {
        val bytes = AtomicFile(file).readFully()
        require(bytes.size <= 65_536)
        JSONObject(String(bytes, Charsets.UTF_8)).also { body ->
            if (file.name.startsWith("checkin-")) {
                LocalDate.parse(file.name.removePrefix("checkin-").removeSuffix(".json"))
                validateCheckin(body)
            } else {
                val keys = body.keys().asSequence().toSet()
                require(keys.containsAll(METRIC_KEYS) && (keys - METRIC_KEYS - setOf("session_id", "client_id")).isEmpty())
                if (!body.has("client_id")) {
                    body.put("client_id", UUID.randomUUID().toString())
                    write(file, body) // Persist migration before any network attempt.
                }
                UUID.fromString(body.getString("client_id"))
                if (body.has("session_id")) require(body.getLong("session_id") > 0)
            }
        }
    } catch (_: Exception) { throw AppError(R.string.pending_unreadable) }

    private fun remove(file: File) {
        AtomicFile(file).delete()
        if (file.exists() || File(file.path + ".bak").exists()) throw AppError(R.string.pending_remove_failed)
    }

    /** Move an item aside for diagnosis so it never blocks the rest of the queue. */
    private fun quarantine(file: File) {
        val bak = File(file.path + ".bak")
        if (bak.exists() && !bak.renameTo(File(file.path + ".rejected.bak"))) throw AppError(R.string.pending_save_failed)
        if (file.exists() && !file.renameTo(File(file.path + ".rejected"))) throw AppError(R.string.pending_save_failed)
    }

    fun sendJump(name: String, post: (JSONObject) -> Long): Long? {
        require(!name.contains('/') && !name.startsWith("checkin-"))
        val file = File(directory, name)
        val body = read(file) ?: return null // Already sent and removed.
        val id = try {
            post(body)
        } catch (e: ApiError) {
            // A jump rejected only for its session is retried once without it, same client_id.
            if (e.status == 422 && e.code == "jump_session_unavailable" && body.has("session_id")) {
                body.remove("session_id")
                post(body)
            } else throw e
        }
        remove(file)
        return id
    }

    fun sendCheckin(day: String, put: (String, JSONObject) -> Unit) {
        val file = File(directory, "checkin-$day.json")
        val body = read(file) ?: return // Already sent and removed.
        put(day, body)
        remove(file)
    }

    /** A permanent rejection or unreadable file is set aside and the rest still send; transient errors stop. */
    fun send(sent: MutableMap<String, Long>, put: (String, JSONObject) -> Unit = { _, _ ->
        throw AppError(R.string.pending_unreadable)
    }, featureSend: (FeatureWrite, String, JSONObject) -> Long = { _, _, _ ->
        throw AppError(R.string.pending_unreadable)
    }, post: (JSONObject) -> Long) {
        for (file in files()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                if (file.name.startsWith("feature-")) {
                    val (kind, path, body) = feature(file)
                    val id = featureSend(kind, path, body)
                    remove(file)
                    sent[file.name] = id
                } else if (file.name.startsWith("checkin-")) {
                    sendCheckin(file.name.removePrefix("checkin-").removeSuffix(".json"), put)
                } else sendJump(file.name, post)?.let { sent[file.name] = it }
            } catch (e: ApiError) {
                // An empty source day leaves nothing to keep or retry; the caller shows why (SPEC 7.4).
                if (e.code == "nothing_to_copy") { remove(file); sent[file.name] = NOTHING_COPIED }
                else if (e.status in 400..499 && e.status !in setOf(401, 408, 429)) quarantine(file) else throw e
            } catch (e: AppError) {
                if (e.resource == R.string.pending_unreadable) quarantine(file) else throw e
            }
        }
    }

    private fun validateCheckin(body: JSONObject) {
        // Check-ins queued before the readiness fields existed carry only the base keys.
        val keys = body.keys().asSequence().toSet()
        require(keys.containsAll(checkinBaseKeys) && checkinKeys.containsAll(keys))
        fun optional(key: String) = if (!body.has(key) || body.isNull(key)) "" else body.getString(key)
        checkinBody(listOf("sleep", "fatigue", "soreness", "stress", "mood").map { body.getInt(it) },
            optional("training_minutes"), optional("rpe"), optional("resting_hr"), optional("hrv_rmssd"),
            optional("bedtime"), optional("wake_time"))
    }

    private companion object {
        val METRIC_KEYS = setOf("test", "fps", "flight_time_ms", "jump_height_cm", "knee_flexion_contact_l",
            "knee_flexion_contact_r", "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r",
            "knee_ankle_ratio", "flexion_asymmetry_pct", "quality", "warnings")
    }
}
