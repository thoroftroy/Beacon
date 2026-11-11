package dev.wonderdesk.beacon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** A search bar on the home screen that opens Beacon ready to type. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(c.packageName, R.layout.widget_search)
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 51, Intent(c, MainActivity::class.java).putExtra("tab", "search").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        v.setOnClickPendingIntent(R.id.w_apps, PendingIntent.getActivity(c, 52, Intent(c, MainActivity::class.java).putExtra("tab", "apps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        ids.forEach { mgr.updateAppWidget(it, v) }
    }
}
