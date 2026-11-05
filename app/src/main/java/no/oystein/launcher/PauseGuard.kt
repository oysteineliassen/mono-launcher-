package no.oystein.launcher

import android.content.Context
import android.content.Intent
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

    fun grant(pkg: String) {
        passed = pkg
        lastPkg = pkg
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
        if (pkg == context.packageName) {
            // Startskjermen (ikke pauseskjermen selv): man er ute av appen, så neste åpning teller.
            if (!cls.endsWith("PauseActivity")) {
                passed = null
                lastPkg = null
            }
            return
        }
        if (pkg in ignoredPackages(context)) return
        if (pkg == lastPkg) return
        lastPkg = pkg
        if (passed != null && passed != pkg) passed = null
        if (passed == pkg) return
        val s = settings ?: return
        if (!s.pauseEverywhere) return
        val limit = s.limits[pkg]
        if (pkg !in s.pausePackages && limit == null) return
        scope.launch(Dispatchers.IO) {
            val over = limit != null && (Usage.today(context)[pkg] ?: 0L) >= limit * 60_000L
            if (pkg !in s.pausePackages && !over) return@launch
            Log.d("Launcher", "pauseskjerm over $pkg (åpnet utenfor launcheren)")
            runCatching {
                context.startActivity(
                    Intent(context, PauseActivity::class.java)
                        .putExtra(PauseActivity.EXTRA_PKG, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
    }
}
