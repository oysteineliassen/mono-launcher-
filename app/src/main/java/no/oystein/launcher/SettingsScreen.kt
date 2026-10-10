package no.oystein.launcher

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    activity: MainActivity,
    settings: AppSettings,
    apps: List<AppEntry>,
    usage: Map<String, Long>,
    resumeCount: Int,
    onPickWidget: () -> Unit,
    onRenameGroup: (String) -> Unit,
    onClose: () -> Unit,
) {
    // Lagring må overleve at dialogen lukkes, derfor aktivitetens scope.
    val scope = activity.lifecycleScope
    val e = activity.editor
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val byPkg = remember(apps) { apps.groupBy { it.packageName }.mapValues { it.value.first() } }
    fun pkgLabel(pkg: String) = byPkg[pkg]?.label(settings) ?: pkg

    // Leses på nytt hver gang launcheren vises igjen (f.eks. etter at tilgang er gitt).
    val isDefault = remember(resumeCount) { activity.isDefaultLauncher() }
    val hasNotif = remember(resumeCount) { MutedNotifications.hasAccess(activity) }
    val hasUsage = remember(resumeCount) { Usage.hasPermission(activity) }
    val widgetLabel = remember(settings.widgetId) {
        activity.widgets.info(settings.widgetId)?.let { activity.widgets.label(it) }
    }

    fun go(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    FullScreen {
        Title("Innstillinger", onClose)
        LazyColumn(Modifier.fillMaxSize().testTag("settings")) {
            sectionHeader("Tilganger")
            item {
                StatusRow("Standard startskjerm", isDefault) { activity.requestDefaultLauncher() }
                StatusRow("Varseltilgang (demping)", hasNotif) { activity.startInternal(MutedNotifications.accessIntent(activity)) }
                StatusRow("Brukstilgang (skjermtid)", hasUsage) { activity.startInternal(Usage.permissionIntent(activity)) }
            }

            sectionHeader("Hjemskjerm")
            item {
                ToggleRow("24-timers klokke", settings.use24h) { go { e.setUse24h(it) } }
                ToggleRow("Vis skjermtid i dag", settings.showScreenTime) { go { e.setShowScreenTime(it) } }
                ToggleRow("Tastatur åpnes i applisten", settings.autoKeyboard) { go { e.setAutoKeyboard(it) } }
            }

            sectionHeader("Favoritter")
            if (settings.favorites.isEmpty()) item { Hint("Hold inne på en app i applisten for å legge den til.") }
            items(settings.favorites, key = { "fav:$it" }) { key ->
                ListRow(byKey[key]?.label(settings) ?: key) {
                    TextAction("↑") { go { e.moveFavorite(key, -1) } }
                    TextAction("↓") { go { e.moveFavorite(key, 1) } }
                    TextAction("Fjern") { go { e.setFavorite(key, false) } }
                }
            }

            sectionHeader("Grupper")
            val groupNames = settings.groupNames()
            if (groupNames.isEmpty()) item { Hint("Hold inne på en app og velg «Legg i gruppe».") }
            items(groupNames, key = { "group:$it" }) { name ->
                val count = settings.groups.values.count { it == name }
                ListRow("$name ($count)") {
                    TextAction("Endre navn") { onRenameGroup(name) }
                    TextAction("Slett") { go { e.deleteGroup(name) } }
                }
            }

            sectionHeader("Widget (sveip til høyre)")
            item {
                ListRow(widgetLabel ?: "Ingen") {
                    TextAction("Velg", onClick = onPickWidget)
                    if (widgetLabel != null) TextAction("Fjern") { activity.removeWidget() }
                }
            }

            sectionHeader("Pauseskjerm")
            item {
                ListRow("Nedtelling: ${settings.pauseSeconds} sek") {
                    TextAction("−") { go { e.setPauseSeconds(settings.pauseSeconds - 1) } }
                    TextAction("+") { go { e.setPauseSeconds(settings.pauseSeconds + 1) } }
                }
            }
            if (settings.pausePackages.isEmpty()) item { Hint("Slås på per app: hold inne på appen.") }
            items(settings.pausePackages.sortedBy { pkgLabel(it).lowercase() }, key = { "pause:$it" }) { pkg ->
                ListRow(pkgLabel(pkg)) { TextAction("Fjern") { go { e.setPause(pkg, false) } } }
            }

            sectionHeader("Daglige grenser")
            if (settings.limits.isEmpty()) item { Hint("Settes per app: hold inne på appen.") }
            items(settings.limits.entries.sortedBy { pkgLabel(it.key).lowercase() }, key = { "limit:${it.key}" }) { (pkg, min) ->
                val used = usage[pkg] ?: 0L
                ListRow("${pkgLabel(pkg)} – ${Usage.format(used)} av $min min") {
                    TextAction("Fjern") { go { e.setLimit(pkg, null) } }
                }
            }

            sectionHeader("Dempede varsler fra")
            if (settings.mutedPackages.isEmpty()) item { Hint("Slås på per app: hold inne på appen.") }
            items(settings.mutedPackages.sortedBy { pkgLabel(it).lowercase() }, key = { "muted:$it" }) { pkg ->
                ListRow(pkgLabel(pkg)) { TextAction("Fjern") { go { e.setMuted(pkg, false) } } }
            }

            sectionHeader("Skjulte apper")
            if (settings.hidden.isEmpty()) item { Hint("Ingen.") }
            items(settings.hidden.sortedBy { (byKey[it]?.label(settings) ?: it).lowercase() }, key = { "hidden:$it" }) { key ->
                ListRow(byKey[key]?.label(settings) ?: key) { TextAction("Vis") { go { e.setHidden(key, false) } } }
            }

            sectionHeader("Skjermtid i dag")
            if (!hasUsage) item { Hint("Krever brukstilgang (se over).") }
            val top = usage.entries.filter { it.value >= 60_000L }.sortedByDescending { it.value }.take(20)
            items(top, key = { "usage:${it.key}" }) { (pkg, ms) ->
                ListRow(pkgLabel(pkg)) { Text(Usage.format(ms), fontSize = 15.sp, color = Dim) }
            }
            if (hasUsage) item { Hint("Totalt: ${Usage.format(usage.values.sum())}") }

            item {
                Spacer(Modifier.height(32.dp))
                val version = remember {
                    runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()
                }
                Text("Launcher ${version ?: ""}", fontSize = 13.sp, color = Faint)
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 15.sp, color = Faint, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ListRow(label: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onClick: () -> Unit) {
    ListRow(label) {
        TextAction(if (ok) "på" else "slå på", color = if (ok) Dim else Color.White, onClick = onClick)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(label) {
        TextAction(if (on) "på" else "av", color = if (on) Color.White else Dim) { onChange(!on) }
    }
}

