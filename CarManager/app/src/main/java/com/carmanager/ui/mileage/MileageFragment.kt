package com.carmanager.ui.mileage

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.carmanager.data.MileageEntry
import com.carmanager.databinding.DialogAddMileageBinding
import com.carmanager.databinding.FragmentMileageBinding
import java.text.SimpleDateFormat
import java.util.*

class MileageFragment : Fragment() {
    private var _binding: FragmentMileageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MileageViewModel by viewModels()
    private lateinit var adapter: MileageAdapter

    private val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val displaySdf = SimpleDateFormat("yyyy년 MM월 dd일 HH:mm", Locale.KOREAN)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMileageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MileageAdapter { entry -> showEditDeleteDialog(entry) }
        binding.recyclerView.adapter = adapter
        setupMonthDropdown()
        observeData()
        binding.fab.setOnClickListener { showAddDialog() }
    }

    private fun observeData() {
        viewModel.mergedList.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            val isEmpty = items.isEmpty()
            binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.emptyView.visibility   = if (isEmpty) View.VISIBLE else View.GONE
        }
        viewModel.monthMileageSummary.observe(viewLifecycleOwner) { summary ->
            if (summary != null) {
                binding.textMonthSummary.text = "이 달 주행: %,d km · %d건".format(summary.first, summary.second)
                binding.textMonthSummary.visibility = View.VISIBLE
            } else {
                binding.textMonthSummary.visibility = View.GONE
            }
        }
        viewModel.todayMileageSummary.observe(viewLifecycleOwner) { summary ->
            if (summary != null) {
                binding.textTodaySummary.text = "오늘 주행: %,d km · %d건".format(summary.first, summary.second)
                binding.textTodaySummary.visibility = View.VISIBLE
            } else {
                binding.textTodaySummary.visibility = View.GONE
            }
        }
        viewModel.lastFuelSummary.observe(viewLifecycleOwner) { summary ->
            if (summary != null) {
                binding.cardLastFuelSummary.visibility = View.VISIBLE
                binding.textLastFuelDate.text = "마지막 주유: %s".format(displaySdf.format(Date(summary.lastFuelDate)))
                binding.textDistanceSinceFuel.text = "%,d km".format(summary.distanceSinceKm)
                binding.textDriveCountSinceFuel.text = "주행 %d건".format(summary.mileageCount)
            } else {
                binding.cardLastFuelSummary.visibility = View.GONE
            }
        }
    }

    private fun setupMonthDropdown() {
        viewModel.availableMonths.observe(viewLifecycleOwner) { months ->
            val labels = months.map { formatLabel(it) }
            binding.dropdownMonth.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
            )
            if (viewModel.selectedMonth.value == MileageViewModel.ALL_MONTHS) {
                val thisMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val initial = when {
                    months.contains(thisMonth) -> thisMonth
                    months.size > 1            -> months[1]
                    else                       -> MileageViewModel.ALL_MONTHS
                }
                viewModel.selectedMonth.value = initial
            }
            binding.dropdownMonth.setText(
                formatLabel(viewModel.selectedMonth.value ?: MileageViewModel.ALL_MONTHS), false
            )
        }
        binding.dropdownMonth.setOnItemClickListener { _, _, position, _ ->
            val months = viewModel.availableMonths.value ?: return@setOnItemClickListener
            viewModel.selectedMonth.value = months[position]
        }
    }

    private fun formatLabel(raw: String): String {
        if (raw == MileageViewModel.ALL_MONTHS) return "전체"
        return try {
            val p = raw.split("-")
            "${p[0]}년 ${p[1].trimStart('0')}월"
        } catch (e: Exception) { raw }
    }

    private fun showEditDeleteDialog(entry: MileageEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("기록 관리")
            .setItems(arrayOf("수정", "삭제")) { _, which ->
                if (which == 0) showEditDialog(entry) else showDeleteConfirm(entry)
            }.show()
    }

    private fun showDeleteConfirm(entry: MileageEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("기록 삭제")
            .setMessage("이 주행기록을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> viewModel.delete(entry) }
            .setNegativeButton("취소", null).show()
    }

    private fun showAddDialog() {
        val b = DialogAddMileageBinding.inflate(layoutInflater)
        val cal = Calendar.getInstance()
        initDateTimeFields(b, cal)
        AlertDialog.Builder(requireContext())
            .setTitle("주행기록 추가")
            .setView(b.root)
            .setPositiveButton("추가") { _, _ -> saveEntry(b, cal, null) }
            .setNegativeButton("취소", null).show()
    }

    private fun showEditDialog(entry: MileageEntry) {
        val b = DialogAddMileageBinding.inflate(layoutInflater)
        val cal = Calendar.getInstance().apply { timeInMillis = entry.date }
        initDateTimeFields(b, cal)
        b.editOdometer.setText(entry.odometer.toString())
        b.editOrigin.setText(entry.origin)
        b.editDestination.setText(entry.destination)
        AlertDialog.Builder(requireContext())
            .setTitle("주행기록 수정")
            .setView(b.root)
            .setPositiveButton("저장") { _, _ -> saveEntry(b, cal, entry) }
            .setNegativeButton("취소", null).show()
    }

    private fun initDateTimeFields(b: DialogAddMileageBinding, cal: Calendar) {
        b.editDate.setText(dateSdf.format(cal.time))
        b.editTime.setText(timeSdf.format(cal.time))
        b.editDate.setOnClickListener {
            DatePickerDialog(requireContext(), { _, y, m, d ->
                cal.set(y, m, d)
                b.editDate.setText(dateSdf.format(cal.time))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        b.editTime.setOnClickListener {
            TimePickerDialog(requireContext(), { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                b.editTime.setText(timeSdf.format(cal.time))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
    }

    private fun saveEntry(b: DialogAddMileageBinding, cal: Calendar, existing: MileageEntry?) {
        val odometer = b.editOdometer.text.toString().toIntOrNull()
        if (odometer == null || odometer < 0) {
            Toast.makeText(requireContext(), "올바른 주행거리를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val origin = b.editOrigin.text.toString().trim()
        val destination = b.editDestination.text.toString().trim()
        if (existing == null) {
            viewModel.insert(MileageEntry(date = cal.timeInMillis, odometer = odometer,
                origin = origin, destination = destination))
        } else {
            viewModel.update(existing.copy(date = cal.timeInMillis, odometer = odometer,
                origin = origin, destination = destination))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}