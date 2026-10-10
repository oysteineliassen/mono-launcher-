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
import kotlinx.coroutines.launch

class TestReceiver : BroadcastReceiver() {
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
        val pkg = intent.getStringExtra("pkg") ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            SettingsEditor(context.applicationContext).setMuted(pkg, true)
            result.finish()
        }
    }
}
