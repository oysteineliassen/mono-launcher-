package no.oystein.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import java.util.Calendar

object Usage {
    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Skjermtid i dag per pakke (millisekunder), regnet ut fra hendelser siden midnatt.
     * queryUsageStats(INTERVAL_DAILY) gir ofte tall for feil periode, derfor brukes hendelsene direkte.
     */
    fun today(context: Context): Map<String, Long> {
        if (!hasPermission(context)) return emptyMap()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now) ?: return emptyMap()
        val total = HashMap<String, Long>()
        val openSince = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> openSince.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> openSince.remove(pkg)?.let { s ->
                    total[pkg] = (total[pkg] ?: 0L) + (event.timeStamp - s)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    for ((p, s) in openSince) total[p] = (total[p] ?: 0L) + (event.timeStamp - s)
                    openSince.clear()
                }
            }
        }
        for ((p, s) in openSince) total[p] = (total[p] ?: 0L) + (now - s)
        total.remove(context.packageName)
        return total
    }

    fun format(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "under 1 min"
            min < 60 -> "$min min"
            else -> "${min / 60} t ${min % 60} min"
        }
    }
}
