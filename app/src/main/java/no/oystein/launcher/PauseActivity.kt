package no.oystein.launcher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pauseskjermen oppå en app som ble åpnet utenfor launcheren (se [PauseGuard]). */
class PauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val launch = packageManager.getLaunchIntentForPackage(pkg)?.component ?: ComponentName(pkg, "")
        val app = AppEntry(pkg, pkg, launch, Process.myUserHandle(), label, false)
        setContent {
            MaterialTheme(colorScheme = colors) {
                val settings by remember { settingsFlow() }.collectAsState(initial = null)
                val used by produceState(0L, pkg) { value = withContext(Dispatchers.IO) { Usage.today(this@PauseActivity)[pkg] ?: 0L } }
                BackHandler { cancel() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    settings?.let { s ->
                        val buy = if (pkg in s.timePackages) remember { TimeBudget.state(this@PauseActivity, pkg) } else null
                        PauseScreen(
                            app = app,
                            settings = s,
                            usedMs = used,
                            onOpen = {
                                PauseGuard.grant(this@PauseActivity, pkg)
                                finish()
                            },
                            onCancel = ::cancel,
                            buyTime = buy,
                            onBuy = { m ->
                                TimeBudget.buy(this@PauseActivity, pkg, m)
                                PauseGuard.grant(this@PauseActivity, pkg)
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }

    /** «Avbryt»: tilbake til hjemskjermen. */
    private fun cancel() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
