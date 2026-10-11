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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val hasLock = remember(resumeCount) { LockService.isEnabled(activity) }
    val hasCalendar = remember(resumeCount) { NextEvent.hasCalendarPermission(activity) }
    var deliveryDialog by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsState()

    fun go(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    if (deliveryDialog) DeliveryDialog(activity, settings) { deliveryDialog = false }

    FullScreen {
        Title("Innstillinger", onClose)
        LazyColumn(Modifier.fillMaxSize().testTag("settings")) {
            sectionHeader("Tilganger")
            item {
                StatusRow("Standard startskjerm", isDefault) { activity.requestDefaultLauncher() }
                StatusRow("Varseltilgang (demping)", hasNotif) { activity.startInternal(MutedNotifications.accessIntent(activity)) }
                StatusRow("Brukstilgang (skjermtid)", hasUsage) { activity.startInternal(Usage.permissionIntent(activity)) }
                StatusRow("Låsing (tilgjengelighet)", hasLock) {
                    activity.startInternal(LockService.settingsIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                if (settings.showNextEvent) {
                    StatusRow("Kalendertilgang (neste hendelse)", hasCalendar) { activity.requestCalendarPermission() }
                }
            }

            sectionHeader("Hjemskjerm")
            item {
                ToggleRow("24-timers klokke", settings.use24h) { go { e.setUse24h(it) } }
                ToggleRow("Vis skjermtid i dag", settings.showScreenTime) { go { e.setShowScreenTime(it) } }
                ToggleRow("Tastatur åpnes i applisten", settings.autoKeyboard) { go { e.setAutoKeyboard(it) } }
                ToggleRow("Dobbelttrykk for å låse", settings.doubleTapLock) { go { e.setDoubleTapLock(it) } }
                ToggleRow("Neste hendelse og alarm", settings.showNextEvent) { on ->
                    go { e.setShowNextEvent(on) }
                    if (on && !hasCalendar) activity.requestCalendarPermission()
                }
            }
            if (settings.doubleTapLock && !hasLock) {
                item { Hint("Dobbelttrykk krever at «Launcher» slås på under Tilgjengelighet (se Tilganger).") }
            }

            sectionHeader("Snarveier på hjemskjermen")
            items(settings.shortcuts, key = { "shortcut:${it.kind.name}" }) { s ->
                ListRow(s.kind.label) {
                    TextAction("↑") { go { e.moveShortcut(s.kind, -1) } }
                    TextAction("↓") { go { e.moveShortcut(s.kind, 1) } }
                    TextAction(if (s.visible) "på" else "av", color = if (s.visible) Color.White else Dim) {
                        go { e.setShortcutVisible(s.kind, !s.visible) }
                    }
                }
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

            sectionHeader("Widgeter (sveip til høyre)")
            items(settings.widgets, key = { "widget:${it.id}" }) { w ->
                val label = remember(w.id) { activity.widgets.info(w.id)?.let { activity.widgets.label(it) } ?: "Ukjent widget" }
                ListRow(label) { TextAction("Fjern") { activity.removeWidget(w.id) } }
            }
            item { ListRow(if (settings.widgets.isEmpty()) "Ingen" else "") { TextAction("Legg til", onClick = onPickWidget) } }

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

            sectionHeader("Dempede varsler vises")
            item {
                val text = if (settings.mutedDelivery.isEmpty()) "Med en gang"
                else "Kl. " + settings.mutedDelivery.joinToString(", ") { formatMinutes(it) }
                ListRow(text) { TextAction("Endre") { deliveryDialog = true } }
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

            sectionHeader("Sikkerhetskopi")
            item {
                ListRow("Lagre oppsettet til en fil") { TextAction("Eksporter") { activity.exportSettings() } }
                ListRow("Hent oppsett fra en fil") { TextAction("Importer") { activity.importSettings() } }
                Hint(
                    "Favoritter, grupper, navn, grenser og innstillinger. Widgetene må legges til på nytt. " +
                        "Android tar i tillegg automatisk sikkerhetskopi når sikkerhetskopi er slått på i telefonens innstillinger."
                )
            }

            sectionHeader("Oppdatering")
            item {
                val version = remember {
                    runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()
                }
                ListRow("Denne versjonen: ${version ?: "?"}") {
                    when (val u = update) {
                        is Updater.State.Available ->
                            TextAction("Installer ${u.release.versionName}", color = Color.White) {
                                activity.lifecycleScope.launch { Updater.install(activity, u.release) }
                            }
                        is Updater.State.Downloading, is Updater.State.Installing -> {}
                        else -> TextAction("Se etter ny") { activity.lifecycleScope.launch { Updater.check(activity, force = true) } }
                    }
                }
                updateText(update)?.let { Hint(it) }
                if (update == Updater.State.Idle) Hint("Launcheren ser selv etter nye versjoner og sier fra på hjemskjermen.")
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

