package com.carnav.app

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

/**
 * 연결된 스마트폰 1대를 전담하는 스레드.
 * HFP AT 커맨드를 읽어 AtCommandProcessor 로 처리하고 응답을 전송한다.
 */
class ClientHandler(
    private val socket: BluetoothSocket,
    private val service: HfpServerService
) : Thread("hfp-${socket.remoteDevice.address}") {

    val deviceAddress: String = socket.remoteDevice.address
    val deviceName: String = socket.remoteDevice.name ?: deviceAddress

    @Volatile private var active = true

    override fun run() {
        Log.d(TAG, "Handler started: $deviceName")
        val input = socket.inputStream
        val output = socket.outputStream

        try {
            val buf = StringBuilder()

            while (active) {
                val b = input.read()
                if (b == -1) break

                val ch = b.toChar()
                buf.append(ch)

                // AT 커맨드는 \r 로 종료 (\r\n 도 처리)
                if (ch == '\r' || ch == '\n') {
                    val cmd = buf.toString().trim()
                    buf.clear()
                    if (cmd.isEmpty()) continue

                    Log.d(TAG, "← [$deviceName] $cmd")
                    val response = AtCommandProcessor.process(cmd)
                    Log.d(TAG, "→ [$deviceName] ${response.replace("\r\n", "↵")}")

                    output.write(response.toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            }
        } catch (e: IOException) {
            if (active) Log.d(TAG, "$deviceName disconnected: ${e.message}")
        } finally {
            active = false
            try { socket.close() } catch (_: IOException) {}
            service.notifyDisconnected(deviceAddress)
            Log.d(TAG, "Handler ended: $deviceName")
        }
    }

    fun close() {
        active = false
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        private const val TAG = "ClientHandler"
    }
}
