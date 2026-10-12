package no.oystein.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.time.LocalTime

/**
 * Gråtoner for hele telefonen, via Androids fargekorrigering («monokromi»). Android lar bare apper med
 * WRITE_SECURE_SETTINGS endre dette, og den tillatelsen kan bare gis én gang fra en datamaskin:
 * `adb shell pm grant no.oystein.launcher android.permission.WRITE_SECURE_SETTINGS`
 */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0

    const val ADB_COMMAND = "adb shell pm grant no.oystein.launcher android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, ENABLED, 0) == 1 &&
            Settings.Secure.getInt(context.contentResolver, MODE, -1) == MONOCHROMACY
    }.getOrDefault(false)

    /** Om gråtoner skal være på nå, ut fra innstillingene (manuelt eller innenfor kveldstiden). */
    fun wanted(settings: AppSettings, now: LocalTime = LocalTime.now()): Boolean {
        if (settings.grayscale) return true
        val (from, to) = settings.grayscaleAuto ?: return false
        val m = now.hour * 60 + now.minute
        // Tidsrommet kan gå over midnatt (f.eks. 21:00–07:00).
        return if (from <= to) m in from until to else m >= from || m < to
    }

    /** Slår gråtoner av eller på hvis det ikke allerede er slik. Returnerer false uten tillatelse. */
    fun apply(context: Context, on: Boolean): Boolean {
        if (!hasPermission(context)) return false
        if (isOn(context) == on) return true
        return runCatching {
            val cr = context.contentResolver
            if (on) Settings.Secure.putInt(cr, MODE, MONOCHROMACY)
            Settings.Secure.putInt(cr, ENABLED, if (on) 1 else 0)
            Log.d("Launcher", "gråtoner ${if (on) "på" else "av"}")
        }.isSuccess
    }

    /** Bruker innstillingene: gråtoner på/av etter manuell bryter og kveldstid. Gjør ingenting uten tillatelse. */
    fun sync(context: Context, settings: AppSettings) {
        // Uten noen av valgene rører vi ikke fargekorrigeringen (den kan være slått på i systemet).
        // Når man slår av bryteren eller kveldstiden, slås gråtonene av med en gang fra innstillingene.
        if (!settings.grayscale && settings.grayscaleAuto == null) return
        apply(context, wanted(settings))
    }
}
