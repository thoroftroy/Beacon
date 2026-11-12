package dev.wonderdesk.beacon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** A search bar on the home screen: tap to search everything, or All apps. */
class SearchWidget : AppWidgetProvider() {
    companion object {
        fun build(c: Context, id: Int): RemoteViews = dev.wonderdesk.shared.responsiveWidget(R.layout.widget_search_small, null, R.layout.widget_search) { fill(c, id, it) }
        private fun fill(c: Context, id: Int, layout: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val v = RemoteViews(c.packageName, layout)
            val dark = s.bool("dark", false)
            v.setInt(R.id.w_root, "setBackgroundResource", if (dark) R.drawable.wd_pill_dark else R.drawable.wd_pill)
            v.setTextColor(R.id.w_hint, if (dark) 0xCCFFFFFF.toInt() else 0xFF6B7078.toInt())
            v.setTextViewText(R.id.w_hint, s.str("hint", "Search apps, files, sums…").ifBlank { "Search apps, files, sums…" })
            v.setViewVisibility(R.id.w_apps, if (s.bool("apps", true)) View.VISIBLE else View.GONE)
            fun lp(rc: Int, search: Boolean) = PendingIntent.getActivity(c, rc, Intent(c, LaunchpadActivity::class.java).putExtra("search", search).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, lp(51, true))
            v.setOnClickPendingIntent(R.id.w_apps, lp(52, false))
            if (layout != R.layout.widget_search) { v.setViewVisibility(R.id.w_hint, View.GONE); v.setViewVisibility(R.id.w_apps, View.GONE) }
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class SearchWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, SearchWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var dark by remember { mutableStateOf(s.bool("dark", false)) }
        var apps by remember { mutableStateOf(s.bool("apps", true)) }
        var hint by remember { mutableStateOf(s.str("hint", "")) }
        WidgetSetup("Beacon", "Search widget", onCancel = { finish() }, onSave = { s.put("dark" to dark, "apps" to apps, "hint" to hint); save() }) {
            Panel {
                SwitchRow("Dark bar", dark, { dark = it }); PanelDivider()
                SwitchRow("All apps button", apps, { apps = it }, subtitle = "Opens all your apps")
            }
            Field(hint, { hint = it.take(40) }, "Placeholder text (optional)", supporting = "Shown in the bar before you tap it")
        }
    }
}
