package com.parking.manager.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parking.manager.ParkingViewModel
import com.parking.manager.databinding.FragmentHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ParkingViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFmt = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREAN)
    private var selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HistoryAdapter { record ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("기록 삭제")
                .setMessage("이 주차 기록을 삭제하시겠습니까?\n\n📍 ${record.parkLocation}\n🕐 ${
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.parkTime))
                }")
                .setPositiveButton("삭제") { _, _ -> viewModel.deleteRecord(record) }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }

        binding.btnDeleteAll.setOnClickListener { showDeleteAllConfirm() }

        setupDateNavigation()
        loadRecordsForDate(selectedDate)
    }

    private fun setupDateNavigation() {
        updateDateLabel()
        binding.btnPrevDay.setOnClickListener { shiftDate(-1) }
        binding.btnNextDay.setOnClickListener { shiftDate(1) }
    }

    private fun shiftDate(days: Int) {
        val cal = Calendar.getInstance()
        cal.time = dateFmt.parse(selectedDate)!!
        cal.add(Calendar.DAY_OF_MONTH, days)
        selectedDate = dateFmt.format(cal.time)
        updateDateLabel()
        loadRecordsForDate(selectedDate)
    }

    private fun updateDateLabel() {
        binding.tvSelectedDate.text = displayFmt.format(dateFmt.parse(selectedDate)!!)
    }

    private fun loadRecordsForDate(date: String) {
        viewModel.getRecordsByDate(date).observe(viewLifecycleOwner) { records ->
            adapter.submitList(records)
            binding.tvEmptyHistory.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerHistory.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showDeleteAllConfirm() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("전체 이력 삭제")
            .setMessage("모든 주차 이력을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("전체 삭제") { _, _ -> viewModel.deleteAllRecords() }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
