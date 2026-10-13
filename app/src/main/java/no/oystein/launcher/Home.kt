package no.oystein.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Bolt
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.provider.AlarmClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val norsk = Locale("nb", "NO")

/**
 * Klokkeslettet nå, oppdatert hvert minutt. Android sender ACTION_TIME_TICK på hvert minuttskifte
 * mens skjermen er på; i tillegg oppdateres det når launcheren vises igjen og når tiden/tidssonen endres.
 * (En ren ventetråd henger etter etter at telefonen har sovet, fordi den bare teller våken tid.)
 */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { now = LocalDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LifecycleResumeEffect(Unit) {
        now = LocalDateTime.now()
        onPauseOrDispose { }
    }
    // Reserve i tilfelle et minuttskifte ikke kommer: sjekk hvert sekund, men bare mens launcheren vises.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t = LocalDateTime.now()
                if (t.minute != now.minute || t.hour != now.hour || t.toLocalDate() != now.toLocalDate()) now = t
                delay(1000L - (System.currentTimeMillis() % 1000L) + 20L)
            }
        }
    }
    return now
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(
    activity: MainActivity,
    settings: AppSettings,
    apps: List<AppEntry>,
    usage: Map<String, Long>,
    opens: Map<String, Int>,
    muted: List<MutedItem>,
    expanded: Set<String>,
    onToggleGroup: (String) -> Unit,
    onGroupMenu: (String) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
    onNote: () -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
) {
    val now = rememberNow()
    val timeFormat = remember(settings.use24h) {
        DateTimeFormatter.ofPattern(if (settings.use24h) "HH:mm" else "h:mm", norsk)
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d. MMMM", norsk) }
    // Dempede varsler holdes tilbake til neste leveringstidspunkt hvis det er valgt i innstillingene.
    val mutedCount = remember(muted, settings.mutedDelivery, now) {
        val cutoff = settings.mutedCutoff(now)
        muted.count { it.time <= cutoff }.also {
            android.util.Log.d("Launcher", "dempede: viser $it av ${muted.size} (levering ${settings.mutedDelivery})")
        }
    }
    val nextLines by produceState(emptyList<String>(), settings.showNextEvent, now) {
        value = if (!settings.showNextEvent) emptyList() else withContext(Dispatchers.IO) {
            listOfNotNull(NextEvent.nextEvent(activity), NextEvent.nextAlarm(activity))
        }
    }
    val update by Updater.state.collectAsState()
    val currentSettings by rememberUpdatedState(settings)
    val currentEditingRoot by rememberUpdatedState(editing)
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val favorites = settings.favorites.mapNotNull { byKey[it] }

    // Ny rekkefølge for én del (enkeltappene eller én gruppe): appene i delen bytter plass seg
    // imellom, mens resten av favorittlisten står urørt.
    fun saveOrder(section: List<String>) {
        val inSection = section.toSet()
        val next = section.iterator()
        val newList = settings.favorites.map { k -> if (k in inSection && next.hasNext()) next.next() else k }
        if (newList != settings.favorites) activity.lifecycleScope.launch { activity.editor.setFavorites(newList) }
    }
    // Favoritter som ligger i en gruppe vises under gruppen; gruppene står øverst i alfabetisk rekkefølge.
    val (groups, singles) = remember(favorites, settings) { favorites.grouped(settings) }
    val favoritesScroll = rememberScrollState()
    val currentEditing by rememberUpdatedState(editing)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onSettings() },
                    onDoubleTap = { if (currentSettings.doubleTapLock && !currentEditingRoot) lockScreen(activity) },
                )
            }
            .observeSwipeDown(
                enabled = { !currentEditing && favoritesScroll.value == 0 },
                onSwipeDown = { expandNotifications(activity) },
            )
            .testTag("home")
    ) {
        // Når statuslinjen er skjult, vises batteriet diskret oppe til høyre i stedet.
        if (settings.hideStatusBar) {
            BatteryIndicator(
                Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .padding(top = 14.dp, end = 24.dp)
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = now.format(timeFormat),
                fontSize = 72.sp,
                fontWeight = FontWeight.Thin,
                color = Color.White,
                // Trykk: alarmer. Hold inne: skriv eller endre notatet på hjemskjermen.
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            activity.startInternal(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onLongClick = onNote,
                    )
                    .testTag("clock"),
            )
            Text(
                text = now.format(dateFormat),
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                color = Dim,
                modifier = Modifier.combinedClickable(onClick = {
                    val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startInternal(i)
                }),
            )
            for (line in nextLines) {
                Spacer(Modifier.height(6.dp))
                Text(
                    line,
                    fontSize = 15.sp,
                    color = Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(onClick = {
                        val i = if (line.startsWith("Alarm")) {
                            Intent(AlarmClock.ACTION_SHOW_ALARMS)
                        } else {
                            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        }
                        activity.startInternal(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }),
                )
            }
            if (settings.showScreenTime && usage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val openCount = opens.values.sum()
                Text(
                    "Skjermtid i dag: ${Usage.format(usage.values.sum())}" +
                        if (openCount > 0) " · $openCount ${if (openCount == 1) "åpning" else "åpninger"}" else "",
                    fontSize = 15.sp,
                    color = Faint,
                )
            }
            if (mutedCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (mutedCount == 1) "1 dempet varsel" else "$mutedCount dempede varsler",
                    fontSize = 15.sp,
                    color = Dim,
                    modifier = Modifier
                        .combinedClickable(onClick = onNotifications)
                        .padding(vertical = 4.dp),
                )
            }

            if (settings.homeNote.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    settings.homeNote,
                    fontSize = 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .combinedClickable(onClick = onNote)
                        .padding(vertical = 4.dp)
                        .testTag("note"),
                )
            }
            updateText(update, onHome = true)?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    fontSize = 15.sp,
                    color = Dim,
                    modifier = Modifier
                        .combinedClickable(onClick = {
                            when (val u = update) {
                                is Updater.State.Available -> activity.lifecycleScope.launch { Updater.install(activity, u.release) }
                                is Updater.State.Failed -> activity.lifecycleScope.launch { Updater.check(activity, force = true) }
                                else -> {}
                            }
                        })
                        .padding(vertical = 4.dp)
                        .testTag("update"),
                )
            }

            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    // Bare rullbar når favorittene ikke får plass; ellers ville et litt skrått sveip til
                    // siden bli tatt som rulling, og sidebyttet uteble.
                    .verticalScroll(favoritesScroll, enabled = favoritesScroll.maxValue > 0)
            ) {
                if (settings.favorites.isEmpty()) {
                    Text(
                        "Sveip til venstre for apper.\nHold inne på en app for å legge den til her.\nHold inne på tom plass for innstillinger.",
                        fontSize = 15.sp,
                        color = Faint,
                    )
                }
                for (group in groups) {
                    val open = group.name in expanded
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            GroupHeader(
                                group = group,
                                expanded = open,
                                fontSize = 28.sp,
                                verticalPadding = 10,
                                onToggle = { onToggleGroup(group.name) },
                                onMenu = { onGroupMenu(group.name) },
                            )
                        }
                        if (editing) MoreButton("Valg for ${group.name}") { onGroupMenu(group.name) }
                    }
                    if (open) {
                        ReorderableFavorites(
                            apps = group.apps,
                            settings = settings,
                            indent = true,
                            editing = editing,
                            onEditingChange = onEditingChange,
                            onOpen = onOpen,
                            onMenu = onMenu,
                            onReorder = ::saveOrder,
                        )
                    }
                }
                ReorderableFavorites(
                    apps = singles,
                    settings = settings,
                    indent = false,
                    editing = editing,
                    onEditingChange = onEditingChange,
                    onOpen = onOpen,
                    onMenu = onMenu,
                    onReorder = ::saveOrder,
                )
            }
            Spacer(Modifier.weight(1f))
            // Snarveiene nederst, fordelt over bredden (rekkefølge og synlighet velges i innstillingene).
            val shortcuts = settings.shortcuts.filter { it.visible }
            if (editing) {
                // Sorteringsmodus: «Ferdig» i stedet for snarveiene (Tilbake og Hjem låser også).
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    PillButton("Ferdig") { onEditingChange(false) }
                }
            } else if (shortcuts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (shortcuts.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
                ) {
                    for (s in shortcuts) {
                        CornerIcon(s.kind.icon(), s.kind.label) { openShortcut(activity, s.kind) }
                    }
                }
            }
        }
    }
}

/**
 * Sveip ned hvor som helst på hjemskjermen – også over favorittene og nederst ved snarveiene – trekker
 * ned varselpanelet, som i Minimalist Phone. Berøringen observeres før appene under ser den, uten å tas
 * over, så trykk, sidebytte og sortering virker som før. Holder man først inne (sortering), teller det ikke.
 */
private fun Modifier.observeSwipeDown(enabled: () -> Boolean, onSwipeDown: () -> Unit): Modifier =
    pointerInput(Unit) {
        val threshold = 48.dp.toPx()
        val timeout = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled()) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // Etter langt trykk er det sortering (eller innstillinger), ikke sveip.
                if (change.uptimeMillis - down.uptimeMillis > timeout) break
                val d = change.position - down.position
                // Sidelengs betyr sidebytte; da gir vi oss.
                if (abs(d.x) > threshold && abs(d.x) > abs(d.y)) break
                if (d.y > threshold && d.y > abs(d.x) * 1.5f) {
                    android.util.Log.d("Launcher", "sveip ned: varselpanel")
                    onSwipeDown()
                    break
                }
            }
        }
    }

/** Lite batterisymbol med prosent, oppdatert når batterinivået endres. */
@Composable
private fun BatteryIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        fun read(i: Intent?) {
            i ?: return
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (l >= 0) level = l * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        // Batteristatus er «sticky»: registreringen gir siste verdi med en gang.
        read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    if (level < 0) return
    val color = if (level <= 15 && !charging) Color.White else Dim
    Row(modifier.testTag("battery"), verticalAlignment = Alignment.CenterVertically) {
        Text("$level %", fontSize = 13.sp, color = color)
        Spacer(Modifier.width(6.dp))
        // Batteriet tegnet som en liten ramme med fyll etter nivået, og en knott på enden.
        Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
            val stroke = 1.2.dp.toPx()
            val nub = 2.dp.toPx()
            val bodyW = size.width - nub
            drawRoundRect(
                color = color,
                size = androidx.compose.ui.geometry.Size(bodyW, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
            val pad = stroke * 1.8f
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size((bodyW - pad * 2) * level / 100f, size.height - pad * 2),
            )
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(bodyW, size.height * 0.3f),
                size = androidx.compose.ui.geometry.Size(nub, size.height * 0.4f),
            )
        }
        if (charging) {
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Outlined.Bolt, contentDescription = "Lader", tint = color, modifier = Modifier.size(12.dp))
        }
    }
}

/** Tekst om ny versjon på hjemskjermen, eller null når det ikke er noe å si. */
fun updateText(state: Updater.State, onHome: Boolean = false): String? = when (state) {
    is Updater.State.Available -> "Ny versjon ${state.release.versionName} – trykk for å oppdatere"
    is Updater.State.Downloading -> "Laster ned oppdatering … ${state.percent} %"
    is Updater.State.Installing -> "Installerer oppdatering …"
    is Updater.State.Failed -> "${state.message} Trykk for å prøve igjen."
    is Updater.State.Info -> if (onHome) null else state.message
    Updater.State.Idle -> null
}

/** Låser telefonen (dobbelttrykk), eller forklarer hvordan tjenesten slås på. */
private fun lockScreen(activity: MainActivity) {
    if (LockService.lock()) return
    Toast.makeText(
        activity,
        "Slå på «Mono Launcher» under Tilgjengelighet for å låse med dobbelttrykk",
        Toast.LENGTH_LONG,
    ).show()
    activity.startInternal(LockService.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Trekker ned varselpanelet (sveip ned på hjemskjermen). */
@SuppressLint("WrongConstant")
private fun expandNotifications(activity: MainActivity) {
    runCatching {
        val sbm = activity.getSystemService("statusbar")
        sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
    }
}

/**
 * Favorittene i én del, sorterbare: hold inne på en app (sorteringsmodus slås på) og dra opp eller
 * ned i ett sveip. Listen står stille mens man drar – bare appen man holder følger fingeren, og de
 * andre dyttes et hakk med en myk animasjon etter hvert som den passerer. Ny rekkefølge lagres ved slipp.
 * (Å flytte radene underveis ville avbrutt berøringen, derfor flyttes de bare visuelt til man slipper.)
 */
@Composable
private fun ReorderableFavorites(
    apps: List<AppEntry>,
    settings: AppSettings,
    indent: Boolean,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val keys = apps.map { it.key }
    var order by remember(keys) { mutableStateOf(keys) }
    val byKey = remember(apps) { apps.associateBy { it.key } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // Radenes plass i listen (uten dra-forskyvning), målt av oppsettet selv.
    val tops = remember { mutableStateMapOf<String, Float>() }
    val haptic = LocalHapticFeedback.current
    val currentEditing by rememberUpdatedState(editing)
    val scope = rememberCoroutineScope()
    // Når man slipper, glir appen det siste lille stykket på plass.
    val settle = remember { Animatable(0f) }
    var settlingKey by remember { mutableStateOf<String?>(null) }

    // Toppen av rad i (målt), og bunnen av listen for i == antall rader.
    fun top(i: Int): Float = when {
        i < order.size -> tops[order[i]] ?: (0 until i).sumOf { heights[order[it]] ?: 0 }.toFloat()
        order.isEmpty() -> 0f
        else -> top(order.lastIndex) + (heights[order.last()] ?: 0)
    }
    fun height(i: Int): Float = (heights[order[i]] ?: 0).toFloat()

    // Hvor appen havner hvis man slipper nå: antall andre apper som ligger over den. Drar man opp,
    // holder det at toppkanten passerer midten av appen over (bunnkanten når man drar ned), så man
    // når første og siste plass uten å måtte treffe på pikselen. Da går det også an å dra forbi
    // mange i ett sveip.
    fun targetIndex(): Int {
        val k = dragging ?: return -1
        val myTop = tops[k] ?: return -1
        val edge = if (offset < 0) myTop + offset else myTop + (heights[k] ?: 0) + offset
        return order.count { other ->
            other != k && (tops[other] ?: 0f) + (heights[other] ?: 0) / 2f < edge
        }
    }

    val start = dragging?.let { order.indexOf(it) } ?: -1
    val target = if (dragging != null) targetIndex() else -1
    var lastTarget by remember { mutableIntStateOf(-1) }
    LaunchedEffect(target) {
        if (target >= 0 && lastTarget >= 0 && target != lastTarget) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastTarget = target
    }

    fun drop() {
        val k = dragging ?: return
        val from = order.indexOf(k)
        val to = targetIndex()
        android.util.Log.d(
            "Launcher",
            "sortering: $k fra $from til $to (forskyvning ${offset.toInt()} px, " +
                "rader ${order.map { "${tops[it]?.toInt()}+${heights[it]}" }})",
        )
        if (from >= 0 && to >= 0 && to != from) {
            // Avstanden mellom der fingeren slapp og der appen faktisk havner, så den kan gli på plass.
            val landTop = if (to > from) top(to) + height(to) - height(from) else top(to)
            val residual = top(from) + offset - landTop
            order = order.toMutableList().apply { add(to, removeAt(from)) }
            settlingKey = k
            // Uten å vente på neste bilde, så appen ikke blinker på feil plass.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settle.snapTo(residual)
                settle.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                settlingKey = null
            }
            onReorder(order)
        } else {
            settlingKey = k
            val residual = offset
            // Uten å vente på neste bilde, så appen ikke blinker på feil plass.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settle.snapTo(residual)
                settle.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                settlingKey = null
            }
        }
        dragging = null
        offset = 0f
    }

    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { i, k ->
            val app = byKey[k] ?: return@forEachIndexed
            key(k) {
                val isDragging = dragging == k
                // De andre dyttes ett hakk (høyden til appen man drar) når den passerer dem.
                val push = when {
                    dragging == null || isDragging || start < 0 || target < 0 -> 0f
                    target > start && i in (start + 1)..target -> -height(start)
                    target < start && i in target until start -> height(start)
                    else -> 0f
                }
                val shift by animateFloatAsState(
                    targetValue = push,
                    animationSpec = if (dragging != null) {
                        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
                    } else {
                        snap()
                    },
                    label = "dytt",
                )
                val scale by animateFloatAsState(if (isDragging) 1.06f else 1f, label = "løft")
                FavoriteRow(
                    app = app,
                    settings = settings,
                    indent = indent,
                    editing = editing,
                    highlighted = isDragging,
                    dimmed = dragging != null && !isDragging,
                    onOpen = { if (!currentEditing) onOpen(app) },
                    onMenu = { onMenu(app) },
                    modifier = Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .onGloballyPositioned { tops[k] = it.positionInParent().y }
                        .zIndex(if (isDragging || settlingKey == k) 1f else 0f)
                        .graphicsLayer {
                            translationY = when {
                                isDragging -> offset
                                settlingKey == k -> settle.value
                                else -> shift
                            }
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .pointerInput(k) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onEditingChange(true)
                                    dragging = k
                                    offset = 0f
                                },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() },
                            ) { change, amount ->
                                change.consume()
                                // Ikke lenger enn litt forbi toppen/bunnen av listen (litt slingringsmonn, så
                                // man alltid når første og siste plass).
                                val i0 = order.indexOf(k)
                                val minOffset = -top(i0) - height(i0) / 2
                                val maxOffset = top(order.size) - top(i0) - height(i0) / 2
                                offset = (offset + amount.y).coerceIn(minOffset, maxOffset)
                            }
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    app: AppEntry,
    settings: AppSettings,
    indent: Boolean,
    editing: Boolean,
    highlighted: Boolean,
    dimmed: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dra-registreringen (i [modifier]) ligger innenfor trykket, så den ser berøringen først.
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen)
            .then(modifier)
            .padding(start = if (indent) 28.dp else 0.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = app.label(settings),
            fontSize = if (indent) 24.sp else 28.sp,
            fontWeight = if (highlighted) FontWeight.Normal else FontWeight.Light,
            color = if (dimmed) Dim else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editing) MoreButton("Valg for ${app.label(settings)}", onClick = onMenu)
    }
}

/** «⋯» helt til høyre i sorteringsmodus: åpner menyen for appen eller gruppen. */
@Composable
private fun MoreButton(description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("⋯", fontSize = 26.sp, color = Dim, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun CornerIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Dim, modifier = Modifier.size(26.dp))
    }
}

private fun ShortcutKind.icon(): ImageVector = when (this) {
    ShortcutKind.PHONE -> Icons.Outlined.Phone
    ShortcutKind.SMS -> Icons.Outlined.ChatBubbleOutline
    ShortcutKind.CAMERA -> Icons.Outlined.PhotoCamera
}

private fun openShortcut(activity: MainActivity, kind: ShortcutKind) = when (kind) {
    ShortcutKind.PHONE -> openDialer(activity)
    ShortcutKind.SMS -> openMessages(activity)
    ShortcutKind.CAMERA -> openCamera(activity)
}

/** Åpner standard meldingsapp. */
private fun openMessages(activity: MainActivity) {
    val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { activity.startActivity(main) }.isFailure) {
        // Reserve: «skriv ny melding» uten mottaker.
        val compose = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(compose) }
    }
}

private fun openDialer(activity: MainActivity) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Åpner kameraappen (fotomodus), med vanlig oppstart av kameraappen som reserve. */
private fun openCamera(activity: MainActivity) {
    val still = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { activity.startActivity(still) }.isSuccess
    if (!ok) {
        val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, "android.intent.category.APP_CAMERA")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(main) }
    }
}
