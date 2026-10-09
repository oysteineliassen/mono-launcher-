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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Alle innstillinger, lest fra DataStore. Apper identifiseres med [AppEntry.key], pakker med pakkenavn. */
data class AppSettings(
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val renames: Map<String, String> = emptyMap(),
    /** Pakker som får pauseskjerm med nedtelling før de åpnes. */
    val pausePackages: Set<String> = emptySet(),
    /** Daglig grense i minutter per pakke. */
    val limits: Map<String, Int> = emptyMap(),
    /** Pakker der varsler dempes og samles i launcheren. */
    val mutedPackages: Set<String> = emptySet(),
    val pauseSeconds: Int = 10,
    val widgetId: Int = -1,
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
    val widgetId = intPreferencesKey("widget_id")
    val use24h = booleanPreferencesKey("use_24h")
    val showScreenTime = booleanPreferencesKey("show_screen_time")
    val autoKeyboard = booleanPreferencesKey("auto_keyboard")
    const val RENAME_PREFIX = "rename:"
    const val LIMIT_PREFIX = "limit:"
    fun rename(key: String) = stringPreferencesKey(RENAME_PREFIX + key)
    fun limit(pkg: String) = intPreferencesKey(LIMIT_PREFIX + pkg)
}

fun Preferences.toSettings(): AppSettings {
    val renames = mutableMapOf<String, String>()
    val limits = mutableMapOf<String, Int>()
    for ((k, v) in asMap()) {
        when {
            k.name.startsWith(SettingsKeys.RENAME_PREFIX) && v is String ->
                renames[k.name.removePrefix(SettingsKeys.RENAME_PREFIX)] = v
            k.name.startsWith(SettingsKeys.LIMIT_PREFIX) && v is Int ->
                limits[k.name.removePrefix(SettingsKeys.LIMIT_PREFIX)] = v
        }
    }
    return AppSettings(
        favorites = this[SettingsKeys.favorites]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        hidden = this[SettingsKeys.hidden] ?: emptySet(),
        renames = renames,
        pausePackages = this[SettingsKeys.pausePackages] ?: emptySet(),
        limits = limits,
        mutedPackages = this[SettingsKeys.mutedPackages] ?: emptySet(),
        pauseSeconds = this[SettingsKeys.pauseSeconds] ?: 10,
        widgetId = this[SettingsKeys.widgetId] ?: -1,
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

    suspend fun setWidgetId(id: Int) = edit { it[SettingsKeys.widgetId] = id }

    suspend fun setUse24h(on: Boolean) = edit { it[SettingsKeys.use24h] = on }

    suspend fun setShowScreenTime(on: Boolean) = edit { it[SettingsKeys.showScreenTime] = on }

    suspend fun setAutoKeyboard(on: Boolean) = edit { it[SettingsKeys.autoKeyboard] = on }
}
