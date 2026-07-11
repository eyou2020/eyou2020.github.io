package com.parking.manager.ui.home

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.parking.manager.ParkingViewModel
import com.parking.manager.data.ParkingLocation
import com.parking.manager.data.ParkingRecord
import com.parking.manager.data.SessionState
import com.parking.manager.data.displayName
import com.parking.manager.data.durationText
import com.parking.manager.data.getState
import com.parking.manager.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ParkingViewModel by activityViewModels()
    private var countDownTimer: CountDownTimer? = null
    private var elapsedTimer: CountDownTimer? = null
    private var cachedLocations: List<ParkingLocation> = emptyList()

    private val durationOptions = listOf(
        "30분" to 30L, "1시간" to 60L, "1시간 30분" to 90L,
        "2시간" to 120L, "2시간 30분" to 150L, "3시간" to 180L,
        "4시간" to 240L, "6시간" to 360L
    )

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestNotificationPermission()
        setupObservers()
        setupClickListeners()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupObservers() {
        viewModel.allLocations.observe(viewLifecycleOwner) { locations ->
            cachedLocations = locations
        }
        viewModel.currentRecord.observe(viewLifecycleOwner) { record ->
            updateUI(record?.getState() ?: SessionState.IDLE, record)
        }
    }

    private fun updateUI(state: SessionState, record: ParkingRecord?) {
        countDownTimer?.cancel()
        elapsedTimer?.cancel()

        // 전체 초기화
        listOf(
            binding.sectionParking, binding.sectionTimer, binding.sectionMoving
        ).forEach { it.visibility = View.GONE }
        binding.tvElapsedTime.visibility = View.GONE
        listOf(
            binding.btnRegisterParking, binding.btnSetTimer,
            binding.btnStartMove, binding.btnCompleteMove,
            binding.btnEditParkTime, binding.btnEditParkLocation
        ).forEach { it.visibility = View.GONE }

        // 레코드 데이터 표시
        record?.let {
            binding.tvParkLocation.text = "📍 위치: ${it.parkLocation}"
            binding.tvParkTime.text = "🕐 주차 시간: ${formatTime(it.parkTime)}"
            it.scheduledMoveTime?.let { moveTime ->
                val durMin = (moveTime - it.parkTime) / 60000
                binding.tvScheduledTime.text =
                    "⏰ 이동 예정: ${formatTime(moveTime)} (주차 후 ${formatDuration(durMin)})"
            }
            it.moveStartTime?.let { t ->
                binding.tvMoveStartTime.text = "🚗 이동 시작: ${formatTime(t)}"
            }
        }

        when (state) {
            SessionState.IDLE -> {
                binding.tvStatusIcon.text = "🅿️"
                binding.tvStatus.text = "현재 등록된 주차 정보가 없습니다"
                binding.btnRegisterParking.visibility = View.VISIBLE
                binding.btnRegisterParking.text = "주차 등록"
            }

            SessionState.PARKED -> {
                binding.tvStatus.text = "주차중"
                binding.sectionParking.visibility = View.VISIBLE
                binding.btnEditParkLocation.visibility = View.VISIBLE
                binding.btnEditParkTime.visibility = View.VISIBLE
                binding.btnSetTimer.visibility = View.VISIBLE
                binding.btnStartMove.visibility = View.VISIBLE
                record?.let { startElapsedTimer(it.parkTime) }
            }

            SessionState.TIMER_SET -> {
                binding.tvStatus.text = "주차중"
                binding.sectionParking.visibility = View.VISIBLE
                binding.sectionTimer.visibility = View.VISIBLE
                binding.btnEditParkLocation.visibility = View.VISIBLE
                binding.btnEditParkTime.visibility = View.VISIBLE
                binding.btnSetTimer.visibility = View.VISIBLE
                binding.btnStartMove.visibility = View.VISIBLE
                record?.let { startElapsedTimer(it.parkTime) }
                record?.scheduledMoveTime?.let {
                    startCountdown(it)
                    showAlarmTimes(it)
                }
            }

            SessionState.MOVING -> {
                binding.tvStatusIcon.text = "🚗"
                binding.tvStatus.text = "주차중(이동)"
                binding.sectionParking.visibility = View.VISIBLE
                binding.sectionMoving.visibility = View.VISIBLE
                binding.btnCompleteMove.visibility = View.VISIBLE
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnRegisterParking.setOnClickListener {
            showLocationPicker("주차 위치 선택") { loc ->
                viewModel.registerParking(loc.name, loc.parkingMinutes, loc.moveTimeMinutes)
            }
        }

        binding.btnEditParkLocation.setOnClickListener {
            showLocationPicker("주차 위치 변경") { loc ->
                viewModel.updateParkLocation(loc.name)
                Toast.makeText(requireContext(), "주차 위치가 변경되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEditParkTime.setOnClickListener {
            showParkTimeEditDialog()
        }

        binding.btnSetTimer.setOnClickListener {
            checkExactAlarmPermission { showDurationPicker() }
        }

        binding.btnStartMove.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("이동 시작")
                .setMessage("차량 이동을 시작하시겠습니까?")
                .setPositiveButton("시작") { _, _ -> viewModel.startMove() }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnCompleteMove.setOnClickListener {
            showLocationPicker("이동 완료 - 새 주차 위치 선택") { loc ->
                viewModel.completeMove(loc.name, loc.parkingMinutes, loc.moveTimeMinutes)
                val autoMsg = loc.durationText()?.let { " (${it} 후 이동 알람 설정)" } ?: ""
                Toast.makeText(
                    requireContext(),
                    "이동 완료!\n📍 ${loc.name} 에 주차 등록되었습니다.$autoMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── 주차 시간 수정 ──────────────────────────────────────────

    private fun showParkTimeEditDialog() {
        val record = viewModel.currentRecord.value ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = record.parkTime }
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val newTime = Calendar.getInstance().apply {
                    timeInMillis = record.parkTime
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                viewModel.updateParkTime(newTime)
                val extra = if (record.scheduledMoveTime != null)
                    "\n이동 예정 시간도 같은 간격으로 재계산되었습니다." else ""
                Toast.makeText(requireContext(), "주차 시간이 수정되었습니다.$extra", Toast.LENGTH_LONG).show()
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).apply { setTitle("주차 시간 수정") }.show()
    }

    // ── 이동 시간 설정 ──────────────────────────────────────────

    private fun showDurationPicker() {
        val record = viewModel.currentRecord.value ?: return
        val labels = durationOptions.map { it.first }.toTypedArray()

        var selectedIndex = 1
        record.scheduledMoveTime?.let { existing ->
            val currentMin = (existing - record.parkTime) / 60000
            val idx = durationOptions.indexOfFirst { it.second == currentMin }
            if (idx >= 0) selectedIndex = idx
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("주차 후 이동 시간 선택")
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("설정") { _, _ ->
                val (label, minutes) = durationOptions[selectedIndex]
                val moveTime = record.parkTime + minutes * 60_000L
                viewModel.setMoveTimeAfterMinutes(minutes)
                Toast.makeText(
                    requireContext(),
                    "알람 설정: 주차 후 $label (${formatTime(moveTime)})\n30분·15분·10분·5분 전 알람",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 위치 선택 ───────────────────────────────────────────────

    private fun showLocationPicker(title: String, onSelected: (ParkingLocation) -> Unit) {
        if (cachedLocations.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("위치 없음")
                .setMessage("등록된 주차 위치가 없습니다.\n'위치 관리' 탭에서 위치를 먼저 추가해주세요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        // "위치명 (주차시간)" 형태로 표시
        val displayNames = cachedLocations.map { it.displayName() }.toTypedArray()
        var selectedIndex = 0

        // 현재 주차 위치를 기본 선택
        val currentLocation = viewModel.currentRecord.value?.parkLocation
        if (currentLocation != null) {
            val idx = cachedLocations.indexOfFirst { it.name == currentLocation }
            if (idx >= 0) selectedIndex = idx
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(displayNames, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("선택") { _, _ -> onSelected(cachedLocations[selectedIndex]) }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 알람 권한 ───────────────────────────────────────────────

    private fun checkExactAlarmPermission(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("정확한 알람 권한 필요")
                    .setMessage("정확한 시간에 알람을 받으려면 권한이 필요합니다.\n설정 화면으로 이동하시겠습니까?")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${requireContext().packageName}")
                            )
                        )
                    }
                    .setNegativeButton("그냥 설정") { _, _ -> onReady() }
                    .show()
                return
            }
        }
        onReady()
    }

    // ── 알람 시각 목록 ──────────────────────────────────────────

    private val alarmMinutes = listOf(30, 15, 10, 5)

    private fun showAlarmTimes(moveTimeMillis: Long) {
        val container = binding.layoutAlarmTimes
        container.removeAllViews()
        val now = System.currentTimeMillis()

        alarmMinutes.forEach { minutes ->
            val alarmTime = moveTimeMillis - minutes * 60_000L
            val passed = alarmTime <= now

            val tv = TextView(requireContext()).apply {
                val timeStr = formatTime(alarmTime)
                text = if (passed) "  ✓  ${minutes}분 전  $timeStr" else "  🔔  ${minutes}분 전  $timeStr"
                textSize = 13f
                setTextColor(
                    if (passed)
                        ContextCompat.getColor(requireContext(), com.parking.manager.R.color.text_secondary)
                    else
                        ContextCompat.getColor(requireContext(), com.parking.manager.R.color.primary)
                )
                if (!passed) setTypeface(null, Typeface.BOLD)
                setPadding(0, 4, 0, 4)
            }
            container.addView(tv)
        }
    }

    // ── 경과 시간 (주차 후) ─────────────────────────────────────

    private fun startElapsedTimer(parkTimeMillis: Long) {
        fun elapsedText(): String {
            val elapsed = System.currentTimeMillis() - parkTimeMillis
            val h = elapsed / 3600000
            val m = (elapsed % 3600000) / 60000
            val s = (elapsed % 60000) / 1000
            return "%02d:%02d:%02d".format(h, m, s)
        }
        binding.tvStatusIcon.text = "🅿️"
        binding.tvElapsedTime.visibility = View.VISIBLE
        binding.tvElapsedTime.text = elapsedText()
        elapsedTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvElapsedTime.text = elapsedText()
            }
            override fun onFinish() {}
        }.start()
    }

    // ── 카운트다운 ──────────────────────────────────────────────

    private fun startCountdown(moveTimeMillis: Long) {
        countDownTimer?.cancel()
        val remaining = moveTimeMillis - System.currentTimeMillis()
        if (remaining <= 0) {
            binding.tvCountdown.text = "⚠️ 이동 시간이 지났습니다!"
            return
        }
        countDownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val h = millisUntilFinished / 3600000
                val m = (millisUntilFinished % 3600000) / 60000
                val s = (millisUntilFinished % 60000) / 1000
                binding.tvCountdown.text = "남은 시간: %02d:%02d:%02d".format(h, m, s)
            }
            override fun onFinish() {
                binding.tvCountdown.text = "⚠️ 이동 시간이 되었습니다!"
            }
        }.start()
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private fun formatDuration(minutes: Long): String = when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0L -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
        elapsedTimer?.cancel()
        _binding = null
    }
}
