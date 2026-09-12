package com.parking.manager.ui.history

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parking.manager.ParkingViewModel
import com.parking.manager.R
import com.parking.manager.data.DailySummary
import com.parking.manager.databinding.FragmentMonthlyHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MonthlyHistoryFragment : Fragment() {

    private var _binding: FragmentMonthlyHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ParkingViewModel by activityViewModels()

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val displayMonthFmt = SimpleDateFormat("yyyy년 MM월", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFmt = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREAN)

    private var currentCalendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }
    private var summaryMap: Map<String, Long> = emptyMap()
    private lateinit var dayAdapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonthlyHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dayAdapter = HistoryAdapter { /* 월별 뷰에서는 개별 삭제 미지원 */ }
        binding.recyclerDayHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dayAdapter
        }

        binding.btnDeleteAll.setOnClickListener { showDeleteAllConfirm() }

        binding.btnPrevMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, -1)
            loadMonth()
        }
        binding.btnNextMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, 1)
            loadMonth()
        }

        loadMonth()
    }

    private fun loadMonth() {
        val yearMonth = monthFmt.format(currentCalendar.time)
        binding.tvSelectedMonth.text = displayMonthFmt.format(currentCalendar.time)
        binding.cardDayDetail.visibility = View.GONE

        lifecycleScope.launch {
            val summaries = viewModel.getDailySummaryForMonth(yearMonth)
            summaryMap = summaries.associate { it.dateKey to it.totalMinutes }
            buildCalendar(yearMonth)
        }
    }

    private fun buildCalendar(yearMonth: String) {
        val cal = currentCalendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0=Sun
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = dateFmt.format(Date())

        // 앞 빈칸 + 날짜 셀 구성
        val cells = mutableListOf<Int?>()
        repeat(firstDayOfWeek) { cells.add(null) }
        for (d in 1..daysInMonth) cells.add(d)
        // 7의 배수로 맞추기
        while (cells.size % 7 != 0) cells.add(null)

        binding.gridCalendar.adapter = CalendarAdapter(
            requireContext(), cells, yearMonth, summaryMap, today
        ) { day ->
            val dateKey = "$yearMonth-${day.toString().padStart(2, '0')}"
            showDayDetail(dateKey)
        }
    }

    private fun showDayDetail(dateKey: String) {
        binding.cardDayDetail.visibility = View.VISIBLE
        val parsed = dateFmt.parse(dateKey) ?: return
        binding.tvDetailDate.text = "📋 ${displayDateFmt.format(parsed)}"

        viewModel.getRecordsByDate(dateKey).observe(viewLifecycleOwner) { records ->
            dayAdapter.submitList(records)
            binding.tvDetailEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerDayHistory.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showDeleteAllConfirm() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("전체 이력 삭제")
            .setMessage("모든 주차 이력을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("전체 삭제") { _, _ ->
                viewModel.deleteAllRecords()
                summaryMap = emptyMap()
                binding.cardDayDetail.visibility = View.GONE
                loadMonth()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ── 달력 어댑터 ─────────────────────────────────────────────────

private class CalendarAdapter(
    private val ctx: Context,
    private val cells: List<Int?>,
    private val yearMonth: String,
    private val summaryMap: Map<String, Long>,
    private val today: String,
    private val onDayClick: (Int) -> Unit
) : BaseAdapter() {

    override fun getCount() = cells.size
    override fun getItem(pos: Int) = cells[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(ctx)
            .inflate(R.layout.item_calendar_day, parent, false)

        val tvDay = view.findViewById<TextView>(R.id.tv_day_number)
        val tvDur = view.findViewById<TextView>(R.id.tv_day_duration)
        val day = cells[pos]

        if (day == null) {
            tvDay.text = ""
            tvDur.visibility = View.INVISIBLE
            view.isClickable = false
            view.setBackgroundColor(Color.TRANSPARENT)
            return view
        }

        val dateKey = "$yearMonth-${day.toString().padStart(2, '0')}"
        val col = pos % 7  // 0=Sun, 6=Sat

        // 날짜 숫자 색상
        tvDay.text = day.toString()
        tvDay.setTextColor(when {
            dateKey == today -> Color.WHITE
            col == 0 -> Color.parseColor("#F44336")   // 일
            col == 6 -> Color.parseColor("#2196F3")   // 토
            else -> Color.parseColor("#212121")
        })
        tvDay.setTypeface(null, if (summaryMap.containsKey(dateKey)) Typeface.BOLD else Typeface.NORMAL)

        // 오늘 배경 원
        if (dateKey == today) {
            tvDay.setBackgroundResource(android.R.drawable.btn_default_small)
            tvDay.setTextColor(Color.WHITE)
        } else {
            tvDay.background = null
        }

        // 주차 시간 표시
        val minutes = summaryMap[dateKey]
        if (minutes != null && minutes > 0) {
            tvDur.text = formatDur(minutes)
            tvDur.visibility = View.VISIBLE
        } else {
            tvDur.visibility = View.INVISIBLE
        }

        view.setOnClickListener { onDayClick(day) }
        return view
    }

    private fun formatDur(min: Long): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h${if (m > 0) "${m}m" else ""}" else "${m}m"
    }
}
