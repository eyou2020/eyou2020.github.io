package com.carmanager.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.carmanager.R
import com.carmanager.data.FuelEntry
import com.carmanager.databinding.FragmentStatsBinding
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by viewModels()

    private val displaySdf = SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREAN)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMileageSection()
        setupFuelSection()
    }

    // ── 주행거리 섹션 ──────────────────────────────────────────
    private fun setupMileageSection() {
        viewModel.mileageMonths.observe(viewLifecycleOwner) { months ->
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                months.map { formatMonthLabel(it) }
            )
            binding.dropdownMileageMonth.setAdapter(adapter)

            // 첫 로드 시 가장 최근 달 자동 선택
            if (months.isNotEmpty() && viewModel.selectedMileageMonth.value == null) {
                val latest = months.first()
                viewModel.selectedMileageMonth.value = latest
                binding.dropdownMileageMonth.setText(formatMonthLabel(latest), false)
                binding.textMileageHint.visibility = View.GONE
            }

            if (months.isEmpty()) {
                binding.textMileageHint.text = "주행기록이 없습니다. 먼저 주행기록을 추가해주세요."
            }
        }

        binding.dropdownMileageMonth.setOnItemClickListener { _, _, position, _ ->
            val months = viewModel.mileageMonths.value ?: return@setOnItemClickListener
            viewModel.selectedMileageMonth.value = months[position]
            binding.textMileageHint.visibility = View.GONE
        }

        viewModel.mileageDetail.observe(viewLifecycleOwner) { detail ->
            if (detail == null) {
                binding.layoutMileageResult.visibility = View.GONE
                binding.textMileageNoData.visibility = View.GONE
                return@observe
            }
            if (detail.entries.isEmpty()) {
                binding.layoutMileageResult.visibility = View.GONE
                binding.textMileageNoData.visibility = View.VISIBLE
            } else {
                binding.textMileageNoData.visibility = View.GONE
                binding.layoutMileageResult.visibility = View.VISIBLE
                binding.textMileageKm.text = "%,d km".format(detail.distanceKm)
                binding.textMileageCount.text = "${detail.entries.size}건"
            }
        }
    }

    // ── 주유 섹션 ──────────────────────────────────────────────
    private fun setupFuelSection() {
        viewModel.fuelMonths.observe(viewLifecycleOwner) { months ->
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                months.map { formatMonthLabel(it) }
            )
            binding.dropdownFuelMonth.setAdapter(adapter)

            // 첫 로드 시 가장 최근 달 자동 선택
            if (months.isNotEmpty() && viewModel.selectedFuelMonth.value == null) {
                val latest = months.first()
                viewModel.selectedFuelMonth.value = latest
                binding.dropdownFuelMonth.setText(formatMonthLabel(latest), false)
                binding.textFuelHint.visibility = View.GONE
            }

            if (months.isEmpty()) {
                binding.textFuelHint.text = "주유기록이 없습니다. 먼저 주유기록을 추가해주세요."
            }
        }

        binding.dropdownFuelMonth.setOnItemClickListener { _, _, position, _ ->
            val months = viewModel.fuelMonths.value ?: return@setOnItemClickListener
            viewModel.selectedFuelMonth.value = months[position]
            binding.textFuelHint.visibility = View.GONE
        }

        viewModel.fuelDetail.observe(viewLifecycleOwner) { detail ->
            if (detail == null) {
                binding.layoutFuelResult.visibility = View.GONE
                binding.layoutFuelList.visibility = View.GONE
                binding.textFuelNoData.visibility = View.GONE
                return@observe
            }
            if (detail.entries.isEmpty()) {
                binding.layoutFuelResult.visibility = View.GONE
                binding.layoutFuelList.visibility = View.GONE
                binding.textFuelNoData.visibility = View.VISIBLE
            } else {
                binding.textFuelNoData.visibility = View.GONE
                binding.layoutFuelResult.visibility = View.VISIBLE
                binding.textFuelAmount.text = "%,d 원".format(detail.totalAmount)
                binding.textFuelCount.text = "${detail.entries.size}회"
                renderFuelList(detail.entries)
            }
        }
    }

    // 주유 항목을 카드 안에 동적으로 표시
    private fun renderFuelList(entries: List<FuelEntry>) {
        binding.layoutFuelList.removeAllViews()
        if (entries.isEmpty()) {
            binding.layoutFuelList.visibility = View.GONE
            return
        }
        binding.layoutFuelList.visibility = View.VISIBLE

        // 구분선
        val divider = View(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x1A000000)
        }
        binding.layoutFuelList.addView(divider)

        entries.forEach { entry ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_fuel_stat_row, binding.layoutFuelList, false)
            row.findViewById<TextView>(R.id.textRowDate).text =
                displaySdf.format(Date(entry.date))
            row.findViewById<TextView>(R.id.textRowAmount).text =
                "%,d 원".format(entry.amount)
            val litersView = row.findViewById<TextView>(R.id.textRowLiters)
            if (entry.liters > 0f) {
                litersView.text = "%.2f L".format(entry.liters)
                litersView.visibility = View.VISIBLE
            } else {
                litersView.visibility = View.GONE
            }
            binding.layoutFuelList.addView(row)
        }
    }

    private fun formatMonthLabel(yyyyMM: String): String {
        return try {
            val parts = yyyyMM.split("-")
            "${parts[0]}년 ${parts[1].trimStart('0')}월"
        } catch (e: Exception) {
            yyyyMM
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
