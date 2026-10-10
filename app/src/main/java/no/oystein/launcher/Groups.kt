package no.oystein.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

data class AppGroup(val name: String, val apps: List<AppEntry>)

/** Alle gruppenavn i alfabetisk rekkefølge. */
fun AppSettings.groupNames(): List<String> = groups.values.distinct().sortedWith(nbCollator)

/**
 * Deler listen i grupper (alfabetisk, øverst) og enkeltapper. Rekkefølgen i [this] beholdes
 * innenfor hver gruppe og for enkeltappene. Grupper uten apper i listen tas ikke med.
 */
fun List<AppEntry>.grouped(settings: AppSettings): Pair<List<AppGroup>, List<AppEntry>> {
    val byGroup = LinkedHashMap<String, MutableList<AppEntry>>()
    val single = mutableListOf<AppEntry>()
    for (app in this) {
        val g = settings.groups[app.key]
        if (g == null) single += app else byGroup.getOrPut(g) { mutableListOf() } += app
    }
    val groups = byGroup.entries
        .sortedWith { a, b -> nbCollator.compare(a.key, b.key) }
        .map { AppGroup(it.key, it.value) }
    return groups to single
}

/** Gruppeoverskrift: navn og antall apper. Trykk for å åpne/lukke, hold inne for gruppemenyen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupHeader(
    group: AppGroup,
    expanded: Boolean,
    fontSize: TextUnit,
    verticalPadding: Int,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onMenu)
            .padding(vertical = verticalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.name,
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (expanded) "−" else "${group.apps.size}",
            fontSize = (fontSize.value * 0.6f).sp,
            color = Faint,
        )
    }
}

/** Velg gruppe for en app: eksisterende gruppe, ny gruppe eller ingen. */
@Composable
fun GroupDialog(activity: MainActivity, app: AppEntry, settings: AppSettings, onClose: () -> Unit) {
    val current = settings.groups[app.key]
    var newName by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    fun set(name: String?) {
        activity.lifecycleScope.launch { activity.editor.setGroup(app.key, name) }
        onClose()
    }
    TextDialog(onClose) {
        Text("Gruppe for ${app.label(settings)}", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(8.dp))
        for (name in settings.groupNames()) {
            MenuItem(if (name == current) "• $name" else name) { set(name) }
        }
        if (current != null) MenuItem("Ingen gruppe") { set(null) }
        Spacer(Modifier.height(12.dp))
        Text("Ny gruppe", fontSize = 14.sp, color = Faint)
        BasicTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) set(newName) }),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).focusRequester(fr),
        )
        HorizontalDivider(color = Faint)
        Row {
            TextAction("Lag gruppe", color = if (newName.isNotBlank()) Color.White else Faint) {
                if (newName.isNotBlank()) set(newName)
            }
            Spacer(Modifier.width(16.dp))
            TextAction("Avbryt", onClick = onClose)
        }
    }
    // Uten grupper fra før er det naturlig å skrive navnet med en gang.
    LaunchedEffect(Unit) { if (settings.groups.isEmpty()) runCatching { fr.requestFocus() } }
}

@Composable
fun GroupMenu(activity: MainActivity, name: String, onRename: () -> Unit, onClose: () -> Unit) {
    TextDialog(onClose) {
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(12.dp))
        MenuItem("Endre navn", onRename)
        MenuItem("Slett gruppe (appene beholdes)") {
            activity.lifecycleScope.launch { activity.editor.deleteGroup(name) }
            onClose()
        }
    }
}

@Composable
fun RenameGroupDialog(activity: MainActivity, name: String, onClose: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    val fr = remember { FocusRequester() }
    fun save() {
        activity.lifecycleScope.launch { activity.editor.renameGroup(name, text) }
        onClose()
    }
    TextDialog(onClose) {
        Text("Endre gruppenavn", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        HorizontalDivider(color = Faint)
        Spacer(Modifier.height(8.dp))
        Row {
            TextAction("Lagre", color = Color.White) { save() }
            Spacer(Modifier.width(16.dp))
            TextAction("Avbryt", onClick = onClose)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
