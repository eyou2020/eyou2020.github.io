package com.workhours;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DayDetailActivity extends AppCompatActivity {

    private TextView     tvDate;
    private LinearLayout layoutTimeCard;
    private LinearLayout layoutCalcCard;
    private TextView     tvAnnualNote;

    private TextView tvStartTime;
    private TextView tvEndTime;
    private TextView tvRawWork;
    private EditText etBreakMinutes;
    private TextView tvNetWork;
    private Button   btnEditStart;
    private Button   btnEditEnd;
    private Button   btnCheckIn;
    private Button   btnCheckOut;
    private Button   btnAnnualLeave;
    private Button   btnHalfLeave;
    private Button   btnPublicLeave;
    private Button   btnRemoteWork;
    private Button       btnSave;
    private Button       btnDelete;
    private android.widget.ImageButton btnClose;

    private DatabaseHelper dbHelper;
    private String     date;
    private WorkRecord record;
    private int        leaveType     = WorkRecord.LEAVE_NONE;
    private boolean    updatingBreak = false;

    // ── 출근 타이머 ──────────────────────────────────────────
    private boolean  isToday        = false;
    private boolean  isTimerRunning = false;
    private long     checkInMillis  = 0L;
    private Handler  timerHandler;
    private Runnable timerRunnable;

    // ── 시간 구분 / 외출 ──────────────────────────────────────
    private boolean  isHolidayOrWeekend = false;
    private long     activeOutingId     = -1;
    private int      outingStartSec     = 0;
    private boolean  isOutingActive     = false;
    private Handler  outingHandler;
    private Runnable outingRunnable;
    private List<OutingRecord> outingList = new ArrayList<>();

    private LinearLayout llSegContainer;
    private LinearLayout llSegWeekdayNight;
    private LinearLayout llSegHolidayDay;
    private LinearLayout llSegHolidayNight;
    private TextView     tvSegWeekdayNight;
    private TextView     tvSegHolidayDay;
    private TextView     tvSegHolidayNight;
    private Button       btnOutingToggle;
    private TextView     tvOutingElapsed;
    private TextView     tvOutingTotal;
    private LinearLayout llOutingRows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day_detail);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_day_detail), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, insets.top, 0, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
        dbHelper = new DatabaseHelper(this);
        date = getIntent().getStringExtra(MainActivity.EXTRA_DATE);
        if (date == null) { finish(); return; }

        initViews();
        loadRecord();
        setupListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimer();
        stopOutingTimer();
    }

    private void initViews() {
        tvDate         = findViewById(R.id.tv_detail_date);
        layoutTimeCard = findViewById(R.id.layout_time_card);
        layoutCalcCard = findViewById(R.id.layout_calc_card);
        tvAnnualNote   = findViewById(R.id.tv_annual_note);
        tvStartTime    = findViewById(R.id.tv_start_time);
        tvEndTime      = findViewById(R.id.tv_end_time);
        tvRawWork      = findViewById(R.id.tv_raw_work);
        etBreakMinutes = findViewById(R.id.et_break_minutes);
        tvNetWork      = findViewById(R.id.tv_net_work);
        btnEditStart   = findViewById(R.id.btn_edit_start);
        btnEditEnd     = findViewById(R.id.btn_edit_end);
        btnCheckIn     = findViewById(R.id.btn_checkin);
        btnCheckOut    = findViewById(R.id.btn_checkout);
        btnAnnualLeave = findViewById(R.id.btn_annual_leave);
        btnHalfLeave   = findViewById(R.id.btn_half_leave);
        btnPublicLeave = findViewById(R.id.btn_public_leave);
        btnRemoteWork  = findViewById(R.id.btn_remote_work);
        btnSave        = findViewById(R.id.btn_save);
        btnDelete      = findViewById(R.id.btn_delete);
        btnClose       = (android.widget.ImageButton) findViewById(R.id.btn_close);

        llSegContainer    = findViewById(R.id.ll_seg_container);
        llSegWeekdayNight = findViewById(R.id.ll_seg_weekday_night);
        llSegHolidayDay   = findViewById(R.id.ll_seg_holiday_day);
        llSegHolidayNight = findViewById(R.id.ll_seg_holiday_night);
        tvSegWeekdayNight = findViewById(R.id.tv_seg_weekday_night);
        tvSegHolidayDay   = findViewById(R.id.tv_seg_holiday_day);
        tvSegHolidayNight = findViewById(R.id.tv_seg_holiday_night);
        btnOutingToggle   = findViewById(R.id.btn_outing_toggle);
        tvOutingElapsed   = findViewById(R.id.tv_outing_elapsed);
        tvOutingTotal     = findViewById(R.id.tv_outing_total);
        llOutingRows      = findViewById(R.id.ll_outing_rows);
    }

    private void loadRecord() {
        String[] p = date.split("-");
        String[] dayOfWeekNames = {"일", "월", "화", "수", "목", "금", "토"};
        Calendar dateCal = Calendar.getInstance();
        dateCal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        String dow = dayOfWeekNames[dateCal.get(Calendar.DAY_OF_WEEK) - 1];
        String dateLabel = p[0] + "년 " + Integer.parseInt(p[1]) + "월 " + Integer.parseInt(p[2]) + "일 (" + dow + ")";

        String holidayName = KoreanHolidays.getHolidayName(date);
        if (holidayName != null) {
            tvDate.setText(dateLabel + "  (" + holidayName + ")");
            tvDate.setTextColor(Color.parseColor("#FFD54F"));
        } else {
            tvDate.setText(dateLabel);
            tvDate.setTextColor(Color.WHITE);
        }

        // 오늘 날짜 여부 판단
        Calendar cal = Calendar.getInstance();
        String today = String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
        isToday = date.equals(today);

        // 공휴일/주말 여부
        int dateDow = dateCal.get(Calendar.DAY_OF_WEEK);
        boolean isWeekend = (dateDow == Calendar.SATURDAY || dateDow == Calendar.SUNDAY);
        boolean isHoliday = KoreanHolidays.getHolidayName(date) != null;
        isHolidayOrWeekend = isWeekend || isHoliday;
        int checkBtnVisibility = isToday ? View.VISIBLE : View.GONE;
        btnCheckIn.setVisibility(checkBtnVisibility);
        btnCheckOut.setVisibility(checkBtnVisibility);

        record = dbHelper.getWorkRecord(date);
        if (record == null) {
            record = new WorkRecord(date);
            int dowIdx = WorkSettings.dayOfWeekFromDate(date);
            record.applyDefaults(
                    WorkSettings.getStartHour(this, dowIdx),
                    WorkSettings.getStartMinute(this, dowIdx),
                    WorkSettings.getEndHour(this, dowIdx),
                    WorkSettings.getEndMinute(this, dowIdx)
            );
        }

        leaveType = record.getLeaveType();
        updateLeaveUI();

        // 외출 기록 로드 + 진행 중 외출 복원 (앱 백그라운드 복귀 시)
        outingList = new ArrayList<>(dbHelper.getOutings(date));
        for (OutingRecord or : outingList) {
            if (or.isOngoing()) {
                activeOutingId = or.id;
                outingStartSec = or.startSec;
                isOutingActive = true;
                updateOutingToggleButton();
                startOutingTimer();
                break;
            }
        }

        // 오늘 진행 중 기록이면 타이머 자동 재시작
        // checkInMillis = 현재시각 - (현재시각 - 출근시각) 초 단위로 계산해 화면 열 때마다 리셋 방지
        if (record.isInProgress() && isToday) {
            Calendar now = Calendar.getInstance();
            long nowTotalSec  = now.get(Calendar.HOUR_OF_DAY) * 3600L
                              + now.get(Calendar.MINUTE)      * 60L
                              + now.get(Calendar.SECOND);
            long startTotalSec = record.getStartHour() * 3600L
                               + record.getStartMinute() * 60L;
            long elapsedSec = Math.max(0, nowTotalSec - startTotalSec);
            checkInMillis = System.currentTimeMillis() - elapsedSec * 1000L;
            startTimer();
        } else {
            updateDisplay();
        }
        refreshOutingUI();
    }

    /** 휴가 버튼 4개 외관 + 카드 표시 업데이트 */
    private void updateLeaveUI() {
        resetLeaveButton(btnAnnualLeave);
        resetLeaveButton(btnHalfLeave);
        resetLeaveButton(btnPublicLeave);
        resetLeaveButton(btnRemoteWork);

        switch (leaveType) {
            case WorkRecord.LEAVE_ANNUAL:
                activateLeaveButton(btnAnnualLeave, "#C62828");
                layoutTimeCard.setVisibility(View.GONE);
                layoutCalcCard.setVisibility(View.GONE);
                tvAnnualNote.setText("연차: 이 달 의무시간에서 8시간이 차감됩니다.");
                tvAnnualNote.setTextColor(Color.parseColor("#C62828"));
                tvAnnualNote.setVisibility(View.VISIBLE);
                break;
            case WorkRecord.LEAVE_HALF:
                activateLeaveButton(btnHalfLeave, "#E65100");
                layoutTimeCard.setVisibility(View.VISIBLE);
                layoutCalcCard.setVisibility(View.VISIBLE);
                tvAnnualNote.setText("반차: 이 달 의무시간에서 4시간이 차감됩니다. (근무시간 입력 가능)");
                tvAnnualNote.setTextColor(Color.parseColor("#E65100"));
                tvAnnualNote.setVisibility(View.VISIBLE);
                break;
            case WorkRecord.LEAVE_PUBLIC:
                activateLeaveButton(btnPublicLeave, "#1B5E20");
                layoutTimeCard.setVisibility(View.GONE);
                layoutCalcCard.setVisibility(View.GONE);
                tvAnnualNote.setText("공가: 이 달 의무시간에서 8시간이 차감됩니다.");
                tvAnnualNote.setTextColor(Color.parseColor("#1B5E20"));
                tvAnnualNote.setVisibility(View.VISIBLE);
                break;
            case WorkRecord.LEAVE_REMOTE:
                activateLeaveButton(btnRemoteWork, "#1565C0");
                layoutTimeCard.setVisibility(View.VISIBLE);
                layoutCalcCard.setVisibility(View.VISIBLE);
                tvAnnualNote.setText("재택: 근무시간 입력 가능. 의무시간 차감 없음.");
                tvAnnualNote.setTextColor(Color.parseColor("#1565C0"));
                tvAnnualNote.setVisibility(View.VISIBLE);
                break;
            default: // LEAVE_NONE
                layoutTimeCard.setVisibility(View.VISIBLE);
                layoutCalcCard.setVisibility(View.VISIBLE);
                tvAnnualNote.setVisibility(View.GONE);
                break;
        }

        btnDelete.setVisibility(record.hasRecord() ? View.VISIBLE : View.GONE);
    }

    private void resetLeaveButton(Button btn) {
        btn.setBackgroundResource(R.drawable.bg_leave_inactive);
        btn.setTextColor(Color.parseColor("#616161"));
    }

    private void activateLeaveButton(Button btn, String colorHex) {
        btn.setBackgroundColor(Color.parseColor("#1976D2"));
        btn.setTextColor(Color.WHITE);
    }

    private void updateDisplay() {
        if (isTimerRunning) return;
        if (leaveType == WorkRecord.LEAVE_ANNUAL || leaveType == WorkRecord.LEAVE_PUBLIC) return;

        // 출근 시간 표시 복원 (normal mode)
        tvStartTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        tvStartTime.setText(record.getStartTimeString());
        tvEndTime.setText(record.getEndTimeString()); // in-progress면 "--:--" 반환

        if (record.isInProgress()) {
            // 퇴근 미입력 상태 표시 (과거 날짜에서 열람 시)
            tvRawWork.setText("총 체류 시간: -");
            updatingBreak = true;
            etBreakMinutes.setText("0");
            updatingBreak = false;
            tvNetWork.setText("실 근무시간: -");
            tvNetWork.setTextColor(Color.parseColor("#616161"));
            llSegContainer.setVisibility(View.GONE);
            btnDelete.setVisibility(View.VISIBLE);
            return;
        }

        int rawMin    = record.getTotalRawMinutes();
        int autoBreak = record.getAutoBreakMinutes();
        tvRawWork.setText("총 체류 시간: " + fmtMin(rawMin));

        updatingBreak = true;
        int custom = record.getCustomBreakMinutes();
        if (custom >= 0) {
            etBreakMinutes.setText(String.valueOf(custom));
        } else {
            etBreakMinutes.setText(""); // 빈칸 = 자동 계산
        }
        etBreakMinutes.setHint(String.valueOf(autoBreak)); // 자동값을 hint로 표시
        updatingBreak = false;

        updateNetWork();
        btnDelete.setVisibility(record.hasRecord() ? View.VISIBLE : View.GONE);
    }

    private void updateNetWork() {
        if (isTimerRunning) return;
        int net = Math.max(0, record.getTotalRawMinutes() - getBreakForCalc());
        tvNetWork.setText("실 근무시간: " + fmtMin(net));
        tvNetWork.setTextColor(net >= 480 ? Color.parseColor("#2E7D32")
                : net >= 240 ? Color.parseColor("#E65100")
                : Color.parseColor("#C62828"));
        updateSegments();
    }

    private void updateSegments() {
        if (leaveType == WorkRecord.LEAVE_ANNUAL || leaveType == WorkRecord.LEAVE_PUBLIC
                || record.isInProgress()) {
            llSegContainer.setVisibility(View.GONE);
            return;
        }
        int[] segs = record.getTimeSegmentsMinutes(isHolidayOrWeekend,
                record.getEndHour(), record.getEndMinute(), getBreakForCalc());
        displaySegments(segs);
    }

    private void displaySegments(int[] segs) {
        llSegContainer.setVisibility(View.VISIBLE);
        llSegWeekdayNight.setVisibility(segs[1] > 0 ? View.VISIBLE : View.GONE);
        if (segs[1] > 0) tvSegWeekdayNight.setText(fmtMin(segs[1]));
        llSegHolidayDay.setVisibility(segs[2] > 0 ? View.VISIBLE : View.GONE);
        if (segs[2] > 0) tvSegHolidayDay.setText(fmtMin(segs[2]));
        llSegHolidayNight.setVisibility(segs[3] > 0 ? View.VISIBLE : View.GONE);
        if (segs[3] > 0) tvSegHolidayNight.setText(fmtMin(segs[3]));
    }

    /** 실제 계산에 사용할 휴게시간(분): 빈칸이면 자동계산, 값이 있으면 그 값 */
    private int getBreakForCalc() {
        String s = etBreakMinutes.getText().toString().trim();
        if (s.isEmpty()) return record.getAutoBreakMinutes();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return record.getAutoBreakMinutes();
        }
    }

    private void commitBreakInputToRecord() {
        String text = etBreakMinutes.getText().toString().trim();
        if (text.isEmpty()) {
            record.setCustomBreakMinutes(-1);
            return;
        }

        try {
            record.setCustomBreakMinutes(Math.max(0, Integer.parseInt(text)));
        } catch (NumberFormatException e) {
            record.setCustomBreakMinutes(-1);
        }
    }

    // ── 타이머 ────────────────────────────────────────────────

    private void startTimer() {
        isTimerRunning = true;
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override public void run() {
                if (!isTimerRunning) return;
                tickTimer();
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        isTimerRunning = false;
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    /** 1초마다 호출: 출근 시간 고정 표시 + 경과 근무시간 갱신 */
    private void tickTimer() {
        // 출근 시간은 누른 시각으로 고정 표시
        tvStartTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        tvStartTime.setText(record.getStartTimeString());
        tvEndTime.setText("--:--");

        long elapsedSec = (System.currentTimeMillis() - checkInMillis) / 1000;
        long eh = elapsedSec / 3600;
        long em = (elapsedSec % 3600) / 60;
        long es = elapsedSec % 60;
        tvRawWork.setText(String.format("경과 시간: %d시간 %d분 %d초", eh, em, es));

        int breakSec = getBreakForCalc() * 60;
        long netSec  = Math.max(0, elapsedSec - breakSec);
        long nh = netSec / 3600;
        long nm = (netSec % 3600) / 60;
        long ns = netSec % 60;
        tvNetWork.setText(String.format("실 근무시간: %d시간 %d분 %d초", nh, nm, ns));

        long netMin = netSec / 60;
        tvNetWork.setTextColor(netMin >= 480 ? Color.parseColor("#2E7D32")
                : netMin >= 240 ? Color.parseColor("#E65100")
                : Color.parseColor("#C62828"));

        // 시간 구분 실시간 표시
        Calendar segNow = Calendar.getInstance();
        int[] segs = record.getTimeSegmentsMinutes(isHolidayOrWeekend,
                segNow.get(Calendar.HOUR_OF_DAY), segNow.get(Calendar.MINUTE),
                (int)(breakSec / 60));
        displaySegments(segs);
    }

    // ── 리스너 ───────────────────────────────────────────────

    private void setupListeners() {
        // 출근 버튼
        btnCheckIn.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR_OF_DAY);
            int m = now.get(Calendar.MINUTE);
            long millis = System.currentTimeMillis() - now.get(Calendar.SECOND) * 1000L;

            if (record.getEndHour() >= 0) {
                // 퇴근 시간이 이미 있으면 유지 여부 확인
                new AlertDialog.Builder(DayDetailActivity.this)
                        .setTitle("출근 시간 변경")
                        .setMessage("퇴근 시간(" + record.getEndTimeString() + ")을 유지하시겠습니까?")
                        .setPositiveButton("유지", (d, w) -> {
                            stopTimer();
                            record.setStartTime(h, m);
                            updateDisplay();
                        })
                        .setNegativeButton("삭제", (d, w) -> {
                            stopTimer();
                            record.setStartTime(h, m);
                            record.setEndTime(-1, -1);
                            record.setCustomBreakMinutes(-1);
                            checkInMillis = millis;
                            startTimer();
                        })
                        .show();
            } else {
                stopTimer();
                record.setStartTime(h, m);
                record.setEndTime(-1, -1);
                record.setCustomBreakMinutes(-1);
                checkInMillis = millis;
                startTimer();
            }
        });

        // 퇴근 버튼
        btnCheckOut.setOnClickListener(v -> {
            stopTimer();
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR_OF_DAY);
            int m = now.get(Calendar.MINUTE);
            record.setEndTime(h, m);
            tvStartTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
            updateDisplay();
            Toast.makeText(this, String.format("퇴근 시간: %02d:%02d", h, m), Toast.LENGTH_SHORT).show();
        });

        // 연차 버튼 토글
        btnAnnualLeave.setOnClickListener(v -> {
            stopTimer();
            leaveType = (leaveType == WorkRecord.LEAVE_ANNUAL)
                    ? WorkRecord.LEAVE_NONE : WorkRecord.LEAVE_ANNUAL;
            record.setLeaveType(leaveType);
            updateLeaveUI();
            if (leaveType == WorkRecord.LEAVE_NONE) updateDisplay();
        });

        // 반차 버튼 토글
        btnHalfLeave.setOnClickListener(v -> {
            stopTimer();
            leaveType = (leaveType == WorkRecord.LEAVE_HALF)
                    ? WorkRecord.LEAVE_NONE : WorkRecord.LEAVE_HALF;
            record.setLeaveType(leaveType);
            updateLeaveUI();
            updateDisplay();
        });

        // 공가 버튼 토글
        btnPublicLeave.setOnClickListener(v -> {
            stopTimer();
            leaveType = (leaveType == WorkRecord.LEAVE_PUBLIC)
                    ? WorkRecord.LEAVE_NONE : WorkRecord.LEAVE_PUBLIC;
            record.setLeaveType(leaveType);
            updateLeaveUI();
            if (leaveType == WorkRecord.LEAVE_NONE) updateDisplay();
        });

        // 재택 버튼 토글
        btnRemoteWork.setOnClickListener(v -> {
            stopTimer();
            leaveType = (leaveType == WorkRecord.LEAVE_REMOTE)
                    ? WorkRecord.LEAVE_NONE : WorkRecord.LEAVE_REMOTE;
            record.setLeaveType(leaveType);
            updateLeaveUI();
            updateDisplay();
        });

        etBreakMinutes.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!updatingBreak) {
                    String text = s.toString().trim();
                    if (text.isEmpty()) {
                        record.setCustomBreakMinutes(-1); // 빈칸 = 자동계산
                    } else {
                        try {
                            record.setCustomBreakMinutes(Integer.parseInt(text));
                        } catch (NumberFormatException e) {
                            record.setCustomBreakMinutes(-1);
                        }
                    }
                    if (!isTimerRunning) updateNetWork();
                }
            }
        });

        btnEditStart.setOnClickListener(v -> {
            stopTimer();
            commitBreakInputToRecord();
            showTimePicker("출근 시간", record.getStartHour(), record.getStartMinute(),
                    (h, m) -> {
                        record.setStartTime(h, m);
                        // in-progress 상태였으면 end는 그대로 유지(-1)
                        updateDisplay();
                    });
        });

        btnEditEnd.setOnClickListener(v -> {
            stopTimer();
            commitBreakInputToRecord();
            // in-progress 상태면 현재 시각을 초기값으로 사용
            int initH = record.getEndHour() >= 0 ? record.getEndHour()
                    : Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            int initM = record.getEndMinute() >= 0 ? record.getEndMinute()
                    : Calendar.getInstance().get(Calendar.MINUTE);
            showTimePicker("퇴근 시간", initH, initM,
                    (h, m) -> { record.setEndTime(h, m); updateDisplay(); });
        });

        btnSave.setOnClickListener(v -> {
            boolean wasTimerRunning = isTimerRunning;
            stopTimer();
            commitBreakInputToRecord();

            if (leaveType == WorkRecord.LEAVE_NONE
                    || leaveType == WorkRecord.LEAVE_HALF
                    || leaveType == WorkRecord.LEAVE_REMOTE) {

                if (wasTimerRunning) {
                    // 퇴근 미입력 상태로 저장 (오늘 날짜만 허용)
                    record.setEndTime(-1, -1);
                    // customBreakMinutes는 TextWatcher에서 이미 설정됨 (-1=자동, 값=직접입력)
                } else {
                    // customBreakMinutes는 TextWatcher에서 이미 설정됨 (-1=자동, 값=직접입력)
                }
            } else {
                record.setCustomBreakMinutes(0);
            }

            record.setLeaveType(leaveType);
            dbHelper.saveWorkRecord(record);

            if (wasTimerRunning) {
                Toast.makeText(DayDetailActivity.this,
                        "출근 기록이 저장되었습니다. 퇴근 후 다시 저장하세요.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(DayDetailActivity.this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            }
            finish();
        });

        btnClose.setOnClickListener(v -> finish());
        btnOutingToggle.setOnClickListener(v -> {
            if (isOutingActive) stopOuting(); else startOuting();
        });

        // 스와이프로 날짜 이동
        ScrollView scrollView = findViewById(R.id.scroll_day_detail);
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_THRESHOLD  = 100;
                    private static final int SWIPE_VELOCITY   = 100;
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vX, float vY) {
                        if (e1 == null || e2 == null) return false;
                        float dX = e2.getX() - e1.getX();
                        float dY = e2.getY() - e1.getY();
                        if (Math.abs(dX) > Math.abs(dY)
                                && Math.abs(dX) > SWIPE_THRESHOLD
                                && Math.abs(vX) > SWIPE_VELOCITY) {
                            navigateDay(dX < 0 ? 1 : -1);
                            return true;
                        }
                        return false;
                    }
                });
        scrollView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false; // ScrollView 기본 스크롤 허용
        });

        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(DayDetailActivity.this)
                    .setTitle("삭제 확인")
                    .setMessage("이 날의 근무 기록을 삭제하시겠습니까?")
                    .setPositiveButton("삭제", (d, w) -> {
                        stopTimer();
                        dbHelper.deleteWorkRecord(date);
                        Toast.makeText(DayDetailActivity.this,
                                "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton("취소", null)
                    .show();
        });
    }

    private void showTimePicker(String title, int initH, int initM, final OnTimeSet cb) {
        View view = getLayoutInflater().inflate(R.layout.dialog_time_picker, null);
        final NumberPicker npH = view.findViewById(R.id.np_hour);
        final NumberPicker npM = view.findViewById(R.id.np_minute);

        npH.setMinValue(0); npH.setMaxValue(23); npH.setValue(Math.max(0, initH));

        String[] minVals = new String[60];
        for (int i = 0; i < 60; i++) minVals[i] = String.format("%02d", i);
        npM.setMinValue(0); npM.setMaxValue(59);
        npM.setDisplayedValues(minVals);
        npM.setValue(Math.max(0, initM));

        new AlertDialog.Builder(this)
                .setTitle(title + " 선택")
                .setView(view)
                .setPositiveButton("확인", (d, w) -> cb.onSet(npH.getValue(), npM.getValue()))
                .setNegativeButton("취소", null)
                .show();
    }

    private String fmtMin(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }

    // ── 외출 ─────────────────────────────────────────────────

    private void startOuting() {
        Calendar now = Calendar.getInstance();
        int startSec = now.get(Calendar.HOUR_OF_DAY) * 3600
                     + now.get(Calendar.MINUTE) * 60
                     + now.get(Calendar.SECOND);
        activeOutingId = dbHelper.insertOuting(date, startSec);
        outingStartSec = startSec;
        isOutingActive = true;
        updateOutingToggleButton();
        startOutingTimer();
    }

    private void stopOuting() {
        Calendar now = Calendar.getInstance();
        int endSec = now.get(Calendar.HOUR_OF_DAY) * 3600
                   + now.get(Calendar.MINUTE) * 60
                   + now.get(Calendar.SECOND);
        dbHelper.finishOuting(activeOutingId, endSec);
        stopOutingTimer();
        isOutingActive = false;
        activeOutingId = -1;
        tvOutingElapsed.setText("경과: --:--:--");
        updateOutingToggleButton();
        outingList = new ArrayList<>(dbHelper.getOutings(date));
        refreshOutingUI();
    }

    private void updateOutingToggleButton() {
        if (isOutingActive) {
            btnOutingToggle.setText("외출 종료");
            btnOutingToggle.setBackgroundResource(R.drawable.bg_checkout_button);
        } else {
            btnOutingToggle.setText("외출 시작");
            btnOutingToggle.setBackgroundResource(R.drawable.bg_checkin_button);
        }
    }

    private void navigateDay(int delta) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Date d = sdf.parse(date);
            Calendar cal = Calendar.getInstance();
            cal.setTime(d);
            cal.add(Calendar.DAY_OF_MONTH, delta);
            String newDate = sdf.format(cal.getTime());
            Intent intent = new Intent(this, DayDetailActivity.class);
            intent.putExtra(MainActivity.EXTRA_DATE, newDate);
            startActivity(intent);
            finish();
            if (delta > 0) {
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            } else {
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void startOutingTimer() {
        outingHandler = new Handler(Looper.getMainLooper());
        outingRunnable = new Runnable() {
            @Override public void run() {
                if (!isOutingActive) return;
                Calendar now = Calendar.getInstance();
                int nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600
                           + now.get(Calendar.MINUTE) * 60
                           + now.get(Calendar.SECOND);
                int elapsed = nowSec - outingStartSec;
                if (elapsed < 0) elapsed += 86400;
                tvOutingElapsed.setText(String.format("경과: %02d:%02d:%02d",
                        elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60));
                outingHandler.postDelayed(this, 1000);
            }
        };
        outingHandler.post(outingRunnable);
    }

    private void stopOutingTimer() {
        if (outingHandler != null && outingRunnable != null) {
            outingHandler.removeCallbacks(outingRunnable);
        }
    }

    private void refreshOutingUI() {
        int totalSec = 0;
        for (OutingRecord r : outingList) {
            if (!r.isOngoing()) totalSec += r.durationSec();
        }

        int breakSec = getBreakForCalc() * 60;
        int displaySec = (breakSec > 0) ? Math.min(totalSec, breakSec) : totalSec;

        if (totalSec == 0) {
            tvOutingTotal.setText("일 누적: --");
        } else {
            tvOutingTotal.setText(String.format("일 누적: %d분 %02d초",
                    displaySec / 60, displaySec % 60));
        }

        llOutingRows.removeAllViews();
        for (OutingRecord r : outingList) {
            if (!r.isOngoing()) addOutingRow(r);
        }
    }

    private void addOutingRow(OutingRecord r) {
        float density = getResources().getDisplayMetrics().density;
        int padV = (int)(4 * density);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, padV, 0, padV);

        if (r.durationSec() <= 600) {
            row.setBackgroundColor(Color.parseColor("#E8F5E9"));
        }

        row.setOnLongClickListener(v -> { showOutingEditDialog(r); return true; });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView tvStart = new TextView(this);
        tvStart.setLayoutParams(p);
        tvStart.setText(r.startTimeStr());
        tvStart.setTextSize(11f);
        tvStart.setGravity(Gravity.CENTER);
        row.addView(tvStart);

        TextView tvEnd = new TextView(this);
        tvEnd.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvEnd.setText(r.endTimeStr());
        tvEnd.setTextSize(11f);
        tvEnd.setGravity(Gravity.CENTER);
        row.addView(tvEnd);

        TextView tvDur = new TextView(this);
        tvDur.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvDur.setText(r.durationStr());
        tvDur.setTextSize(11f);
        tvDur.setGravity(Gravity.CENTER);
        row.addView(tvDur);

        Button btnDel = new Button(this);
        btnDel.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(52 * density), LinearLayout.LayoutParams.WRAP_CONTENT));
        btnDel.setText("삭제");
        btnDel.setTextSize(9f);
        btnDel.setOnClickListener(v -> {
            dbHelper.deleteOuting(r.id);
            outingList = new ArrayList<>(dbHelper.getOutings(date));
            refreshOutingUI();
        });
        row.addView(btnDel);

        llOutingRows.addView(row);
    }

    private void showOutingEditDialog(OutingRecord r) {
        float dp = getResources().getDisplayMetrics().density;
        int padPx = (int)(16 * dp);

        String[] minVals = new String[60];
        for (int i = 0; i < 60; i++) minVals[i] = String.format("%02d", i);
        String[] secVals = minVals.clone();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padPx, padPx, padPx, 0);

        // 시작 시간
        TextView tvStartLabel = new TextView(this);
        tvStartLabel.setText("외출 시작");
        tvStartLabel.setTextSize(13f);
        tvStartLabel.setTextColor(Color.parseColor("#616161"));
        root.addView(tvStartLabel);

        LinearLayout startRow = new LinearLayout(this);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.setGravity(Gravity.CENTER);

        NumberPicker npSH = new NumberPicker(this);
        npSH.setMinValue(0); npSH.setMaxValue(23);
        npSH.setValue(r.startSec / 3600);

        NumberPicker npSM = new NumberPicker(this);
        npSM.setMinValue(0); npSM.setMaxValue(59);
        npSM.setDisplayedValues(minVals.clone());
        npSM.setValue((r.startSec % 3600) / 60);

        NumberPicker npSS = new NumberPicker(this);
        npSS.setMinValue(0); npSS.setMaxValue(59);
        npSS.setDisplayedValues(secVals.clone());
        npSS.setValue(r.startSec % 60);

        TextView c1 = makeColon(dp); TextView c2 = makeColon(dp);
        startRow.addView(npSH); startRow.addView(c1);
        startRow.addView(npSM); startRow.addView(c2);
        startRow.addView(npSS);
        root.addView(startRow);

        // 구분선
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = (int)(8 * dp); divLp.bottomMargin = (int)(8 * dp);
        div.setLayoutParams(divLp);
        div.setBackgroundColor(Color.parseColor("#EEEEEE"));
        root.addView(div);

        // 종료 시간
        TextView tvEndLabel = new TextView(this);
        tvEndLabel.setText("외출 종료");
        tvEndLabel.setTextSize(13f);
        tvEndLabel.setTextColor(Color.parseColor("#616161"));
        root.addView(tvEndLabel);

        LinearLayout endRow = new LinearLayout(this);
        endRow.setOrientation(LinearLayout.HORIZONTAL);
        endRow.setGravity(Gravity.CENTER);

        NumberPicker npEH = new NumberPicker(this);
        npEH.setMinValue(0); npEH.setMaxValue(23);
        npEH.setValue(r.endSec / 3600);

        NumberPicker npEM = new NumberPicker(this);
        npEM.setMinValue(0); npEM.setMaxValue(59);
        npEM.setDisplayedValues(minVals.clone());
        npEM.setValue((r.endSec % 3600) / 60);

        NumberPicker npES = new NumberPicker(this);
        npES.setMinValue(0); npES.setMaxValue(59);
        npES.setDisplayedValues(secVals.clone());
        npES.setValue(r.endSec % 60);

        TextView c3 = makeColon(dp); TextView c4 = makeColon(dp);
        endRow.addView(npEH); endRow.addView(c3);
        endRow.addView(npEM); endRow.addView(c4);
        endRow.addView(npES);
        root.addView(endRow);

        new AlertDialog.Builder(this)
                .setTitle("외출 시간 수정")
                .setView(root)
                .setPositiveButton("저장", (d, w) -> {
                    int newStart = npSH.getValue() * 3600 + npSM.getValue() * 60 + npSS.getValue();
                    int newEnd   = npEH.getValue() * 3600 + npEM.getValue() * 60 + npES.getValue();
                    dbHelper.updateOuting(r.id, newStart, newEnd);
                    outingList = new ArrayList<>(dbHelper.getOutings(date));
                    refreshOutingUI();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private TextView makeColon(float dp) {
        TextView tv = new TextView(this);
        tv.setText(":");
        tv.setTextSize(18f);
        tv.setPadding((int)(4 * dp), 0, (int)(4 * dp), 0);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    interface OnTimeSet { void onSet(int hour, int minute); }
}
