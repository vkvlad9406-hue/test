package ru.mts.duty.parser

import jxl.Cell
import jxl.Sheet
import jxl.Workbook
import jxl.WorkbookSettings
import jxl.format.Pattern
import ru.mts.duty.model.DutyEntry
import ru.mts.duty.model.DutyRole
import ru.mts.duty.model.Schedule
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Parses the "График дежурств на дому" .xls into a [Schedule].
 *
 * Layout assumptions (validated against РП-МТС-176-7 Приложение 1):
 *  - The first sheet contains:
 *      • a free-text header with a month/year in Russian, e.g. "на июнь 2026г.".
 *      • a "header" row where contiguous cells hold the day numbers 1..N for the month.
 *      • a column with employee full name (ФИО), placed at the same offset for every row.
 *      • per-employee duty cells holding "1" — coloured cells = main on-call, non-coloured = backup.
 *
 * The parser auto-detects the day-header row and the FIO column so minor template tweaks still work.
 */
class ScheduleParser {

    @Throws(ScheduleParseException::class)
    fun parse(input: InputStream, displayName: String): Schedule {
        val workbook = try {
            val settings = WorkbookSettings().apply {
                encoding = "Cp1251"
                isGCDisabled = true
                suppressWarnings = true
            }
            Workbook.getWorkbook(input, settings)
        } catch (t: Throwable) {
            throw ScheduleParseException("Не удалось открыть .xls (поддерживается только BIFF8): ${t.message}", t)
        }

        try {
            if (workbook.numberOfSheets == 0) throw ScheduleParseException("В файле нет ни одного листа")
            val sheet = workbook.getSheet(0)

            val (year, month) = detectMonthYear(sheet)
                ?: throw ScheduleParseException(
                    "Не нашёл подпись «на <месяц> <год>» в шапке листа. Проверь, что файл — это шаблон графика дежурств."
                )

            val daysHeader = detectDayHeaderRow(sheet, month, year)
                ?: throw ScheduleParseException(
                    "Не нашёл строку с номерами дней (1..N). Возможно, шаблон сильно изменён."
                )

            val fioCol = detectFioColumn(sheet, daysHeader.row)
                ?: throw ScheduleParseException(
                    "Не нашёл колонку с ФИО (ожидалось «ФИО (полностью)» либо столбец 8/I)."
                )

            val employees = mutableListOf<String>()
            val entries = mutableListOf<DutyEntry>()

            for (row in (daysHeader.row + 1) until sheet.rows) {
                val fio = sheet.getCell(fioCol, row).contents?.trim().orEmpty()
                if (fio.length < 4 || fio.split(" ").size < 2) continue
                if (fio.contains("ФИО", ignoreCase = true)) continue
                if (employees.contains(fio)) continue

                var rowHasAny = false
                for ((day, col) in daysHeader.dayColumns) {
                    val cell = sheet.getCell(col, row)
                    val raw = cell.contents?.trim().orEmpty()
                    val numeric = raw.replace(',', '.').toDoubleOrNull()
                    val isDuty = numeric != null && numeric > 0.0
                    if (!isDuty) continue
                    rowHasAny = true

                    val date = try {
                        LocalDate.of(year, month, day)
                    } catch (_: DateTimeException) {
                        continue
                    }
                    val role = roleOf(cell)
                    entries += DutyEntry(employee = fio, date = date, role = role)
                }

                if (rowHasAny || isLikelyEmployeeRow(sheet, row, fioCol)) {
                    employees += fio
                }
            }

            if (employees.isEmpty()) throw ScheduleParseException("Не нашёл ни одного сотрудника с ФИО на листе.")

            return Schedule(
                year = year,
                month = month,
                employees = employees,
                entries = entries,
                sourceName = displayName,
            )
        } finally {
            workbook.close()
        }
    }

    private fun isLikelyEmployeeRow(sheet: Sheet, row: Int, fioCol: Int): Boolean {
        val fio = sheet.getCell(fioCol, row).contents?.trim().orEmpty()
        return fio.split(" ").size >= 2
    }

    private fun roleOf(cell: Cell): DutyRole {
        val pattern = try {
            cell.cellFormat?.pattern
        } catch (_: Throwable) {
            null
        }
        return if (pattern != null && pattern != Pattern.NONE) DutyRole.MAIN else DutyRole.BACKUP
    }

    private fun detectMonthYear(sheet: Sheet): Pair<Int, Int>? {
        val regex = Regex("""(?i)на\s+([А-Яа-яЁё]+)[^\d]*?(\d{4})""")
        val limit = minOf(sheet.rows, 12)
        for (row in 0 until limit) {
            for (col in 0 until sheet.columns) {
                val s = sheet.getCell(col, row).contents?.trim().orEmpty()
                if (s.isEmpty()) continue
                val m = regex.find(s) ?: continue
                val monthName = m.groupValues[1].lowercase(Locale("ru"))
                val year = m.groupValues[2].toIntOrNull() ?: continue
                val month = MONTH_NAMES[monthName.take(4)] ?: MONTH_NAMES[monthName.take(3)]
                if (month != null) return year to month
            }
        }
        return null
    }

    private fun detectDayHeaderRow(sheet: Sheet, month: Int, year: Int): DayHeader? {
        val expectedDays = LocalDate.of(year, month, 1).lengthOfMonth()
        val limit = minOf(sheet.rows, 20)
        for (row in 0 until limit) {
            val matches = mutableListOf<Pair<Int, Int>>() // (day, col)
            for (col in 0 until sheet.columns) {
                val raw = sheet.getCell(col, row).contents?.trim().orEmpty()
                val n = raw.replace(',', '.').toDoubleOrNull()?.toInt() ?: continue
                if (n in 1..31) matches += n to col
            }
            // Require at least 25 sequential day numbers
            val sequential = matches.filter { it.first <= expectedDays }
                .sortedBy { it.first }
                .distinctBy { it.first }
            if (sequential.size >= 25 && sequential.first().first == 1) {
                return DayHeader(row, sequential.associate { it.first to it.second })
            }
        }
        return null
    }

    private fun detectFioColumn(sheet: Sheet, headerRow: Int): Int? {
        // Try columns 0..15 for any header cell whose content contains "ФИО"
        val limit = minOf(sheet.columns, 16)
        for (row in maxOf(0, headerRow - 2)..headerRow + 1) {
            for (col in 0 until limit) {
                val s = sheet.getCell(col, row).contents?.trim().orEmpty()
                if (s.contains("ФИО", ignoreCase = true)) return col
            }
        }
        // Fallback: column I = 8, which is the position in the official template
        if (sheet.columns > 8) return 8
        return null
    }

    private data class DayHeader(val row: Int, val dayColumns: Map<Int, Int>)

    companion object {
        private val MONTH_NAMES = mapOf(
            "янва" to 1, "янв" to 1,
            "февр" to 2, "фев" to 2,
            "март" to 3, "мар" to 3,
            "апре" to 4, "апр" to 4,
            "мая" to 5, "май" to 5,
            "июнь" to 6, "июня" to 6, "июн" to 6,
            "июль" to 7, "июля" to 7, "июл" to 7,
            "авгу" to 8, "авг" to 8,
            "сент" to 9, "сен" to 9,
            "октя" to 10, "окт" to 10,
            "нояб" to 11, "ноя" to 11,
            "дека" to 12, "дек" to 12,
        )
    }
}

class ScheduleParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
