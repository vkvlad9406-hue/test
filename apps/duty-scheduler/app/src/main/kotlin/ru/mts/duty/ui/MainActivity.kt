package ru.mts.duty.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mts.duty.R
import ru.mts.duty.calendar.CalendarSync
import ru.mts.duty.databinding.ActivityMainBinding
import ru.mts.duty.model.CalendarRef
import ru.mts.duty.model.DutyRole
import ru.mts.duty.model.Schedule
import ru.mts.duty.parser.ScheduleParseException
import ru.mts.duty.parser.ScheduleParser

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var schedule: Schedule? = null
    private var calendars: List<CalendarRef> = emptyList()
    private var selectedCalendar: CalendarRef? = null
    private var selectedEmployee: String? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            loadCalendars()
        } else {
            Snackbar.make(binding.root, R.string.permissions_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onFilePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/vnd.ms-excel", "application/octet-stream", "*/*"))
        }
        binding.acCalendar.setOnItemClickListener { _, _, position, _ ->
            selectedCalendar = calendars.getOrNull(position)
            refreshPushButton()
        }
        binding.acEmployee.setOnItemClickListener { _, _, position, _ ->
            selectedEmployee = schedule?.employees?.getOrNull(position)
            refreshEmployeeStats()
            refreshPushButton()
        }
        binding.btnPush.setOnClickListener { onPushClicked() }

        ensureCalendarPermissions()
    }

    private fun ensureCalendarPermissions() {
        val needed = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) loadCalendars() else permissionRequest.launch(needed.toTypedArray())
    }

    private fun loadCalendars() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { CalendarSync(this@MainActivity).listWritableCalendars() }
            calendars = list
            if (list.isEmpty()) {
                Snackbar.make(binding.root, R.string.no_calendars, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val labels = list.map { it.label() }
            binding.acCalendar.setAdapter(
                ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            )
            if (selectedCalendar == null) {
                selectedCalendar = list.first()
                binding.acCalendar.setText(labels.first(), false)
                refreshPushButton()
            }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val display = queryDisplayName(uri)
        binding.tvFileState.text = "Загружаю $display…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) return@withContext Result.failure(IllegalStateException("openInputStream вернул null"))
                        Result.success(ScheduleParser().parse(input, display))
                    }
                } catch (e: ScheduleParseException) {
                    Result.failure(e)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            result
                .onSuccess { sched ->
                    schedule = sched
                    binding.tvFileState.text = getString(
                        R.string.parse_ok,
                        sched.sourceName,
                        sched.employees.size,
                        sched.monthLabelRu(),
                    )
                    binding.acEmployee.setAdapter(
                        ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, sched.employees)
                    )
                    selectedEmployee = null
                    binding.acEmployee.setText("", false)
                    binding.tvEmployeeStats.text = ""
                    refreshPushButton()
                }
                .onFailure { err ->
                    schedule = null
                    selectedEmployee = null
                    binding.acEmployee.setAdapter(null)
                    binding.tvFileState.text = getString(R.string.parse_error, err.message ?: err.javaClass.simpleName)
                    refreshPushButton()
                }
        }
    }

    private fun refreshEmployeeStats() {
        val sched = schedule ?: return
        val emp = selectedEmployee ?: return
        val entries = sched.entriesFor(emp)
        val main = entries.count { it.role == DutyRole.MAIN }
        val backup = entries.count { it.role == DutyRole.BACKUP }
        binding.tvEmployeeStats.text = "Главных: $main · Резерв: $backup"
    }

    private fun refreshPushButton() {
        binding.btnPush.isEnabled = (schedule != null) && (selectedEmployee != null) && (selectedCalendar != null)
    }

    private fun onPushClicked() {
        val sched = schedule ?: return
        val emp = selectedEmployee ?: return
        val cal = selectedCalendar ?: return
        if (sched.entriesFor(emp).isEmpty()) {
            val log = "ℹ️ У $emp нет дежурств в ${sched.monthLabelRu()} — добавлять нечего."
            binding.tvLog.text = log
            Snackbar.make(binding.root, log, Snackbar.LENGTH_LONG).show()
            return
        }
        binding.btnPush.isEnabled = false
        val sync = CalendarSync(applicationContext)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val deleted = sync.clearMonth(cal, sched.year, sched.month)
                val inserted = sync.insertDuties(sched, emp, cal)
                deleted to inserted
            }
            val (deleted, inserted) = report
            val log = buildString {
                appendLine(getString(R.string.cleared_existing, deleted, sched.monthLabelRu()))
                appendLine(getString(R.string.inserted_events, inserted.total, inserted.main, inserted.backup))
            }
            binding.tvLog.text = log
            Snackbar.make(binding.root, log.trim(), Snackbar.LENGTH_LONG).show()
            binding.btnPush.isEnabled = true
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) return cur.getString(idx) ?: uri.lastPathSegment ?: "schedule.xls"
        }
        return uri.lastPathSegment ?: "schedule.xls"
    }
}
