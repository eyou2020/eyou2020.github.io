package com.workhours;

/**
 * 하루 근무 기록 데이터 모델
 * endHour == -1 → 퇴근 미입력(근무 진행 중) 상태
 */
public class WorkRecord {

    /** 휴가 유형 */
    public static final int LEAVE_NONE   = 0; // 일반 근무
    public static final int LEAVE_ANNUAL = 1; // 연차  (의무 -8h, 근무 0)
    public static final int LEAVE_HALF   = 2; // 반차  (의무 -4h, 근무 입력 가능)
    public static final int LEAVE_PUBLIC = 3; // 공가  (의무 -8h, 근무 0)
    public static final int LEAVE_REMOTE = 4; // 재택  (표시만, 별도 계산 없음)

    private String  date;
    private int     startHour;
    private int     startMinute;
    private int     endHour;        // -1 = 퇴근 미입력(진행 중)
    private int     endMinute;
    private int     customBreakMinutes; // -1 = 자동계산
    private int     leaveType;          // LEAVE_* 상수
    private boolean hasRecord;

    /** 새 기록 (기본값 09:00~18:00) */
    public WorkRecord(String date) {
        this.date               = date;
        this.startHour          = 9;
        this.startMinute        = 0;
        this.endHour            = 18;
        this.endMinute          = 0;
        this.customBreakMinutes = -1;
        this.leaveType          = LEAVE_NONE;
        this.hasRecord          = false;
    }

    /** DB에서 로드된 기록 */
    public WorkRecord(String date,
                      int startHour, int startMinute,
                      int endHour,   int endMinute,
                      int customBreakMinutes,
                      int leaveType) {
        this.date               = date;
        this.startHour          = startHour;
        this.startMinute        = startMinute;
        this.endHour            = endHour;
        this.endMinute          = endMinute;
        this.customBreakMinutes = customBreakMinutes;
        this.leaveType          = leaveType;
        this.hasRecord          = true;
    }

    // ── Getters / Setters ────────────────────────────────────

    public String  getDate()               { return date; }
    public int     getStartHour()          { return startHour; }
    public int     getStartMinute()        { return startMinute; }
    public int     getEndHour()            { return endHour; }
    public int     getEndMinute()          { return endMinute; }
    public int     getCustomBreakMinutes() { return customBreakMinutes; }
    public int     getLeaveType()          { return leaveType; }
    public boolean hasRecord()             { return hasRecord; }

    /** 퇴근 미입력(진행 중) 상태 여부 */
    public boolean isInProgress()         { return endHour < 0; }

    /** 호환성: 연차 여부 */
    public boolean isAnnualLeave()         { return leaveType == LEAVE_ANNUAL; }

    public void setStartTime(int h, int m)         { startHour = h; startMinute = m; hasRecord = true; }
    public void setEndTime(int h, int m)           { endHour   = h; endMinute   = m; hasRecord = true; }
    public void setCustomBreakMinutes(int minutes) { customBreakMinutes = minutes; }
    public void setLeaveType(int type)             { leaveType = type; hasRecord = true; }

    /** 기본값 사전 설정 (hasRecord는 false 유지 — 아직 저장 안 된 상태) */
    public void applyDefaults(int sh, int sm, int eh, int em) {
        startHour = sh; startMinute = sm;
        endHour   = eh; endMinute   = em;
    }

    // ── 시간 계산 ────────────────────────────────────────────

    /** 총 체류 시간(분). 퇴근 미입력 시 0 */
    public int getTotalRawMinutes() {
        if (endHour < 0) return 0;
        int diff = (endHour * 60 + endMinute) - (startHour * 60 + startMinute);
        return Math.max(0, diff);
    }

    /** 자동 계산 휴게시간(분): 4h30m 이상 → 30분 / 9h 이상 → 60분 */
    public int getAutoBreakMinutes() {
        int raw = getTotalRawMinutes();
        if (raw >= 540) return 60;
        if (raw >= 270) return 30;
        return 0;
    }

    /** 실제 사용 휴게시간 (custom ≥ 0이면 그 값, 아니면 자동) */
    public int getBreakMinutes() {
        return customBreakMinutes >= 0 ? customBreakMinutes : getAutoBreakMinutes();
    }

    /**
     * 실 근무시간(분)
     * 연차/공가: 0, 퇴근 미입력: 0 (getNetWorkMinutesAtNow 사용)
     */
    public int getNetWorkMinutes() {
        if (leaveType == LEAVE_ANNUAL || leaveType == LEAVE_PUBLIC) return 0;
        if (endHour < 0) return 0; // 진행 중
        return Math.max(0, getTotalRawMinutes() - getBreakMinutes());
    }

    /**
     * 퇴근 미입력 상태에서 현재 시각(nowH:nowM) 기준 실 근무시간(분) 계산
     */
    public int getNetWorkMinutesAtNow(int nowH, int nowM) {
        int raw = (nowH * 60 + nowM) - (startHour * 60 + startMinute);
        raw = Math.max(0, raw);
        int brk;
        if (customBreakMinutes >= 0) {
            brk = customBreakMinutes;
        } else {
            brk = raw >= 540 ? 60 : raw >= 270 ? 30 : 0;
        }
        return Math.max(0, raw - brk);
    }

    /**
     * 퇴근 미입력 상태에서 현재 시각(nowH:nowM:nowS) 기준 실 근무시간(초) 계산
     */
    public long getNetWorkSecondsAtNow(int nowH, int nowM, int nowS) {
        long rawSec = (nowH * 3600L + nowM * 60L + nowS)
                    - (startHour * 3600L + startMinute * 60L);
        rawSec = Math.max(0, rawSec);
        int rawMin = (int)(rawSec / 60);
        int brk; // 분 단위
        if (customBreakMinutes >= 0) {
            brk = customBreakMinutes;
        } else {
            brk = rawMin >= 540 ? 60 : rawMin >= 270 ? 30 : 0;
        }
        return Math.max(0, rawSec - brk * 60L);
    }

    /** 실 근무시간(분) — 평일 06:00-22:00 구간만 */
    public int getRegularWorkMinutes(boolean isHolidayOrWeekend) {
        if (leaveType == LEAVE_ANNUAL || leaveType == LEAVE_PUBLIC) return 0;
        if (endHour < 0) return 0;
        return getTimeSegmentsMinutes(isHolidayOrWeekend, endHour, endMinute, getBreakMinutes())[0];
    }

    /** 퇴근 미입력 시 현재 시각 기준 실 근무시간(초) — 평일 06:00-22:00 구간만 */
    public long getRegularWorkSecondsAtNow(boolean isHolidayOrWeekend, int nowH, int nowM, int nowS) {
        int rawMin = Math.max(0, (nowH * 60 + nowM) - (startHour * 60 + startMinute));
        int brk = customBreakMinutes >= 0 ? customBreakMinutes
                : (rawMin >= 540 ? 60 : rawMin >= 270 ? 30 : 0);
        int[] segs = getTimeSegmentsMinutes(isHolidayOrWeekend, nowH, nowM, brk);
        long regularSec = segs[0] * 60L;
        // 현재가 평일 주간(6-22시) 구간이면 초 추가 (부드러운 초 카운트)
        int nowMin = nowH * 60 + nowM;
        if (!isHolidayOrWeekend && nowMin >= 360 && nowMin < 1320 && segs[0] > 0) {
            regularSec += nowS;
        }
        return regularSec;
    }

    /**
     * 시간 구분별 근무시간(분) — 휴게시간 차감 후
     * [0]=근무(평일 6-22), [1]=평일야간(0-6,22-24), [2]=휴일주간(6-22), [3]=휴일야간(0-6,22-24)
     */
    public int[] getTimeSegmentsMinutes(boolean isHolidayOrWeekend, int endH, int endM, int breakMin) {
        if (leaveType == LEAVE_ANNUAL || leaveType == LEAVE_PUBLIC) return new int[]{0, 0, 0, 0};
        int startMin = startHour * 60 + startMinute;
        int endMin   = endH * 60 + endM;
        if (endMin <= startMin) return new int[]{0, 0, 0, 0};

        final int DAY_START = 360;  // 6:00
        final int DAY_END   = 1320; // 22:00

        int earlyNight = Math.max(0, Math.min(endMin, DAY_START) - startMin);
        int dayTime    = Math.max(0, Math.min(endMin, DAY_END) - Math.max(startMin, DAY_START));
        int lateNight  = Math.max(0, endMin - Math.max(startMin, DAY_END));
        int nightTime  = earlyNight + lateNight;

        int netDay = Math.max(0, dayTime - breakMin);
        int remainBrk = Math.max(0, breakMin - dayTime);
        int netNight  = Math.max(0, nightTime - remainBrk);

        return isHolidayOrWeekend
                ? new int[]{0, 0, netDay, netNight}
                : new int[]{netDay, netNight, 0, 0};
    }

    public String getStartTimeString() {
        return String.format("%02d:%02d", startHour, startMinute);
    }

    public String getEndTimeString() {
        if (endHour < 0) return "--:--";
        return String.format("%02d:%02d", endHour, endMinute);
    }
}
