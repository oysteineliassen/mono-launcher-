package no.oystein.launcher

import android.appwidget.AppWidgetHostView
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Widgetsiden: så mange widgeter man vil, i en liste som kan rulles. Hver widget har egen høyde og
 * hel eller halv bredde; to halve etter hverandre legges side om side. «Rediger» viser knapper for å
 * flytte, endre bredde og fjerne, og et håndtak nederst for å dra høyden.
 */
@Composable
fun WidgetPage(activity: MainActivity, widgets: List<WidgetSlot>, onAdd: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(widgets.isEmpty()) { if (widgets.isEmpty()) editing = false }

    // Halve widgeter etter hverandre settes sammen to og to.
    val rows = remember(widgets) {
        val out = mutableListOf<List<WidgetSlot>>()
        var i = 0
        while (i < widgets.size) {
            val w = widgets[i]
            val next = widgets.getOrNull(i + 1)
            if (w.half && next != null && next.half) {
                out += listOf(w, next); i += 2
            } else {
                out += listOf(w); i += 1
            }
        }
        out
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp)
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
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (row in rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (slot in row) {
                            key(slot.id) {
                                WidgetCell(
                                    activity = activity,
                                    slot = slot,
                                    editing = editing,
                                    isFirst = slot.id == widgets.first().id,
                                    isLast = slot.id == widgets.last().id,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // En halv widget uten partner beholder halv bredde.
                        if (row.size == 1 && row[0].half) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
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

@Composable
private fun WidgetCell(
    activity: MainActivity,
    slot: WidgetSlot,
    editing: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val scope = activity.lifecycleScope
    val e = activity.editor
    // Høyden følger fingeren mens man drar, og lagres når man slipper.
    var heightDp by remember(slot.id) { mutableFloatStateOf(slot.heightDp.toFloat()) }
    LaunchedEffect(slot.heightDp) { heightDp = slot.heightDp.toFloat() }
    var sizeDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val info = remember(slot.id) { activity.widgets.info(slot.id) }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .then(if (editing) Modifier.border(BorderStroke(1.dp, Faint)) else Modifier)
        ) {
            if (info == null) {
                Text("Widgeten er ikke tilgjengelig", color = Faint, modifier = Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    factory = { ctx -> activity.widgets.createView(ctx, slot.id) ?: FrameLayout(ctx) },
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            sizeDp = with(density) { size.width.toDp().value to size.height.toDp().value }
                        },
                    update = { view ->
                        val s = sizeDp
                        if (view is AppWidgetHostView && s != null) activity.widgets.updateSize(view, s.first, s.second)
                    },
                )
            }
            if (editing) {
                // Sperrer trykk på selve widgeten mens man redigerer, og viser knappene.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                )
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    if (!isFirst) EditAction("↑") { scope.launch { e.moveWidget(slot.id, -1) } }
                    if (!isLast) EditAction("↓") { scope.launch { e.moveWidget(slot.id, 1) } }
                    EditAction(if (slot.half) "Hel" else "Halv") { scope.launch { e.updateWidget(slot.id, half = !slot.half) } }
                    EditAction("Fjern") { activity.removeWidget(slot.id) }
                }
            }
        }
        if (editing) {
            Text(
                "═══  dra for høyde  ═══",
                fontSize = 12.sp,
                color = Dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(slot.id) {
                        detectVerticalDragGestures(
                            onDragEnd = { scope.launch { e.updateWidget(slot.id, heightDp = heightDp.toInt()) } },
                            onDragCancel = { scope.launch { e.updateWidget(slot.id, heightDp = heightDp.toInt()) } },
                        ) { change, dy ->
                            change.consume()
                            heightDp = (heightDp + dy / density.density).coerceIn(60f, 1200f)
                        }
                    }
                    .padding(vertical = 10.dp)
                    .testTag("resize"),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EditAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 14.sp,
        color = Color.White,
        modifier = Modifier
            .padding(start = 4.dp)
            .background(Color.Black)
            .border(BorderStroke(1.dp, Faint))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
