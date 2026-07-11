package com.workhours;

/**
 * 휴가 기간 설정 (예: 2026-03 ~ 2027-02, 연 15일)
 */
public class VacationSettings {
    /** 시작 연월 "YYYY-MM" */
    public String startYearMonth;
    /** 종료 연월 "YYYY-MM" */
    public String endYearMonth;
    /** 1년 휴가 일수 */
    public int annualDays;

    public VacationSettings(String startYearMonth, String endYearMonth, int annualDays) {
        this.startYearMonth = startYearMonth;
        this.endYearMonth   = endYearMonth;
        this.annualDays     = annualDays;
    }
}
