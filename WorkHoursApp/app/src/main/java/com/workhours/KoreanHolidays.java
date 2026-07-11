package com.workhours;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 한국 공휴일 데이터 (2020~2030)
 * - 고정 공휴일 (매년 동일)
 * - 음력 공휴일: 설날, 추석, 부처님오신날
 * - 대체공휴일
 */
public class KoreanHolidays {

    /** key: "yyyy-MM-dd", value: 공휴일 이름 */
    private static final Map<String, String> MAP = new HashMap<>();

    static {
        // ── 고정 공휴일 (매년) ───────────────────────────────
        for (int y = 2020; y <= 2035; y++) {
            add(y, 1,  1,  "신정");
            add(y, 3,  1,  "삼일절");
            add(y, 5,  5,  "어린이날");
            add(y, 6,  6,  "현충일");
            if (y >= 2026) add(y, 7, 17, "제헌절"); // 2026년부터 법정공휴일 재적용
            add(y, 8,  15, "광복절");
            add(y, 10, 3,  "개천절");
            add(y, 10, 9,  "한글날");
            add(y, 12, 25, "크리스마스");
        }

        // ── 설날 연휴 (음력 1/1 전날·당일·다음날) ────────────
        setLunar("2020-01-24","설날연휴"); setLunar("2020-01-25","설날"); setLunar("2020-01-26","설날연휴");
        setLunar("2020-01-27","대체공휴일"); // 설날(토) 대체
        setLunar("2021-02-11","설날연휴"); setLunar("2021-02-12","설날"); setLunar("2021-02-13","설날연휴");
        setLunar("2021-02-15","대체공휴일"); // 설날연휴(일) 대체
        setLunar("2022-01-31","설날연휴"); setLunar("2022-02-01","설날"); setLunar("2022-02-02","설날연휴");
        setLunar("2023-01-21","설날연휴"); setLunar("2023-01-22","설날"); setLunar("2023-01-23","설날연휴");
        setLunar("2023-01-24","대체공휴일"); // 설날연휴(일) 대체
        setLunar("2024-02-09","설날연휴"); setLunar("2024-02-10","설날"); setLunar("2024-02-11","설날연휴");
        setLunar("2024-02-12","대체공휴일"); // 설날연휴(일) 대체
        setLunar("2025-01-28","설날연휴"); setLunar("2025-01-29","설날"); setLunar("2025-01-30","설날연휴");
        setLunar("2026-02-16","설날연휴"); setLunar("2026-02-17","설날"); setLunar("2026-02-18","설날연휴");
        // 2027 설날: 2/6(토)=전날, 2/7(일)=설날 → 2/8=다음날, 2/9=대체
        setLunar("2027-02-06","설날연휴"); setLunar("2027-02-07","설날"); setLunar("2027-02-08","설날연휴");
        setLunar("2027-02-09","대체공휴일"); // 설날(일)+전날(토) 대체
        // 2028 설날: 1/26(수)=전날, 1/27(목)=설날, 1/28(금)=다음날 → 주말 없음, 대체 없음
        setLunar("2028-01-26","설날연휴"); setLunar("2028-01-27","설날"); setLunar("2028-01-28","설날연휴");
        setLunar("2029-02-12","설날연휴"); setLunar("2029-02-13","설날"); setLunar("2029-02-14","설날연휴");
        setLunar("2029-02-15","대체공휴일"); // 설날연휴(일) 대체
        // 2030 설날: 2/2(토)=전날, 2/3(일)=설날 → 대체 1일
        setLunar("2030-02-02","설날연휴"); setLunar("2030-02-03","설날"); setLunar("2030-02-04","설날연휴");
        setLunar("2030-02-05","대체공휴일"); // 설날 전날(토)+설날(일) 대체

        // ── 부처님오신날 (음력 4/8) ───────────────────────────
        setLunar("2020-04-30","부처님오신날");
        setLunar("2021-05-19","부처님오신날");
        setLunar("2022-05-08","부처님오신날"); setLunar("2022-05-09","대체공휴일"); // 일요일 대체
        setLunar("2023-05-27","부처님오신날"); setLunar("2023-05-29","대체공휴일"); // 토요일 대체
        setLunar("2024-05-15","부처님오신날");
        setLunar("2025-05-05","부처님오신날"); // 어린이날 중복 → 5/6 대체
        setLunar("2025-05-06","대체공휴일");
        setLunar("2026-05-24","부처님오신날"); setLunar("2026-05-25","대체공휴일"); // 일요일 대체
        setLunar("2027-05-13","부처님오신날");
        setLunar("2028-05-05","부처님오신날"); // 어린이날(5/5)과 동일, 대체 없음(금요일)
        setLunar("2029-05-20","부처님오신날"); setLunar("2029-05-21","대체공휴일"); // 일요일 대체
        setLunar("2030-05-09","부처님오신날");

        // ── 추석 연휴 (음력 8/15 전날·당일·다음날) ───────────
        setLunar("2020-09-30","추석연휴"); setLunar("2020-10-01","추석"); setLunar("2020-10-02","추석연휴");
        setLunar("2020-10-05","대체공휴일"); // 추석(목)·개천절 연속, 대체
        setLunar("2021-09-20","추석연휴"); setLunar("2021-09-21","추석"); setLunar("2021-09-22","추석연휴");
        setLunar("2022-09-09","추석연휴"); setLunar("2022-09-10","추석"); setLunar("2022-09-11","추석연휴");
        setLunar("2022-09-12","대체공휴일"); // 추석연휴(일) 대체
        setLunar("2023-09-28","추석연휴"); setLunar("2023-09-29","추석"); setLunar("2023-09-30","추석연휴");
        setLunar("2023-10-02","대체공휴일"); // 추석연휴(토) 대체
        setLunar("2024-09-16","추석연휴"); setLunar("2024-09-17","추석"); setLunar("2024-09-18","추석연휴");
        setLunar("2025-10-05","추석연휴"); setLunar("2025-10-06","추석"); setLunar("2025-10-07","추석연휴");
        setLunar("2025-10-08","대체공휴일"); // 추석연휴(일) 대체
        setLunar("2026-09-24","추석연휴"); setLunar("2026-09-25","추석"); setLunar("2026-09-26","추석연휴");
        setLunar("2026-09-28","대체공휴일"); // 추석연휴(토) 대체
        setLunar("2027-09-14","추석연휴"); setLunar("2027-09-15","추석"); setLunar("2027-09-16","추석연휴");
        // 2028 추석: 10/2(월)~10/4(수), 개천절(10/3) 중복 — 모두 평일, 대체 없음
        setLunar("2028-10-02","추석연휴"); setLunar("2028-10-03","추석"); setLunar("2028-10-04","추석연휴");
        setLunar("2029-09-21","추석연휴"); setLunar("2029-09-22","추석"); setLunar("2029-09-23","추석연휴");
        setLunar("2030-09-10","추석연휴"); setLunar("2030-09-11","추석"); setLunar("2030-09-12","추석연휴");

        // ── 대체공휴일 (고정 공휴일이 주말인 경우) ────────────
        // 2021
        setLunar("2021-03-02","대체공휴일"); // 삼일절(일)
        // 2022
        setLunar("2022-06-07","대체공휴일"); // 현충일(일)
        // 2023
        setLunar("2023-05-29","대체공휴일"); // 부처님오신날(토) → 이미 위에 추가
        // 2024
        setLunar("2024-05-06","대체공휴일"); // 어린이날(일)
        // 2025
        setLunar("2025-03-03","대체공휴일"); // 삼일절(토)
        // 2026
        setLunar("2026-03-02","대체공휴일"); // 삼일절(일)
        setLunar("2026-06-03","선거일");     // 2026 지방선거 임시공휴일
        // 2026-06-08 현충일(토) 대체공휴일 없음
        setLunar("2026-08-17","대체공휴일"); // 광복절(토)
        setLunar("2026-10-05","대체공휴일"); // 개천절(토)
        // 2027
        setLunar("2027-06-07","대체공휴일"); // 현충일(일)
        setLunar("2027-07-19","대체공휴일"); // 제헌절(토)
        setLunar("2027-08-16","대체공휴일"); // 광복절(일)
        setLunar("2027-10-04","대체공휴일"); // 개천절(일)
        setLunar("2027-10-11","대체공휴일"); // 한글날(토)
        setLunar("2027-12-27","대체공휴일"); // 크리스마스(토) 대체
        // 2028: 설날 1/27(목) 평일, 대체 없음 / 추석+개천절 10/3(화) 평일, 대체 없음
        // 2029: 삼일절=3/1(목), 광복절=8/15(수) → 둘 다 평일, 대체 없음
        // 추석 9/22(토), 연휴 9/23(일) → 대체 2일
        setLunar("2029-09-24","대체공휴일"); // 추석(토) 대체
        setLunar("2029-09-25","대체공휴일"); // 추석연휴(일) 대체
        // 2030: 현충일=6/6(목), 한글날=10/9(수), 제헌절=7/17(수) → 모두 평일, 대체 없음
        // 2032
        setLunar("2032-07-19","대체공휴일"); // 제헌절(토)
        // 2033
        setLunar("2033-07-18","대체공휴일"); // 제헌절(일)
    }

    /** 고정 공휴일 추가 (중복 시 합산 표시) */
    private static void add(int year, int month, int day, String name) {
        String key = String.format("%04d-%02d-%02d", year, month, day);
        merge(key, name);
    }

    /** 음력/대체 공휴일 추가 (중복 시 합산 표시) */
    private static void setLunar(String date, String name) {
        merge(date, name);
    }

    private static void merge(String key, String name) {
        String existing = MAP.get(key);
        if (existing == null) {
            MAP.put(key, name);
        } else if (!existing.contains(name)) {
            MAP.put(key, existing + "·" + name);
        }
    }

    // ─── 공개 API ────────────────────────────────────────────

    /**
     * 날짜(yyyy-MM-dd)에 해당하는 공휴일 이름 반환.
     * 공휴일이 아니면 null.
     */
    public static String getHolidayName(String date) {
        return MAP.get(date);
    }

    /**
     * 해당 월의 공휴일 Map 반환 (key: day 정수, value: 이름)
     */
    public static Map<Integer, String> getHolidaysForMonth(int year, int month) {
        Map<Integer, String> result = new HashMap<>();
        String prefix = String.format("%04d-%02d-", year, month);
        for (Map.Entry<String, String> entry : MAP.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                int day = Integer.parseInt(entry.getKey().substring(8));
                result.put(day, entry.getValue());
            }
        }
        return result;
    }

    /**
     * 해당 월의 실제 근무일 수 (월~금 중 공휴일 제외)
     */
    public static int countWorkdays(int year, int month) {
        Map<Integer, String> holidays = getHolidaysForMonth(year, month);
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int count = 0;
        for (int d = 1; d <= maxDay; d++) {
            cal.set(year, month - 1, d);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            // 월(2)~금(6) 이면서 공휴일 아닌 날
            if (dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY
                    && !holidays.containsKey(d)) {
                count++;
            }
        }
        return count;
    }
}
