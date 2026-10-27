package no.oystein.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
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
    /** Satt for festede snarveier (f.eks. nettapper/PWA lagt til fra Chrome), ellers null. */
    val shortcut: ShortcutInfo? = null,
    /** Når appen ble installert første gang (0 hvis ukjent). Brukes til «Nye» øverst i applisten. */
    val installedAt: Long = 0L,
) {
    val isShortcut: Boolean get() = shortcut != null
}

private const val CACHE_FILE = "apps-cache.tsv"

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
                    // Forhåndsinstallerte apper får «installert» = første oppstart av telefonen; de er aldri nye.
                    installedAt = if (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) 0L
                    else runCatching { info.firstInstallTime }.getOrDefault(0L),
                )
            }
            result += loadPinnedShortcuts(user, work, serial)
        }
        return result
    }

    /**
     * Snarveier som er festet til denne launcheren (nettapper/PWA fra Chrome, kontakter o.l.).
     * Krever at launcheren er standard startskjerm; ellers er listen tom.
     */
    private fun loadPinnedShortcuts(user: UserHandle, work: Boolean, serial: Long): List<AppEntry> {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val shortcuts = runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty()
        return shortcuts.filter { it.isEnabled }.map { sc ->
            val base = "shortcut:${sc.`package`}/${sc.id}"
            AppEntry(
                key = if (work) "$base@$serial" else base,
                packageName = sc.`package`,
                component = sc.activity ?: ComponentName(sc.`package`, ""),
                user = user,
                originalLabel = (sc.shortLabel ?: sc.longLabel ?: sc.id).toString(),
                isWorkProfile = work,
                shortcut = sc,
            )
        }
    }

    /**
     * Sist kjente appliste, lagret på disk, så hjemskjermen kan vises med en gang selv når Android har
     * avsluttet launcheren (f.eks. om natten). Den ekte listen lastes rett etterpå og erstatter denne.
     */
    fun cached(): List<AppEntry> = runCatching {
        val file = java.io.File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            val user = userManager.getUserForSerialNumber(f[3].toLongOrNull() ?: return@mapNotNull null)
                ?: return@mapNotNull null
            AppEntry(
                key = f[0],
                packageName = f[1],
                component = ComponentName.unflattenFromString(f[2]) ?: ComponentName(f[1], ""),
                user = user,
                originalLabel = f[5],
                isWorkProfile = f[4] == "1",
            )
        }
    }.getOrDefault(emptyList())

    fun saveCache(apps: List<AppEntry>) {
        runCatching {
            val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ') }
            val text = apps.joinToString("\n") { a ->
                listOf(
                    a.key, a.packageName, a.component.flattenToString(),
                    userManager.getSerialNumberForUser(a.user).toString(),
                    if (a.isWorkProfile) "1" else "0", a.originalLabel,
                ).joinToString("\t") { clean(it) }
            }
            val tmp = java.io.File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(java.io.File(context.filesDir, CACHE_FILE))
        }
    }

    /** Tar imot en snarvei eller widget som en app ber om å feste (f.eks. «Legg til på startskjermen» i Chrome). */
    fun pinRequest(intent: Intent): LauncherApps.PinItemRequest? =
        runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()

    /** Fjerner en festet snarvei (selve appen den hører til blir værende). */
    fun unpin(app: AppEntry) {
        val sc = app.shortcut ?: return
        runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                .setPackage(sc.`package`)
            val keep = launcherApps.getShortcuts(query, app.user).orEmpty().map { it.id }.filter { it != sc.id }
            launcherApps.pinShortcuts(sc.`package`, keep, app.user)
        }
    }

    /** Sender et signal hver gang apper installeres, fjernes eller endres. */
    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, user: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, user: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
                trySend(Unit)
            }
        }
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(cb) }
    }

    fun launch(app: AppEntry, bounds: Rect? = null): Boolean = try {
        val sc = app.shortcut
        if (sc != null) {
            launcherApps.startShortcut(sc, bounds, null)
        } else {
            launcherApps.startMainActivity(app.component, app.user, bounds, null)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun openAppInfo(app: AppEntry) {
        try {
            // For en snarvei vises info om appen som eier den (f.eks. Chrome).
            val cn = if (app.isShortcut) {
                context.packageManager.getLaunchIntentForPackage(app.packageName)?.component ?: app.component
            } else {
                app.component
            }
            launcherApps.startAppDetailsActivity(cn, app.user, null, null)
        } catch (_: Exception) {
        }
    }

    /** Androids avinstalleringsdialog for appen (også for apper i jobbprofilen). Appinfo hvis det ikke går. */
    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppInfo(app)
        }
    }
}

fun AppEntry.label(settings: AppSettings): String =
    settings.renames[key] ?: (if (isWorkProfile) "$originalLabel (jobb)" else originalLabel)

val nbCollator: Collator = Collator.getInstance(Locale("nb", "NO")).apply { strength = Collator.PRIMARY }

fun List<AppEntry>.sortedByLabel(settings: AppSettings): List<AppEntry> =
    sortedWith { a, b -> nbCollator.compare(a.label(settings), b.label(settings)) }

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
