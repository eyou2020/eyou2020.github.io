package com.carnav.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * 차량 블루투스 연결 시 자동으로 실행되는 전체화면 주행 모드 UI.
 * 잠금 화면 위에서도 표시되며, 화면이 꺼져 있으면 자동으로 켜진다.
 */
class DrivingModeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvConnectionStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    // 블루투스 연결 해제 수신 → 자동 종료
    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            tvConnectionStatus.text = "연결 해제됨 — 3초 후 종료"
            tvConnectionStatus.setTextColor(getColor(R.color.status_disconnected))
            handler.postDelayed({ finish() }, 3000)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화면 항상 켜기
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 잠금 화면 위 표시 + 화면 켜기
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_driving)

        tvTime = findViewById(R.id.tv_time)
        tvDate = findViewById(R.id.tv_date)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)

        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "차량"
        tvConnectionStatus.text = "● $deviceName 연결됨"

        findViewById<Button>(R.id.btn_google_maps).setOnClickListener {
            launchNav("com.google.android.apps.maps")
        }
        findViewById<Button>(R.id.btn_kakao_navi).setOnClickListener {
            launchNav("com.locnall.KimGiSa")
        }
        findViewById<Button>(R.id.btn_tmap).setOnClickListener {
            launchNav("com.skt.tmap.eu")
        }
        findViewById<Button>(R.id.btn_exit).setOnClickListener { finish() }

        registerReceiver(
            disconnectReceiver,
            IntentFilter(BluetoothMonitorService.ACTION_CAR_DISCONNECTED)
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        enterImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(disconnectReceiver)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI 업데이트
    // ──────────────────────────────────────────────────────────────────────────

    private fun updateClock() {
        val now = Date()
        tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(now)
        tvDate.text = SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA).format(now)
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내비게이션 앱 실행
    // ──────────────────────────────────────────────────────────────────────────

    private fun launchNav(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "앱을 설치해 주세요", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
