package com.workhours;

import android.graphics.Color;
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

public class VacationSettingsActivity extends AppCompatActivity {

    private NumberPicker npStartYear;
    private NumberPicker npStartMonth;
    private NumberPicker npEndYear;
    private NumberPicker npEndMonth;
    private NumberPicker npAnnualDays;
    private TextView     tvCurrentSettings;
    private Button       btnSave;

    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vacation_settings);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_vacation_settings), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, insets.top, 0, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("휴가 설정");
        }
        dbHelper = new DatabaseHelper(this);

        npStartYear  = findViewById(R.id.np_start_year);
        npStartMonth = findViewById(R.id.np_start_month);
        npEndYear    = findViewById(R.id.np_end_year);
        npEndMonth   = findViewById(R.id.np_end_month);
        npAnnualDays = findViewById(R.id.np_annual_days);
        tvCurrentSettings = findViewById(R.id.tv_current_vacation_settings);
        btnSave      = findViewById(R.id.btn_vacation_save);

        // 연도 범위: 2020 ~ 2040
        npStartYear.setMinValue(2020); npStartYear.setMaxValue(2040);
        npEndYear.setMinValue(2020);   npEndYear.setMaxValue(2040);

        // 월 범위: 1 ~ 12
        String[] months = {"1월","2월","3월","4월","5월","6월","7월","8월","9월","10월","11월","12월"};
        npStartMonth.setMinValue(1); npStartMonth.setMaxValue(12);
        npStartMonth.setDisplayedValues(months);
        npEndMonth.setMinValue(1);   npEndMonth.setMaxValue(12);
        npEndMonth.setDisplayedValues(months);

        // 휴가일수: 1 ~ 50
        npAnnualDays.setMinValue(1);
        npAnnualDays.setMaxValue(50);

        // 저장된 설정 불러오기
        VacationSettings vs = dbHelper.getVacationSettings();
        if (vs != null) {
            try {
                String[] sp = vs.startYearMonth.split("-");
                String[] ep = vs.endYearMonth.split("-");
                npStartYear.setValue(Integer.parseInt(sp[0]));
                npStartMonth.setValue(Integer.parseInt(sp[1]));
                npEndYear.setValue(Integer.parseInt(ep[0]));
                npEndMonth.setValue(Integer.parseInt(ep[1]));
                npAnnualDays.setValue(vs.annualDays);
            } catch (Exception ignored) {
                setDefaultValues();
            }
            tvCurrentSettings.setText(buildSettingsText(vs));
            tvCurrentSettings.setTextColor(Color.parseColor("#1565C0"));
        } else {
            setDefaultValues();
            tvCurrentSettings.setText("미지정");
            tvCurrentSettings.setTextColor(Color.parseColor("#9E9E9E"));
        }

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int sy = npStartYear.getValue();
                int sm = npStartMonth.getValue();
                int ey = npEndYear.getValue();
                int em = npEndMonth.getValue();
                int days = npAnnualDays.getValue();

                String start = String.format("%04d-%02d", sy, sm);
                String end   = String.format("%04d-%02d", ey, em);

                if (start.compareTo(end) >= 0) {
                    Toast.makeText(VacationSettingsActivity.this,
                            "종료 연월은 시작 연월 이후여야 합니다.", Toast.LENGTH_SHORT).show();
                    return;
                }

                VacationSettings vs = new VacationSettings(start, end, days);
                dbHelper.saveVacationSettings(vs);
                tvCurrentSettings.setText(buildSettingsText(vs));
                tvCurrentSettings.setTextColor(Color.parseColor("#1565C0"));
                Toast.makeText(VacationSettingsActivity.this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void setDefaultValues() {
        int curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        npStartYear.setValue(curYear);
        npStartMonth.setValue(3);       // 올해 3월
        npEndYear.setValue(curYear + 1);
        npEndMonth.setValue(2);         // 내년 2월
        npAnnualDays.setValue(15);
    }

    private String buildSettingsText(VacationSettings vs) {
        String[] sp = vs.startYearMonth.split("-");
        String[] ep = vs.endYearMonth.split("-");
        return "기간: " + sp[0] + "년 " + Integer.parseInt(sp[1]) + "월"
             + " ~ " + ep[0] + "년 " + Integer.parseInt(ep[1]) + "월"
             + "  /  연간 " + vs.annualDays + "일";
    }
}
