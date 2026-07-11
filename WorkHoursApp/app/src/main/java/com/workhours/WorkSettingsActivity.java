package com.workhours;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Calendar;

/**
 * 요일별 기본 출퇴근 시간 설정 화면 (월~금)
 */
public class WorkSettingsActivity extends AppCompatActivity {

    // 월~금: index 0=월, 1=화, 2=수, 3=목, 4=금
    private static final int[] DAYS = {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY
    };

    private final int[] startH = new int[5];
    private final int[] startM = new int[5];
    private final int[] endH   = new int[5];
    private final int[] endM   = new int[5];

    private final TextView[] tvStart = new TextView[5];
    private final TextView[] tvEnd   = new TextView[5];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_work_settings);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_work_settings), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, insets.top, 0, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("근무 설정");
        }
        loadSettings();
        bindViews();
        setupListeners();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void loadSettings() {
        for (int i = 0; i < 5; i++) {
            int dow = DAYS[i];
            startH[i] = WorkSettings.getStartHour(this, dow);
            startM[i] = WorkSettings.getStartMinute(this, dow);
            endH[i]   = WorkSettings.getEndHour(this, dow);
            endM[i]   = WorkSettings.getEndMinute(this, dow);
        }
    }

    private void bindViews() {
        int[] startIds = {
                R.id.tv_start_mon, R.id.tv_start_tue, R.id.tv_start_wed,
                R.id.tv_start_thu, R.id.tv_start_fri
        };
        int[] endIds = {
                R.id.tv_end_mon, R.id.tv_end_tue, R.id.tv_end_wed,
                R.id.tv_end_thu, R.id.tv_end_fri
        };
        for (int i = 0; i < 5; i++) {
            tvStart[i] = findViewById(startIds[i]);
            tvEnd[i]   = findViewById(endIds[i]);
            updateTimeView(i);
        }
    }

    private void updateTimeView(int i) {
        tvStart[i].setText(String.format("%02d:%02d", startH[i], startM[i]));
        tvEnd[i].setText(String.format("%02d:%02d", endH[i], endM[i]));
    }

    private void setupListeners() {
        for (int i = 0; i < 5; i++) {
            final int idx = i;

            tvStart[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showTimePicker(WorkSettings.dayName(DAYS[idx]) + " 출근",
                            startH[idx], startM[idx],
                            new OnTimeSet() {
                                @Override public void onSet(int h, int m) {
                                    startH[idx] = h;
                                    startM[idx] = m;
                                    updateTimeView(idx);
                                }
                            });
                }
            });

            tvEnd[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showTimePicker(WorkSettings.dayName(DAYS[idx]) + " 퇴근",
                            endH[idx], endM[idx],
                            new OnTimeSet() {
                                @Override public void onSet(int h, int m) {
                                    endH[idx] = h;
                                    endM[idx] = m;
                                    updateTimeView(idx);
                                }
                            });
                }
            });
        }

        Button btnSave = findViewById(R.id.btn_settings_save);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (int i = 0; i < 5; i++) {
                    WorkSettings.save(WorkSettingsActivity.this, DAYS[i],
                            startH[i], startM[i], endH[i], endM[i]);
                }
                Toast.makeText(WorkSettingsActivity.this,
                        "기본 근무 시간이 저장되었습니다.", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void showTimePicker(String title, int initH, int initM, final OnTimeSet cb) {
        View view = getLayoutInflater().inflate(R.layout.dialog_time_picker, null);
        final NumberPicker npH = view.findViewById(R.id.np_hour);
        final NumberPicker npM = view.findViewById(R.id.np_minute);

        npH.setMinValue(0); npH.setMaxValue(23); npH.setValue(initH);

        String[] minVals = new String[60];
        for (int i = 0; i < 60; i++) minVals[i] = String.format("%02d", i);
        npM.setMinValue(0); npM.setMaxValue(59);
        npM.setDisplayedValues(minVals);
        npM.setValue(initM);

        new AlertDialog.Builder(this)
                .setTitle(title + " 선택")
                .setView(view)
                .setPositiveButton("확인", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        cb.onSet(npH.getValue(), npM.getValue());
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    interface OnTimeSet { void onSet(int hour, int minute); }
}
