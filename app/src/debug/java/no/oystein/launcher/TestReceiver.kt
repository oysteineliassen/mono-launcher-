package no.oystein.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            SettingsEditor(context.applicationContext).setMuted(pkg, true)
            result.finish()
        }
    }
}
