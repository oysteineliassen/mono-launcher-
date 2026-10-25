package no.oystein.launcher

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.Normalizer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListPage(
    settings: AppSettings,
    apps: List<AppEntry>,
    usage: Map<String, Long>,
    query: String,
    onQuery: (String) -> Unit,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
    isCurrent: Boolean,
    overlayOpen: Boolean,
    expanded: Set<String>,
    onToggleGroup: (String) -> Unit,
    onGroupMenu: (String) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onSettings: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val visible = remember(apps, settings, showHidden) {
        if (showHidden) apps else apps.filter { it.key !in settings.hidden }
    }
    val results = remember(visible, query, settings) { visible.search(query, settings) }
    val hiddenCount = remember(apps, settings) { apps.count { it.key in settings.hidden } }
    // Uten søk: grupper øverst (alfabetisk), så enkeltappene. Med søk: grupper med treff i navnet
    // vises åpne øverst, deretter alle apper som passer søket.
    val (groups, singles) = remember(visible, results, query, settings) {
        if (query.isBlank()) {
            results.grouped(settings)
        } else {
            val q = query.trim().lowercase()
            visible.grouped(settings).first.filter { it.name.lowercase().contains(q) } to results
        }
    }

    // Nylig installerte apper ligger øverst de første to døgnene, deretter i alfabetisk rekkefølge med resten.
    // (Bare uten søk; apper som er lagt i en gruppe, blir i gruppen.)
    val newApps = remember(singles, query) {
        if (query.isNotBlank()) emptyList() else {
            val since = System.currentTimeMillis() - NEW_APP_MS
            singles.filter { it.installedAt > since && !it.isShortcut }.sortedByDescending { it.installedAt }
        }
    }
    val restSingles = remember(singles, newApps) {
        if (newApps.isEmpty()) singles else newApps.map { it.key }.toSet().let { keys -> singles.filter { it.key !in keys } }
    }

    // Tastaturet følger siden og eventuelle dialoger; søket nullstilles bare når man forlater siden.
    // Første plass i listen for hver forbokstav blant enkeltappene (grupper og åpne gruppeapper ligger før).
    val letterIndex = remember(groups, restSingles, newApps, expanded, query, settings) {
        var idx = 1 // mellomrommet øverst i listen
        if (newApps.isNotEmpty()) idx += 2 + newApps.size // «Nye», appene og skillelinjen
        for (g in groups) {
            idx += 1
            if (query.isNotBlank() || g.name in expanded) idx += g.apps.size
        }
        val map = LinkedHashMap<Char, Int>()
        restSingles.forEachIndexed { i, a -> map.putIfAbsent(firstLetter(a.label(settings)), idx + i) }
        map
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isCurrent, overlayOpen) {
        if (isCurrent && !overlayOpen && settings.autoKeyboard) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            keyboard?.hide()
            focus.clearFocus()
            onQuery("")
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }

    fun openAndReset(app: AppEntry) {
        keyboard?.hide()
        onOpen(app)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            // Ingen høyremarg her: bokstavlisten skal gå helt ut mot skjermkanten.
            .padding(start = 32.dp)
    ) {
        // Luft over søkefeltet; med skjult statuslinje står batteriet her, som på hjemskjermen.
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            if (settings.hideStatusBar) {
                BatteryIndicator(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 24.dp))
            }
        }
        Row(Modifier.padding(end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let { openAndReset(it) } }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag("search"),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth()) {
                        if (query.isEmpty()) Text("Søk", fontSize = 24.sp, color = Faint, fontWeight = FontWeight.Light)
                        field()
                    }
                },
            )
            Box(
                Modifier
                    .size(48.dp)
                    // Trykk: launcherens innstillinger. Hold inne: Androids innstillinger.
                    .combinedClickable(
                        onClick = { keyboard?.hide(); onSettings() },
                        onLongClick = { keyboard?.hide(); openAndroidSettings(context) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "Innstillinger", tint = Dim, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(end = 32.dp), color = Faint)
        Row(Modifier.fillMaxSize().padding(end = 4.dp)) {
            val showLetters = query.isBlank() && letterIndex.size > 1
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = if (showLetters) 0.dp else 28.dp)
                    .testTag("applist"),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (newApps.isNotEmpty()) {
                    item(key = "nye") {
                        Text("Nye", fontSize = 14.sp, color = Faint, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                    items(newApps, key = { "ny:" + it.key }) { app ->
                        AppRow(app, settings, usage, indent = false, onOpen = ::openAndReset, onMenu = onMenu)
                    }
                    // Luft og en tynn strek mellom de nye og resten av listen.
                    item(key = "nye-slutt") {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(Modifier.padding(end = 32.dp), color = Faint, thickness = 0.5.dp)
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                for (group in groups) {
                    val open = query.isNotBlank() || group.name in expanded
                    item(key = "g:" + group.name) {
                        GroupHeader(
                            group = group,
                            expanded = open,
                            fontSize = 22.sp,
                            verticalPadding = 11,
                            onToggle = { onToggleGroup(group.name) },
                            onMenu = { onGroupMenu(group.name) },
                        )
                    }
                    if (open) {
                        items(group.apps, key = { "g:${group.name}:${it.key}" }) { app ->
                            AppRow(app, settings, usage, indent = true, onOpen = ::openAndReset, onMenu = onMenu)
                        }
                    }
                }
                items(restSingles, key = { it.key }) { app ->
                    AppRow(app, settings, usage, indent = false, onOpen = ::openAndReset, onMenu = onMenu)
                }
                if (results.isEmpty() && groups.isEmpty() && query.isNotBlank()) {
                    item { Text("Ingen treff", fontSize = 18.sp, color = Faint, modifier = Modifier.padding(vertical = 12.dp)) }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    if (hiddenCount > 0) {
                        Text(
                            if (showHidden) "Skjul skjulte apper" else "Vis skjulte apper ($hiddenCount)",
                            fontSize = 16.sp,
                            color = Dim,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onShowHidden(!showHidden) })
                                .padding(vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
            if (showLetters) {
                AlphabetBar(
                    letters = letterIndex.keys.toList(),
                    onLetter = { c -> letterIndex[c]?.let { scope.launch { listState.scrollToItem(it) } } },
                    modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppEntry,
    settings: AppSettings,
    usage: Map<String, Long>,
    indent: Boolean,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val hidden = app.key in settings.hidden
    val used = usage[app.packageName] ?: 0L
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onMenu(app) })
            .padding(start = if (indent) 24.dp else 0.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            app.label(settings),
            fontSize = if (indent) 20.sp else 22.sp,
            fontWeight = FontWeight.Light,
            color = if (hidden) Faint else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (used >= 60_000L) {
            Text(Usage.format(used), fontSize = 13.sp, color = Faint)
        }
    }
}

/** Forbokstav for bokstavlisten: Æ, Ø og Å beholdes, andre aksenter fjernes, alt som ikke er bokstav blir #. */
fun firstLetter(label: String): Char {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    if (c in "ÆØÅ") return c
    val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().uppercaseChar()
    return if (base.isLetter()) base else '#'
}

/** Hvor lenge en nyinstallert app ligger øverst i applisten. */
private const val NEW_APP_MS = 48 * 60 * 60 * 1000L

/** Åpner Androids egne innstillinger. */
fun openAndroidSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
