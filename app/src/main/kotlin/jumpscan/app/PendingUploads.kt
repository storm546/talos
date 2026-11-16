package jumpscan.app

import android.util.AtomicFile
import jumpscan.core.JumpMetrics
import jumpscan.core.toJson
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal val uploadLock = Any()

/** Directory identity binds metrics to one athlete on one server. Tokens, images and poses are excluded. */
internal class PendingUploads(root: File, server: String, owner: Long) {
    private val directory = File(root, MessageDigest.getInstance("SHA-256")
        .digest("$server\n$owner".toByteArray()).joinToString("") { "%02x".format(it) })

    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(directory, it.name.removeSuffix(".bak")) }.distinctBy { it.name }.sortedBy { it.name }

    fun count(): Int = files().size

    fun enqueue(metrics: JumpMetrics): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw AppError(R.string.pending_save_failed)
        val file = File(directory, "${UUID.randomUUID()}.json")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(metrics.toJson().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
        return file.name
    }

    /** Delete only after a successful POST. HTTP failures and unreadable files stay available for recovery. */
    fun send(sent: MutableMap<String, Long>, post: (JSONObject) -> Long) {
        for (file in files()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val metrics = try {
                require(file.length() <= 65_536)
                JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8)).also {
                    require(it.keys().asSequence().toSet() == METRIC_KEYS)
                }
            } catch (_: Exception) { throw AppError(R.string.pending_unreadable) }
            val id = post(metrics)
            AtomicFile(file).delete()
            if (file.exists()) throw AppError(R.string.pending_remove_failed)
            sent[file.name] = id
        }
    }

    private companion object {
        val METRIC_KEYS = setOf("test", "fps", "flight_time_ms", "jump_height_cm", "knee_flexion_contact_l",
            "knee_flexion_contact_r", "knee_flexion_peak_l", "knee_flexion_peak_r", "fppa_peak_l", "fppa_peak_r",
            "knee_ankle_ratio", "flexion_asymmetry_pct", "quality", "warnings")
    }
}
