package no.oystein.launcher

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Neste kalenderhendelse og neste alarm, som korte tekstlinjer til hjemskjermen. */
object NextEvent {
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun hasCalendarPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** F.eks. «Tannlege 09:30», «Nå: Møte (til 10:00)», «I morgen: Fly 07:15». Null hvis ingenting de neste 24 timene. */
    fun nextEvent(context: Context, now: Long = System.currentTimeMillis()): String? {
        if (!hasCalendarPermission(context)) return null
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val cursor = runCatching {
            CalendarContract.Instances.query(context.contentResolver, projection, now, now + 24 * 3_600_000L)
        }.getOrNull() ?: return null
        var best: Triple<String, Long, Long>? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val visible = c.getInt(4) == 1
                val declined = c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                val begin = c.getLong(1)
                val end = c.getLong(2)
                if (allDay || !visible || declined || end <= now) continue
                if (best == null || begin < best!!.second) best = Triple(c.getString(0).orEmpty().ifBlank { "Hendelse" }, begin, end)
            }
        }
        val (title, begin, end) = best ?: return null
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(begin).atZone(zone)
        return when {
            begin <= now -> "Nå: $title (til ${Instant.ofEpochMilli(end).atZone(zone).format(time)})"
            start.toLocalDate() == LocalDate.now(zone) -> "$title ${start.format(time)}"
            else -> "I morgen: $title ${start.format(time)}"
        }
    }

    /** F.eks. «Alarm 06:30» eller «Alarm i morgen 06:30» for alarmer de neste 24 timene. */
    fun nextAlarm(context: Context, now: Long = System.currentTimeMillis()): String? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        if (info.triggerTime - now > 24 * 3_600_000L) return null
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(info.triggerTime).atZone(zone)
        return if (t.toLocalDate() == LocalDate.now(zone)) "Alarm ${t.format(time)}" else "Alarm i morgen ${t.format(time)}"
    }
}
