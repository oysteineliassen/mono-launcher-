package no.oystein.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TestReceiver : BroadcastReceiver() {
    private suspend fun handleAsync(context: Context, intent: Intent) {
        val editor = SettingsEditor(context)
        when (intent.action) {
            // «later»: neste levering om to timer, så nye dempede varsler holdes tilbake. «now»: med en gang.
            "no.oystein.launcher.TEST_DELIVERY" -> {
                val later = intent.getStringExtra("mode") == "later"
                val now = java.time.LocalTime.now()
                editor.setMutedDelivery(if (later) listOf(((now.hour + 2) % 24) * 60) else emptyList())
                Log.d("Launcher", "test: levering ${if (later) "senere" else "med en gang"}")
            }
            // Eksporter til fil, endre en innstilling, importer igjen og sjekk at den er tilbake.
            "no.oystein.launcher.TEST_BACKUP" -> {
                val file = java.io.File(context.cacheDir, "test-oppsett.json")
                val uri = Uri.fromFile(file)
                val before = context.settingsFlow().first()
                Backup.export(context, uri)
                editor.setUse24h(!before.use24h)
                editor.setFavorites(emptyList())
                val count = Backup.import(context, uri)
                val after = context.settingsFlow().first()
                val ok = after.use24h == before.use24h && after.favorites == before.favorites &&
                    after.groups == before.groups && after.widgets == before.widgets
                Log.d("Launcher", "test: sikkerhetskopi ${if (ok) "ok" else "FEIL"} ($count innstillinger, ${file.length()} byte)")
            }
            "no.oystein.launcher.TEST_NEXT" -> {
                editor.setShowNextEvent(true)
                Log.d("Launcher", "test: neste hendelse på")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "no.oystein.launcher.TEST_PIN") {
            // Samme vei som Chrome bruker for «Legg til på startskjermen».
            val sm = context.getSystemService(ShortcutManager::class.java)
            val shortcut = ShortcutInfo.Builder(context, "testsnarvei")
                .setShortLabel("Testsnarvei")
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
                .build()
            Log.d("Launcher", "requestPinShortcut supported=${sm.isRequestPinShortcutSupported} ok=${sm.requestPinShortcut(shortcut, null)}")
            return
        }
        when (intent.action) {
            "no.oystein.launcher.TEST_LOCK" -> {
                Log.d("Launcher", "test: lås ${LockService.lock()}")
                return
            }
            "no.oystein.launcher.TEST_DELIVERY", "no.oystein.launcher.TEST_BACKUP", "no.oystein.launcher.TEST_NEXT" -> {
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { handleAsync(context.applicationContext, intent) }
                        .onFailure { Log.d("Launcher", "test: feil ${it.message}") }
                    result.finish()
                }
                return
            }
        }
        val pkg = intent.getStringExtra("pkg") ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            SettingsEditor(context.applicationContext).setMuted(pkg, true)
            result.finish()
        }
    }
}
