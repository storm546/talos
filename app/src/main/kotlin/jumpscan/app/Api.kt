package jumpscan.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal class ApiError(val status: Int, message: String) : IOException(message)

internal fun serverUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    val uri = try { URI(value) } catch (_: Exception) { throw IOException("Enter a valid server URL.") }
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        (uri.scheme != "https" && !(BuildConfig.DEBUG && uri.scheme == "http"))) {
        throw IOException(if (BuildConfig.DEBUG) "Enter an http:// or https:// server URL."
            else "Enter an https:// server URL.")
    }
    return value
}

// Only credentials and metrics JSON enter this function. Video and pose data stay on the device.
internal fun api(base: String, path: String, method: String = "GET", token: String = "", body: JSONObject? = null): String {
    val connection = URI(serverUrl(base) + path).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (result.length + count > 1_048_576) throw IOException("Server response is too large.")
                result.append(buffer, 0, count)
            }
            result.toString()
        }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(response).optString("detail").takeIf { it.isNotBlank() } }.getOrNull()
            throw ApiError(status, when (status) {
                401 -> "Login failed or your session expired. Please sign in again."
                403 -> "This account cannot perform this action. Uploads need an athlete account."
                429 -> "Too many login attempts. Please try again later."
                else -> detail?.take(300) ?: "Server request failed ($status). Try again."
            })
        }
        return response
    } finally { connection.disconnect() }
}
