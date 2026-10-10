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
data class WidgetSlot(val id: Int, val heightDp: Int, val half: Boolean)

private fun parseWidgets(text: String?): List<WidgetSlot> =
    text.orEmpty().split('\n').mapNotNull { line ->
        val p = line.split(',')
        val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        WidgetSlot(id, p.getOrNull(1)?.toIntOrNull() ?: 300, p.getOrNull(2) == "1")
    }

private fun List<WidgetSlot>.encode() = joinToString("\n") { "${it.id},${it.heightDp},${if (it.half) 1 else 0}" }

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
)

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
            ?: this[SettingsKeys.widgetId]?.takeIf { it >= 0 }?.let { listOf(WidgetSlot(it, 360, false)) }
            ?: emptyList(),
        use24h = this[SettingsKeys.use24h] ?: true,
        showScreenTime = this[SettingsKeys.showScreenTime] ?: true,
        autoKeyboard = this[SettingsKeys.autoKeyboard] ?: true,
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

    suspend fun addWidget(slot: WidgetSlot) = edit { p -> p.editWidgets { list -> list.removeAll { it.id == slot.id }; list += slot } }

    suspend fun removeWidget(id: Int) = edit { p -> p.editWidgets { list -> list.removeAll { it.id == id } } }

    suspend fun updateWidget(id: Int, heightDp: Int? = null, half: Boolean? = null) = edit { p ->
        p.editWidgets { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                val w = list[i]
                list[i] = w.copy(heightDp = (heightDp ?: w.heightDp).coerceIn(60, 1200), half = half ?: w.half)
            }
        }
    }

    suspend fun moveWidget(id: Int, delta: Int) = edit { p ->
        p.editWidgets { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i >= 0 && j in list.indices) list[i] = list[j].also { list[j] = list[i] }
        }
    }

    suspend fun setUse24h(on: Boolean) = edit { it[SettingsKeys.use24h] = on }

    suspend fun setShowScreenTime(on: Boolean) = edit { it[SettingsKeys.showScreenTime] = on }

    suspend fun setAutoKeyboard(on: Boolean) = edit { it[SettingsKeys.autoKeyboard] = on }
}
