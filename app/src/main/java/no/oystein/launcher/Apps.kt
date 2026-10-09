package no.oystein.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.Collator
import java.util.Locale

/** En startbar app. [key] er stabil og brukes i innstillingene (pakke/klasse, + @bruker for jobbprofil). */
data class AppEntry(
    val key: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val originalLabel: String,
    val isWorkProfile: Boolean,
)

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun loadApps(): List<AppEntry> {
        val me = Process.myUserHandle()
        val result = mutableListOf<AppEntry>()
        for (user in launcherApps.profiles) {
            val work = user != me
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == context.packageName) continue
                val base = "${cn.packageName}/${cn.className}"
                result += AppEntry(
                    key = if (work) "$base@$serial" else base,
                    packageName = cn.packageName,
                    component = cn,
                    user = user,
                    originalLabel = info.label?.toString() ?: cn.packageName,
                    isWorkProfile = work,
                )
            }
        }
        return result
    }

    /** Sender et signal hver gang apper installeres, fjernes eller endres. */
    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, user: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, user: UserHandle?, r: Boolean) { trySend(Unit) }
        }
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(cb) }
    }

    fun launch(app: AppEntry, bounds: Rect? = null): Boolean = try {
        launcherApps.startMainActivity(app.component, app.user, bounds, null)
        true
    } catch (e: Exception) {
        false
    }

    fun openAppInfo(app: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (_: Exception) {
        }
    }

    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

fun AppEntry.label(settings: AppSettings): String =
    settings.renames[key] ?: (if (isWorkProfile) "$originalLabel (jobb)" else originalLabel)

private val collator: Collator = Collator.getInstance(Locale("nb", "NO")).apply { strength = Collator.PRIMARY }

fun List<AppEntry>.sortedByLabel(settings: AppSettings): List<AppEntry> =
    sortedWith { a, b -> collator.compare(a.label(settings), b.label(settings)) }

/** Søk som tåler små feil: starter med, inneholder ord som starter med, eller inneholder. */
fun List<AppEntry>.search(query: String, settings: AppSettings): List<AppEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return this
    fun score(a: AppEntry): Int {
        val l = a.label(settings).lowercase()
        val orig = a.originalLabel.lowercase()
        return when {
            l.startsWith(q) -> 0
            l.split(' ', '-', '.').any { it.startsWith(q) } -> 1
            l.contains(q) -> 2
            orig.contains(q) || a.packageName.contains(q) -> 3
            initials(l).startsWith(q) -> 4
            else -> -1
        }
    }
    return mapNotNull { a -> score(a).takeIf { it >= 0 }?.let { it to a } }
        .sortedBy { it.first }
        .map { it.second }
}

private fun initials(s: String) = s.split(' ', '-').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
