package no.oystein.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SizeF

/** Én widget på siden til venstre for hjemskjermen. */
class WidgetController(context: Context) {
    val appContext: Context = context.applicationContext
    val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)
    val host = AppWidgetHost(appContext, HOST_ID)

    fun providers(): List<AppWidgetProviderInfo> =
        manager.installedProviders.sortedBy { label(it).lowercase() }

    fun label(info: AppWidgetProviderInfo): String {
        val pm = appContext.packageManager
        val widget = info.loadLabel(pm) ?: info.provider.className
        val app = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        }.getOrDefault(info.provider.packageName)
        return if (widget.equals(app, ignoreCase = true)) app else "$app – $widget"
    }

    fun info(id: Int): AppWidgetProviderInfo? = if (id < 0) null else manager.getAppWidgetInfo(id)

    fun allocate(): Int = host.allocateAppWidgetId()

    fun delete(id: Int) {
        if (id >= 0) host.deleteAppWidgetId(id)
    }

    /** Fjerner ID-er som er tildelt men ikke i bruk (f.eks. etter avbrutt valg). */
    fun cleanup(keep: Set<Int>) {
        host.appWidgetIds.filter { it !in keep }.forEach { host.deleteAppWidgetId(it) }
    }

    /** Startstørrelse i ruter for en ny widget: det widgeten selv ber om, men minst 2 × 2. */
    fun defaultCells(info: AppWidgetProviderInfo?, cellWidthDp: Float, cellHeightDp: Float): Pair<Int, Int> {
        val density = appContext.resources.displayMetrics.density
        val wDp = (info?.minWidth ?: 0) / density
        val hDp = (info?.minHeight ?: 0) / density
        val w = kotlin.math.ceil(wDp / cellWidthDp).toInt().coerceIn(2, GRID_COLUMNS)
        val h = kotlin.math.ceil(hDp / cellHeightDp).toInt().coerceIn(2, 6)
        return w to h
    }

    fun createView(context: Context, id: Int): AppWidgetHostView? {
        val info = info(id) ?: return null
        return host.createView(context, id, info)
    }

    /** Forteller widgeten hvor stor plass den har (i dp), slik at f.eks. kalenderen viser mest mulig. */
    fun updateSize(view: AppWidgetHostView, widthDp: Float, heightDp: Float) {
        if (widthDp <= 0f || heightDp <= 0f) return
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp.toInt())
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            view.updateAppWidgetSize(options, listOf(SizeF(widthDp, heightDp)))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(options, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
        }
    }

    companion object {
        const val HOST_ID = 4711
    }
}
