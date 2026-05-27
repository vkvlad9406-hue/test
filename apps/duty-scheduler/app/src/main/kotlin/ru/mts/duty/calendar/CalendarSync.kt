package ru.mts.duty.calendar

import android.content.ContentResolver
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

/**
 * Wraps the Android CalendarProvider for the duty-scheduler use case.
 *
 * Responsibilities:
 *  - List writable calendars on the device.
 *  - Clear existing «Дежурство…» events for a given month + calendar
 *    using a sync-adapter URI so the rows are *hard* deleted (otherwise a non-
 *    syncadapter delete only sets `deleted=1`, which leaves the row in place
 *    until the actual sync adapter processes it — that caused duplicate events
 *    on repeated imports).
 *  - Insert one event per duty day for the selected employee, with role-specific titles,
 *    24h time-block (00:00–23:59) in the device's default time zone, no reminders.
 *    Every inserted event has a description starting with [MARKER_PREFIX] so we can
 *    recognize and reliably remove our own past events on the next import even if
 *    the title scheme is changed later.
 */
class CalendarSync(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Marker string embedded in DESCRIPTION so we can identify events we own. */
    private val markerPrefix = "[duty-scheduler]"

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
        resolver.query(
            Calendars.CONTENT_URI, projection, selection, args,
            "${Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { c ->
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

    /**
     * Hard-delete every event in [calendar] whose [Events.DTSTART] is in the given
     * month and whose title matches our prefix OR whose description starts with our
     * sentinel marker. Returns the number of deleted rows.
     */
    fun clearMonth(calendar: CalendarRef, year: Int, month: Int): Int {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, month, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val selection = (
            "${Events.CALENDAR_ID} = ? AND " +
            "${Events.DTSTART} >= ? AND ${Events.DTSTART} < ? AND " +
            "(${Events.TITLE} LIKE ? OR ${Events.DESCRIPTION} LIKE ?)"
        )
        val args = arrayOf(
            calendar.id.toString(),
            start.toString(),
            end.toString(),
            "Дежурство%",
            "$markerPrefix%",
        )
        val syncUri = asSyncAdapter(Events.CONTENT_URI, calendar)
        // First try the hard sync-adapter delete (removes the row entirely).
        return runCatching { resolver.delete(syncUri, selection, args) }
            .recoverCatching { resolver.delete(Events.CONTENT_URI, selection, args) }
            .getOrDefault(0)
    }

    data class InsertReport(val total: Int, val main: Int, val backup: Int)

    /**
     * Insert one event per duty day for [employee] in [schedule] into [calendar].
     * Events are role-specific 00:00–23:59 in [zone]. No reminders attached.
     */
    fun insertDuties(
        schedule: Schedule,
        employee: String,
        calendar: CalendarRef,
        zone: ZoneId = ZoneId.systemDefault(),
    ): InsertReport {
        var main = 0
        var backup = 0
        val zoneId = zone.id
        val mainTitle = context.getString(R.string.event_main_title)
        val backupTitle = context.getString(R.string.event_backup_title)
        // Use a sync-adapter URI so the inserted rows are immediately persistent
        // and the next clearMonth can hard-delete them via the same URI.
        val insertUri = asSyncAdapter(Events.CONTENT_URI, calendar)
        for (entry in schedule.entriesFor(employee)) {
            val startMs = entry.date.atStartOfDay(zone).toInstant().toEpochMilli()
            val endLocal = LocalDateTime.of(entry.date, java.time.LocalTime.of(23, 59))
            val endMs = endLocal.atZone(zone).toInstant().toEpochMilli()
            val title = if (entry.role == DutyRole.MAIN) mainTitle else backupTitle
            val description = "$markerPrefix\n" + context.getString(
                R.string.event_description,
                employee,
                schedule.monthLabelRu(),
                schedule.sourceName,
            )
            val cv = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar.id)
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
            val uri: Uri? = runCatching { resolver.insert(insertUri, cv) }
                .recoverCatching { resolver.insert(Events.CONTENT_URI, cv) }
                .getOrNull()
            if (uri != null) {
                if (entry.role == DutyRole.MAIN) main++ else backup++
            }
        }
        return InsertReport(total = main + backup, main = main, backup = backup)
    }

    /** Convert a content URI into the sync-adapter variant for the given calendar account. */
    private fun asSyncAdapter(uri: Uri, calendar: CalendarRef): Uri {
        val acc = calendar.accountName
        val type = calendar.accountType
        if (acc.isEmpty() || type.isEmpty()) return uri
        return uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, acc)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, type)
            .build()
    }
}
