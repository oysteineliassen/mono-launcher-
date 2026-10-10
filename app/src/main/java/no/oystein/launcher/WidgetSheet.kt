package no.oystein.launcher

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SheetColor = Color(0xFF101010)
private val CardColor = Color(0xFF1C1C1C)

/**
 * Panel som glir opp nedenfra med søk og forhåndsvisning av alle widgeter, som på iPhone.
 * Trykk på en widget for å legge den til.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSheet(controller: WidgetController, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val providers = remember { controller.providers().map { it to controller.label(it) } }
    var query by remember { mutableStateOf("") }
    // Treff på alle ordene i søket, i hvilken som helst rekkefølge (f.eks. «kal mån» → «Kalender – Måned»).
    val shown = remember(providers, query) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) providers else providers.filter { (_, label) -> words.all { label.lowercase().contains(it) } }
    }
    val cellWidthDp = LocalConfiguration.current.screenWidthDp / GRID_COLUMNS.toFloat()

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = SheetColor,
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Faint) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Velg widget", fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                TextAction("Lukk", onClick = onClose)
            }
            // Søkefelt i en avrundet boks, som «Search Widgets» på iPhone.
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { shown.singleOrNull()?.let { onPick(it.first) } }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("widgetsearch"),
                decorationBox = { field ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardColor)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (query.isEmpty()) Text("Søk etter widget eller app", fontSize = 17.sp, color = Faint)
                        field()
                    }
                },
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.first.provider.flattenToString() + it.first.profile.hashCode() }) { (info, label) ->
                    WidgetCard(controller, info, label, cellWidthDp) { onPick(info) }
                }
                if (providers.isEmpty()) {
                    item(span = { GridItemSpan(2) }) { Text("Fant ingen widgeter.", color = Faint) }
                } else if (shown.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Text("Ingen treff", fontSize = 17.sp, color = Faint, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun WidgetCard(
    controller: WidgetController,
    info: AppWidgetProviderInfo,
    label: String,
    cellWidthDp: Float,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Forhåndsbildet lastes i bakgrunnen; widgeter uten bilde viser appens ikon i stedet.
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(1f, 480f / maxOf(w, h))
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    val (w, h) = remember(info) { controller.defaultCells(info, cellWidthDp, GRID_CELL_HEIGHT_DP) }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: Box(Modifier.size(40.dp))
        }
        Text(
            label,
            fontSize = 14.sp,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("$w × $h", fontSize = 12.sp, color = Faint)
    }
}
