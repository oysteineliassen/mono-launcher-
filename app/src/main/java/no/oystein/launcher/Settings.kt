package no.oystein.launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** En widget på widgetsiden: høyde i dp og om den tar halv bredde (to halve legges side om side). */
/** Antall kolonner i widgetrutenettet. */
const val GRID_COLUMNS = 4

/** En widget i rutenettet: øverste venstre rute ([x], [y]) og størrelse i ruter ([w] × [h]). */
data class WidgetSlot(val id: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: WidgetSlot) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

/** Ingen overlapp med de andre widgetene og innenfor kolonnene. */
fun List<WidgetSlot>.fits(slot: WidgetSlot): Boolean =
    slot.x >= 0 && slot.y >= 0 && slot.w >= 1 && slot.h >= 1 && slot.x + slot.w <= GRID_COLUMNS &&
        none { it.id != slot.id && it.overlaps(slot) }

/** Første ledige plass (ovenfra, fra venstre) for en widget på [w] × [h] ruter. */
fun List<WidgetSlot>.firstFree(id: Int, w: Int, h: Int): WidgetSlot {
    val ww = w.coerceIn(1, GRID_COLUMNS)
    var y = 0
    while (true) {
        for (x in 0..GRID_COLUMNS - ww) {
            val s = WidgetSlot(id, x, y, ww, h)
            if (fits(s)) return s
        }
        y++
    }
}

private fun parseWidgets(text: String?): List<WidgetSlot> {
    val out = mutableListOf<WidgetSlot>()
    for (line in text.orEmpty().split('\n')) {
        val p = line.split(',').map { it.toIntOrNull() }
        val id = p.getOrNull(0) ?: continue
        out += if (p.size >= 5) {
            WidgetSlot(id, p[1] ?: 0, p[2] ?: 0, p[3] ?: GRID_COLUMNS, p[4] ?: 2)
        } else {
            // Eldre format (id,høyde i dp,halv): legg dem etter hverandre i rutenettet.
            val h = ((p.getOrNull(1) ?: 300) / 90).coerceIn(1, 12)
            val w = if (p.getOrNull(2) == 1) GRID_COLUMNS / 2 else GRID_COLUMNS
            out.firstFree(id, w, h)
        }
    }
    return out
}

private fun List<WidgetSlot>.encode() = joinToString("\n") { "${it.id},${it.x},${it.y},${it.w},${it.h}" }

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Alle innstillinger, lest fra DataStore. Apper identifiseres med [AppEntry.key], pakker med pakkenavn. */
data class AppSettings(
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val renames: Map<String, String> = emptyMap(),
    /** Gruppe per app ([AppEntry.key] → gruppenavn). En gruppe finnes så lenge den har minst én app. */
    val groups: Map<String, String> = emptyMap(),
    /** Pakker som får pauseskjerm med nedtelling før de åpnes. */
    val pausePackages: Set<String> = emptySet(),
    /** Daglig grense i minutter per pakke. */
    val limits: Map<String, Int> = emptyMap(),
    /** Pakker der varsler dempes og samles i launcheren. */
    val mutedPackages: Set<String> = emptySet(),
    val pauseSeconds: Int = 10,
    /** Widgetene på widgetsiden, i rekkefølge. */
    val widgets: List<WidgetSlot> = emptyList(),
    val use24h: Boolean = true,
    val showScreenTime: Boolean = true,
    val autoKeyboard: Boolean = true,
    /** Snarveiene nederst på hjemskjermen, i rekkefølge fra venstre. */
    val shortcuts: List<HomeShortcut> = defaultShortcuts(),
    /** Widgetsiden (sveip til høyre). Når den er av, ligger widgetene lagret til den slås på igjen. */
    val widgetPage: Boolean = true,
    /** Dobbelttrykk på tom plass på hjemskjermen låser telefonen. */
    val doubleTapLock: Boolean = true,
    /** Neste kalenderhendelse og alarm under datoen. */
    val showNextEvent: Boolean = false,
    /**
     * Klokkeslett (minutter etter midnatt) da dempede varsler vises på hjemskjermen. Tom liste: med en gang.
     * Ellers holdes varsler tilbake til neste klokkeslett i listen, så de kommer samlet noen ganger om dagen.
     */
    val mutedDelivery: List<Int> = emptyList(),
)

/** Siste leveringstidspunkt for dempede varsler før [now] (millisekunder), eller [Long.MAX_VALUE] hvis alt vises med en gang. */
fun AppSettings.mutedCutoff(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Long {
    if (mutedDelivery.isEmpty()) return Long.MAX_VALUE
    val zone = java.time.ZoneId.systemDefault()
    val today = now.toLocalDate()
    val minutes = now.hour * 60 + now.minute
    val last = mutedDelivery.sorted().lastOrNull { it <= minutes }
    val at = if (last != null) {
        today.atTime(last / 60, last % 60)
    } else {
        val m = mutedDelivery.max()
        today.minusDays(1).atTime(m / 60, m % 60)
    }
    return at.atZone(zone).toInstant().toEpochMilli()
}

/** Neste leveringstidspunkt etter nå, f.eks. «18:00», eller null hvis alt vises med en gang. */
fun AppSettings.nextMutedDelivery(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String? {
    if (mutedDelivery.isEmpty()) return null
    val minutes = now.hour * 60 + now.minute
    val next = mutedDelivery.sorted().firstOrNull { it > minutes } ?: mutedDelivery.min()
    return formatMinutes(next)
}

fun formatMinutes(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** «12, 18:30» → [720, 1110]. Ugyldige deler hoppes over. */
fun parseTimes(text: String): List<Int> = text.split(',', ' ', ';', '\n')
    .mapNotNull { part ->
        val t = part.trim().replace('.', ':')
        if (t.isEmpty()) return@mapNotNull null
        val (h, m) = t.split(':').let { (it.getOrNull(0)?.toIntOrNull()) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        if (h == null || h !in 0..23 || m !in 0..59) null else h * 60 + m
    }
    .distinct()
    .sorted()

/** Snarveier som kan vises nederst på hjemskjermen. */
enum class ShortcutKind(val label: String) {
    PHONE("Telefon"),
    SMS("Meldinger"),
    CAMERA("Kamera"),
}

data class HomeShortcut(val kind: ShortcutKind, val visible: Boolean)

fun defaultShortcuts() = listOf(
    HomeShortcut(ShortcutKind.PHONE, true),
    HomeShortcut(ShortcutKind.SMS, true),
    HomeShortcut(ShortcutKind.CAMERA, true),
)

/** «PHONE:1,SMS:1,CAMERA:0». Snarveier som mangler (nye i en senere versjon) legges til bakerst. */
private fun parseShortcuts(text: String?): List<HomeShortcut> {
    if (text.isNullOrBlank()) return defaultShortcuts()
    val list = text.split(',').mapNotNull { part ->
        val (name, on) = part.split(':').let { it.getOrNull(0) to it.getOrNull(1) }
        val kind = ShortcutKind.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        HomeShortcut(kind, on != "0")
    }.distinctBy { it.kind }
    return list + ShortcutKind.entries.filter { k -> list.none { it.kind == k } }.map { HomeShortcut(it, true) }
}

private fun List<HomeShortcut>.encodeShortcuts() = joinToString(",") { "${it.kind.name}:${if (it.visible) 1 else 0}" }

object SettingsKeys {
    val favorites = stringPreferencesKey("favorites")
    val hidden = stringSetPreferencesKey("hidden")
    val pausePackages = stringSetPreferencesKey("pause_packages")
    val mutedPackages = stringSetPreferencesKey("muted_packages")
    val pauseSeconds = intPreferencesKey("pause_seconds")
    /** Gammel innstilling med bare én widget; flyttes over til [widgets] ved lesing. */
    val widgetId = intPreferencesKey("widget_id")
    val widgets = stringPreferencesKey("widgets")
    val use24h = booleanPreferencesKey("use_24h")
    val showScreenTime = booleanPreferencesKey("show_screen_time")
    val autoKeyboard = booleanPreferencesKey("auto_keyboard")
    val shortcuts = stringPreferencesKey("shortcuts")
    val doubleTapLock = booleanPreferencesKey("double_tap_lock")
    val widgetPage = booleanPreferencesKey("widget_page")
    val showNextEvent = booleanPreferencesKey("show_next_event")
    val mutedDelivery = stringPreferencesKey("muted_delivery")
    const val RENAME_PREFIX = "rename:"
    const val LIMIT_PREFIX = "limit:"
    const val GROUP_PREFIX = "group:"
    fun rename(key: String) = stringPreferencesKey(RENAME_PREFIX + key)
    fun limit(pkg: String) = intPreferencesKey(LIMIT_PREFIX + pkg)
    fun group(key: String) = stringPreferencesKey(GROUP_PREFIX + key)
}

fun Preferences.toSettings(): AppSettings {
    val renames = mutableMapOf<String, String>()
    val limits = mutableMapOf<String, Int>()
    val groups = mutableMapOf<String, String>()
    for ((k, v) in asMap()) {
        when {
            k.name.startsWith(SettingsKeys.RENAME_PREFIX) && v is String ->
                renames[k.name.removePrefix(SettingsKeys.RENAME_PREFIX)] = v
            k.name.startsWith(SettingsKeys.LIMIT_PREFIX) && v is Int ->
                limits[k.name.removePrefix(SettingsKeys.LIMIT_PREFIX)] = v
            k.name.startsWith(SettingsKeys.GROUP_PREFIX) && v is String ->
                groups[k.name.removePrefix(SettingsKeys.GROUP_PREFIX)] = v
        }
    }
    return AppSettings(
        favorites = this[SettingsKeys.favorites]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        hidden = this[SettingsKeys.hidden] ?: emptySet(),
        renames = renames,
        groups = groups,
        pausePackages = this[SettingsKeys.pausePackages] ?: emptySet(),
        limits = limits,
        mutedPackages = this[SettingsKeys.mutedPackages] ?: emptySet(),
        pauseSeconds = this[SettingsKeys.pauseSeconds] ?: 10,
        widgets = this[SettingsKeys.widgets]?.let(::parseWidgets)
            ?: this[SettingsKeys.widgetId]?.takeIf { it >= 0 }?.let { listOf(WidgetSlot(it, 0, 0, GRID_COLUMNS, 4)) }
            ?: emptyList(),
        use24h = this[SettingsKeys.use24h] ?: true,
        showScreenTime = this[SettingsKeys.showScreenTime] ?: true,
        autoKeyboard = this[SettingsKeys.autoKeyboard] ?: true,
        shortcuts = parseShortcuts(this[SettingsKeys.shortcuts]),
        doubleTapLock = this[SettingsKeys.doubleTapLock] ?: true,
        widgetPage = this[SettingsKeys.widgetPage] ?: true,
        showNextEvent = this[SettingsKeys.showNextEvent] ?: false,
        mutedDelivery = this[SettingsKeys.mutedDelivery]?.split(',')?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList(),
    )
}

fun Context.settingsFlow(): Flow<AppSettings> = dataStore.data.map { it.toSettings() }

/** Små hjelpefunksjoner for å endre innstillinger. */
class SettingsEditor(private val context: Context) {
    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private fun MutablePreferences.toggle(key: Preferences.Key<Set<String>>, value: String, on: Boolean) {
        val cur = this[key] ?: emptySet()
        this[key] = if (on) cur + value else cur - value
    }

    private fun MutablePreferences.favoriteList() =
        this[SettingsKeys.favorites]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun setFavorite(key: String, on: Boolean) = edit {
        val list = it.favoriteList().filter { k -> k != key }
        it[SettingsKeys.favorites] = (if (on) list + key else list).joinToString("\n")
    }

    /** Lagrer favorittene i ny rekkefølge (fra sortering på hjemskjermen). */
    suspend fun setFavorites(keys: List<String>) = edit { it[SettingsKeys.favorites] = keys.joinToString("\n") }

    suspend fun moveFavorite(key: String, delta: Int) = edit {
        val list = it.favoriteList().toMutableList()
        val i = list.indexOf(key)
        val j = i + delta
        if (i >= 0 && j in list.indices) {
            list[i] = list[j].also { list[j] = list[i] }
            it[SettingsKeys.favorites] = list.joinToString("\n")
        }
    }

    suspend fun setHidden(key: String, on: Boolean) = edit { it.toggle(SettingsKeys.hidden, key, on) }

    suspend fun rename(key: String, name: String?) = edit {
        if (name.isNullOrBlank()) {
            it.remove(SettingsKeys.rename(key))
        } else {
            it[SettingsKeys.rename(key)] = name.trim()
        }
    }

    /** Legger appen i en gruppe, eller tar den ut av gruppen når [name] er tomt. */
    suspend fun setGroup(key: String, name: String?) = edit {
        if (name.isNullOrBlank()) {
            it.remove(SettingsKeys.group(key))
        } else {
            it[SettingsKeys.group(key)] = name.trim()
        }
    }

    private fun MutablePreferences.groupKeys(name: String) =
        asMap().filter { (k, v) -> k.name.startsWith(SettingsKeys.GROUP_PREFIX) && v == name }.keys
            .map { k -> k.name.removePrefix(SettingsKeys.GROUP_PREFIX) }

    suspend fun renameGroup(old: String, new: String) = edit {
        val name = new.trim()
        if (name.isEmpty() || name == old) return@edit
        for (key in it.groupKeys(old)) it[SettingsKeys.group(key)] = name
    }

    /** Sletter gruppen; appene blir liggende som enkeltapper. */
    suspend fun deleteGroup(name: String) = edit {
        for (key in it.groupKeys(name)) it.remove(SettingsKeys.group(key))
    }

    suspend fun setPause(pkg: String, on: Boolean) = edit { it.toggle(SettingsKeys.pausePackages, pkg, on) }

    suspend fun setMuted(pkg: String, on: Boolean) = edit { it.toggle(SettingsKeys.mutedPackages, pkg, on) }

    suspend fun setLimit(pkg: String, minutes: Int?) = edit {
        if (minutes == null || minutes <= 0) {
            it.remove(SettingsKeys.limit(pkg))
        } else {
            it[SettingsKeys.limit(pkg)] = minutes
        }
    }

    suspend fun setPauseSeconds(seconds: Int) = edit { it[SettingsKeys.pauseSeconds] = seconds.coerceIn(3, 120) }

    private fun MutablePreferences.editWidgets(block: (MutableList<WidgetSlot>) -> Unit) {
        val list = toSettings().widgets.toMutableList()
        block(list)
        this[SettingsKeys.widgets] = list.encode()
        remove(SettingsKeys.widgetId)
    }

    /** Legger widgeten på første ledige plass i rutenettet. */
    suspend fun addWidget(id: Int, w: Int, h: Int) = edit { p ->
        p.editWidgets { list ->
            list.removeAll { it.id == id }
            list += list.firstFree(id, w, h)
        }
    }

    suspend fun removeWidget(id: Int) = edit { p -> p.editWidgets { list -> list.removeAll { it.id == id } } }

    /** Flytter eller endrer størrelse; ignoreres hvis den nye plassen overlapper en annen widget. */
    suspend fun placeWidget(slot: WidgetSlot) = edit { p ->
        p.editWidgets { list ->
            val i = list.indexOfFirst { it.id == slot.id }
            if (i >= 0 && list.fits(slot)) list[i] = slot
        }
    }

    suspend fun setUse24h(on: Boolean) = edit { it[SettingsKeys.use24h] = on }

    suspend fun setShowScreenTime(on: Boolean) = edit { it[SettingsKeys.showScreenTime] = on }

    suspend fun setAutoKeyboard(on: Boolean) = edit { it[SettingsKeys.autoKeyboard] = on }

    suspend fun setWidgetPage(on: Boolean) = edit { it[SettingsKeys.widgetPage] = on }

    suspend fun setDoubleTapLock(on: Boolean) = edit { it[SettingsKeys.doubleTapLock] = on }

    suspend fun setShowNextEvent(on: Boolean) = edit { it[SettingsKeys.showNextEvent] = on }

    suspend fun setMutedDelivery(times: List<Int>) = edit {
        if (times.isEmpty()) it.remove(SettingsKeys.mutedDelivery) else it[SettingsKeys.mutedDelivery] = times.joinToString(",")
    }

    private fun MutablePreferences.editShortcuts(block: (MutableList<HomeShortcut>) -> Unit) {
        val list = parseShortcuts(this[SettingsKeys.shortcuts]).toMutableList()
        block(list)
        this[SettingsKeys.shortcuts] = list.encodeShortcuts()
    }

    suspend fun moveShortcut(kind: ShortcutKind, delta: Int) = edit { p ->
        p.editShortcuts { list ->
            val i = list.indexOfFirst { it.kind == kind }
            val j = i + delta
            if (i >= 0 && j in list.indices) list[i] = list[j].also { list[j] = list[i] }
        }
    }

    suspend fun setShortcutVisible(kind: ShortcutKind, on: Boolean) = edit { p ->
        p.editShortcuts { list ->
            val i = list.indexOfFirst { it.kind == kind }
            if (i >= 0) list[i] = list[i].copy(visible = on)
        }
    }
}
