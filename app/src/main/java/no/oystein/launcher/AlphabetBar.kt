package no.oystein.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Bokstavliste langs høyre kant. Dra fingeren opp og ned for å hoppe til apper med den forbokstaven.
 * Bokstaven under fingeren forstørres og skyves inn mot listen, og naboene følger med litt (fiskeøye).
 */
@Composable
fun AlphabetBar(letters: List<Char>, onLetter: (Char) -> Unit, modifier: Modifier = Modifier) {
    var active by remember { mutableIntStateOf(-1) }
    var heightPx by remember { mutableIntStateOf(1) }
    val haptic = LocalHapticFeedback.current
    // Plasseringene endres når grupper åpnes, uten at bokstavene endres; bruk alltid nyeste versjon.
    val currentOnLetter by rememberUpdatedState(onLetter)

    Column(
        modifier
            .width(48.dp)
            .onSizeChanged { heightPx = maxOf(1, it.height) }
            .pointerInput(letters) {
                fun select(y: Float) {
                    val i = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    if (i != active) {
                        active = i
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnLetter(letters[i])
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    select(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        select(change.position.y)
                    }
                    active = -1
                }
            }
            .testTag("alphabet"),
        horizontalAlignment = Alignment.End,
    ) {
        letters.forEachIndexed { i, c ->
            val distance = if (active < 0) Int.MAX_VALUE else abs(i - active)
            val target = when (distance) {
                0 -> 2.6f
                1 -> 1.7f
                2 -> 1.25f
                else -> 1f
            }
            val scale by animateFloatAsState(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                label = "bokstavstørrelse",
            )
            // Forstørrede bokstaver skyves mot venstre så de ikke havner utenfor skjermen.
            val shift by animateFloatAsState(
                targetValue = (target - 1f) * -22f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                label = "bokstavforskyvning",
            )
            val emphasis = ((scale - 1f) / 1.6f).coerceIn(0f, 1f)
            // Hver bokstav får like stor del av hele høyden, nøyaktig som berøringen regnes ut.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    c.toString(),
                    fontSize = 13.sp,
                    fontWeight = if (distance == 0) FontWeight.Normal else FontWeight.Light,
                    color = lerp(Dim, Color.White, emphasis),
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = shift.dp.toPx()
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
                )
            }
        }
    }
}
