package no.oystein.launcher

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    lateinit var apps: AppRepository
        private set
    lateinit var widgets: WidgetController
        private set
    lateinit var editor: SettingsEditor
        private set

    private val _homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Sendes når hjemknappen trykkes eller launcheren vises igjen (f.eks. etter opplåsing). */
    val homeEvents: SharedFlow<Unit> = _homeEvents

    private val _resumeCount = MutableStateFlow(0)

    /** Øker hver gang aktiviteten blir synlig, slik at bruk, tilganger og apper lastes på nytt. */
    val resumeCount: StateFlow<Int> = _resumeCount

    /**
     * Når vi sist åpnet noe selv (widgetoppsett, tillatelser, innstillinger). Stopper aktiviteten like
     * etter, skal vi bli stående der vi var når brukeren kommer tilbake, i stedet for å hoppe hjem.
     * Et tidspunkt i stedet for et flagg: Android kan levere svar fra én dialog og starte neste før
     * onStart, og en dialog som bare pauser aktiviteten skal ikke påvirke en senere, ekte avslutning.
     */
    private var internalLaunchAt = 0L
    private var stoppedForInternal = false

    private fun markInternalLaunch() {
        internalLaunchAt = SystemClock.elapsedRealtime()
    }

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = pendingWidgetId
        val info = pendingWidgetInfo
        Log.d(TAG, "bind result=${res.resultCode} id=$id")
        if (res.resultCode == RESULT_OK && id >= 0 && info != null) {
            configureWidget(id, info)
        } else {
            widgets.delete(id)
            pendingWidgetId = -1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        apps = AppRepository(this)
        widgets = WidgetController(this)
        editor = SettingsEditor(this)
        MutedNotifications.init(this)
        setContent { LauncherApp(this) }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgets.host.startListening() }
        _resumeCount.value++
        if (!stoppedForInternal) _homeEvents.tryEmit(Unit)
        stoppedForInternal = false
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgets.host.stopListening() }
        stoppedForInternal = SystemClock.elapsedRealtime() - internalLaunchAt < 5_000
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            internalLaunchAt = 0L
            stoppedForInternal = false
            _homeEvents.tryEmit(Unit)
        }
    }

    /** Starter en systemskjerm (innstillinger o.l.) og blir stående der vi var når brukeren kommer tilbake. */
    fun startInternal(intent: Intent) {
        markInternalLaunch()
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Noen telefoner støtter ikke direktelenke til egen app; prøv den generelle skjermen.
            try {
                startActivity(Intent(intent.action))
            } catch (_: Exception) {
                internalLaunchAt = 0L
            }
        }
    }

    fun launchApp(app: AppEntry) {
        apps.launch(app)
    }

    fun isDefaultLauncher(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun requestDefaultLauncher() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            markInternalLaunch()
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
        } else {
            startInternal(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
        }
    }

    // ---- Widget ----

    fun pickWidget(info: AppWidgetProviderInfo) {
        val id = widgets.allocate()
        Log.d(TAG, "pickWidget ${info.provider.flattenToShortString()} id=$id")
        pendingWidgetId = id
        pendingWidgetInfo = info
        val allowed = widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        Log.d(TAG, "bindAppWidgetIdIfAllowed=$allowed")
        if (allowed) {
            configureWidget(id, info)
        } else {
            markInternalLaunch()
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            bindWidget.launch(intent)
        }
    }

    private fun configureWidget(id: Int, info: AppWidgetProviderInfo) {
        val optional = Build.VERSION.SDK_INT >= 31 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        Log.d(TAG, "configureWidget id=$id configure=${info.configure} optional=$optional")
        if (info.configure != null && !optional) {
            markInternalLaunch()
            try {
                // Android 14+: launcheren må eksplisitt tillate at widgetens oppsett startes, ellers
                // stopper systemet det med en gang og vi får «avbrutt» tilbake.
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, options.toBundle())
                return
            } catch (_: Exception) {
                // Faller tilbake til å bruke widgeten uten oppsett.
            }
        }
        finishWidget(id)
    }

    private fun finishWidget(id: Int) {
        pendingWidgetId = -1
        val info = widgets.info(id)
        Log.d(TAG, "finishWidget id=$id info=${info?.provider}")
        val cellW = resources.configuration.screenWidthDp / GRID_COLUMNS.toFloat()
        val (w, h) = widgets.defaultCells(info, cellW, GRID_CELL_HEIGHT_DP)
        lifecycleScope.launch {
            editor.addWidget(id, w, h)
            widgets.cleanup(keep = settingsFlow().first().widgets.map { it.id }.toSet())
        }
    }

    fun removeWidget(id: Int) {
        lifecycleScope.launch {
            widgets.delete(id)
            editor.removeWidget(id)
        }
    }

    @Deprecated("Brukes for widgetoppsett, som bare finnes med det gamle API-et")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            Log.d(TAG, "configure result=$resultCode id=$pendingWidgetId")
            val id = pendingWidgetId
            if (id < 0) return
            if (resultCode == RESULT_OK) {
                finishWidget(id)
            } else {
                widgets.delete(id)
                pendingWidgetId = -1
            }
        }
    }

    companion object {
        private const val TAG = "Launcher"
        private const val REQ_CONFIGURE = 11
        private const val REQ_ROLE = 12
    }
}
