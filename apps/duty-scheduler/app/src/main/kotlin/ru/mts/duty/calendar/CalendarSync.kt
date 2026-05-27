package ru.mts.duty.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import ru.mts.duty.R
import ru.mts.duty.model.CalendarRef
import ru.mts.duty.model.DutyEntry
import ru.mts.duty.model.DutyRole
import ru.mts.duty.model.Schedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Wraps the Android CalendarProvider for the duty-scheduler use case.
 *
 * Responsibilities:
 *  - List writable calendars on the device.
 *  - Clear existing «Дежурство…» events for a given month + calendar (clean-import behaviour).
 *  - Insert one event per duty day for the selected employee, with role-specific titles,
 *    24h time-block (00:00–23:59) in the device's default time zone, no reminders.
 */
class CalendarSync(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Returns calendars the current app can write to. */
    fun listWritableCalendars(): List<CalendarRef> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.SYNC_EVENTS,
        )
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val out = mutableListOf<CalendarRef>()
        resolver.query(Calendars.CONTENT_URI, projection, selection, args, "${Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += CalendarRef(
                    id = c.getLong(0),
                    displayName = c.getString(1) ?: "(без названия)",
                    accountName = c.getString(2) ?: "",
                    accountType = c.getString(3) ?: "",
                    ownerAccount = c.getString(4),
                )
            }
        }
        return out
    }

    /** Title prefix used to recognize events we own. */
    private val titlePrefix: String get() = "Дежурство"

    /**
     * Delete existing events whose title starts with [titlePrefix] in [calendarId]
     * whose start time falls in [year]/[month].
     * Returns the number of deleted events.
     */
    fun clearMonth(calendarId: Long, year: Int, month: Int): Int {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, month, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} >= ? AND ${Events.DTSTART} < ? AND ${Events.TITLE} LIKE ?"
        val args = arrayOf(
            calendarId.toString(),
            start.toString(),
            end.toString(),
            "$titlePrefix%",
        )
        return resolver.delete(Events.CONTENT_URI, selection, args)
    }

    data class InsertReport(val total: Int, val main: Int, val backup: Int)

    /**
     * Insert one event per duty day for [employee] in [schedule] into [calendarId].
     * Events are role-specific 00:00–23:59 in [zone]. No reminders are attached.
     */
    fun insertDuties(
        schedule: Schedule,
        employee: String,
        calendarId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): InsertReport {
        var main = 0
        var backup = 0
        val zoneId = zone.id
        val mainTitle = context.getString(R.string.event_main_title)
        val backupTitle = context.getString(R.string.event_backup_title)
        for (entry in schedule.entriesFor(employee)) {
            val startMs = entry.date.atStartOfDay(zone).toInstant().toEpochMilli()
            val endLocal = LocalDateTime.of(entry.date, java.time.LocalTime.of(23, 59))
            val endMs = endLocal.atZone(zone).toInstant().toEpochMilli()
            val title = if (entry.role == DutyRole.MAIN) mainTitle else backupTitle
            val description = context.getString(
                R.string.event_description,
                employee,
                schedule.monthLabelRu(),
                schedule.sourceName,
            )
            val cv = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DESCRIPTION, description)
                put(Events.DTSTART, startMs)
                put(Events.DTEND, endMs)
                put(Events.EVENT_TIMEZONE, zoneId)
                put(Events.HAS_ALARM, 0)
                put(Events.ALL_DAY, 0)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                put(Events.STATUS, Events.STATUS_CONFIRMED)
            }
            val uri: Uri? = resolver.insert(Events.CONTENT_URI, cv)
            if (uri != null) {
                if (entry.role == DutyRole.MAIN) main++ else backup++
            }
        }
        return InsertReport(total = main + backup, main = main, backup = backup)
    }
}
