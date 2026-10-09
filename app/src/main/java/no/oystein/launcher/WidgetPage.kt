package no.oystein.launcher

import android.appwidget.AppWidgetHostView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WidgetPage(activity: MainActivity, widgetId: Int, onPick: () -> Unit) {
    val info = remember(widgetId) { activity.widgets.info(widgetId) }
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("widgetpage")
    ) {
        if (info == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ingen widget valgt", fontSize = 20.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    TextAction("Velg widget", color = androidx.compose.ui.graphics.Color.White, size = 20, onClick = onPick)
                }
            }
        } else {
            key(widgetId) {
                // Størrelsen leses i update-blokken, så widgeten får beskjed når plassen endres.
                var sizeDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
                AndroidView(
                    factory = { ctx ->
                        activity.widgets.createView(ctx, widgetId) ?: FrameLayout(ctx)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            sizeDp = with(density) { size.width.toDp().value to size.height.toDp().value }
                        },
                    update = { view ->
                        val s = sizeDp
                        if (view is AppWidgetHostView && s != null) activity.widgets.updateSize(view, s.first, s.second)
                    },
                )
            }
            TextAction("Bytt widget", modifier = Modifier.align(Alignment.CenterHorizontally), onClick = onPick)
        }
    }
}
