package no.oystein.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tilgjengelighetstjeneste for å låse skjermen (dobbelttrykk på hjemskjermen) og vise pauseskjermen når
 * en app åpnes utenfor launcheren. Android lar ikke vanlige apper slå av skjermen eller se hvilken app som
 * åpnes; dette er samme løsning som Minimalist Phone bruker. Tjenesten ser bare hvilken app som er fremme,
 * ikke innholdet på skjermen.
 */
class LockService : AccessibilityService() {
    private var scope: CoroutineScope? = null

    override fun onServiceConnected() {
        instance = this
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { settingsFlow().collect { PauseGuard.settings = it } }
        }
        // Kjøpt tid teller ikke mens skjermen er av.
        runCatching {
            registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> PauseGuard.stopTracking()
                Intent.ACTION_USER_PRESENT -> PauseGuard.onUnlocked(this@LockService)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        scope?.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val s = scope ?: return
        event?.let { PauseGuard.onEvent(this, it, s) }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        private var instance: LockService? = null

        /** Låser skjermen. Returnerer false hvis tjenesten ikke er slått på. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, LockService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
