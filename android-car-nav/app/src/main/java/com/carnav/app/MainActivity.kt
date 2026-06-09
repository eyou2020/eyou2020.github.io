package com.carnav.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvSelectedDevice: TextView
    private lateinit var btnSelectDevice: Button
    private lateinit var btnToggleService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tv_service_status)
        tvSelectedDevice = findViewById(R.id.tv_selected_device)
        btnSelectDevice = findViewById(R.id.btn_select_device)
        btnToggleService = findViewById(R.id.btn_toggle_service)

        btnSelectDevice.setOnClickListener { showDeviceSelector() }
        btnToggleService.setOnClickListener { toggleService() }

        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
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
            ) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refreshUI()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 블루투스 기기 선택
    // ──────────────────────────────────────────────────────────────────────────

    private fun showDeviceSelector() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "블루투스를 먼저 켜주세요", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            Toast.makeText(this, "블루투스 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            requestRequiredPermissions()
            return
        }

        val bonded: List<BluetoothDevice> = (adapter.bondedDevices ?: emptySet()).toList()
        if (bonded.isEmpty()) {
            Toast.makeText(
                this,
                "페어링된 블루투스 기기가 없습니다.\n먼저 차량 블루투스와 페어링하세요.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = bonded.map { "${it.name ?: "Unknown"}  (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("차량 블루투스 기기 선택")
            .setItems(names) { _, i ->
                val dev = bonded[i]
                PrefsHelper.saveTargetDevice(this, dev.address, dev.name ?: dev.address)
                refreshUI()
                Toast.makeText(this, "${dev.name} 선택됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 서비스 토글
    // ──────────────────────────────────────────────────────────────────────────

    private fun toggleService() {
        if (BluetoothMonitorService.isRunning) {
            stopService(Intent(this, BluetoothMonitorService::class.java))
            PrefsHelper.setServiceEnabled(this, false)
            refreshUI()
        } else {
            if (PrefsHelper.getTargetDeviceAddress(this) == null) {
                Toast.makeText(this, "먼저 차량 블루투스 기기를 선택해주세요", Toast.LENGTH_SHORT).show()
                return
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, BluetoothMonitorService::class.java)
            )
            PrefsHelper.setServiceEnabled(this, true)
            // 서비스 기동에 약간의 시간이 필요하므로 300ms 후 UI 갱신
            Handler(mainLooper).postDelayed({ refreshUI() }, 300)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI 갱신
    // ──────────────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val name = PrefsHelper.getTargetDeviceName(this)
        val addr = PrefsHelper.getTargetDeviceAddress(this)

        tvSelectedDevice.text = if (name != null && addr != null)
            "$name\n$addr"
        else
            "선택된 기기 없음"

        if (BluetoothMonitorService.isRunning) {
            tvServiceStatus.text = "● 실행 중 — 블루투스 감시 중"
            tvServiceStatus.setTextColor(getColor(R.color.status_connected))
            btnToggleService.text = "서비스 중지"
            btnToggleService.backgroundTintList =
                getColorStateList(R.color.danger_red)
        } else {
            tvServiceStatus.text = "○ 중지됨"
            tvServiceStatus.setTextColor(getColor(R.color.status_disconnected))
            btnToggleService.text = "서비스 시작"
            btnToggleService.backgroundTintList =
                getColorStateList(R.color.accent_green)
        }
    }
}
