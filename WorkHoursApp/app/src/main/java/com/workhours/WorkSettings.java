package com.workhours;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * 요일별 기본 출퇴근 시간 설정 (SharedPreferences 저장)
 * dayOfWeek: Calendar.MONDAY(2) ~ Calendar.FRIDAY(6)
 */
public class WorkSettings {

    private static final String PREF = "work_default_times";

    // ── 읽기 ────────────────────────────────────────────────

    // 기본 출근: 08:00 / 기본 퇴근: 17:00

    public static int getStartHour(Context ctx, int dayOfWeek) {
        return prefs(ctx).getInt("sh_" + dayOfWeek, 8);
    }
    public static int getStartMinute(Context ctx, int dayOfWeek) {
        return prefs(ctx).getInt("sm_" + dayOfWeek, 0);
    }
    public static int getEndHour(Context ctx, int dayOfWeek) {
        return prefs(ctx).getInt("eh_" + dayOfWeek, 17);
    }
    public static int getEndMinute(Context ctx, int dayOfWeek) {
        return prefs(ctx).getInt("em_" + dayOfWeek, 0);
    }

    // ── 쓰기 ────────────────────────────────────────────────

    public static void save(Context ctx, int dayOfWeek,
                            int startH, int startM, int endH, int endM) {
        prefs(ctx).edit()
                .putInt("sh_" + dayOfWeek, startH)
                .putInt("sm_" + dayOfWeek, startM)
                .putInt("eh_" + dayOfWeek, endH)
                .putInt("em_" + dayOfWeek, endM)
                .apply();
    }

    // ── 유틸 ────────────────────────────────────────────────

    /** 날짜 문자열("yyyy-MM-dd")의 요일(Calendar 상수) 반환 */
    public static int dayOfWeekFromDate(String date) {
        String[] p = date.split("-");
        Calendar cal = Calendar.getInstance();
        cal.set(Integer.parseInt(p[0]),
                Integer.parseInt(p[1]) - 1,
                Integer.parseInt(p[2]));
        return cal.get(Calendar.DAY_OF_WEEK);
    }

    /** 요일 한글 이름 */
    public static String dayName(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:    return "월요일";
            case Calendar.TUESDAY:   return "화요일";
            case Calendar.WEDNESDAY: return "수요일";
            case Calendar.THURSDAY:  return "목요일";
            case Calendar.FRIDAY:    return "금요일";
            case Calendar.SATURDAY:  return "토요일";
            case Calendar.SUNDAY:    return "일요일";
            default: return "";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
