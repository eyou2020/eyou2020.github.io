package com.workhours;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

public class VacationQueryActivity extends AppCompatActivity {

    private TextView    tvRemainingDays;
    private TextView    tvPeriodInfo;
    private LinearLayout layoutLeaveList;
    private TextView    tvEmpty;

    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vacation_query);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_vacation_query), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, insets.top, 0, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("휴가 조회");
        }
        dbHelper = new DatabaseHelper(this);

        tvRemainingDays = findViewById(R.id.tv_remaining_days);
        tvPeriodInfo    = findViewById(R.id.tv_period_info);
        layoutLeaveList = findViewById(R.id.layout_leave_list);
        tvEmpty         = findViewById(R.id.tv_leave_empty);

        loadData();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void loadData() {
        VacationSettings vs = dbHelper.getVacationSettings();
        if (vs == null) {
            tvPeriodInfo.setText("휴가 설정이 없습니다. 먼저 [휴가 설정]에서 기간을 설정해 주세요.");
            tvRemainingDays.setText("잔여: -");
            tvEmpty.setVisibility(View.VISIBLE);
            layoutLeaveList.setVisibility(View.GONE);
            return;
        }

        // 기간 표시
        String[] sp = vs.startYearMonth.split("-");
        String[] ep = vs.endYearMonth.split("-");
        String periodStr = sp[0] + "년 " + Integer.parseInt(sp[1]) + "월"
                + " ~ " + ep[0] + "년 " + Integer.parseInt(ep[1]) + "월"
                + "  (연간 " + vs.annualDays + "일)";
        tvPeriodInfo.setText(periodStr);

        // 해당 기간의 휴가 기록 조회 (연차·반차·공가만, 재택 제외)
        List<WorkRecord> all = dbHelper.getLeaveRecordsInPeriod(
                vs.startYearMonth, vs.endYearMonth);
        List<WorkRecord> leaveRecords = new java.util.ArrayList<>();
        for (WorkRecord r : all) {
            int lt = r.getLeaveType();
            if (lt == WorkRecord.LEAVE_ANNUAL
                    || lt == WorkRecord.LEAVE_HALF
                    || lt == WorkRecord.LEAVE_PUBLIC) {
                leaveRecords.add(r);
            }
        }

        // 사용 일수 계산 (연차=1, 반차=0.5, 공가=0)
        double usedDays = 0;
        int annualCount = 0;
        int halfCount   = 0;
        int publicCount = 0;
        for (WorkRecord r : leaveRecords) {
            switch (r.getLeaveType()) {
                case WorkRecord.LEAVE_ANNUAL: usedDays += 1.0; annualCount++; break;
                case WorkRecord.LEAVE_HALF:   usedDays += 0.5; halfCount++;   break;
                case WorkRecord.LEAVE_PUBLIC: /* 차감 없음 */  publicCount++; break;
            }
        }
        double remaining = vs.annualDays - usedDays;

        // 잔여 표시
        String remainStr = String.format("잔여 휴가: %.1f일 / %d일  (사용 %.1f일)",
                remaining, vs.annualDays, usedDays);
        tvRemainingDays.setText(remainStr);
        tvRemainingDays.setTextColor(remaining > 0
                ? Color.parseColor("#1565C0") : Color.parseColor("#C62828"));

        // 사용 요약
        StringBuilder summary = new StringBuilder();
        if (annualCount > 0) summary.append("연차 ").append(annualCount).append("일  ");
        if (halfCount   > 0) summary.append("반차 ").append(halfCount).append("회(").append(halfCount * 0.5).append("일)  ");
        if (publicCount > 0) summary.append("공가 ").append(publicCount).append("일");
        if (summary.length() == 0) summary.append("사용 내역 없음");
        tvPeriodInfo.setText(periodStr + "\n" + summary.toString().trim());

        // 리스트 구성
        layoutLeaveList.removeAllViews();
        if (leaveRecords.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            layoutLeaveList.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            layoutLeaveList.setVisibility(View.VISIBLE);
            for (WorkRecord r : leaveRecords) {
                layoutLeaveList.addView(buildLeaveRow(r));
            }
        }
    }

    private View buildLeaveRow(WorkRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 12);

        // 날짜
        String[] dp = r.getDate().split("-");
        String dateStr = dp[0] + "." + Integer.parseInt(dp[1]) + "." + Integer.parseInt(dp[2]);
        TextView tvDate = new TextView(this);
        tvDate.setText(dateStr);
        tvDate.setTextSize(14);
        tvDate.setTextColor(Color.parseColor("#212121"));
        LinearLayout.LayoutParams lpDate = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        tvDate.setLayoutParams(lpDate);

        // 유형
        String typeStr;
        int typeColor;
        String deductStr;
        switch (r.getLeaveType()) {
            case WorkRecord.LEAVE_ANNUAL:
                typeStr   = "연차";
                typeColor = Color.parseColor("#C62828");
                deductStr = "-1일";
                break;
            case WorkRecord.LEAVE_HALF:
                typeStr   = "반차";
                typeColor = Color.parseColor("#E65100");
                deductStr = "-0.5일";
                break;
            case WorkRecord.LEAVE_PUBLIC:
                typeStr   = "공가";
                typeColor = Color.parseColor("#1B5E20");
                deductStr = "차감없음";
                break;
            default:
                typeStr   = "-";
                typeColor = Color.GRAY;
                deductStr = "";
        }

        TextView tvType = new TextView(this);
        tvType.setText(typeStr);
        tvType.setTextSize(14);
        tvType.setTextColor(typeColor);
        tvType.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lpType = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvType.setLayoutParams(lpType);
        tvType.setGravity(Gravity.CENTER);

        // 차감
        TextView tvDeduct = new TextView(this);
        tvDeduct.setText(deductStr);
        tvDeduct.setTextSize(13);
        tvDeduct.setTextColor(r.getLeaveType() == WorkRecord.LEAVE_PUBLIC
                ? Color.parseColor("#9E9E9E") : typeColor);
        LinearLayout.LayoutParams lpDeduct = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvDeduct.setLayoutParams(lpDeduct);
        tvDeduct.setGravity(Gravity.END);

        row.addView(tvDate);
        row.addView(tvType);
        row.addView(tvDeduct);

        // 반차 근무시간 표시 제거 — 차감일수만 표시

        // 구분선 래퍼
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackground(null);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));

        wrapper.addView(row);
        wrapper.addView(divider);
        return wrapper;
    }
}
