package no.oystein.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Alle apper sortert etter når de ble installert, nyeste øverst. Forhåndsinstallerte apper kommer til slutt.
 * Trykk på en app åpner appinfo (der den kan avinstalleres).
 */
@Composable
fun InstalledAppsScreen(activity: MainActivity, apps: List<AppEntry>, settings: AppSettings, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val zone = ZoneId.systemDefault()
    val (installed, preinstalled) = remember(apps, settings) {
        val real = apps.filter { !it.isShortcut }.distinctBy { it.packageName to it.user }
        real.filter { it.installedAt > 0 }.sortedByDescending { it.installedAt } to
            real.filter { it.installedAt <= 0 }.sortedBy { it.label(settings).lowercase() }
    }
    val sameYear = DateTimeFormatter.ofPattern("d. MMM", Locale("nb", "NO"))
    val otherYear = DateTimeFormatter.ofPattern("d. MMM yyyy", Locale("nb", "NO"))
    fun date(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            d == today -> "i dag"
            d == today.minusDays(1) -> "i går"
            d.year == today.year -> d.format(sameYear)
            else -> d.format(otherYear)
        }
    }

    FullScreen {
        Title("Etter installering", onClose)
        LazyColumn(Modifier.fillMaxSize().testTag("installed")) {
            items(installed, key = { "i:${it.key}" }) { app ->
                AppDateRow(app.label(settings), date(app.installedAt)) { activity.openAppInfo(app) }
            }
            if (preinstalled.isNotEmpty()) {
                item {
                    Text(
                        "Forhåndsinstallert",
                        fontSize = 14.sp,
                        color = Faint,
                        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
                    )
                }
                items(preinstalled, key = { "p:${it.key}" }) { app ->
                    AppDateRow(app.label(settings), "") { activity.openAppInfo(app) }
                }
            }
        }
    }
}

@Composable
private fun AppDateRow(label: String, date: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(date, fontSize = 15.sp, color = Dim)
    }
}
