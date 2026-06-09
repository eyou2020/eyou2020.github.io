package com.autoclean.app.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.autoclean.app.R
import com.autoclean.app.databinding.ActivityAddScheduleBinding
import com.autoclean.app.model.CleanSchedule

class AddScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddScheduleBinding
    private val viewModel: AddScheduleViewModel by viewModels()
    private var editingSchedule: CleanSchedule? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val folderName = uri.lastPathSegment?.substringAfterLast(":") ?: uri.toString()
        viewModel.folderUri.value = uri.toString()
        viewModel.folderName.value = folderName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editingSchedule = intent.getParcelableExtra(MainActivity.EXTRA_SCHEDULE)
        editingSchedule?.let {
            supportActionBar?.title = getString(R.string.edit_schedule)
            viewModel.loadSchedule(it)
        } ?: run {
            supportActionBar?.title = getString(R.string.add_schedule)
        }

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        viewModel.folderName.observe(this) { name ->
            binding.tvFolderSelected.text = name ?: getString(R.string.no_folder_selected)
        }

        viewModel.scheduledHour.observe(this) { updateTimeDisplay() }
        viewModel.scheduledMinute.observe(this) { updateTimeDisplay() }

        viewModel.repeatDays.observe(this) { repeat ->
            binding.chipGroupRepeat.clearCheck()
            when (repeat) {
                CleanSchedule.REPEAT_DAILY -> binding.chipDaily.isChecked = true
                CleanSchedule.REPEAT_WEEKDAY -> binding.chipWeekday.isChecked = true
                CleanSchedule.REPEAT_WEEKEND -> binding.chipWeekend.isChecked = true
                CleanSchedule.REPEAT_ONCE -> binding.chipOnce.isChecked = true
                else -> {
                    binding.chipCustom.isChecked = true
                    showCustomDayChips(repeat)
                }
            }
        }

        viewModel.deleteMode.observe(this) { mode ->
            if (mode == CleanSchedule.DELETE_MODE_PERMANENT) {
                binding.radioPermanent.isChecked = true
            } else {
                binding.radioTrash.isChecked = true
            }
        }

        viewModel.saveResult.observe(this) { success ->
            if (success) {
                Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, R.string.select_folder_first, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectFolder.setOnClickListener {
            folderPicker.launch(null)
        }

        binding.btnSelectTime.setOnClickListener {
            val hour = viewModel.scheduledHour.value ?: 8
            val minute = viewModel.scheduledMinute.value ?: 0
            TimePickerDialog(this, { _, h, m ->
                viewModel.scheduledHour.value = h
                viewModel.scheduledMinute.value = m
            }, hour, minute, true).show()
        }

        binding.chipGroupRepeat.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chipDaily -> {
                    viewModel.repeatDays.value = CleanSchedule.REPEAT_DAILY
                    binding.chipGroupCustomDays.visibility = android.view.View.GONE
                }
                R.id.chipWeekday -> {
                    viewModel.repeatDays.value = CleanSchedule.REPEAT_WEEKDAY
                    binding.chipGroupCustomDays.visibility = android.view.View.GONE
                }
                R.id.chipWeekend -> {
                    viewModel.repeatDays.value = CleanSchedule.REPEAT_WEEKEND
                    binding.chipGroupCustomDays.visibility = android.view.View.GONE
                }
                R.id.chipOnce -> {
                    viewModel.repeatDays.value = CleanSchedule.REPEAT_ONCE
                    binding.chipGroupCustomDays.visibility = android.view.View.GONE
                }
                R.id.chipCustom -> {
                    binding.chipGroupCustomDays.visibility = android.view.View.VISIBLE
                }
            }
        }

        binding.chipGroupCustomDays.setOnCheckedStateChangeListener { group, _ ->
            val dayMap = mapOf(
                R.id.chipSun to "SUN", R.id.chipMon to "MON",
                R.id.chipTue to "TUE", R.id.chipWed to "WED",
                R.id.chipThu to "THU", R.id.chipFri to "FRI",
                R.id.chipSat to "SAT"
            )
            val checkedIds = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<com.google.android.material.chip.Chip>()
                .filter { it.isChecked }
                .mapNotNull { dayMap[it.id] }

            if (checkedIds.isNotEmpty()) {
                viewModel.repeatDays.value = checkedIds.joinToString(",")
            }
        }

        binding.radioGroupDeleteMode.setOnCheckedChangeListener { _, checkedId ->
            viewModel.deleteMode.value = if (checkedId == R.id.radioPermanent)
                CleanSchedule.DELETE_MODE_PERMANENT else CleanSchedule.DELETE_MODE_TRASH
        }

        binding.btnSave.setOnClickListener {
            viewModel.saveSchedule(editingSchedule?.id ?: 0L)
        }
    }

    private fun updateTimeDisplay() {
        val hour = viewModel.scheduledHour.value ?: 8
        val minute = viewModel.scheduledMinute.value ?: 0
        binding.tvSelectedTime.text = String.format("%02d:%02d", hour, minute)
    }

    private fun showCustomDayChips(repeatDays: String) {
        binding.chipGroupCustomDays.visibility = android.view.View.VISIBLE
        val days = repeatDays.split(",").map { it.trim() }
        val dayMap = mapOf(
            "SUN" to R.id.chipSun, "MON" to R.id.chipMon,
            "TUE" to R.id.chipTue, "WED" to R.id.chipWed,
            "THU" to R.id.chipThu, "FRI" to R.id.chipFri,
            "SAT" to R.id.chipSat
        )
        days.forEach { day ->
            dayMap[day]?.let { id ->
                binding.chipGroupCustomDays.findViewById<com.google.android.material.chip.Chip>(id)
                    ?.isChecked = true
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
