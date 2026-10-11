package no.oystein.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MutedItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
)

/** Varsler som er dempet og samlet. Lagres i en fil slik at de overlever omstart. */
object MutedNotifications {
    private const val MAX = 300
    private val _items = MutableStateFlow<List<MutedItem>>(emptyList())
    val items: StateFlow<List<MutedItem>> = _items.asStateFlow()

    /** Hva som skjer når man trykker på varselet. Holdes bare i minnet. */
    private val intents = HashMap<String, PendingIntent>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "muted.json")
        file = f
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                _items.value = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MutedItem(
                        key = o.getString("key"),
                        packageName = o.getString("pkg"),
                        appLabel = o.optString("app"),
                        title = o.optString("title"),
                        text = o.optString("text"),
                        time = o.optLong("time"),
                    )
                }
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (it in _items.value) {
            arr.put(
                JSONObject()
                    .put("key", it.key).put("pkg", it.packageName).put("app", it.appLabel)
                    .put("title", it.title).put("text", it.text).put("time", it.time)
            )
        }
        runCatching { f.writeText(arr.toString()) }
    }

    @Synchronized
    fun add(item: MutedItem, intent: PendingIntent?) {
        android.util.Log.d("Launcher", "dempet: ${item.packageName} «${item.title}»")
        _items.update { list -> (listOf(item) + list.filter { it.key != item.key }).take(MAX) }
        if (intent != null) intents[item.key] = intent else intents.remove(item.key)
        save()
    }

    @Synchronized
    fun remove(key: String) {
        _items.update { list -> list.filter { it.key != key } }
        intents.remove(key)
        save()
    }

    @Synchronized
    fun removePackage(pkg: String) {
        val keys = _items.value.filter { it.packageName == pkg }.map { it.key }
        _items.update { list -> list.filter { it.packageName != pkg } }
        keys.forEach { intents.remove(it) }
        save()
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        intents.clear()
        save()
    }

    /** Åpner det varselet pekte på, eller appen hvis det ikke finnes lenger. */
    fun open(context: Context, item: MutedItem) {
        val pi = synchronized(this) { intents[item.key] }
        var opened = false
        if (pi != null) {
            opened = runCatching {
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pi.send(context, 0, null, null, null, null, options.toBundle())
            }.isSuccess
        }
        if (!opened) {
            context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        remove(item.key)
    }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationFilterService::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
}

/**
 * Fjerner varsler fra valgte apper fra varsellinjen og samler dem i launcheren.
 * Merk: Android spiller av lyd/vibrasjon før lytteren får varselet, så for helt stille varsler
 * bør lyd også slås av for appen i systeminnstillingene.
 */
class NotificationFilterService : NotificationListenerService() {
    private var scope: CoroutineScope? = null

    @Volatile
    private var muted: Set<String> = emptySet()

    override fun onListenerConnected() {
        MutedNotifications.init(this)
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            settingsFlow().map { it.mutedPackages }.distinctUntilChanged().collect { set ->
                muted = set
                sweep()
            }
        }
    }

    override fun onListenerDisconnected() {
        scope?.cancel()
        scope = null
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private fun sweep() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in muted) return
        val n = sbn.notification
        // La pågående varsler (musikk, navigasjon, nedlasting, samtaler) være i fred.
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) return

        val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (!isSummary) {
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isNotBlank() || text.isNotBlank()) {
                val label = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                MutedNotifications.add(
                    MutedItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime),
                    n.contentIntent,
                )
            }
        }
        runCatching { cancelNotification(sbn.key) }
    }
}
