package jumpscan.app

import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal class ApiError(val status: Int, response: String = "") : IOException() {
    val code = try { JSONObject(response).opt("code") as? String } catch (_: JSONException) { null }
}
internal class AppError(val resource: Int) : IOException()

internal fun serverUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    val uri = try { URI(value) } catch (_: Exception) { throw AppError(R.string.invalid_url) }
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        (uri.scheme != "https" && !(BuildConfig.DEBUG && uri.scheme == "http"))) {
        throw AppError(if (BuildConfig.DEBUG) R.string.debug_url else R.string.https_url)
    }
    return value
}

// Only account, club and metrics JSON enter this function. Video and pose data stay on the device.
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
                if (result.length + count > 1_048_576) throw AppError(R.string.response_large)
                result.append(buffer, 0, count)
            }
            result.toString()
        }.orEmpty()
        if (status !in 200..299) {
            throw ApiError(status, response)
        }
        return response
    } finally { connection.disconnect() }
}
