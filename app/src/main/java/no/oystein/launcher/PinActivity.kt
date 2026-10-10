package no.oystein.launcher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Systemet sender hit når en app vil feste noe på startskjermen: en snarvei (nettapp/PWA fra Chrome,
 * en kontakt …) eller en widget. Vi godtar uten egen dialog; snarveien havner i applisten og på
 * hjemskjermen, widgeten på widgetsiden.
 */
class PinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = AppRepository(this).pinRequest(intent)
        if (request != null && request.isValid) {
            when (request.requestType) {
                LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> acceptShortcut(request)
                LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> acceptWidget(request)
            }
        }
        finish()
    }

    private fun acceptShortcut(request: LauncherApps.PinItemRequest) {
        val sc = request.shortcutInfo ?: return
        if (!request.accept()) return
        val label = (sc.shortLabel ?: sc.longLabel ?: sc.id).toString()
        Log.d(TAG, "pinned shortcut ${sc.`package`}/${sc.id}")
        // Samme nøkkel som i applisten, så den også legges på hjemskjermen.
        val key = "shortcut:${sc.`package`}/${sc.id}"
        val app = applicationContext
        scope.launch { SettingsEditor(app).setFavorite(key, true) }
        Toast.makeText(this, "«$label» er lagt til", Toast.LENGTH_SHORT).show()
    }

    private fun acceptWidget(request: LauncherApps.PinItemRequest) {
        val widgets = WidgetController(this)
        val id = widgets.allocate()
        val options = Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }
        if (!request.accept(options)) {
            widgets.delete(id)
            return
        }
        val info = request.getAppWidgetProviderInfo(this)
        val cellW = resources.configuration.screenWidthDp / GRID_COLUMNS.toFloat()
        val (w, h) = widgets.defaultCells(info, cellW, GRID_CELL_HEIGHT_DP)
        val app = applicationContext
        scope.launch { SettingsEditor(app).addWidget(id, w, h) }
        Toast.makeText(this, "Widgeten er lagt til på widgetsiden", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "Launcher"

        /** Lagringen skal fullføres selv om aktiviteten avsluttes med en gang. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
