package jumpscan.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * The "Today" home-screen widget (SPEC 10.2): readiness, the main finding, check-in done or not, kcal eaten of target. It draws the
 * cached `today` answer; the system asks for an update at most every 30 minutes, which also fetches a fresh one.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        Thread {
            try {
                val account = context.getSharedPreferences("account", Context.MODE_PRIVATE)
                val token = account.getString("token", "").orEmpty()
                val server = account.getString("server", null)
                if (token.isNotEmpty() && server != null)
                    runCatching { context.saveToday(org.json.JSONObject(api(server, "/api/me/today?day=${todayLocal()}&language=${if (context.resources.configuration.locales[0].language == "bg") "bg" else "en"}", token = token))) }
                refresh(context)
            } finally { done.finish() }
        }.start()
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        internal fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            fun open(code: Int, screen: String) = PendingIntent.getActivity(context, code,
                Intent(context, MainActivity::class.java).putExtra("open", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open(60, "today"))
            views.setOnClickPendingIntent(R.id.widget_check_in, open(61, "checkin"))
            val signedIn = context.getSharedPreferences("account", Context.MODE_PRIVATE).getString("token", "").orEmpty().isNotEmpty()
            val today = context.cachedToday()?.first?.takeIf { signedIn && it.optString("day") == todayLocal() }
            if (today == null) {
                views.setTextViewText(R.id.widget_readiness, context.getString(if (signedIn) R.string.today_no_data else R.string.widget_signed_out))
                views.setViewVisibility(R.id.widget_checkin, View.GONE)
                views.setViewVisibility(R.id.widget_finding, View.GONE)
                views.setViewVisibility(R.id.widget_kcal, View.GONE)
                views.setViewVisibility(R.id.widget_check_in, View.GONE)
                return views
            }
            val status = today.optJSONObject("readiness")?.optString("status")
            views.setTextViewText(R.id.widget_readiness, context.getString(R.string.rd_title) + ": " + context.getString(when (status) {
                "good" -> R.string.rd_good
                "take_care" -> R.string.rd_take_care
                "still_learning" -> R.string.rd_still_learning
                "normal" -> R.string.rd_normal
                else -> R.string.rd_no_data
            }))
            // The finding that matters most: something to watch first, else the first good one (SPEC 10.9).
            val notes = today.optJSONArray("findings")?.let { array -> List(array.length()) { array.getJSONObject(it) } }.orEmpty()
            val top = notes.firstOrNull { it.optString("tone") == "watch" } ?: notes.firstOrNull { it.optString("tone") == "good" }
            views.setTextViewText(R.id.widget_finding, top?.optString("title").orEmpty())
            views.setViewVisibility(R.id.widget_finding, if (top == null) View.GONE else View.VISIBLE)
            val checked = today.optJSONObject("checkin")?.optBoolean("done") == true
            views.setTextViewText(R.id.widget_checkin, context.getString(if (checked) R.string.widget_checkin_done else R.string.widget_checkin_missing))
            views.setViewVisibility(R.id.widget_checkin, View.VISIBLE)
            views.setViewVisibility(R.id.widget_check_in, if (checked) View.GONE else View.VISIBLE)
            val food = today.optJSONObject("nutrition")
            val locale = context.resources.configuration.locales[0]
            if (food == null) views.setViewVisibility(R.id.widget_kcal, View.GONE) else {
                val eaten = metricNumber(food.getJSONObject("eaten").getDouble("kcal"), "kcal", locale)
                val target = food.optJSONObject("target")?.getDouble("kcal")
                views.setTextViewText(R.id.widget_kcal, if (target == null) context.getString(R.string.widget_kcal_plain, eaten)
                    else context.getString(R.string.widget_kcal, eaten, metricNumber(target, "kcal", locale)))
                views.setViewVisibility(R.id.widget_kcal, View.VISIBLE)
            }
            return views
        }
    }
}
