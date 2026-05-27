package ru.mts.duty.model

import java.time.LocalDate

/** A duty slot: an employee on duty on a specific date with a role. */
data class DutyEntry(
    val employee: String,
    val date: LocalDate,
    val role: DutyRole,
)

enum class DutyRole {
    /** Main on-call (yellow-filled cell). */
    MAIN,
    /** Backup / secondary on-call (un-filled cell). */
    BACKUP,
}

/** Parsed schedule for a single calendar month. */
data class Schedule(
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** Distinct list of employees in row order. */
    val employees: List<String>,
    /** All duty entries across the month, both roles. */
    val entries: List<DutyEntry>,
    /** Original file display name, used in event descriptions. */
    val sourceName: String,
) {
    fun entriesFor(employee: String): List<DutyEntry> = entries.filter { it.employee == employee }

    fun monthLabelRu(): String {
        val months = listOf(
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
        )
        return "${months[month - 1]} $year"
    }
}

/** Description of an Android calendar account writable to. */
data class CalendarRef(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String?,
) {
    /** Label suitable for spinner item. */
    fun label(): String = "$displayName · $accountName"
}
