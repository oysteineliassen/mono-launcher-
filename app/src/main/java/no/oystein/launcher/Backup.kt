package no.oystein.launcher

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eksport og import av hele oppsettet (favoritter, grupper, navn, grenser, dempede apper, innstillinger)
 * som en JSON-fil. Widgetene tas ikke med: de er knyttet til denne installasjonen og må legges til på nytt.
 */
object Backup {
    private const val FORMAT = "no.oystein.launcher/oppsett"
    private val skipped = setOf(SettingsKeys.widgets.name, SettingsKeys.widgetId.name)

    suspend fun export(context: Context, uri: Uri) {
        val prefs = context.dataStore.data.first()
        val values = JSONObject()
        for ((key, value) in prefs.asMap()) {
            if (key.name in skipped) continue
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            values.put(key.name, entry)
        }
        val json = JSONObject().put("format", FORMAT).put("version", 1).put("values", values).toString(2)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                ?: error("kunne ikke skrive filen")
        }
    }

    /** Erstatter oppsettet med innholdet i filen. Returnerer antall innstillinger som ble lest inn. */
    suspend fun import(context: Context, uri: Uri): Int {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("kunne ikke lese filen")
        }
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "filen er ikke et launcher-oppsett" }
        val values = root.getJSONObject("values")
        var count = 0
        context.dataStore.edit { p ->
            // Behold widgetene (de hører til denne telefonen), fjern resten og legg inn det importerte.
            val keep = p.asMap().filterKeys { it.name in skipped }
            p.clear()
            for ((k, v) in keep) p.putAny(k, v)
            for (name in values.keys()) {
                if (name in skipped) continue
                val e = values.getJSONObject(name)
                when (e.optString("t")) {
                    "s" -> p[stringPreferencesKey(name)] = e.getString("v")
                    "i" -> p[intPreferencesKey(name)] = e.getInt("v")
                    "l" -> p[longPreferencesKey(name)] = e.getLong("v")
                    "b" -> p[booleanPreferencesKey(name)] = e.getBoolean("v")
                    "f" -> p[floatPreferencesKey(name)] = e.getDouble("v").toFloat()
                    "ss" -> {
                        val arr = e.getJSONArray("v")
                        p[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    else -> continue
                }
                count++
            }
        }
        return count
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putAny(key: Preferences.Key<*>, value: Any) {
        set(key as Preferences.Key<Any>, value)
    }
}
