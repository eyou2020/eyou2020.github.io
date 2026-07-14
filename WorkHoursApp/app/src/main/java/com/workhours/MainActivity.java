package com.workhours;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private TextView tvDuty;
    private TextView tvWork;
    private TextView tvPlan;
    private TextView tvShortage;
    private TextView tvWorkDays;
    private TextView tvRemoteDays;
    private TextView tvRemoteRatio;
    private TextView tvRemoteHours;
    private TextView tvWeekdayNight;
    private TextView tvHolidayDay;
    private TextView tvHolidayNight;
    private TextView btnSetTarget;
    private TextView btnWorkSettings;
    private TextView btnVacationSettings;
    private TextView btnVacationQuery;

    private TextView    tvMonthYear;
    private GridView    gridCalendar;
    private ImageButton btnPrevMonth;
    private ImageButton btnNextMonth;
    private TextView    btnBulkApply;
    private TextView    btnReset;

    private Calendar        currentCalendar;
    private DatabaseHelper  dbHelper;
    private CalendarAdapter calendarAdapter;

    // 오늘 진행 중 기록이 있을 때 1초마다 요약+달력 갱신
    private Handler  minuteHandler;
    private Runnable minuteRunnable;
    private boolean  hasInProgressToday = false;

    // loadCalendar() 결과 캐시 (매초 DB 접근 방지)
    private Map<String, WorkRecord> cachedRecords;
    private Map<Integer, String>    cachedHolidays;
    private String cachedYearMonth;
    private int    cachedYear, cachedMonth;

    public static final String EXTRA_DATE = "extra_date";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_main), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, insets.top, 0, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
        dbHelper        = new DatabaseHelper(this);
        currentCalendar = Calendar.getInstance();
        initViews();
        setupListeners();
        loadCalendar();

        // 앱 첫 실행 시 오늘 날짜 상세 화면을 자동으로 열기
        if (savedInstanceState == null) {
            Calendar today = Calendar.getInstance();
            String todayDate = String.format("%04d-%02d-%02d",
                    today.get(Calendar.YEAR),
                    today.get(Calendar.MONTH) + 1,
                    today.get(Calendar.DAY_OF_MONTH));
            Intent intent = new Intent(this, DayDetailActivity.class);
            intent.putExtra(EXTRA_DATE, todayDate);
            startActivity(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCalendar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMinuteTimer();
    }

    private void startMinuteTimer() {
        stopMinuteTimer();
        minuteHandler  = new Handler(Looper.getMainLooper());
        minuteRunnable = new Runnable() {
            @Override public void run() {
                refreshSummaryOnly();
                // 여전히 진행 중일 때만 다음 틱 예약
                if (hasInProgressToday) {
                    minuteHandler.postDelayed(this, 1_000L);
                }
            }
        };
        minuteHandler.postDelayed(minuteRunnable, 1_000L);
    }

    private void stopMinuteTimer() {
        if (minuteHandler != null && minuteRunnable != null) {
            minuteHandler.removeCallbacks(minuteRunnable);
        }
    }

    /** 캘린더 그리드 셀 + 요약 카드를 모두 갱신 (DB 재접근 없이 캐시 사용) */
    private void refreshSummaryOnly() {
        if (cachedRecords == null) return;
        updateSummary(cachedYearMonth, cachedYear, cachedMonth, cachedRecords, cachedHolidays);
        if (calendarAdapter != null) calendarAdapter.notifyDataSetChanged();
    }

    private void initViews() {
        tvMonthYear         = findViewById(R.id.tv_month_year);
        tvDuty              = findViewById(R.id.tv_duty);
        tvWork              = findViewById(R.id.tv_work);
        tvPlan              = findViewById(R.id.tv_plan);
        tvShortage          = findViewById(R.id.tv_shortage);
        tvWorkDays          = findViewById(R.id.tv_work_days);
        tvRemoteDays        = findViewById(R.id.tv_remote_days);
        tvRemoteRatio       = findViewById(R.id.tv_remote_ratio);
        tvRemoteHours       = findViewById(R.id.tv_remote_hours);
        tvWeekdayNight      = findViewById(R.id.tv_weekday_night);
        tvHolidayDay        = findViewById(R.id.tv_holiday_day);
        tvHolidayNight      = findViewById(R.id.tv_holiday_night);
        btnSetTarget        = findViewById(R.id.btn_set_target);
        btnWorkSettings     = findViewById(R.id.btn_work_settings);
        btnVacationSettings = findViewById(R.id.btn_vacation_settings);
        btnVacationQuery    = findViewById(R.id.btn_vacation_query);
        gridCalendar        = findViewById(R.id.grid_calendar);
        btnPrevMonth        = findViewById(R.id.btn_prev_month);
        btnNextMonth        = findViewById(R.id.btn_next_month);
        btnBulkApply        = findViewById(R.id.btn_bulk_apply);
        btnReset            = findViewById(R.id.btn_reset);
    }

    private void setupListeners() {
        btnPrevMonth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentCalendar.add(Calendar.MONTH, -1); loadCalendar();
            }
        });
        btnNextMonth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentCalendar.add(Calendar.MONTH, 1); loadCalendar();
            }
        });
        btnSetTarget.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSetTargetDialog(); }
        });
        btnWorkSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, WorkSettingsActivity.class));
            }
        });
        btnVacationSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VacationSettingsActivity.class));
            }
        });
        btnVacationQuery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VacationQueryActivity.class));
            }
        });
        btnBulkApply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBulkApplyDialog(); }
        });
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showResetDialog(); }
        });

        gridCalendar.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                String dayStr = (String) calendarAdapter.getItem(pos);
                if (dayStr == null || dayStr.isEmpty()) return;
                int dayNum = Integer.parseInt(dayStr);
                calendarAdapter.setSelectedDay(dayNum);
                String date = String.format("%s-%02d", getYearMonth(), dayNum);
                Intent intent = new Intent(MainActivity.this, DayDetailActivity.class);
                intent.putExtra(EXTRA_DATE, date);
                startActivity(intent);
            }
        });

        final GestureDetector swipeDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_MIN_DISTANCE = 80;
                    private static final int SWIPE_MIN_VELOCITY = 80;

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                          float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float diffX = e2.getX() - e1.getX();
                        float diffY = e2.getY() - e1.getY();
                        if (Math.abs(diffX) > Math.abs(diffY)
                                && Math.abs(diffX) > SWIPE_MIN_DISTANCE
                                && Math.abs(velocityX) > SWIPE_MIN_VELOCITY) {
                            if (diffX < 0) currentCalendar.add(Calendar.MONTH, 1);
                            else           currentCalendar.add(Calendar.MONTH, -1);
                            loadCalendar();
                            return true;
                        }
                        return false;
                    }
                });

        gridCalendar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                swipeDetector.onTouchEvent(event);
                return false;
            }
        });

        gridCalendar.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                String dayStr = (String) calendarAdapter.getItem(pos);
                if (dayStr == null || dayStr.isEmpty()) return false;

                int dayNum = Integer.parseInt(dayStr);
                final String date = String.format("%s-%02d", getYearMonth(), dayNum);
                final WorkRecord record = dbHelper.getWorkRecord(date);

                if (record == null) {
                    Toast.makeText(MainActivity.this, "근무 기록이 없습니다.", Toast.LENGTH_SHORT).show();
                    return true;
                }

                String[] p = date.split("-");
                String label = p[0] + "년 " + Integer.parseInt(p[1]) + "월 " + Integer.parseInt(p[2]) + "일";
                String info;
                switch (record.getLeaveType()) {
                    case WorkRecord.LEAVE_ANNUAL: info = "연차"; break;
                    case WorkRecord.LEAVE_HALF:
                        info = "반차  출근 " + record.getStartTimeString()
                             + "  퇴근 " + record.getEndTimeString()
                             + (record.isInProgress() ? "\n근무 진행 중"
                               : "\n실 근무: " + fmtMin(record.getNetWorkMinutes())); break;
                    case WorkRecord.LEAVE_PUBLIC: info = "공가"; break;
                    default:
                        info = "출근 " + record.getStartTimeString()
                             + "  퇴근 " + record.getEndTimeString()
                             + (record.isInProgress() ? "\n근무 진행 중"
                               : "\n실 근무: " + fmtMin(record.getNetWorkMinutes())); break;
                }

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("근무 기록 삭제")
                        .setMessage(label + " 기록을 삭제하시겠습니까?\n\n" + info)
                        .setPositiveButton("예", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                dbHelper.deleteWorkRecord(date);
                                loadCalendar();
                                Toast.makeText(MainActivity.this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("아니오", null)
                        .show();
                return true;
            }
        });
    }

    private void loadCalendar() {
        int year  = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH) + 1;
        String yearMonth = getYearMonth();

        tvMonthYear.setText(year + "년 " + month + "월");

        Map<String, WorkRecord> records  = dbHelper.getMonthRecords(yearMonth);
        Map<Integer, String>    holidays = KoreanHolidays.getHolidaysForMonth(year, month);
        // 캐시 갱신 (1초 타이머에서 DB 재접근 없이 사용)
        cachedRecords   = records;
        cachedHolidays  = holidays;
        cachedYearMonth = yearMonth;
        cachedYear      = year;
        cachedMonth     = month;
        List<String>            days     = buildDayList();

        Calendar todayCal = Calendar.getInstance();
        int todayDay = -1;
        if (todayCal.get(Calendar.YEAR)  == year
         && todayCal.get(Calendar.MONTH) == month - 1) {
            todayDay = todayCal.get(Calendar.DAY_OF_MONTH);
        }

        calendarAdapter = new CalendarAdapter(this, days, yearMonth, records, holidays, todayDay);
        gridCalendar.setAdapter(calendarAdapter);
        updateSummary(yearMonth, year, month, records, holidays);

        // 오늘 진행 중 기록이 있으면 1분 타이머 시작
        if (hasInProgressToday) startMinuteTimer();
        else stopMinuteTimer();

        // GridView를 빈틈 없이 채우도록 행 높이를 레이아웃 완료 후 계산
        gridCalendar.post(new Runnable() {
            @Override public void run() {
                int rows = calendarAdapter.getCount() / 7;
                int h    = gridCalendar.getHeight();
                if (rows > 0 && h > 0) {
                    calendarAdapter.setRowHeight(h / rows);
                    calendarAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private List<String> buildDayList() {
        List<String> days = new ArrayList<>();
        Calendar cal = (Calendar) currentCalendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1;
        int maxDay   = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int i = 0; i < firstDow; i++) days.add("");
        for (int d = 1; d <= maxDay; d++) days.add(String.valueOf(d));
        while (days.size() % 7 != 0) days.add("");
        return days;
    }

    private void updateSummary(String yearMonth, int year, int month,
                               Map<String, WorkRecord> allRecords,
                               Map<Integer, String> holidays) {

        Calendar today     = Calendar.getInstance();
        int todayYear  = today.get(Calendar.YEAR);
        int todayMonth = today.get(Calendar.MONTH) + 1;
        int todayDay   = today.get(Calendar.DAY_OF_MONTH);
        int todayH     = today.get(Calendar.HOUR_OF_DAY);
        int todayM     = today.get(Calendar.MINUTE);
        int todayS     = today.get(Calendar.SECOND);

        // 오늘 진행 중 기록 여부 (요약 1분 갱신 판단에 사용)
        hasInProgressToday = false;

        // ── 휴가 유형별 날짜 수집 ───────────────────────────────
        Set<Integer> annualDays = new HashSet<>();
        Set<Integer> halfDays   = new HashSet<>();
        Set<Integer> publicDays = new HashSet<>();
        Set<Integer> remoteDays = new HashSet<>();
        for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
            int d = Integer.parseInt(e.getKey().split("-")[2]);
            switch (e.getValue().getLeaveType()) {
                case WorkRecord.LEAVE_ANNUAL: annualDays.add(d); break;
                case WorkRecord.LEAVE_HALF:   halfDays.add(d);   break;
                case WorkRecord.LEAVE_PUBLIC: publicDays.add(d); break;
                case WorkRecord.LEAVE_REMOTE: remoteDays.add(d); break;
            }
        }

        // ── 의무 계산 ────────────────────────────────────────────
        int dutyMinutes = computeDutyMinutes(year, month, yearMonth, holidays,
                annualDays, halfDays, publicDays);

        // ── 근무: 1일~오늘 실제 기록 합산 (평일 06-22시 구간만, 초 단위 정밀도) ──
        long actualWorkSec = 0;
        for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
            WorkRecord r = e.getValue();
            String dateStr = e.getKey();
            int d = Integer.parseInt(dateStr.split("-")[2]);
            boolean isHoW = isHolidayOrWeekendDate(dateStr, holidays);
            boolean include;
            if (year < todayYear || (year == todayYear && month < todayMonth)) {
                include = true;
            } else if (year == todayYear && month == todayMonth) {
                include = (d <= todayDay);
            } else {
                include = false;
            }
            if (include) {
                if (r.isInProgress()
                        && d == todayDay
                        && year == todayYear && month == todayMonth) {
                    actualWorkSec += r.getRegularWorkSecondsAtNow(isHoW, todayH, todayM, todayS);
                    hasInProgressToday = true;
                } else {
                    actualWorkSec += r.getRegularWorkMinutes(isHoW) * 60L;
                }
            }
        }

        // ── 계획: 내일~말일 기록된 근무시간 (평일 06-22시 구간만) ──────
        long plannedWorkSec = 0;
        int futureFrom;
        if (year < todayYear || (year == todayYear && month < todayMonth)) {
            futureFrom = Integer.MAX_VALUE;
        } else if (year == todayYear && month == todayMonth) {
            futureFrom = todayDay + 1;
        } else {
            futureFrom = 1;
        }
        if (futureFrom != Integer.MAX_VALUE) {
            for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
                WorkRecord r = e.getValue();
                String dateStr = e.getKey();
                int d = Integer.parseInt(dateStr.split("-")[2]);
                boolean isHoW = isHolidayOrWeekendDate(dateStr, holidays);
                if (d >= futureFrom) plannedWorkSec += r.getRegularWorkMinutes(isHoW) * 60L;
            }
        }

        long dutySec     = dutyMinutes * 60L;
        long shortageSec = dutySec - actualWorkSec - plannedWorkSec;

        tvDuty.setText("의무: " + fmtSec(dutySec));
        tvWork.setText("근무: " + fmtSec(actualWorkSec));
        tvPlan.setText("계획: " + fmtSec(plannedWorkSec));

        if (shortageSec <= 0) {
            tvShortage.setText("여유 : +" + fmtSec(-shortageSec));
            tvShortage.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            tvShortage.setText("부족 : -" + fmtSec(shortageSec));
            tvShortage.setTextColor(Color.parseColor("#C62828"));
        }

        // ── 시간대별 합산 (평일야간, 휴일주간, 휴일야간) ──────────
        long weekdayNightSec = 0, holidayDaySec = 0, holidayNightSec = 0;
        for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
            WorkRecord r = e.getValue();
            String dateStr = e.getKey();
            int d = Integer.parseInt(dateStr.split("-")[2]);
            boolean isHoW = isHolidayOrWeekendDate(dateStr, holidays);
            int[] segs;
            if (r.isInProgress() && d == todayDay && year == todayYear && month == todayMonth) {
                int rawMin = Math.max(0, (todayH * 60 + todayM) - (r.getStartHour() * 60 + r.getStartMinute()));
                int brk = r.getCustomBreakMinutes() >= 0 ? r.getCustomBreakMinutes()
                        : (rawMin >= 540 ? 60 : rawMin >= 270 ? 30 : 0);
                segs = r.getTimeSegmentsMinutes(isHoW, todayH, todayM, brk);
            } else if (!r.isInProgress()) {
                segs = r.getTimeSegmentsMinutes(isHoW, r.getEndHour(), r.getEndMinute(), r.getBreakMinutes());
            } else {
                continue;
            }
            weekdayNightSec += segs[1] * 60L;
            holidayDaySec   += segs[2] * 60L;
            holidayNightSec += segs[3] * 60L;
        }
        tvWeekdayNight.setText("평일야간: " + fmtSec(weekdayNightSec));
        tvHolidayDay.setText("휴일주간: " + fmtSec(holidayDaySec));
        tvHolidayNight.setText("휴일야간: " + fmtSec(holidayNightSec));

        // ── 재택 통계 ────────────────────────────────────────────
        // 근무일수: 근무(NONE) + 반차(HALF) + 재택(REMOTE) 기록이 있는 날
        int workDays = 0;
        for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
            int lt = e.getValue().getLeaveType();
            if (lt == WorkRecord.LEAVE_NONE
                    || lt == WorkRecord.LEAVE_HALF
                    || lt == WorkRecord.LEAVE_REMOTE) {
                workDays++;
            }
        }
        int remoteDayCount = remoteDays.size();
        int remoteRatio    = workDays > 0 ? Math.round(remoteDayCount * 100f / workDays) : 0;

        int remoteWorkMin = 0;
        for (Map.Entry<String, WorkRecord> e : allRecords.entrySet()) {
            if (e.getValue().getLeaveType() == WorkRecord.LEAVE_REMOTE) {
                remoteWorkMin += e.getValue().getNetWorkMinutes();
            }
        }

        tvWorkDays.setText("근무일수: " + workDays + "일");
        tvRemoteDays.setText("재택일수: " + remoteDayCount + "일");
        tvRemoteRatio.setText("재택비율: " + remoteRatio + "%");
        tvRemoteHours.setText("재택시간: " + fmtMin(remoteWorkMin));
    }

    /** 날짜 문자열(YYYY-MM-DD)이 공휴일 또는 주말인지 확인 */
    private boolean isHolidayOrWeekendDate(String date, Map<Integer, String> holidays) {
        String[] parts = date.split("-");
        int day = Integer.parseInt(parts[2]);
        if (holidays.containsKey(day)) return true;
        Calendar cal = Calendar.getInstance();
        cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, day);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        return (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
    }

    /**
     * 의무 시간 계산 (분 단위)
     * 연차/공가: 8h 차감, 반차: 4h 차감
     */
    private int computeDutyMinutes(int year, int month, String yearMonth,
                                   Map<Integer, String> holidays,
                                   Set<Integer> annualDays,
                                   Set<Integer> halfDays,
                                   Set<Integer> publicDays) {
        // 수동 설정 우선 적용
        int saved = dbHelper.getMonthlyTarget(yearMonth);
        if (saved > 0) {
            int deduct = annualDays.size() * 480
                       + halfDays.size()   * 240
                       + publicDays.size() * 480;
            return Math.max(0, saved - deduct);
        }

        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        int base;
        if (!holidays.isEmpty()) {
            // 공휴일 있는 달: 평일 × 8h (연차·공가·반차 날 제외)
            int count = 0;
            for (int d = 1; d <= maxDay; d++) {
                if (holidays.containsKey(d)) continue;
                if (annualDays.contains(d) || publicDays.contains(d)) continue;
                if (halfDays.contains(d)) continue; // 반차도 평일 카운트에서 제외 (별도 차감)
                cal.set(year, month - 1, d);
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                if (dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY) count++;
            }
            base = count * 480;
            // 반차: 평일 카운트에서 뺐으므로, 4h만 다시 추가
            base += halfDays.size() * 240;
        } else {
            // 공휴일 없는 달: floor(일수 × 40/7) 시간 기준
            base = (maxDay * 40 / 7) * 60;
            base -= annualDays.size()  * 480;
            base -= halfDays.size()    * 240;
            base -= publicDays.size()  * 480;
        }
        return Math.max(0, base);
    }

    private void showSetTargetDialog() {
        final String yearMonth = getYearMonth();
        int year  = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH) + 1;

        Map<Integer, String> holidays   = KoreanHolidays.getHolidaysForMonth(year, month);
        Map<String, WorkRecord> records = dbHelper.getMonthRecords(yearMonth);
        Set<Integer> annualDays  = new HashSet<>();
        Set<Integer> halfDays    = new HashSet<>();
        Set<Integer> publicDays  = new HashSet<>();
        for (Map.Entry<String, WorkRecord> e : records.entrySet()) {
            int d = Integer.parseInt(e.getKey().split("-")[2]);
            switch (e.getValue().getLeaveType()) {
                case WorkRecord.LEAVE_ANNUAL: annualDays.add(d); break;
                case WorkRecord.LEAVE_HALF:   halfDays.add(d);   break;
                case WorkRecord.LEAVE_PUBLIC: publicDays.add(d); break;
            }
        }
        int currentDuty = computeDutyMinutes(year, month, yearMonth, holidays,
                annualDays, halfDays, publicDays);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_set_target, null);
        final NumberPicker npHour   = dialogView.findViewById(R.id.np_target_hour);
        final NumberPicker npMinute = dialogView.findViewById(R.id.np_target_minute);

        npHour.setMinValue(0); npHour.setMaxValue(300);
        npMinute.setMinValue(0); npMinute.setMaxValue(59);
        npHour.setValue(currentDuty / 60);
        npMinute.setValue(currentDuty % 60);

        new AlertDialog.Builder(this)
                .setTitle(year + "년 " + month + "월 의무 시간 수동 설정")
                .setView(dialogView)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int min = npHour.getValue() * 60 + npMinute.getValue();
                        if (min > 0) {
                            dbHelper.saveMonthlyTarget(yearMonth, min);
                            loadCalendar();
                            Toast.makeText(MainActivity.this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("자동 계산으로 초기화", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        dbHelper.saveMonthlyTarget(yearMonth, 0);
                        loadCalendar();
                        Toast.makeText(MainActivity.this, "자동 계산값으로 초기화되었습니다.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showBulkApplyDialog() {
        int year  = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH) + 1;
        final String yearMonth = getYearMonth();
        final Map<Integer, String> holidays = KoreanHolidays.getHolidaysForMonth(year, month);

        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1);
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int targetCount = 0;
        for (int d = 1; d <= maxDay; d++) {
            if (holidays.containsKey(d)) continue;
            cal.set(year, month - 1, d);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            if (dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY) targetCount++;
        }

        final int finalYear = year, finalMonth = month, finalMax = maxDay;
        final int count = targetCount;

        new AlertDialog.Builder(this)
                .setTitle("전체 반영")
                .setMessage(year + "년 " + month + "월 평일 " + count + "일에\n"
                        + "근무 설정 초기값을 일괄 적용합니다.\n\n"
                        + "※ 기존 기록이 있는 날도 덮어씁니다.")
                .setPositiveButton("반영", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyBulkSettings(finalYear, finalMonth, finalMax, yearMonth, holidays);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void applyBulkSettings(int year, int month, int maxDay,
                                    String yearMonth, Map<Integer, String> holidays) {
        Calendar cal = Calendar.getInstance();
        int saved = 0;
        for (int d = 1; d <= maxDay; d++) {
            if (holidays.containsKey(d)) continue;
            cal.set(year, month - 1, d);
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            if (dow < Calendar.MONDAY || dow > Calendar.FRIDAY) continue;

            String date = String.format("%s-%02d", yearMonth, d);
            int sh = WorkSettings.getStartHour(this, dow);
            int sm = WorkSettings.getStartMinute(this, dow);
            int eh = WorkSettings.getEndHour(this, dow);
            int em = WorkSettings.getEndMinute(this, dow);

            WorkRecord record = new WorkRecord(date, sh, sm, eh, em, -1, WorkRecord.LEAVE_NONE);
            dbHelper.saveWorkRecord(record);
            saved++;
        }
        loadCalendar();
        Toast.makeText(this, saved + "일 반영되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private void showResetDialog() {
        int year  = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH) + 1;
        final String yearMonth = getYearMonth();

        new AlertDialog.Builder(this)
                .setTitle("전체 초기화")
                .setMessage(year + "년 " + month + "월의\n"
                        + "· 근무 기록 전체\n"
                        + "· 의무 시간 수동 설정\n\n"
                        + "을 모두 삭제하시겠습니까?")
                .setPositiveButton("초기화", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        dbHelper.deleteMonthRecords(yearMonth);
                        dbHelper.deleteMonthlyTarget(yearMonth);
                        loadCalendar();
                        Toast.makeText(MainActivity.this, "초기화되었습니다.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private String fmtMin(int minutes) {
        return fmtSec(minutes * 60L);
    }

    private String fmtSec(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h + "시간 " + m + "분 " + s + "초";
    }

    private String getYearMonth() {
        return String.format("%04d-%02d",
                currentCalendar.get(Calendar.YEAR),
                currentCalendar.get(Calendar.MONTH) + 1);
    }
}
