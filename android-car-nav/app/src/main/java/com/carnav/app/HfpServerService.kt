package com.carnav.app

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * HFP Handsfree(HF) 역할로 RFCOMM 서버를 열어 다른 스마트폰이
 * 이 기기를 "차량 핸즈프리 / 네비게이션 기기"로 인식하게 한다.
 *
 * UUID 0x111E = Handsfree Profile (HF 쪽 = 차량 역할)
 * 이 UUID를 SDP에 등록하면 연결하는 폰이 AG(Audio Gateway) 모드로 접속한다.
 */
class HfpServerService : Service() {

    companion object {
        @Volatile var isRunning = false
        val connectedDevices: MutableMap<String, String> = ConcurrentHashMap()

        // HFP Handsfree UUID — 이게 등록되면 다른 폰이 "차량 핸즈프리"로 인식
        val UUID_HFP_HF: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

        const val ACTION_DEVICE_CONNECTED = "com.carnav.app.DEVICE_CONNECTED"
        const val ACTION_DEVICE_DISCONNECTED = "com.carnav.app.DEVICE_DISCONNECTED"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "hfp_server"
        private const val TAG = "HfpServerService"
    }

    private lateinit var btAdapter: BluetoothAdapter
    private var serverSocket: BluetoothServerSocket? = null
    private val clientThreads = ConcurrentHashMap<String, ClientHandler>()
    private var acceptThread: Thread? = null
    private var originalName: String? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        if (!btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off — stopping service")
            stopSelf()
            return
        }

        createNotificationChannel()
        val notification = buildNotification("대기 중 — 연결 기다리는 중")
        // Android 14+(API 34+) 타겟 시 foregroundServiceType 명시 필수.
        // 없으면 ForegroundServiceDidNotStartInTimeException 또는 SecurityException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        applyDeviceName()
        startAcceptLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        clientThreads.values.forEach { it.close() }
        clientThreads.clear()
        connectedDevices.clear()
        closeServer()
        restoreDeviceName()
        Log.d(TAG, "Service destroyed")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 블루투스 이름 설정
    // ──────────────────────────────────────────────────────────────────────────

    private fun applyDeviceName() {
        try {
            originalName = btAdapter.name
            val name = PrefsHelper.getDeviceName(this)
            btAdapter.name = name
            Log.d(TAG, "BT name set to: $name")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set BT name: ${e.message}")
        }
    }

    private fun restoreDeviceName() {
        originalName?.let {
            try { btAdapter.name = it } catch (e: Exception) { /* ignore */ }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RFCOMM 서버 (HFP UUID)
    // ──────────────────────────────────────────────────────────────────────────

    private fun startAcceptLoop() {
        acceptThread = Thread({
            try {
                // "Handsfree" 이름 + HFP HF UUID → SDP에 등록됨
                // 다른 폰이 SDP 검색 시 이 기기를 핸즈프리(차량)로 인식
                serverSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    "Handsfree", UUID_HFP_HF
                )
                Log.d(TAG, "HFP RFCOMM server listening on UUID: $UUID_HFP_HF")

                while (isRunning) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: IOException) {
                        if (isRunning) Log.e(TAG, "accept() error: ${e.message}")
                        break
                    }

                    val device = socket.remoteDevice
                    Log.d(TAG, "Accepted: ${device.name} (${device.address})")
                    val handler = ClientHandler(socket, this)
                    clientThreads[device.address] = handler
                    handler.start()
                    // notifyConnected is called by ClientHandler after SLC is established
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server loop error: ${e.message}")
            }
        }, "hfp-accept").also { it.start() }
    }

    private fun closeServer() {
        try { serverSocket?.close() } catch (e: IOException) { /* ignore */ }
        acceptThread?.interrupt()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 연결/해제 이벤트
    // ──────────────────────────────────────────────────────────────────────────

    fun notifyConnected(address: String, name: String) {
        connectedDevices[address] = name
        updateNotification()
        // RECEIVER_NOT_EXPORTED 수신기는 같은 패키지에서 보낸 브로드캐스트만 받음.
        // setPackage()로 명시해야 Android 14+ 에서 확실히 전달됨.
        sendBroadcast(Intent(ACTION_DEVICE_CONNECTED).apply {
            `package` = packageName
            putExtra(EXTRA_DEVICE_ADDRESS, address)
            putExtra(EXTRA_DEVICE_NAME, name)
        })
    }

    fun notifyDisconnected(address: String) {
        val name = connectedDevices.remove(address) ?: address
        clientThreads.remove(address)
        updateNotification()
        sendBroadcast(Intent(ACTION_DEVICE_DISCONNECTED).apply {
            `package` = packageName
            putExtra(EXTRA_DEVICE_ADDRESS, address)
            putExtra(EXTRA_DEVICE_NAME, name)
        })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 알림
    // ──────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "차량 HFP 서버", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("차량 네비 에뮬레이터 (HFP 서버)")
            .setContentText(text)
            .setSmallIcon(
                if (connectedDevices.isEmpty()) R.drawable.ic_car_waiting
                else R.drawable.ic_car_connected
            )
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val text = if (connectedDevices.isEmpty())
            "대기 중 — 연결 기다리는 중"
        else
            "${connectedDevices.size}대 연결됨: ${connectedDevices.values.joinToString()}"

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
