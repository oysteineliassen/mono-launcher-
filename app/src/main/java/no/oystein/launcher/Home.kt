package no.oystein.launcher

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
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

@Composable
private fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            // Vent til neste hele minutt (litt over, så vi ikke havner før).
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
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
    mutedCount: Int,
    expanded: Set<String>,
    onToggleGroup: (String) -> Unit,
    onGroupMenu: (String) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onSettings: () -> Unit,
    onNotifications: () -> Unit,
) {
    val now = rememberNow()
    val timeFormat = remember(settings.use24h) {
        DateTimeFormatter.ofPattern(if (settings.use24h) "HH:mm" else "h:mm", norsk)
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d. MMMM", norsk) }
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val favorites = settings.favorites.mapNotNull { byKey[it] }
    // Favoritter som ligger i en gruppe vises under gruppen; gruppene står øverst i alfabetisk rekkefølge.
    val (groups, singles) = remember(favorites, settings) { favorites.grouped(settings) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onSettings() }) }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 120f) expandNotifications(activity) },
                ) { _, dy -> total += dy }
            }
            .testTag("home")
    ) {
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
                modifier = Modifier.combinedClickable(onClick = {
                    activity.startInternal(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }),
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
            if (settings.showScreenTime && usage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Skjermtid i dag: ${Usage.format(usage.values.sum())}",
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

            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (favorites.isEmpty()) {
                    Text(
                        "Sveip til venstre for apper.\nHold inne på en app for å legge den til her.\nHold inne på tom plass for innstillinger.",
                        fontSize = 15.sp,
                        color = Faint,
                    )
                }
                for (group in groups) {
                    val open = group.name in expanded
                    GroupHeader(
                        group = group,
                        expanded = open,
                        fontSize = 28.sp,
                        verticalPadding = 10,
                        onToggle = { onToggleGroup(group.name) },
                        onMenu = { onGroupMenu(group.name) },
                    )
                    if (open) {
                        for (app in group.apps) FavoriteRow(app, settings, indent = true, onOpen, onMenu)
                    }
                }
                for (app in singles) FavoriteRow(app, settings, indent = false, onOpen, onMenu)
            }
            Spacer(Modifier.weight(1f))
            // Snarveiene nederst, fordelt over bredden (rekkefølge og synlighet velges i innstillingene).
            val shortcuts = settings.shortcuts.filter { it.visible }
            if (shortcuts.isNotEmpty()) {
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

/** Trekker ned varselpanelet (sveip ned på hjemskjermen). */
@SuppressLint("WrongConstant")
private fun expandNotifications(activity: MainActivity) {
    runCatching {
        val sbm = activity.getSystemService("statusbar")
        sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    app: AppEntry,
    settings: AppSettings,
    indent: Boolean,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    Text(
        text = app.label(settings),
        fontSize = if (indent) 24.sp else 28.sp,
        fontWeight = FontWeight.Light,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen(app) }, onLongClick = { onMenu(app) })
            .padding(start = if (indent) 28.dp else 0.dp, top = 10.dp, bottom = 10.dp),
    )
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
