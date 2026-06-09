package com.carnav.app

import android.app.*
import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 포그라운드 서비스: 블루투스 연결 이벤트를 감시하다가
 * 지정된 차량 기기가 연결되면 주행 모드를 활성화한다.
 *
 * Android 8+ 에서는 암묵적 브로드캐스트 수신이 제한되므로
 * 서비스 내부에서 동적으로 BroadcastReceiver를 등록한다.
 */
class BluetoothMonitorService : Service() {

    companion object {
        @Volatile var isRunning = false

        const val ACTION_CAR_CONNECTED = "com.carnav.app.CAR_CONNECTED"
        const val ACTION_CAR_DISCONNECTED = "com.carnav.app.CAR_DISCONNECTED"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "car_nav_channel"
        private const val TAG = "CarNavService"
    }

    private val uiModeManager by lazy {
        getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var btReceiver: BroadcastReceiver

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("대기 중 — 블루투스 감시 중", false))
        registerBtReceiver()
        Log.d(TAG, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterReceiver(btReceiver)
        releaseWakeLock()
        runCatching { uiModeManager.disableCarMode(0) }
        Log.d(TAG, "Service stopped")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bluetooth 이벤트
    // ──────────────────────────────────────────────────────────────────────────

    private fun registerBtReceiver() {
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let { onConnected(it) }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let { onDisconnected(it) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(btReceiver, filter)
    }

    private fun onConnected(device: BluetoothDevice) {
        val target = PrefsHelper.getTargetDeviceAddress(this) ?: return
        if (device.address != target) return
        Log.d(TAG, "Target device connected: ${device.address}")

        // Android 차량 모드 활성화
        uiModeManager.enableCarMode(0)

        // 화면 강제 켜기
        acquireWakeLock()

        // 주행 화면 실행
        val name = PrefsHelper.getTargetDeviceName(this) ?: device.address
        startActivity(
            Intent(this, DrivingModeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(DrivingModeActivity.EXTRA_DEVICE_NAME, name)
            }
        )

        updateNotification("차량 연결됨 — 주행 모드 활성화", true)
        sendBroadcast(Intent(ACTION_CAR_CONNECTED))
    }

    private fun onDisconnected(device: BluetoothDevice) {
        val target = PrefsHelper.getTargetDeviceAddress(this) ?: return
        if (device.address != target) return
        Log.d(TAG, "Target device disconnected: ${device.address}")

        uiModeManager.disableCarMode(0)
        releaseWakeLock()
        updateNotification("대기 중 — 블루투스 감시 중", false)
        sendBroadcast(Intent(ACTION_CAR_DISCONNECTED))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Wake lock
    // ──────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        releaseWakeLock()
        // SCREEN_BRIGHT_WAKE_LOCK: deprecated API 26 but still functional;
        // ACQUIRE_CAUSES_WAKEUP: 화면이 꺼진 상태에서도 강제로 켬
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CarNav:DrivingWakeLock"
        ).also { it.acquire(60 * 60 * 1000L) }  // 최대 1시간
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 알림
    // ──────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "차량 네비게이션",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "차량 블루투스 연결 감시 서비스"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String, connected: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("차량 네비 에뮬레이터")
            .setContentText(text)
            .setSmallIcon(if (connected) R.drawable.ic_car_connected else R.drawable.ic_car_waiting)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String, connected: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text, connected))
    }
}
