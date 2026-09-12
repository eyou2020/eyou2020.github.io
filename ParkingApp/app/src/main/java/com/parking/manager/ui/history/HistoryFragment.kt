package com.parking.manager.ui.history

import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parking.manager.ParkingViewModel
import com.parking.manager.databinding.FragmentHistoryBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ParkingViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFmt = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREAN)
    private var selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private lateinit var gestureDetector: GestureDetectorCompat

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

        setupDateNavigation()
        setupSwipeGesture()
        loadRecordsForDate(selectedDate)
    }

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                private val SWIPE_THRESHOLD = 100
                private val SWIPE_VELOCITY_THRESHOLD = 100

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val diffX = e2.x - (e1?.x ?: 0f)
                    if (abs(diffX) > SWIPE_THRESHOLD &&
                        abs(velocityX) > SWIPE_VELOCITY_THRESHOLD &&
                        abs(diffX) > abs(e2.y - (e1?.y ?: 0f))
                    ) {
                        if (diffX > 0) shiftDate(-1) else shiftDate(1)
                        return true
                    }
                    return false
                }
            })

        binding.root.setOnTouchListener { v, event ->
            if (gestureDetector.onTouchEvent(event)) true
            else { v.performClick(); false }
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
