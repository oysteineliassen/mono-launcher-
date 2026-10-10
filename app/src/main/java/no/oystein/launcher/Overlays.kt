package no.oystein.launcher

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Felles byggeklosser ----------

/** Svart helskjerm som sluker alle trykk under seg. */
@Composable
fun FullScreen(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 32.dp),
            content = content,
        )
    }
}

@Composable
fun Title(text: String, onClose: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White, modifier = Modifier.weight(1f))
        if (onClose != null) TextAction("Lukk", onClick = onClose)
    }
}

@Composable
fun TextAction(text: String, color: Color = Dim, size: Int = 16, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        fontSize = size.sp,
        color = color,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    )
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 19.sp,
        fontWeight = FontWeight.Light,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
fun TextDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color.Black, border = BorderStroke(1.dp, Faint), shape = androidx.compose.ui.graphics.RectangleShape) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

// ---------- App-meny ----------

@Composable
fun AppMenu(
    activity: MainActivity,
    app: AppEntry,
    settings: AppSettings,
    onRename: () -> Unit,
    onLimit: () -> Unit,
    onGroup: () -> Unit,
    onClose: () -> Unit,
) {
    // Lagring må overleve at dialogen lukkes, derfor aktivitetens scope.
    val scope = activity.lifecycleScope
    val e = activity.editor
    val isFav = app.key in settings.favorites
    val isHidden = app.key in settings.hidden
    val pause = app.packageName in settings.pausePackages
    val muted = app.packageName in settings.mutedPackages
    val limit = settings.limits[app.packageName]
    fun act(block: suspend () -> Unit) {
        scope.launch { block() }
        onClose()
    }
    TextDialog(onClose) {
        Text(app.label(settings), fontSize = 24.sp, fontWeight = FontWeight.Light, color = Color.White)
        if (app.label(settings) != app.originalLabel) Text(app.originalLabel, fontSize = 14.sp, color = Faint)
        Spacer(Modifier.height(12.dp))
        MenuItem(if (isFav) "Fjern fra hjemskjermen" else "Legg til på hjemskjermen") { act { e.setFavorite(app.key, !isFav) } }
        MenuItem("Endre navn", onRename)
        MenuItem(settings.groups[app.key]?.let { "Gruppe: $it" } ?: "Legg i gruppe", onGroup)
        MenuItem(if (isHidden) "Vis i applisten" else "Skjul fra applisten") { act { e.setHidden(app.key, !isHidden) } }
        MenuItem(if (pause) "Pauseskjerm: på" else "Pauseskjerm: av") { act { e.setPause(app.packageName, !pause) } }
        MenuItem(if (limit != null) "Daglig grense: $limit min" else "Daglig grense: ingen", onLimit)
        MenuItem(if (muted) "Demp varsler: på" else "Demp varsler: av") {
            act {
                e.setMuted(app.packageName, !muted)
                if (!muted && !MutedNotifications.hasAccess(activity)) {
                    activity.startInternal(MutedNotifications.accessIntent(activity))
                }
            }
        }
        MenuItem("Appinfo") { activity.apps.openAppInfo(app); onClose() }
        if (app.isShortcut) {
            MenuItem("Fjern snarvei") { activity.apps.unpin(app); onClose() }
        } else {
            MenuItem("Avinstaller") { activity.apps.uninstall(app); onClose() }
        }
    }
}

@Composable
fun RenameDialog(activity: MainActivity, app: AppEntry, settings: AppSettings, onClose: () -> Unit) {
    // Lagring må overleve at dialogen lukkes, derfor aktivitetens scope.
    val scope = activity.lifecycleScope
    var text by remember { mutableStateOf(app.label(settings)) }
    val fr = remember { FocusRequester() }
    fun save(name: String?) {
        scope.launch { activity.editor.rename(app.key, name?.takeIf { it.trim() != app.originalLabel }) }
        onClose()
    }
    TextDialog(onClose) {
        Text("Endre navn", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save(text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        HorizontalDivider(color = Faint)
        Spacer(Modifier.height(8.dp))
        Row {
            TextAction("Lagre", color = Color.White) { save(text) }
            Spacer(Modifier.width(16.dp))
            TextAction("Originalt navn") { save(null) }
            Spacer(Modifier.width(16.dp))
            TextAction("Avbryt", onClick = onClose)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
fun LimitDialog(activity: MainActivity, app: AppEntry, settings: AppSettings, onClose: () -> Unit) {
    // Lagring må overleve at dialogen lukkes, derfor aktivitetens scope.
    val scope = activity.lifecycleScope
    val current = settings.limits[app.packageName]
    fun set(min: Int?) {
        scope.launch { activity.editor.setLimit(app.packageName, min) }
        if (min != null && !Usage.hasPermission(activity)) activity.startInternal(Usage.permissionIntent(activity))
        onClose()
    }
    TextDialog(onClose) {
        Text("Daglig grense for ${app.label(settings)}", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White)
        Text("Når grensen er nådd, må du vente lenger på pauseskjermen.", fontSize = 14.sp, color = Faint)
        Spacer(Modifier.height(8.dp))
        MenuItem(if (current == null) "• Ingen grense" else "Ingen grense") { set(null) }
        for (m in listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180)) {
            val label = if (m < 60 || m % 60 != 0) "$m min" else "${m / 60} t"
            MenuItem(if (current == m) "• $label" else label) { set(m) }
        }
    }
}

// ---------- Pauseskjerm ----------

@Composable
fun PauseScreen(app: AppEntry, settings: AppSettings, usedMs: Long, onOpen: () -> Unit, onCancel: () -> Unit) {
    val limit = settings.limits[app.packageName]
    val overLimit = limit != null && usedMs >= limit * 60_000L
    val total = if (overLimit) maxOf(settings.pauseSeconds * 3, 30) else settings.pauseSeconds
    var left by remember(app.key) { mutableIntStateOf(total) }
    LaunchedEffect(app.key) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    FullScreen {
        Spacer(Modifier.weight(1f))
        Text(app.label(settings), fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                overLimit -> "Du har nådd dagens grense på $limit min."
                else -> "Pust rolig. Trenger du egentlig å åpne den nå?"
            },
            fontSize = 18.sp,
            color = Dim,
        )
        if (usedMs > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Brukt i dag: ${Usage.format(usedMs)}" + (if (limit != null && !overLimit) " av $limit min" else ""),
                fontSize = 15.sp,
                color = Faint,
            )
        }
        Spacer(Modifier.height(48.dp))
        Text(
            if (left > 0) "$left" else "",
            fontSize = 96.sp,
            fontWeight = FontWeight.Thin,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("countdown"),
        )
        Spacer(Modifier.weight(1f))
        if (left == 0) {
            TextAction("Åpne ${app.label(settings)}", color = Color.White, size = 20, onClick = onOpen)
        }
        TextAction("Avbryt", color = Dim, size = 20, onClick = onCancel)
        Spacer(Modifier.height(32.dp))
    }
}

// ---------- Dempede varsler ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationsScreen(activity: MainActivity, items: List<MutedItem>, onClose: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("d.M. HH:mm") }
    val groups = remember(items) { items.groupBy { it.packageName }.values.sortedByDescending { g -> g.maxOf { it.time } } }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }
    FullScreen {
        Title("Dempede varsler", onClose)
        Row {
            TextAction("Fjern alle") { MutedNotifications.clear() }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for (group in groups) {
                val first = group.first()
                item(key = "h:" + first.packageName) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(first.appLabel, fontSize = 20.sp, color = Color.White, modifier = Modifier.weight(1f))
                        TextAction("Fjern") { MutedNotifications.removePackage(first.packageName) }
                    }
                }
                items(group, key = { it.key }) { n ->
                    val t = Instant.ofEpochMilli(n.time).atZone(zone)
                    val time = if (t.toLocalDate() == LocalDate.now()) t.format(timeFmt) else t.format(dayFmt)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { MutedNotifications.open(activity, n) },
                                onLongClick = { MutedNotifications.remove(n.key) },
                            )
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            listOf(n.title, time).filter { it.isNotBlank() }.joinToString("  ·  "),
                            fontSize = 16.sp,
                            color = Dim,
                        )
                        if (n.text.isNotBlank()) Text(n.text, fontSize = 15.sp, color = Faint, maxLines = 4)
                    }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ---------- Widget ----------

@Composable
fun WidgetPicker(controller: WidgetController, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val providers = remember { controller.providers().map { it to controller.label(it) } }
    FullScreen {
        Title("Velg widget", onClose)
        LazyColumn(Modifier.fillMaxSize()) {
            items(providers, key = { it.first.provider.flattenToString() + it.first.profile.hashCode() }) { (info, label) ->
                MenuItem(label) { onPick(info) }
            }
            if (providers.isEmpty()) item { Text("Fant ingen widgeter.", color = Faint) }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

fun LazyListScope.sectionHeader(text: String) {
    item(key = "section:$text") {
        Text(
            text.uppercase(),
            fontSize = 13.sp,
            color = Faint,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
        )
    }
}
