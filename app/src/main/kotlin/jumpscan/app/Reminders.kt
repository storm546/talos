package jumpscan.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Daily check-in reminder and the coach's nudge (SPEC 9 wave 2). Both are local notifications: the reminder from
// an inexact daily alarm, the nudge from the server's events stream while the app is open. No push service is used.

private const val CHANNEL = "reminders"
internal fun reminderSettings(context: Context) = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)

private fun reminderIntent(context: Context) = PendingIntent.getBroadcast(context, 40,
    Intent(context, ReminderReceiver::class.java).setAction("jumpscan.app.REMINDER"),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

/** Schedule the next reminder at the chosen local time, or cancel it when none is set. */
internal fun scheduleReminder(context: Context) {
    val alarms = context.getSystemService(AlarmManager::class.java)
    val minutes = reminderSettings(context).getInt("minutes", -1)
    alarms.cancel(reminderIntent(context))
    if (minutes < 0) return
    val zone = ZoneId.systemDefault()
    var next = LocalDate.now(zone).atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone)
    if (!next.toInstant().isAfter(java.time.Instant.now())) next = next.plusDays(1)
    // An inexact window needs no exact-alarm permission; a few minutes late is fine for a check-in.
    alarms.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), 10 * 60_000L, reminderIntent(context))
}

internal fun postNotification(context: Context, id: Int, title: String, body: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.rd_channel), NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
        .putExtra("open", "checkin").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(id, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_checkin)
        .setContentTitle(title).setContentText(body).setContentIntent(open).setAutoCancel(true).build())
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "jumpscan.app.REMINDER")
            postNotification(context, 1, context.getString(R.string.rd_notify_title), context.getString(R.string.rd_notify_text))
        scheduleReminder(context) // the next day, and after a reboot or an app update
    }
}

/** Reads the events stream while the app is in the foreground and shows the coach's nudge as a notification. */
internal class NudgeStream(private val context: Context, private val base: String, private val token: String,
                           private val changed: (String) -> Unit = {}) {
    @Volatile private var open = true
    @Volatile private var connection: HttpURLConnection? = null
    private val thread = Thread {
        var delay = 5_000L
        while (open) {
            try {
                val link = URI(serverUrl(base) + "/api/events").toURL().openConnection() as HttpURLConnection
                connection = link
                link.connectTimeout = 15_000; link.readTimeout = 60_000 // the server sends a keep-alive every 25 s
                link.setRequestProperty("Authorization", "Bearer $token")
                link.setRequestProperty("Accept", "text/event-stream")
                if (link.responseCode != 200) throw java.io.IOException()
                delay = 5_000L
                link.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!open) break
                        if (!line.startsWith("data:")) continue
                        val event = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                        if (event.optString("type") == "nudge") postNotification(context, 2,
                            context.getString(R.string.rd_nudge_title), context.getString(R.string.rd_nudge_text))
                        else changed(event.optString("type"))
                    }
                }
            } catch (_: Exception) {}
            finally { connection?.disconnect() }
            if (open) try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            delay = (delay * 2).coerceAtMost(60_000L)
        }
    }.apply { isDaemon = true; name = "jumpscan-nudges"; start() }

    fun close() { open = false; thread.interrupt(); Thread { connection?.disconnect() }.start() }
}
