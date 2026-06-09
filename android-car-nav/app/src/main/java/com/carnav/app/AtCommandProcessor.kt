package com.carnav.app

import android.util.Log

/**
 * HFP(Hands-Free Profile) AT 커맨드 처리기.
 *
 * 연결 폰이 AG(Audio Gateway)로 접속하면 이 시퀀스로 SLC를 맺는다:
 *   1. AT+BRSF  → 지원 기능 협상
 *   2. AT+CIND=? → 표시기 목록 조회
 *   3. AT+CIND?  → 현재 표시기 값 조회
 *   4. AT+CMER  → 이벤트 리포팅 설정
 *   (이후 통화, 볼륨 등 추가 커맨드)
 *
 * 연결된 폰은 이 기기를 차량 핸즈프리 유닛으로 인식한다.
 */
object AtCommandProcessor {

    private const val TAG = "AtCmdProc"

    // HF(차량) 지원 기능 비트맵 — Enhanced Call Status(bit5) 만 선언
    private const val HF_FEATURES = 32

    // 차량이 제공하는 표시기 목록 (HFP 규격)
    private const val CIND_LIST =
        "(\"call\",(0,1))," +
        "(\"callsetup\",(0-3))," +
        "(\"service\",(0,1))," +
        "(\"signal\",(0-5))," +
        "(\"roam\",(0,1))," +
        "(\"battchg\",(0-5))," +
        "(\"callheld\",(0,2))"

    // 현재 표시기 값: 통화 없음, 서비스 있음, 신호 5, 배터리 5
    private const val CIND_VALUES = "0,0,1,5,0,5,0"

    fun process(rawCmd: String): String {
        val cmd = rawCmd.trim().uppercase()

        return when {
            // ── SLC 수립 시퀀스 ────────────────────────────────────────────────

            // 1) 지원 기능 협상
            cmd.startsWith("AT+BRSF") ->
                "\r\n+BRSF: $HF_FEATURES\r\nOK\r\n"

            // 2a) 표시기 목록
            cmd.startsWith("AT+CIND=?") ->
                "\r\n+CIND: $CIND_LIST\r\nOK\r\n"

            // 2b) 현재 표시기 값
            cmd.startsWith("AT+CIND?") ->
                "\r\n+CIND: $CIND_VALUES\r\nOK\r\n"

            // 3) 이벤트 리포팅 활성화
            cmd.startsWith("AT+CMER") ->
                "\r\nOK\r\n"

            // ── 통화 관리 ──────────────────────────────────────────────────────

            cmd.startsWith("AT+CHLD=?") ->
                "\r\n+CHLD: (0,1,1x,2,2x,3,4)\r\nOK\r\n"

            cmd.startsWith("AT+CHLD") ->
                "\r\nOK\r\n"

            // 수신 통화 응답
            cmd.startsWith("ATA") ->
                "\r\nOK\r\n"

            // 통화 종료/거부
            cmd.startsWith("ATH") ->
                "\r\nOK\r\n"

            // 전화 걸기 (차량에서는 미지원 → OK만 반환)
            cmd.startsWith("ATD") ->
                "\r\nOK\r\n"

            // 현재 통화 목록 (통화 없음)
            cmd.startsWith("AT+CLCC") ->
                "\r\nOK\r\n"

            // ── HF 표시기 (Android 확장) ───────────────────────────────────────

            cmd.startsWith("AT+BIND=?") ->
                "\r\n+BIND: (1,2)\r\nOK\r\n"

            cmd.startsWith("AT+BIND?") ->
                "\r\n+BIND: 1,1\r\n+BIND: 2,1\r\nOK\r\n"

            cmd.startsWith("AT+BIND") ->
                "\r\nOK\r\n"

            // HF 표시기 값 업데이트 수신
            cmd.startsWith("AT+BIEV") ->
                "\r\nOK\r\n"

            // BT 표시기 활성화 설정
            cmd.startsWith("AT+BIA") ->
                "\r\nOK\r\n"

            // ── 오디오 / 코덱 ──────────────────────────────────────────────────

            // 코덱 협상 (SBC/mSBC)
            cmd.startsWith("AT+BAC") ->
                "\r\nOK\r\n"

            // 코덱 연결
            cmd.startsWith("AT+BCC") ->
                "\r\nOK\r\n"

            // 코덱 선택 확인
            cmd.startsWith("AT+BCS") ->
                "\r\nOK\r\n"

            // 에코 제거 / 노이즈 억제 비활성화 요청
            cmd.startsWith("AT+NREC") ->
                "\r\nOK\r\n"

            // 스피커 볼륨
            cmd.startsWith("AT+VGS") ->
                "\r\nOK\r\n"

            // 마이크 볼륨
            cmd.startsWith("AT+VGM") ->
                "\r\nOK\r\n"

            // ── 정보 조회 ──────────────────────────────────────────────────────

            // 발신자 번호 표시
            cmd.startsWith("AT+CLIP") ->
                "\r\nOK\r\n"

            // 통화 대기 알림
            cmd.startsWith("AT+CCWA") ->
                "\r\nOK\r\n"

            // 확장 오류 코드
            cmd.startsWith("AT+CMEE") ->
                "\r\nOK\r\n"

            // 음성 인식
            cmd.startsWith("AT+BVRA") ->
                "\r\nOK\r\n"

            // 통신사 이름 (차량 네비로 표시)
            cmd.startsWith("AT+COPS") ->
                "\r\n+COPS: 0,0,\"CarNav\"\r\nOK\r\n"

            // 가입자 번호
            cmd.startsWith("AT+CNUM") ->
                "\r\nOK\r\n"

            // ── 기타 AT ────────────────────────────────────────────────────────

            cmd.startsWith("AT") -> {
                Log.d(TAG, "Unhandled AT: $rawCmd → OK")
                "\r\nOK\r\n"
            }

            else -> {
                Log.w(TAG, "Non-AT: $rawCmd")
                "\r\nOK\r\n"
            }
        }
    }
}
