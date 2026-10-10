package no.oystein.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
     * Skjermtid i dag per pakke (millisekunder), regnet ut fra hendelsene siden midnatt.
     * queryUsageStats(INTERVAL_DAILY) gir ofte tall for feil periode, derfor brukes hendelsene direkte.
     *
     * Som Digital Velvære teller vi bare én app i forgrunnen om gangen (en ny app avslutter den
     * forrige), stopper ved låseskjerm og slukket skjerm, og teller ikke startskjermer eller
     * systemgrensesnittet. Uten dette ble overlapp og tid bak låseskjermen talt med, og totalen ble
     * altfor høy.
     */
    fun today(context: Context): Map<String, Long> {
        if (!hasPermission(context)) return emptyMap()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now) ?: return emptyMap()
        val ignored = ignoredPackages(context)
        val total = HashMap<String, Long>()
        var current: String? = null
        var since = 0L

        fun close(at: Long) {
            val pkg = current ?: return
            if (at > since) total[pkg] = (total[pkg] ?: 0L) + (at - since)
            current = null
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            val t = event.timeStamp
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg == null || pkg in ignored) {
                        // Startskjerm/systemgrensesnitt i forgrunnen: ingen app teller.
                        close(t)
                    } else if (pkg != current) {
                        close(t)
                        current = pkg
                        since = t
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg == current) close(t)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> close(t)
            }
        }
        close(now)
        return total
    }

    /** Startskjermer (også denne), systemgrensesnittet og tastaturer telles ikke som skjermtid. */
    private fun ignoredPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_ALL,
        ).map { it.activityInfo.packageName }
        return homes.toSet() + setOf(context.packageName, "com.android.systemui", "android")
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
