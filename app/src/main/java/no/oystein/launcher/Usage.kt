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
     * Som Digital Velvære teller vi tiden appen er synlig, én app om gangen (en ny app avslutter den
     * forrige), og stopper ved låseskjerm, slukket skjerm og når startskjermen vises. Bytter appen
     * mellom egne skjermbilder, teller det som sammenhengende bruk: den gamle skjermens «stoppet»
     * kommer etter at den nye er vist, og skal ikke avslutte tellingen. Et kortvarig «pause» (dialog,
     * tillatelse o.l.) avslutter heller ikke, bare «stoppet» for skjermbildet som vises.
     */
    fun today(context: Context): Map<String, Long> = todayStats(context).time

    /** Skjermtid (ms) og antall åpninger per pakke i dag. */
    data class Stats(val time: Map<String, Long>, val opens: Map<String, Int>) {
        val totalOpens: Int get() = opens.values.sum()
    }

    /**
     * Som [today], men teller også åpninger: hver gang man går inn i en app. Det teller ikke som en ny
     * åpning når man kommer tilbake til samme app etter å ha låst opp skjermen, eller innen et halvt
     * minutt etter et kort avbrudd (dialog, deling, tillatelse, startskjermen et øyeblikk). Apper som
     * bare blinker forbi i under ett sekund (f.eks. når en app sender videre til en annen) telles ikke.
     */
    fun todayStats(context: Context): Stats {
        if (!hasPermission(context)) return Stats(emptyMap(), emptyMap())
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = startOfDay()
        val now = System.currentTimeMillis()
        // Litt før midnatt også, så en app som allerede var åpen ved midnatt telles fra midnatt.
        val events = usm.queryEvents(start - 3 * 3_600_000L, now) ?: return Stats(emptyMap(), emptyMap())
        val ignored = ignoredPackages(context)
        val total = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        var current: String? = null
        var currentClass: String? = null
        var since = 0L
        // Om økten som pågår er en ny åpning, og hvilken app som sist var i bruk (og når/hvordan den sluttet).
        var currentIsOpen = false
        var lastPkg: String? = null
        var lastEnd = 0L
        var lastEndedByScreen = false

        fun close(at: Long, byScreen: Boolean = false) {
            val pkg = current ?: return
            val from = maxOf(since, start)
            if (at > from) total[pkg] = (total[pkg] ?: 0L) + (at - from)
            // Glimt på under ett sekund er støy: de teller ikke og endrer ikke hvilken app som sist var i bruk.
            if (at - since >= 1_000L) {
                if (currentIsOpen && since >= start) opens[pkg] = (opens[pkg] ?: 0) + 1
                lastPkg = pkg
                lastEnd = at
                lastEndedByScreen = byScreen
            }
            current = null
            currentClass = null
        }

        fun begin(pkg: String, cls: String?, t: Long) {
            current = pkg
            currentClass = cls
            since = t
            val continued = pkg == lastPkg && (lastEndedByScreen || t - lastEnd < 30_000L)
            currentIsOpen = !continued
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
                        begin(pkg, event.className, t)
                    } else {
                        // Nytt skjermbilde i samme app: fortsett tellingen.
                        currentClass = event.className
                    }
                }
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (pkg == current && event.className == currentClass) close(t)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> close(t, byScreen = true)
            }
        }
        close(now)
        return Stats(total, opens)
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
