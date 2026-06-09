package com.carnav.app

import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvCurrentName: TextView
    private lateinit var etDeviceName: EditText
    private lateinit var btnSaveName: Button
    private lateinit var btnToggleService: Button
    private lateinit var tvConnectedList: TextView

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HfpServerService.ACTION_DEVICE_CONNECTED,
                HfpServerService.ACTION_DEVICE_DISCONNECTED -> refreshUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tv_service_status)
        tvCurrentName = findViewById(R.id.tv_current_name)
        etDeviceName = findViewById(R.id.et_device_name)
        btnSaveName = findViewById(R.id.btn_save_name)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        tvConnectedList = findViewById(R.id.tv_connected_list)

        // 저장된 이름으로 EditText 초기화
        etDeviceName.setText(PrefsHelper.getDeviceName(this))

        btnSaveName.setOnClickListener { saveDeviceName() }
        btnToggleService.setOnClickListener { toggleService() }

        registerReceiver(
            eventReceiver,
            IntentFilter().apply {
                addAction(HfpServerService.ACTION_DEVICE_CONNECTED)
                addAction(HfpServerService.ACTION_DEVICE_DISCONNECTED)
            }
        )

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(eventReceiver)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 권한
    // ──────────────────────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT))
                    add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            ) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshUI()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 기기 이름 저장 및 적용
    // ──────────────────────────────────────────────────────────────────────────

    private fun saveDeviceName() {
        val newName = etDeviceName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        PrefsHelper.setDeviceName(this, newName)

        // 서비스 실행 중이면 서비스 재시작하여 이름 반영
        if (HfpServerService.isRunning) {
            stopService(Intent(this, HfpServerService::class.java))
            Handler(mainLooper).postDelayed({
                ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
                refreshUI()
            }, 500)
        }

        // 블루투스 어댑터 이름을 즉시 변경 시도
        try {
            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            btAdapter.name = newName
        } catch (e: Exception) { /* 권한 없으면 서비스 시작 시 적용됨 */ }

        // 키보드 숨기기
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etDeviceName.windowToken, 0)

        Toast.makeText(this, "이름 저장됨: $newName", Toast.LENGTH_SHORT).show()
        refreshUI()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 서비스 시작 / 중지
    // ──────────────────────────────────────────────────────────────────────────

    private fun toggleService() {
        if (HfpServerService.isRunning) {
            stopService(Intent(this, HfpServerService::class.java))
            PrefsHelper.setServiceEnabled(this, false)
            refreshUI()
        } else {
            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (!btAdapter.isEnabled) {
                Toast.makeText(this, "블루투스를 먼저 켜주세요", Toast.LENGTH_SHORT).show()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
            PrefsHelper.setServiceEnabled(this, true)
            Handler(mainLooper).postDelayed({ refreshUI() }, 400)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI 갱신
    // ──────────────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        tvCurrentName.text = "현재 BT 이름: ${PrefsHelper.getDeviceName(this)}"

        if (HfpServerService.isRunning) {
            tvServiceStatus.text = "● HFP 서버 실행 중"
            tvServiceStatus.setTextColor(getColor(R.color.status_connected))
            btnToggleService.text = "서버 중지"
            btnToggleService.backgroundTintList = getColorStateList(R.color.danger_red)
        } else {
            tvServiceStatus.text = "○ 서버 중지됨"
            tvServiceStatus.setTextColor(getColor(R.color.status_disconnected))
            btnToggleService.text = "서버 시작"
            btnToggleService.backgroundTintList = getColorStateList(R.color.accent_green)
        }

        val devices = HfpServerService.connectedDevices
        tvConnectedList.text = if (devices.isEmpty()) {
            "연결된 기기 없음"
        } else {
            devices.values.joinToString("\n") { "● $it" }
        }
    }
}
