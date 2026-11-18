package jumpscan.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun todayLocal(clock: Clock = Clock.systemDefaultZone()): String = LocalDate.now(clock).toString()

internal fun localDate(value: String, locale: Locale): String =
    LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

internal fun localDateTime(value: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(zone).format(Instant.parse(value))

internal data class ClubSession(val id: Long, val title: String, val startsAt: String, val place: String,
    val test: String, val trials: Int, val jumpCount: Int, val cancelled: Boolean)

internal fun parseSessions(value: String): List<ClubSession> {
    val rows = JSONArray(value)
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        ClubSession(row.getLong("id"), row.getString("title"), row.getString("starts_at"),
            row.getString("place"), row.getString("test"), row.getInt("trials"),
            row.getInt("jump_count"), when (row.get("cancelled")) {
                true, 1 -> true
                false, 0 -> false
                else -> throw IllegalArgumentException("Invalid session flag")
            }).also {
            require(it.id > 0 && it.test in listOf("cmj", "drop_jump") && it.trials in 1..5 && it.jumpCount >= 0)
            Instant.parse(it.startsAt)
        }
    }.sortedBy { Instant.parse(it.startsAt) }
}

internal val checkinKeys = setOf("sleep", "fatigue", "soreness", "stress", "mood", "training_minutes", "rpe")

internal fun checkinBody(scores: List<Int>, minutes: String, rpe: String): JSONObject {
    if (scores.size != 5 || scores.any { it !in 1..5 }) throw AppError(R.string.checkin_invalid)
    val m = minutes.trim(); val r = rpe.trim()
    if (m.isBlank() != r.isBlank() || (m.isNotBlank() &&
        (m.toIntOrNull() !in 0..600 || r.toIntOrNull() !in 0..10))) throw AppError(R.string.checkin_invalid)
    return JSONObject().apply {
        listOf("sleep", "fatigue", "soreness", "stress", "mood").zip(scores).forEach { (key, value) -> put(key, value) }
        put("training_minutes", if (m.isBlank()) JSONObject.NULL else m.toInt())
        put("rpe", if (r.isBlank()) JSONObject.NULL else r.toInt())
    }
}

internal data class ClubComment(val id: Long, val authorName: String, val authorRole: String,
    val body: String, val createdAt: String, val jumpId: Long?, val sessionId: Long?)

internal fun parseComments(value: String): List<ClubComment> {
    val rows = JSONArray(value)
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        ClubComment(row.getLong("id"), row.getString("author_name"), row.getString("author_role"),
            row.getString("body"), row.getString("created_at"),
            if (row.isNull("jump_id")) null else row.getLong("jump_id"),
            if (row.isNull("session_id")) null else row.getLong("session_id")).also { Instant.parse(it.createdAt) }
    }.sortedWith(compareByDescending<ClubComment> { Instant.parse(it.createdAt) }.thenByDescending { it.id })
}
