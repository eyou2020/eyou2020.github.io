package com.carmanager.ui.fuel

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
import com.carmanager.data.FuelEntry
import com.carmanager.databinding.DialogAddFuelBinding
import com.carmanager.databinding.FragmentFuelBinding
import java.text.SimpleDateFormat
import java.util.*

class FuelFragment : Fragment() {
    private var _binding: FragmentFuelBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FuelViewModel by viewModels()
    private lateinit var adapter: FuelAdapter

    private val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFuelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FuelAdapter { entry -> showEditDeleteDialog(entry) }
        binding.recyclerView.adapter = adapter

        setupMonthDropdown()

        viewModel.filteredEntries.observe(viewLifecycleOwner) { entries ->
            adapter.submitList(entries)
            val isEmpty = entries.isEmpty()
            binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.emptyView.visibility   = if (isEmpty) View.VISIBLE else View.GONE
        }

        viewModel.monthTotal.observe(viewLifecycleOwner) { total ->
            if (total != null) {
                val count = viewModel.filteredEntries.value?.size ?: 0
                binding.textMonthSummary.text = "이 달 합계: %,d 원 · %d회".format(total, count)
                binding.textMonthSummary.visibility = View.VISIBLE
            } else {
                binding.textMonthSummary.visibility = View.GONE
            }
        }

        binding.fab.setOnClickListener { showAddDialog() }
    }

    private fun setupMonthDropdown() {
        viewModel.availableMonths.observe(viewLifecycleOwner) { months ->
            val labels = months.map { formatLabel(it) }
            binding.dropdownMonth.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
            )
            if (viewModel.selectedMonth.value == FuelViewModel.ALL_MONTHS) {
                val thisMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val initial = when {
                    months.contains(thisMonth) -> thisMonth
                    months.size > 1            -> months[1]
                    else                       -> FuelViewModel.ALL_MONTHS
                }
                viewModel.selectedMonth.value = initial
            }
            binding.dropdownMonth.setText(
                formatLabel(viewModel.selectedMonth.value ?: FuelViewModel.ALL_MONTHS), false
            )
        }
        binding.dropdownMonth.setOnItemClickListener { _, _, position, _ ->
            val months = viewModel.availableMonths.value ?: return@setOnItemClickListener
            viewModel.selectedMonth.value = months[position]
        }
    }

    private fun formatLabel(raw: String): String {
        if (raw == FuelViewModel.ALL_MONTHS) return "전체"
        return try {
            val p = raw.split("-")
            "${p[0]}년 ${p[1].trimStart('0')}월"
        } catch (e: Exception) { raw }
    }

    // ── 수정/삭제 ────────────────────────────────────────────
    private fun showEditDeleteDialog(entry: FuelEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("기록 관리")
            .setItems(arrayOf("수정", "삭제")) { _, which ->
                if (which == 0) showEditDialog(entry) else showDeleteConfirm(entry)
            }.show()
    }

    private fun showDeleteConfirm(entry: FuelEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("기록 삭제")
            .setMessage("이 주유기록을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> viewModel.delete(entry) }
            .setNegativeButton("취소", null).show()
    }

    // ── 추가 다이얼로그 ──────────────────────────────────────
    private fun showAddDialog() {
        val b = DialogAddFuelBinding.inflate(layoutInflater)
        val cal = Calendar.getInstance()
        initDateTimeFields(b, cal)
        AlertDialog.Builder(requireContext())
            .setTitle("주유기록 추가")
            .setView(b.root)
            .setPositiveButton("추가") { _, _ -> saveEntry(b, cal, null) }
            .setNegativeButton("취소", null).show()
    }

    // ── 수정 다이얼로그 ──────────────────────────────────────
    private fun showEditDialog(entry: FuelEntry) {
        val b = DialogAddFuelBinding.inflate(layoutInflater)
        val cal = Calendar.getInstance().apply { timeInMillis = entry.date }
        initDateTimeFields(b, cal)
        b.editAmount.setText(entry.amount.toString())
        if (entry.liters > 0f) b.editLiters.setText(entry.liters.toString())
        AlertDialog.Builder(requireContext())
            .setTitle("주유기록 수정")
            .setView(b.root)
            .setPositiveButton("저장") { _, _ -> saveEntry(b, cal, entry) }
            .setNegativeButton("취소", null).show()
    }

    private fun initDateTimeFields(b: DialogAddFuelBinding, cal: Calendar) {
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

    private fun saveEntry(b: DialogAddFuelBinding, cal: Calendar, existing: FuelEntry?) {
        val amount = b.editAmount.text.toString().toIntOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(requireContext(), "올바른 금액을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val liters = b.editLiters.text.toString().toFloatOrNull() ?: 0f
        if (existing == null) {
            viewModel.insert(FuelEntry(date = cal.timeInMillis, amount = amount, liters = liters))
        } else {
            viewModel.update(existing.copy(date = cal.timeInMillis, amount = amount, liters = liters))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
