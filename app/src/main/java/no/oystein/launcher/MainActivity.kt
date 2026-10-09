package no.oystein.launcher

import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    /** Settes når vi åpner egne dialoger (widgetvalg, tilganger), så vi ikke hopper til hjemskjermen etterpå. */
    private var keepState = false

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = pendingWidgetId
        val info = pendingWidgetInfo
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
        if (!keepState) _homeEvents.tryEmit(Unit)
        keepState = false
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgets.host.stopListening() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            keepState = false
            _homeEvents.tryEmit(Unit)
        }
    }

    /** Starter en systemskjerm (innstillinger o.l.) og blir stående der vi var når brukeren kommer tilbake. */
    fun startInternal(intent: Intent) {
        keepState = true
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Noen telefoner støtter ikke direktelenke til egen app; prøv den generelle skjermen.
            try {
                startActivity(Intent(intent.action))
            } catch (_: Exception) {
                keepState = false
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
            keepState = true
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
        } else {
            startInternal(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
        }
    }

    // ---- Widget ----

    fun pickWidget(info: AppWidgetProviderInfo) {
        val id = widgets.allocate()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureWidget(id, info)
        } else {
            keepState = true
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
        if (info.configure != null && !optional) {
            keepState = true
            try {
                widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (_: Exception) {
                // Faller tilbake til å bruke widgeten uten oppsett.
            }
        }
        finishWidget(id)
    }

    private fun finishWidget(id: Int) {
        pendingWidgetId = -1
        lifecycleScope.launch {
            val old = settingsFlow().first().widgetId
            if (old != id) widgets.delete(old)
            editor.setWidgetId(id)
            widgets.cleanup(keep = id)
        }
    }

    fun removeWidget() {
        lifecycleScope.launch {
            val old = settingsFlow().first().widgetId
            widgets.delete(old)
            editor.setWidgetId(-1)
        }
    }

    @Deprecated("Brukes for widgetoppsett, som bare finnes med det gamle API-et")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
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
        private const val REQ_CONFIGURE = 11
        private const val REQ_ROLE = 12
    }
}
