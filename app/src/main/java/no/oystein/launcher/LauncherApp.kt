package no.oystein.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Dim = Color(0xFF8A8A8A)
val Faint = Color(0xFF4A4A4A)

private val colors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Dim,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Dim,
    surfaceContainerHigh = Color(0xFF0E0E0E),
    outline = Faint,
)

const val PAGE_WIDGET = 0
const val PAGE_HOME = 1
const val PAGE_APPS = 2

/** Hva som vises over sidene. */
sealed interface Overlay {
    data object Settings : Overlay
    data object Notifications : Overlay
    data object WidgetPicker : Overlay
    data class AppMenu(val app: AppEntry) : Overlay
    data class Rename(val app: AppEntry) : Overlay
    data class Limit(val app: AppEntry) : Overlay
    data class Group(val app: AppEntry) : Overlay
    data class GroupMenu(val name: String) : Overlay
    data class RenameGroup(val name: String) : Overlay
    data class Pause(val app: AppEntry) : Overlay
}

@Composable
fun LauncherApp(activity: MainActivity) {
    MaterialTheme(colorScheme = colors) {
        val settingsOrNull by remember { activity.settingsFlow() }.collectAsState(initial = activity.initialSettings)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            settingsOrNull?.let { LauncherContent(activity, it) }
        }
    }
}

@Composable
private fun LauncherContent(activity: MainActivity, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val resumeCount by activity.resumeCount.collectAsState()
    val muted by MutedNotifications.items.collectAsState()
    // Starter med sist kjente liste (fra disk) til den ekte er lastet.
    var allApps by remember { mutableStateOf(activity.initialApps) }
    var appsReady by remember { mutableStateOf(false) }
    // Trykk på en app før den ekte listen er klar, åpnes så snart den er det.
    var pendingOpen by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf(emptyMap<String, Long>()) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // Til dette tidspunktet holdes widgetsiden fast (se under).
    var pinWidgetPageUntil by remember { mutableLongStateOf(0L) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    // Sorteringsmodus for favorittene på hjemskjermen.
    var homeEditing by remember { mutableStateOf(false) }
    // Åpne grupper, felles for hjemskjerm og appliste. Lukkes når man går hjem.
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    fun toggleGroup(name: String) {
        expanded = if (name in expanded) expanded - name else expanded + name
    }
    val pager = rememberPagerState(initialPage = PAGE_HOME) { 3 }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Applisten lastes ved start og når apper installeres/fjernes.
    LaunchedEffect(Unit) {
        merge(activity.apps.changes(), activity.homeEvents).onStart { emit(Unit) }.collect {
            val loaded = withContext(Dispatchers.Default) { activity.apps.loadApps() }
            allApps = loaded
            appsReady = true
            withContext(Dispatchers.IO) { activity.apps.saveCache(loaded) }
        }
    }
    // Ny versjon på GitHub? Sjekkes når launcheren vises (høyst hver tredje time).
    LaunchedEffect(resumeCount) { Updater.check(activity) }
    LaunchedEffect(resumeCount, pager.settledPage) {
        usage = withContext(Dispatchers.Default) { Usage.today(activity) }
    }
    LaunchedEffect(Unit) {
        activity.homeEvents.collect {
            overlay = null
            homeEditing = false
            pinWidgetPageUntil = 0L
            query = ""
            showHidden = false
            expanded = emptySet()
            keyboard?.hide()
            focus.clearFocus()
            pager.scrollToPage(PAGE_HOME)
        }
    }

    LaunchedEffect(pager.settledPage) {
        android.util.Log.d("Launcher", "side=${pager.settledPage}")
        if (pager.settledPage != PAGE_HOME) homeEditing = false
    }

    // Mens widgetpanelet er åpent, og en stund etter at en widget er valgt (tillatelse og oppsett i
    // andre vinduer), skal siden bak stå stille på widgetsiden. Tastaturet i panelet endrer
    // vindusstørrelsen, og det kunne få sidebladingen til å hoppe til hjemskjermen.
    val pinned = overlay == Overlay.WidgetPicker || android.os.SystemClock.elapsedRealtime() < pinWidgetPageUntil
    LaunchedEffect(pager.currentPage, pinned) {
        if (pinned && pager.currentPage != PAGE_WIDGET) {
            android.util.Log.d("Launcher", "holder widgetsiden (var ${pager.currentPage})")
            pager.scrollToPage(PAGE_WIDGET)
        }
    }
    LaunchedEffect(pinWidgetPageUntil) {
        val left = pinWidgetPageUntil - android.os.SystemClock.elapsedRealtime()
        if (left > 0) {
            kotlinx.coroutines.delay(left)
            pinWidgetPageUntil = 0L
        }
    }

    val sorted = remember(allApps, settings) { allApps.sortedByLabel(settings) }

    fun open(app: AppEntry) {
        if (!appsReady) {
            pendingOpen = app.key
            return
        }
        val limit = settings.limits[app.packageName]
        val used = usage[app.packageName] ?: 0L
        val overLimit = limit != null && used >= limit * 60_000L
        if (overLimit || app.packageName in settings.pausePackages) {
            keyboard?.hide()
            overlay = Overlay.Pause(app)
        } else {
            activity.launchApp(app)
        }
    }

    LaunchedEffect(appsReady, pendingOpen) {
        val key = pendingOpen ?: return@LaunchedEffect
        if (!appsReady) return@LaunchedEffect
        pendingOpen = null
        allApps.firstOrNull { it.key == key }?.let { open(it) }
    }

    BackHandler {
        when {
            overlay != null -> overlay = null
            homeEditing -> homeEditing = false
            pager.currentPage != PAGE_HOME -> {
                android.util.Log.d("Launcher", "home: tilbake-knapp")
                scope.launch { pager.animateScrollToPage(PAGE_HOME) }
            }
        }
    }

    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 2,
        // Bytt side allerede når man har dratt en firedel av bredden (standard er halvparten), så også
        // korte, rolige sveip virker.
        flingBehavior = PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = 0.25f),
    ) { page ->
        when (page) {
            PAGE_WIDGET -> WidgetPage(
                activity = activity,
                widgets = settings.widgets,
                onAdd = { overlay = Overlay.WidgetPicker },
            )
            PAGE_HOME -> HomePage(
                activity = activity,
                settings = settings,
                apps = allApps,
                usage = usage,
                muted = muted,
                expanded = expanded,
                onToggleGroup = ::toggleGroup,
                onGroupMenu = { overlay = Overlay.GroupMenu(it) },
                onOpen = ::open,
                onMenu = { overlay = Overlay.AppMenu(it) },
                onSettings = { overlay = Overlay.Settings },
                onNotifications = { overlay = Overlay.Notifications },
                editing = homeEditing,
                onEditingChange = { homeEditing = it },
            )
            PAGE_APPS -> AppListPage(
                settings = settings,
                apps = sorted,
                usage = usage,
                query = query,
                onQuery = { query = it },
                showHidden = showHidden,
                onShowHidden = { showHidden = it },
                isCurrent = pager.settledPage == PAGE_APPS,
                overlayOpen = overlay != null,
                expanded = expanded,
                onToggleGroup = ::toggleGroup,
                onGroupMenu = { overlay = Overlay.GroupMenu(it) },
                onOpen = ::open,
                onMenu = { overlay = Overlay.AppMenu(it) },
                onSettings = { overlay = Overlay.Settings },
            )
        }
    }

    when (val o = overlay) {
        null -> {}
        Overlay.Settings -> SettingsScreen(
            activity = activity,
            settings = settings,
            apps = sorted,
            usage = usage,
            resumeCount = resumeCount,
            onPickWidget = { overlay = Overlay.WidgetPicker },
            onRenameGroup = { overlay = Overlay.RenameGroup(it) },
            onClose = { overlay = null },
        )
        Overlay.Notifications -> NotificationsScreen(
            activity = activity,
            items = remember(muted, settings.mutedDelivery, resumeCount) {
                val cutoff = settings.mutedCutoff()
                muted.filter { it.time <= cutoff }
            },
            onClose = { overlay = null },
        )
        Overlay.WidgetPicker -> WidgetSheet(
            controller = activity.widgets,
            onPick = {
                pinWidgetPageUntil = android.os.SystemClock.elapsedRealtime() + 20_000
                overlay = null
                activity.pickWidget(it)
            },
            onClose = { overlay = null },
        )
        is Overlay.AppMenu -> AppMenu(
            activity = activity,
            app = o.app,
            settings = settings,
            onRename = { overlay = Overlay.Rename(o.app) },
            onLimit = { overlay = Overlay.Limit(o.app) },
            onGroup = { overlay = Overlay.Group(o.app) },
            onClose = { overlay = null },
        )
        is Overlay.Rename -> RenameDialog(activity, o.app, settings, onClose = { overlay = null })
        is Overlay.Limit -> LimitDialog(activity, o.app, settings, onClose = { overlay = null })
        is Overlay.Group -> GroupDialog(activity, o.app, settings, onClose = { overlay = null })
        is Overlay.GroupMenu -> GroupMenu(
            activity = activity,
            name = o.name,
            onRename = { overlay = Overlay.RenameGroup(o.name) },
            onClose = { overlay = null },
        )
        is Overlay.RenameGroup -> RenameGroupDialog(activity, o.name, onClose = { overlay = null })
        is Overlay.Pause -> PauseScreen(
            app = o.app,
            settings = settings,
            usedMs = usage[o.app.packageName] ?: 0L,
            onOpen = {
                overlay = null
                activity.launchApp(o.app)
            },
            onCancel = { overlay = null },
        )
    }
}
