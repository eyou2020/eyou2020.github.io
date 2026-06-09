package com.carnav.app

import android.bluetooth.BluetoothAdapter
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
    private lateinit var tvDiscoverableStatus: TextView
    private lateinit var btnRequestDiscoverable: Button
    private lateinit var tvCurrentName: TextView
    private lateinit var etDeviceName: EditText
    private lateinit var btnSaveName: Button
    private lateinit var btnToggleService: Button
    private lateinit var tvConnectedList: TextView

    // 서비스 연결/해제 이벤트 수신
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HfpServerService.ACTION_DEVICE_CONNECTED,
                HfpServerService.ACTION_DEVICE_DISCONNECTED -> refreshUI()
            }
        }
    }

    // 5초마다 검색 가능 상태를 갱신 (타임아웃 반영)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val periodicRefresh = object : Runnable {
        override fun run() {
            refreshUI()
            refreshHandler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus        = findViewById(R.id.tv_service_status)
        tvDiscoverableStatus   = findViewById(R.id.tv_discoverable_status)
        btnRequestDiscoverable = findViewById(R.id.btn_request_discoverable)
        tvCurrentName          = findViewById(R.id.tv_current_name)
        etDeviceName           = findViewById(R.id.et_device_name)
        btnSaveName            = findViewById(R.id.btn_save_name)
        btnToggleService       = findViewById(R.id.btn_toggle_service)
        tvConnectedList        = findViewById(R.id.tv_connected_list)

        etDeviceName.setText(PrefsHelper.getDeviceName(this))

        btnSaveName.setOnClickListener { saveDeviceName() }
        btnToggleService.setOnClickListener { toggleService() }
        btnRequestDiscoverable.setOnClickListener { requestDiscoverable() }

        // Android 14+(targetSdk 34+): RECEIVER_NOT_EXPORTED 필수
        ContextCompat.registerReceiver(
            this,
            eventReceiver,
            IntentFilter().apply {
                addAction(HfpServerService.ACTION_DEVICE_CONNECTED)
                addAction(HfpServerService.ACTION_DEVICE_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        refreshHandler.post(periodicRefresh)   // 검색 가능 상태 주기적 갱신
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(periodicRefresh)
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
                if (!hasPermission(android.Manifest.permission.BLUETOOTH_SCAN))
                    add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            ) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshUI()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 검색 가능(Discoverable) 모드 요청
    // ──────────────────────────────────────────────────────────────────────────

    private fun requestDiscoverable() {
        // ACTION_REQUEST_DISCOVERABLE: 시스템 다이얼로그를 띄워 검색 허용 요청
        // duration=300 → 300초(5분) 동안 다른 폰의 BT 스캔에 이 기기가 표시됨
        // duration=0   → 무제한 (일부 기기에서 허용)
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivity(intent)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 기기 이름 저장
    // ──────────────────────────────────────────────────────────────────────────

    private fun saveDeviceName() {
        val newName = etDeviceName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        PrefsHelper.setDeviceName(this, newName)

        if (HfpServerService.isRunning) {
            stopService(Intent(this, HfpServerService::class.java))
            Handler(mainLooper).postDelayed({
                ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
                refreshUI()
            }, 500)
        }

        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.name = newName
        } catch (_: Exception) { }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                Toast.makeText(this, "블루투스 권한을 허용해주세요", Toast.LENGTH_SHORT).show()
                requestRequiredPermissions()
                return
            }
            val enabled = try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.isEnabled
            } catch (_: SecurityException) {
                Toast.makeText(this, "블루투스 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                return
            }
            if (!enabled) {
                Toast.makeText(this, "블루투스를 먼저 켜주세요", Toast.LENGTH_SHORT).show()
                return
            }

            ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
            PrefsHelper.setServiceEnabled(this, true)

            // 서비스 시작 직후 검색 허용 요청 — 다른 폰이 이 기기를 찾을 수 있게 됨
            Handler(mainLooper).postDelayed({
                requestDiscoverable()
                refreshUI()
            }, 400)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI 갱신
    // ──────────────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        // 서비스 상태
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

        // 검색 가능(Discoverable) 상태
        val isDiscoverable = try {
            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            btAdapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } catch (_: Exception) { false }

        if (isDiscoverable) {
            tvDiscoverableStatus.text = "● 검색 가능 — 다른 폰에서 이 기기가 보임"
            tvDiscoverableStatus.setTextColor(getColor(R.color.status_connected))
            btnRequestDiscoverable.text = "검색 허용 갱신 (300초)"
        } else {
            tvDiscoverableStatus.text = "○ 검색 불가 — 아래 버튼을 눌러야 다른 폰에서 보임"
            tvDiscoverableStatus.setTextColor(getColor(R.color.status_disconnected))
            btnRequestDiscoverable.text = "검색 허용 (300초)"
        }

        // 기기 이름
        tvCurrentName.text = "현재 BT 이름: ${PrefsHelper.getDeviceName(this)}"

        // 연결된 기기 목록
        val devices = HfpServerService.connectedDevices
        tvConnectedList.text = if (devices.isEmpty()) "연결된 기기 없음"
        else devices.values.joinToString("\n") { "● $it" }
    }
}
