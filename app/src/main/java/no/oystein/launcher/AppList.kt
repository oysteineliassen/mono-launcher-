package no.oystein.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListPage(
    settings: AppSettings,
    apps: List<AppEntry>,
    usage: Map<String, Long>,
    query: String,
    onQuery: (String) -> Unit,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
    isCurrent: Boolean,
    overlayOpen: Boolean,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onSettings: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val visible = remember(apps, settings, showHidden) {
        if (showHidden) apps else apps.filter { it.key !in settings.hidden }
    }
    val results = remember(visible, query, settings) { visible.search(query, settings) }
    val hiddenCount = remember(apps, settings) { apps.count { it.key in settings.hidden } }

    // Tastaturet følger siden og eventuelle dialoger; søket nullstilles bare når man forlater siden.
    LaunchedEffect(isCurrent, overlayOpen) {
        if (isCurrent && !overlayOpen && settings.autoKeyboard) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            keyboard?.hide()
            focus.clearFocus()
            onQuery("")
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }

    fun openAndReset(app: AppEntry) {
        keyboard?.hide()
        onOpen(app)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 32.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let { openAndReset(it) } }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("search"),
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) Text("Søk", fontSize = 24.sp, color = Faint, fontWeight = FontWeight.Light)
                    field()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Faint)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("applist")) {
            item { Spacer(Modifier.height(8.dp)) }
            items(results, key = { it.key }) { app ->
                val hidden = app.key in settings.hidden
                val used = usage[app.packageName] ?: 0L
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { openAndReset(app) }, onLongClick = { onMenu(app) })
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        app.label(settings),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Light,
                        color = if (hidden) Faint else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (used >= 60_000L) {
                        Text(Usage.format(used), fontSize = 13.sp, color = Faint)
                    }
                }
            }
            if (results.isEmpty() && query.isNotBlank()) {
                item { Text("Ingen treff", fontSize = 18.sp, color = Faint, modifier = Modifier.padding(vertical = 12.dp)) }
            }
            item {
                Spacer(Modifier.height(24.dp))
                if (hiddenCount > 0) {
                    Text(
                        if (showHidden) "Skjul skjulte apper" else "Vis skjulte apper ($hiddenCount)",
                        fontSize = 16.sp,
                        color = Dim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onShowHidden(!showHidden) })
                            .padding(vertical = 10.dp),
                    )
                }
                Text(
                    "Innstillinger",
                    fontSize = 16.sp,
                    color = Dim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { keyboard?.hide(); onSettings() })
                        .padding(vertical = 10.dp),
                )
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}
