package no.oystein.launcher

import android.appwidget.AppWidgetHostView
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Høyden på én rute i widgetrutenettet. Bredden er skjermbredden delt på [GRID_COLUMNS]. */
const val GRID_CELL_HEIGHT_DP = 88f

private val springSpec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * Widgetsiden: et rutenett med [GRID_COLUMNS] kolonner der widgetene kan plasseres fritt.
 * I «Rediger» drar man en widget for å flytte den og hjørnet nederst til høyre for å endre størrelse.
 * Begge deler fester seg til rutene, og en widget kan ikke legges oppå en annen.
 */
@Composable
fun WidgetPage(activity: MainActivity, widgets: List<WidgetSlot>, onAdd: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(widgets.isEmpty()) { if (widgets.isEmpty()) editing = false }
    val density = LocalDensity.current

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 8.dp)
            .testTag("widgetpage")
    ) {
        if (widgets.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ingen widgeter", fontSize = 20.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    TextAction("Legg til widget", color = Color.White, size = 20, onClick = onAdd)
                }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val cellW = with(density) { maxWidth.toPx() } / GRID_COLUMNS
                val cellH = with(density) { GRID_CELL_HEIGHT_DP.dp.toPx() }
                // I redigering er det ekstra tomme rader nederst å flytte til.
                val usedRows = widgets.maxOf { it.y + it.h }
                val viewportRows = (with(density) { maxHeight.toPx() } / cellH).toInt()
                val rows = maxOf(usedRows + if (editing) 3 else 0, if (editing) viewportRows else 0)
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { (rows * cellH).toDp() })
                            .then(if (editing) Modifier.drawBehind { drawGridDots(cellW, cellH, rows) } else Modifier)
                    ) {
                        for (slot in widgets) {
                            key(slot.id) {
                                WidgetCell(activity, slot, widgets, editing, cellW, cellH)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextAction("Legg til widget", onClick = onAdd)
                Spacer(Modifier.padding(horizontal = 12.dp))
                TextAction(if (editing) "Ferdig" else "Rediger", color = if (editing) Color.White else Dim) {
                    editing = !editing
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGridDots(cellW: Float, cellH: Float, rows: Int) {
    for (r in 0..rows) {
        for (c in 0..GRID_COLUMNS) {
            drawCircle(Faint, radius = 2.dp.toPx(), center = Offset(c * cellW, r * cellH))
        }
    }
}

@Composable
private fun WidgetCell(
    activity: MainActivity,
    slot: WidgetSlot,
    all: List<WidgetSlot>,
    editing: Boolean,
    cellW: Float,
    cellH: Float,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val info = remember(slot.id) { activity.widgets.info(slot.id) }

    // Plassen vi viser; oppdateres med en gang ved slipp, før DataStore har lagret.
    var local by remember(slot.id) { mutableStateOf(slot) }
    LaunchedEffect(slot) { local = slot }
    var active by remember { mutableStateOf(false) }
    var settle by remember { mutableIntStateOf(0) }

    val ax = remember { Animatable(local.x * cellW) }
    val ay = remember { Animatable(local.y * cellH) }
    val aw = remember { Animatable(local.w * cellW) }
    val ah = remember { Animatable(local.h * cellH) }
    LaunchedEffect(local, cellW, cellH, settle) {
        if (active) return@LaunchedEffect
        launch { ax.animateTo(local.x * cellW, springSpec) }
        launch { ay.animateTo(local.y * cellH, springSpec) }
        launch { aw.animateTo(local.w * cellW, springSpec) }
        launch { ah.animateTo(local.h * cellH, springSpec) }
    }

    fun drop(candidate: WidgetSlot) {
        active = false
        if (candidate != local && all.fits(candidate)) {
            local = candidate
            activity.lifecycleScope.launch { activity.editor.placeWidget(candidate) }
        }
        settle++ // gli tilbake til (ny eller gammel) rute
    }

    // Widgeten får vite sin nye størrelse litt etter at man har sluppet, ikke for hvert bilde.
    var hostView by remember { mutableStateOf<AppWidgetHostView?>(null) }
    var sizeDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(sizeDp, hostView) {
        val v = hostView ?: return@LaunchedEffect
        val s = sizeDp ?: return@LaunchedEffect
        delay(300)
        activity.widgets.updateSize(v, s.first, s.second)
    }

    Box(
        Modifier
            .offset { IntOffset(ax.value.roundToInt(), ay.value.roundToInt()) }
            .size(with(density) { aw.value.toDp() }, with(density) { ah.value.toDp() })
            .zIndex(if (active) 1f else 0f)
            .padding(4.dp)
            .testTag("widget")
    ) {
        if (info == null) {
            Text("Widgeten er ikke tilgjengelig", color = Faint, modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                factory = { ctx ->
                    (activity.widgets.createView(ctx, slot.id) ?: FrameLayout(ctx)).also {
                        if (it is AppWidgetHostView) hostView = it
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        sizeDp = with(density) { size.width.toDp().value to size.height.toDp().value }
                    },
            )
        }
        if (editing) {
            // Dekker widgeten: dra for å flytte.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (active) 0.15f else 0.35f))
                    .border(BorderStroke(1.dp, if (active) Color.White else Faint))
                    .pointerInput(local, cellW, cellH) {
                        detectDragGestures(
                            onDragStart = { active = true },
                            onDragEnd = {
                                val nx = (ax.value / cellW).roundToInt().coerceIn(0, GRID_COLUMNS - local.w)
                                val ny = (ay.value / cellH).roundToInt().coerceAtLeast(0)
                                drop(local.copy(x = nx, y = ny))
                            },
                            onDragCancel = { drop(local) },
                        ) { change, amount ->
                            change.consume()
                            scope.launch {
                                ax.snapTo((ax.value + amount.x).coerceIn(0f, (GRID_COLUMNS - local.w) * cellW))
                                ay.snapTo((ay.value + amount.y).coerceAtLeast(0f))
                            }
                        }
                    }
            )
            Text(
                "Fjern",
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black)
                    .border(BorderStroke(1.dp, Faint))
                    .clickable { activity.removeWidget(slot.id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // Hjørnet nederst til høyre: dra for å endre størrelse.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .pointerInput(local, cellW, cellH) {
                        detectDragGestures(
                            onDragStart = { active = true },
                            onDragEnd = {
                                val nw = (aw.value / cellW).roundToInt().coerceIn(1, GRID_COLUMNS - local.x)
                                val nh = (ah.value / cellH).roundToInt().coerceIn(1, 24)
                                drop(local.copy(w = nw, h = nh))
                            },
                            onDragCancel = { drop(local) },
                        ) { change, amount ->
                            change.consume()
                            scope.launch {
                                aw.snapTo((aw.value + amount.x).coerceIn(cellW * 0.6f, (GRID_COLUMNS - local.x) * cellW))
                                ah.snapTo((ah.value + amount.y).coerceIn(cellH * 0.6f, cellH * 24))
                            }
                        }
                    }
                    .testTag("resize"),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Text("◢", fontSize = 22.sp, color = Color.White, modifier = Modifier.padding(2.dp))
            }
        }
    }
}
