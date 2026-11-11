package no.oystein.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pauseskjerm også når appen åpnes utenfor launcheren (fra et varsel, en lenke eller oversikten over
 * åpne apper). Tilgjengelighetstjenesten ser hvilken app som kommer fram, og legger pauseskjermen oppå.
 * Den ser bare navnet på appen, ikke innholdet.
 */
object PauseGuard {
    /** Appen man har fått lov til å åpne (trykket «Åpne», eller åpnet fra launcheren). Gjelder til man går ut av den. */
    @Volatile private var passed: String? = null
    /** Appen som sist var fremme, så samme app ikke utløser pauseskjermen flere ganger. */
    @Volatile private var lastPkg: String? = null
    @Volatile var settings: AppSettings? = null

    // «Kjøp tid»: appen som er fremme og har kjøpt tid, og siden når. Tiden trekkes når den forlates.
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var trackingPkg: String? = null
    private var trackingSince = 0L
    private val expire = Runnable { onExpired() }

    /** Appen åpnes med lov (fra launcheren eller etter pauseskjermen). Har den kjøpt tid, begynner tellingen. */
    fun grant(context: Context, pkg: String) {
        passed = pkg
        lastPkg = pkg
        appContext = context.applicationContext
        stopTracking()
        if (pkg in (settings?.timePackages ?: emptySet()) && TimeBudget.state(context, pkg) == TimeBudget.State.ACTIVE) {
            startTracking(context, pkg)
        }
    }

    private fun startTracking(context: Context, pkg: String) {
        val left = TimeBudget.left(context, pkg) ?: return
        appContext = context.applicationContext
        trackingPkg = pkg
        trackingSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, left)
    }

    /** Appen er ikke lenger fremme (annen app, startskjermen eller skjermen av): trekk tiden som ble brukt. */
    fun stopTracking() {
        val pkg = trackingPkg ?: return
        val ctx = appContext ?: return
        handler.removeCallbacks(expire)
        TimeBudget.consume(ctx, pkg, SystemClock.elapsedRealtime() - trackingSince)
        trackingPkg = null
    }

    /** Skjermen er på igjen og låst opp: fortsett tellingen hvis appen med kjøpt tid fortsatt er fremme. */
    fun onUnlocked(context: Context) {
        val pkg = lastPkg ?: return
        if (pkg !in (settings?.timePackages ?: emptySet())) return
        when (TimeBudget.state(context, pkg)) {
            TimeBudget.State.ACTIVE -> startTracking(context, pkg)
            else -> showPause(context, pkg)
        }
    }

    private fun onExpired() {
        val pkg = trackingPkg ?: return
        val ctx = appContext ?: return
        stopTracking()
        passed = null
        Log.d("Launcher", "kjøpt tid brukt opp: $pkg")
        showPause(ctx, pkg)
    }

    private fun showPause(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(context, PauseActivity::class.java)
                    .putExtra(PauseActivity.EXTRA_PKG, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private fun ignoredPackages(context: Context): Set<String> {
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return setOfNotNull("com.android.systemui", "android", ime)
    }

    fun onEvent(context: Context, event: AccessibilityEvent, scope: CoroutineScope) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        appContext = context.applicationContext
        if (pkg == context.packageName) {
            // Startskjermen eller pauseskjermen: appen er ikke fremme lenger, så tiden stopper.
            stopTracking()
            if (!cls.endsWith("PauseActivity")) {
                passed = null
                lastPkg = null
            }
            return
        }
        if (pkg in ignoredPackages(context)) return
        if (pkg == lastPkg) return
        stopTracking()
        lastPkg = pkg
        if (passed != null && passed != pkg) passed = null
        val s = settings ?: return
        if (pkg in s.timePackages) {
            when (TimeBudget.state(context, pkg)) {
                TimeBudget.State.ACTIVE -> startTracking(context, pkg)
                else -> {
                    Log.d("Launcher", "kjøp tid: pauseskjerm over $pkg")
                    showPause(context, pkg)
                }
            }
            return
        }
        if (passed == pkg) return
        if (!s.pauseEverywhere) return
        val limit = s.limits[pkg]
        if (pkg !in s.pausePackages && limit == null) return
        scope.launch(Dispatchers.IO) {
            val over = limit != null && (Usage.today(context)[pkg] ?: 0L) >= limit * 60_000L
            if (pkg !in s.pausePackages && !over) return@launch
            Log.d("Launcher", "pauseskjerm over $pkg (åpnet utenfor launcheren)")
            showPause(context, pkg)
        }
    }
}
