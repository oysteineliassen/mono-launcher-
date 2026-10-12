package no.oystein.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ser etter nye versjoner under Releases på GitHub og installerer dem i appen (Android spør om lov først).
 * Sjekkes ved oppstart og når hjemskjermen vises, høyst hver tredje time.
 */
object Updater {
    private const val TAG = "Launcher"
    private const val LATEST = "https://api.github.com/repos/oysteineliassen/test/releases/latest"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
        /** Svar på «Se etter ny» i innstillingene. Vises bare der, aldri på hjemskjermen. */
        data class Info(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastCheck = 0L

    fun currentVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** Sjekker GitHub hvis det er lenge siden sist (eller alltid med [force]). */
    suspend fun check(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        lastCheck = now
        val current = currentVersionCode(context)
        val release = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            .onFailure { Log.d(TAG, "oppdatering: kunne ikke sjekke (${it.message})") }
            .getOrNull()
        if (release == null) {
            Log.d(TAG, "oppdatering: fant ingen utgivelse")
            _state.value = if (force) State.Info("Fant ingen oppdatering.") else State.Idle
            return
        }
        Log.d(TAG, "oppdatering: siste ${release.versionName} (${release.versionCode}), denne $current")
        _state.value = when {
            release.versionCode > current -> State.Available(release)
            force -> State.Info("Du har nyeste versjon.")
            else -> State.Idle
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.d(TAG, "oppdatering: GitHub svarte ${conn.responseCode}")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name") // launcher-v1.0.N
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    return Release(code, tag.removePrefix("launcher-v"), a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Laster ned og installerer. Ber først om lov til å installere apper hvis det mangler. */
    suspend fun install(activity: MainActivity, release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Tillat at Mono Launcher installerer apper, og trykk på oppdateringen igjen", Toast.LENGTH_LONG).show()
            activity.startInternal(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        _state.value = State.Downloading(release, 0)
        val file = File(activity.cacheDir, "oppdatering.apk")
        val ok = runCatching { withContext(Dispatchers.IO) { download(release, file) } }
            .onFailure { Log.d(TAG, "oppdatering: nedlasting feilet (${it.message})") }
            .isSuccess
        if (!ok) {
            _state.value = State.Failed("Nedlastingen feilet. Prøv igjen.")
            return
        }
        _state.value = State.Installing(release)
        val result = runCatching { withContext(Dispatchers.IO) { commit(activity, file) } }
        if (result.isFailure) {
            Log.d(TAG, "oppdatering: installasjon feilet (${result.exceptionOrNull()?.message})")
            _state.value = State.Failed("Installasjonen feilet. Prøv igjen.")
        }
    }

    private fun download(release: Release, file: File) {
        var url = URL(release.apkUrl)
        // GitHub sender videre til en annen vert; følg manuelt så det virker uansett.
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        url = URL(url, conn.getHeaderField("Location"))
                        return@repeat
                    }
                    200 -> {
                        val total = conn.contentLengthLong
                        var done = 0L
                        conn.inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) _state.value = State.Downloading(release, (done * 100 / total).toInt())
                                }
                            }
                        }
                        return
                    }
                    else -> error("HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("for mange videresendinger")
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("launcher.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.d(TAG, "oppdatering: status $status $msg")
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    State.Idle.also { lastCheck = 0L }
                } else {
                    State.Failed("Installasjonen feilet${msg?.let { ": $it" } ?: ""}")
                }
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onResult(context, intent)
}
