package com.workhours;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Calendar;

public class DayDetailActivity extends AppCompatActivity {

    private TextView     tvDate;
    private LinearLayout layoutTimeCard;
    private LinearLayout layoutCalcCard;
    private TextView     tvAnnualNote;

    private TextView tvStartTime;
    private TextView tvEndTime;
    private TextView tvRawWork;
    private EditText etBreakMinutes;
    private TextView tvBreakHint;
    private TextView tvNetWork;
    private Button   btnEditStart;
    private Button   btnEditEnd;
    private Button   btnCheckIn;
    private Button   btnCheckOut;
    private Button   btnAnnualLeave;
    private Button   btnHalfLeave;
    private Button   btnPublicLeave;
    private Button   btnRemoteWork;
    private Button   btnSave;
    private Button   btnDelete;

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
        tvBreakHint    = findViewById(R.id.tv_break_hint);
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
    }

    private void loadRecord() {
        String[] p = date.split("-");
        String dateLabel = p[0] + "년 " + Integer.parseInt(p[1]) + "월 " + Integer.parseInt(p[2]) + "일";

        String holidayName = KoreanHolidays.getHolidayName(date);
        if (holidayName != null) {
            tvDate.setText(dateLabel + "  (" + holidayName + ")");
            tvDate.setTextColor(Color.parseColor("#D32F2F"));
        } else {
            tvDate.setText(dateLabel);
        }

        // 오늘 날짜 여부 판단
        Calendar cal = Calendar.getInstance();
        String today = String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
        isToday = date.equals(today);
        int checkBtnVisibility = isToday ? View.VISIBLE : View.GONE;
        btnCheckIn.setVisibility(checkBtnVisibility);
        btnCheckOut.setVisibility(checkBtnVisibility);

        record = dbHelper.getWorkRecord(date);
        if (record == null) {
            record = new WorkRecord(date);
            int dow = WorkSettings.dayOfWeekFromDate(date);
            record.applyDefaults(
                    WorkSettings.getStartHour(this, dow),
                    WorkSettings.getStartMinute(this, dow),
                    WorkSettings.getEndHour(this, dow),
                    WorkSettings.getEndMinute(this, dow)
            );
        }

        leaveType = record.getLeaveType();
        updateLeaveUI();

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
        btn.setTextColor(Color.parseColor("#757575"));
    }

    private void activateLeaveButton(Button btn, String colorHex) {
        btn.setBackgroundColor(Color.parseColor(colorHex));
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
            tvBreakHint.setText("자동");
            updatingBreak = true;
            etBreakMinutes.setText("0");
            updatingBreak = false;
            tvNetWork.setText("실 근무시간: -");
            tvNetWork.setTextColor(Color.parseColor("#616161"));
            btnDelete.setVisibility(View.VISIBLE);
            return;
        }

        int rawMin    = record.getTotalRawMinutes();
        int autoBreak = record.getAutoBreakMinutes();
        tvRawWork.setText("총 체류 시간: " + fmtMin(rawMin));
        tvBreakHint.setText("미입력 시 자동: " + autoBreak + "분");

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
    }

    // ── 리스너 ───────────────────────────────────────────────

    private void setupListeners() {
        // 출근 버튼
        btnCheckIn.setOnClickListener(v -> {
            stopTimer();
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR_OF_DAY);
            int m = now.get(Calendar.MINUTE);
            record.setStartTime(h, m);
            record.setEndTime(-1, -1); // 퇴근 미입력 상태
            record.setCustomBreakMinutes(-1);
            checkInMillis = System.currentTimeMillis()
                    - (now.get(Calendar.SECOND) * 1000L);
            startTimer();
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

    interface OnTimeSet { void onSet(int hour, int minute); }
}
