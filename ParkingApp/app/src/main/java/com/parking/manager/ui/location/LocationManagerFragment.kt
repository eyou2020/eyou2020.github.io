package com.parking.manager.ui.location

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parking.manager.ParkingViewModel
import com.parking.manager.data.ParkingLocation
import com.parking.manager.databinding.FragmentLocationManagerBinding

class LocationManagerFragment : Fragment() {

    private var _binding: FragmentLocationManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ParkingViewModel by activityViewModels()
    private lateinit var adapter: LocationAdapter

    private val durationLabels = arrayOf(
        "미설정", "30분", "1시간", "1시간 30분",
        "2시간", "2시간 30분", "3시간", "4시간", "6시간"
    )
    private val durationValues = intArrayOf(0, 30, 60, 90, 120, 150, 180, 240, 360)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LocationAdapter(
            onEdit = { showInputDialog(it) },
            onDelete = { showDeleteConfirm(it) }
        )

        binding.recyclerLocations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@LocationManagerFragment.adapter
        }

        viewModel.allLocations.observe(viewLifecycleOwner) { locations ->
            adapter.submitList(locations)
            binding.tvEmptyLocations.visibility =
                if (locations.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabAddLocation.setOnClickListener { showInputDialog(null) }
    }

    private fun showInputDialog(existing: ParkingLocation?) {
        val isEdit = existing != null
        val ctx = requireContext()

        // ── 레이아웃 구성 ──────────────────────────────────────
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 8)
        }

        // 위치 이름
        val etName = EditText(ctx).apply {
            hint = "위치 이름 (예: B주차장)"
            setText(existing?.name ?: "")
        }

        // 주차 가능 시간 레이블
        val tvParkingLabel = TextView(ctx).apply {
            text = "주차 가능 시간"
            textSize = 14f
            setTextColor(resources.getColor(com.parking.manager.R.color.text_secondary, null))
            setPadding(0, 24, 0, 8)
        }

        // 주차 가능 시간 스피너
        val spinnerParking = Spinner(ctx).apply {
            val a = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, durationLabels)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = a
            existing?.let { loc ->
                val idx = durationValues.indexOfFirst { it == loc.parkingMinutes }
                if (idx >= 0) setSelection(idx)
            }
        }

        // 이동시간 레이블
        val tvMoveLabel = TextView(ctx).apply {
            text = "이동시간 알림 (주차 후 경과 시 알림)"
            textSize = 14f
            setTextColor(resources.getColor(com.parking.manager.R.color.text_secondary, null))
            setPadding(0, 24, 0, 8)
        }

        // 이동시간 스피너
        val spinnerMove = Spinner(ctx).apply {
            val a = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, durationLabels)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = a
            existing?.let { loc ->
                val idx = durationValues.indexOfFirst { it == loc.moveTimeMinutes }
                if (idx >= 0) setSelection(idx)
            }
        }

        layout.addView(etName)
        layout.addView(tvParkingLabel)
        layout.addView(spinnerParking)
        layout.addView(tvMoveLabel)
        layout.addView(spinnerMove)

        // ── 다이얼로그 표시 ────────────────────────────────────
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isEdit) "위치 수정" else "위치 추가")
            .setView(layout)
            .setPositiveButton(if (isEdit) "수정" else "추가") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val parkingMin = durationValues[spinnerParking.selectedItemPosition]
                    val moveMin = durationValues[spinnerMove.selectedItemPosition]
                    if (isEdit) {
                        viewModel.updateLocation(
                            existing!!.copy(name = name, parkingMinutes = parkingMin, moveTimeMinutes = moveMin)
                        )
                    } else {
                        viewModel.addLocation(name, parkingMin, moveMin)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDeleteConfirm(location: ParkingLocation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("위치 삭제")
            .setMessage("'${location.name}'을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> viewModel.deleteLocation(location) }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
