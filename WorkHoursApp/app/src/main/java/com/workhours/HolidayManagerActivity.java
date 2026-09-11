package com.workhours;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class HolidayManagerActivity extends AppCompatActivity {

    private TextView tvYear;
    private ListView lvHolidays;

    private int year;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_holiday_manager);

        // 하단 내비게이션 바 가림 방지
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_holiday_manager), (v, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, bars.top, 0, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        KoreanHolidays.loadCustom(this);

        year = Calendar.getInstance().get(Calendar.YEAR);

        tvYear      = findViewById(R.id.tv_year);
        lvHolidays  = findViewById(R.id.lv_holidays);

        findViewById(R.id.btn_hm_close).setOnClickListener(v -> finish());

        ((ImageButton) findViewById(R.id.btn_year_prev)).setOnClickListener(v -> {
            year--;
            refresh();
        });
        ((ImageButton) findViewById(R.id.btn_year_next)).setOnClickListener(v -> {
            year++;
            refresh();
        });

        findViewById(R.id.btn_hm_add).setOnClickListener(v -> showAddDialog());

        refresh();
    }

    private void refresh() {
        tvYear.setText(year + "년");

        Map<String, String> map = KoreanHolidays.getHolidaysForYear(year);
        List<String[]> items = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            items.add(new String[]{e.getKey(), e.getValue()});
        }

        HolidayAdapter adapter = new HolidayAdapter(this, items);
        lvHolidays.setAdapter(adapter);
    }

    private void showAddDialog() {
        // 날짜 선택 → 이름 입력 순으로 진행
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        DatePickerDialog dpd = new DatePickerDialog(this,
                (view, y, m, d) -> {
                    String date = String.format("%04d-%02d-%02d", y, m + 1, d);
                    showNameInputDialog(date);
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH));
        dpd.show();
    }

    private void showNameInputDialog(String date) {
        EditText et = new EditText(this);
        et.setHint("휴일 이름 (예: 임시공휴일)");
        et.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle(date + " 휴일 추가")
                .setView(et)
                .setPositiveButton("추가", (dlg, which) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "이름을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    KoreanHolidays.addCustomHoliday(this, date, name);
                    refresh();
                    Toast.makeText(this, "추가되었습니다.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ── 어댑터 ────────────────────────────────────────────────

    private class HolidayAdapter extends ArrayAdapter<String[]> {

        private final List<String[]> items;

        HolidayAdapter(Context ctx, List<String[]> items) {
            super(ctx, 0, items);
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_holiday, parent, false);
            }

            String[] item = items.get(position);
            String date = item[0];
            String name = item[1];

            TextView tvDate   = convertView.findViewById(R.id.tv_holiday_date);
            TextView tvName   = convertView.findViewById(R.id.tv_holiday_name);
            TextView btnDel   = convertView.findViewById(R.id.btn_delete_holiday);

            // 날짜 → "M월 D일 (요일)" 형식
            String[] dayNames = {"일","월","화","수","목","금","토"};
            String[] parts = date.split("-");
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            String dateLabel = Integer.parseInt(parts[1]) + "월 " + Integer.parseInt(parts[2]) + "일 (" + dayNames[dow - 1] + ")";

            tvDate.setText(dateLabel);
            tvName.setText(name);

            // 커스텀 추가 항목 → 삭제 버튼 표시, 기본 항목 → 숨김 처리 가능
            boolean isCustom = KoreanHolidays.isCustomAdded(date);
            btnDel.setVisibility(View.VISIBLE);

            if (isCustom) {
                tvDate.setTextColor(Color.parseColor("#1565C0"));
                tvName.setTextColor(Color.parseColor("#1565C0"));
            } else {
                tvDate.setTextColor(Color.parseColor("#424242"));
                tvName.setTextColor(Color.parseColor("#212121"));
            }
            btnDel.setText("삭제");
            btnDel.setTextColor(Color.parseColor("#C62828"));

            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(HolidayManagerActivity.this)
                        .setTitle(name + " 삭제")
                        .setMessage(dateLabel + "\n이 휴일을 삭제하시겠습니까?")
                        .setPositiveButton("삭제", (dlg, which) -> {
                            KoreanHolidays.deleteHoliday(HolidayManagerActivity.this, date);
                            refresh();
                        })
                        .setNegativeButton("취소", null)
                        .show();
            });

            return convertView;
        }
    }
}
