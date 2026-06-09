package com.carnav.app

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvDiscoverableStatus: TextView
    private lateinit var btnRequestDiscoverable: Button
    private lateinit var tvCurrentName: TextView
    private lateinit var tvActualBtName: TextView
    private lateinit var etDeviceName: EditText
    private lateinit var btnSaveName: Button
    private lateinit var btnToggleService: Button
    private lateinit var tvConnectedList: TextView

    // 서비스 이벤트 수신 (Android 14+: RECEIVER_NOT_EXPORTED 필수)
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HfpServerService.ACTION_DEVICE_CONNECTED,
                HfpServerService.ACTION_DEVICE_DISCONNECTED -> refreshUI()
            }
        }
    }

    // 5초마다 Discoverable 상태 갱신 (타임아웃 반영)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val periodicRefresh = object : Runnable {
        override fun run() {
            refreshUI()
            refreshHandler.postDelayed(this, 5_000)
        }
    }

    // ACTION_REQUEST_DISCOVERABLE 결과 수신
    // → 사용자가 허용했을 때 실제 BT 이름을 Toast로 알려줌
    private val discoverableResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_CANCELED) {
            val name = getActualBtName()
            Toast.makeText(
                this,
                "검색 허용됨!\n다른 폰에서 \"$name\" 이름으로 검색하세요",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "검색 허용 취소됨 — 다른 폰에서 이 기기가 보이지 않습니다.\n[검색 허용] 버튼을 다시 눌러주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus        = findViewById(R.id.tv_service_status)
        tvDiscoverableStatus   = findViewById(R.id.tv_discoverable_status)
        btnRequestDiscoverable = findViewById(R.id.btn_request_discoverable)
        tvCurrentName          = findViewById(R.id.tv_current_name)
        tvActualBtName         = findViewById(R.id.tv_actual_bt_name)
        etDeviceName           = findViewById(R.id.et_device_name)
        btnSaveName            = findViewById(R.id.btn_save_name)
        btnToggleService       = findViewById(R.id.btn_toggle_service)
        tvConnectedList        = findViewById(R.id.tv_connected_list)

        etDeviceName.setText(PrefsHelper.getDeviceName(this))

        btnSaveName.setOnClickListener { saveDeviceName() }
        btnToggleService.setOnClickListener { toggleService() }
        btnRequestDiscoverable.setOnClickListener { requestDiscoverable() }

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
        refreshHandler.post(periodicRefresh)
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
        if (requestCode == 100) {
            refreshUI()
            // 권한 허용 직후 이름 변경 재시도
            applyBtNameIfPermitted()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BT 이름 변경 (권한 확인 후)
    // ──────────────────────────────────────────────────────────────────────────

    private fun applyBtNameIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val wanted = PrefsHelper.getDeviceName(this)
            if (adapter.name != wanted) {
                adapter.name = wanted
            }
        } catch (_: Exception) { }
        refreshUI()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 실제 블루투스 어댑터 이름 조회
    // ──────────────────────────────────────────────────────────────────────────

    private fun getActualBtName(): String = try {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.name ?: "읽기 실패"
    } catch (_: Exception) { "권한 없음" }

    // ──────────────────────────────────────────────────────────────────────────
    // 검색 가능(Discoverable) 요청
    // ──────────────────────────────────────────────────────────────────────────

    private fun requestDiscoverable() {
        discoverableResult.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
        )
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

        // 즉시 BT 이름 변경 시도
        val changed = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.name = newName
            true
        } catch (_: Exception) { false }

        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etDeviceName.windowToken, 0)

        if (HfpServerService.isRunning) {
            stopService(Intent(this, HfpServerService::class.java))
            Handler(mainLooper).postDelayed({
                ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
                refreshUI()
            }, 600)
        }

        val msg = if (changed) "이름 변경됨: $newName\n[검색 허용] 버튼으로 다시 검색 허용하세요"
                  else "저장됨 (BT 이름 변경 실패 — 블루투스 권한을 확인하세요)"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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

            // 서비스 시작 전 이름 먼저 적용
            applyBtNameIfPermitted()

            ContextCompat.startForegroundService(this, Intent(this, HfpServerService::class.java))
            PrefsHelper.setServiceEnabled(this, true)

            // 서비스 기동 후 검색 허용 요청
            Handler(mainLooper).postDelayed({ requestDiscoverable() }, 500)
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

        // 검색 가능 상태
        val isDiscoverable = try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
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

        // 저장된 이름 vs 실제 BT 이름
        val savedName = PrefsHelper.getDeviceName(this)
        val actualName = getActualBtName()
        tvCurrentName.text = "저장된 이름: $savedName"
        if (actualName == savedName) {
            tvActualBtName.text = "실제 BT 이름: $actualName ✓"
            tvActualBtName.setTextColor(getColor(R.color.status_connected))
        } else {
            tvActualBtName.text = "실제 BT 이름: $actualName ⚠ (다른 폰에서 이 이름으로 보임)"
            tvActualBtName.setTextColor(getColor(R.color.kakao_color))
        }

        // 연결된 기기
        val devices = HfpServerService.connectedDevices
        tvConnectedList.text = if (devices.isEmpty()) "연결된 기기 없음"
        else devices.values.joinToString("\n") { "● $it" }
    }
}
