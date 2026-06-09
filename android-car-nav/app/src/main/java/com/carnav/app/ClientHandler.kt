package com.carnav.app

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * HFP SLC(Service Level Connection) 수립 스레드.
 *
 * [핵심] HFP 규격: HF(차량=우리)가 먼저 AT+BRSF를 보내야 한다.
 *        AG(상대 스마트폰)는 HF의 첫 명령을 기다린다.
 *        기존 코드는 반대로 AG의 명령을 기다렸음 → 교착 → "연결할 수 없음"
 *
 * SLC 수립 순서:
 *   HF → AT+BRSF=<features>       (HF 지원 기능 알림)
 *   AG ← +BRSF:<ag_features> / OK
 *   HF → AT+CIND=?                (표시기 목록 조회)
 *   AG ← +CIND:... / OK
 *   HF → AT+CIND?                 (표시기 현재값 조회)
 *   AG ← +CIND:... / OK
 *   HF → AT+CMER=3,0,0,1         (이벤트 리포팅 활성화)
 *   AG ← OK
 *   HF → AT+CHLD=?               (통화 보류 지원 조회)
 *   AG ← +CHLD:... / OK
 *   ─── SLC 수립 완료 ────────────────────────────────
 */
class ClientHandler(
    private val socket: BluetoothSocket,
    private val service: HfpServerService
) : Thread("hfp-${socket.remoteDevice.address}") {

    val deviceAddress: String = socket.remoteDevice.address
    val deviceName: String = try {
        socket.remoteDevice.name ?: deviceAddress
    } catch (_: Exception) { deviceAddress }

    @Volatile private var active = true

    private val HF_FEATURES = 32  // Enhanced Call Status(bit5)

    override fun run() {
        Log.d(TAG, "RFCOMM accepted from: $deviceName ($deviceAddress)")
        val input = socket.inputStream
        val output = socket.outputStream

        try {
            // ── 1. HFP SLC 수립 (HF가 먼저 보냄) ────────────────────────────
            if (!establishSlc(input, output)) {
                Log.w(TAG, "SLC failed for $deviceName")
                return
            }
            Log.d(TAG, "✓ SLC established with $deviceName")

            // ── 2. 연결 완료 알림 ─────────────────────────────────────────────
            service.notifyConnected(deviceAddress, deviceName)

            // ── 3. AG 이벤트/명령 수신 루프 ──────────────────────────────────
            monitorAgEvents(input, output)

        } catch (e: IOException) {
            if (active) Log.d(TAG, "$deviceName disconnected: ${e.message}")
        } finally {
            active = false
            try { socket.close() } catch (_: IOException) {}
            service.notifyDisconnected(deviceAddress)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HFP SLC 수립
    // ──────────────────────────────────────────────────────────────────────────

    private fun establishSlc(input: InputStream, output: OutputStream): Boolean {
        // AT+BRSF — HF 지원 기능 알림 (SLC의 첫 번째 명령, HF가 반드시 먼저 보냄)
        if (!sendAndWait(output, input, "AT+BRSF=$HF_FEATURES")) return false

        // AT+CIND=? — AG가 제공하는 표시기 목록 조회
        if (!sendAndWait(output, input, "AT+CIND=?")) return false

        // AT+CIND? — 현재 표시기 값 조회
        if (!sendAndWait(output, input, "AT+CIND?")) return false

        // AT+CMER — 이벤트 리포팅 활성화 (필수)
        if (!sendAndWait(output, input, "AT+CMER=3,0,0,1")) return false

        // AT+CHLD=? — 통화 보류 지원 조회 (일부 AG가 지원 안 할 수 있으므로 실패해도 진행)
        sendAndWait(output, input, "AT+CHLD=?")

        return true
    }

    /** 명령 전송 후 OK / ERROR 수신까지 대기. true = OK 수신 */
    private fun sendAndWait(output: OutputStream, input: InputStream, cmd: String): Boolean {
        sendCmd(output, cmd)
        repeat(30) {
            val line = readLine(input) ?: return false
            if (line == "OK") return true
            if (line == "ERROR") return false
        }
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SLC 이후: AG 이벤트 수신
    // ──────────────────────────────────────────────────────────────────────────

    private fun monitorAgEvents(input: InputStream, output: OutputStream) {
        while (active) {
            val line = readLine(input) ?: break
            Log.d(TAG, "← AG: $line")

            // AG가 HF에게 보내는 AT 명령 (볼륨 조정 등)에는 OK 응답
            if (line.startsWith("AT")) {
                output.write("\r\nOK\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                Log.d(TAG, "→ OK")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 유틸리티
    // ──────────────────────────────────────────────────────────────────────────

    private fun sendCmd(output: OutputStream, cmd: String) {
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        Log.d(TAG, "→ HF: $cmd")
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (active) {
            val b = input.read()
            if (b == -1) return null
            val c = b.toChar()
            if (c == '\r' || c == '\n') {
                val s = buf.toString().trim()
                buf.clear()
                if (s.isNotEmpty()) return s
            } else {
                buf.append(c)
            }
        }
        return null
    }

    fun close() {
        active = false
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        private const val TAG = "ClientHandler"
    }
}
