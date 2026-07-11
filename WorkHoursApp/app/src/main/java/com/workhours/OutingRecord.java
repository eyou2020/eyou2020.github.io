package com.workhours;

public class OutingRecord {
    public long id;
    public String date;
    public int startSec;  // 자정 기준 초
    public int endSec;    // -1 = 진행 중

    public OutingRecord(long id, String date, int startSec, int endSec) {
        this.id = id;
        this.date = date;
        this.startSec = startSec;
        this.endSec = endSec;
    }

    public boolean isOngoing() { return endSec < 0; }

    public int durationSec() {
        return (endSec >= 0) ? Math.max(0, endSec - startSec) : 0;
    }

    public String startTimeStr() {
        return String.format("%02d:%02d:%02d", startSec / 3600, (startSec % 3600) / 60, startSec % 60);
    }

    public String endTimeStr() {
        if (endSec < 0) return "--:--:--";
        return String.format("%02d:%02d:%02d", endSec / 3600, (endSec % 3600) / 60, endSec % 60);
    }

    public String durationStr() {
        int d = durationSec();
        return d / 60 + "분 " + String.format("%02d", d % 60) + "초";
    }
}
