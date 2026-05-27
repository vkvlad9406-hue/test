// Standalone test for ScheduleParser — runs the actual Kotlin parser against a fixture file.
import ru.mts.duty.parser.ScheduleParser
import ru.mts.duty.model.DutyRole
import java.io.File

fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "/home/schedule_real.xls"
    val name = File(path).name
    val sched = ScheduleParser().parse(File(path).inputStream(), name)
    println("Schedule: ${sched.monthLabelRu()} — ${sched.employees.size} employees")
    for (emp in sched.employees) {
        val entries = sched.entriesFor(emp)
        val main = entries.count { it.role == DutyRole.MAIN }
        val backup = entries.count { it.role == DutyRole.BACKUP }
        val days = entries.sortedBy { it.date }.joinToString(", ") {
            "${it.date.dayOfMonth}=${if (it.role == DutyRole.MAIN) "M" else "B"}"
        }
        println("  ${emp.padEnd(35)} main=$main  backup=$backup  total=${entries.size}")
        println("    $days")
    }
}
