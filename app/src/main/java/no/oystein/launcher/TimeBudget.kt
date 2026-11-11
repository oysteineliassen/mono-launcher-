package no.oystein.launcher

import android.content.Context
import java.time.LocalDate

/**
 * «Kjøp tid» per app: man velger hvor lenge man vil bruke appen, og først når den tiden er brukt opp
 * kommer pauseskjermen. Etter nedtellingen kan man kjøpe mer tid. Tiden telles bare mens appen er
 * fremme (av [PauseGuard]), og nullstilles hver dag. Lagres i en egen fil så den overlever omstart.
 */
object TimeBudget {
    enum class State { FIRST, ACTIVE, EXPIRED }

    /** Hvor mye tid man kan kjøpe av gangen, i minutter. */
    val CHOICES = listOf(5, 10, 15, 30)

    private fun prefs(context: Context) = context.getSharedPreferences("kjop_tid", Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toEpochDay()

    /** Gjenstående tid i dag (ms), eller null hvis det ikke er kjøpt tid i dag. */
    fun left(context: Context, pkg: String): Long? {
        val p = prefs(context)
        if (p.getLong("$pkg.day", -1) != today()) return null
        return p.getLong("$pkg.left", 0L)
    }

    fun state(context: Context, pkg: String): State {
        val left = left(context, pkg) ?: return State.FIRST
        return if (left > 0) State.ACTIVE else State.EXPIRED
    }

    fun buy(context: Context, pkg: String, minutes: Int) {
        val left = (left(context, pkg) ?: 0L).coerceAtLeast(0L)
        prefs(context).edit()
            .putLong("$pkg.day", today())
            .putLong("$pkg.left", left + minutes * 60_000L)
            .apply()
    }

    /** Trekker brukt tid fra det som er kjøpt. Returnerer det som er igjen. */
    fun consume(context: Context, pkg: String, ms: Long): Long {
        val left = left(context, pkg) ?: return 0L
        val next = (left - ms).coerceAtLeast(0L)
        prefs(context).edit().putLong("$pkg.day", today()).putLong("$pkg.left", next).apply()
        return next
    }

    /** Brukes av testen: bruk opp tiden med en gang. */
    fun expire(context: Context, pkg: String) {
        prefs(context).edit().putLong("$pkg.day", today()).putLong("$pkg.left", 0L).apply()
    }
}
