package com.workhours;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class CalendarAdapter extends BaseAdapter {

    private final Context context;
    private final List<String> days;
    private final String yearMonth;
    private final Map<String, WorkRecord> records;
    private final Map<Integer, String> holidays;
    private final int today;
    private int selectedDay = -1;
    private int rowHeight   = 0; // 0 = wrap_content

    public void setRowHeight(int h) { rowHeight = h; }

    public CalendarAdapter(Context context, List<String> days, String yearMonth,
                           Map<String, WorkRecord> records,
                           Map<Integer, String> holidays, int today) {
        this.context   = context;
        this.days      = days;
        this.yearMonth = yearMonth;
        this.records   = records;
        this.holidays  = holidays;
        this.today     = today;
    }

    public void setSelectedDay(int day) { selectedDay = day; notifyDataSetChanged(); }

    /** 출근 시간: HH:MM:00 (저장값은 초 없음) */
    private String startTimeText(WorkRecord record) {
        return record.getStartTimeString() + ":00";
    }

    /** 퇴근 시간: 오늘 진행 중이면 HH:MM:SS(현재 시각), 아니면 HH:MM:00 */
    private String endTimeText(WorkRecord record, int dayNum) {
        if (record.isInProgress() && dayNum == today) {
            Calendar now = Calendar.getInstance();
            return String.format("%02d:%02d:%02d",
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND));
        }
        if (record.isInProgress()) return "--:--:--";
        return record.getEndTimeString() + ":00";
    }

    /** 근무시간: 오늘 진행 중이면 초 단위 포함, 아니면 분 단위 */
    private String workTimeText(WorkRecord record, int dayNum) {
        long sec;
        if (record.isInProgress() && dayNum == today) {
            Calendar now = Calendar.getInstance();
            sec = record.getNetWorkSecondsAtNow(
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND));
        } else {
            sec = record.getNetWorkMinutes() * 60L;
        }
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h + "h" + m + "m" + s + "s";
    }

    @Override public int    getCount()         { return days.size(); }
    @Override public Object getItem(int pos)   { return days.get(pos); }
    @Override public long   getItemId(int pos) { return pos; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                    .inflate(R.layout.calendar_day_cell, parent, false);
        }
        // 동적 행 높이 — GridView가 측정된 뒤 주입됨
        if (rowHeight > 0) {
            convertView.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, rowHeight));
        }

        TextView tvDay        = convertView.findViewById(R.id.tv_day);
        TextView tvHoliday    = convertView.findViewById(R.id.tv_holiday);
        TextView tvCellStart  = convertView.findViewById(R.id.tv_cell_start);
        TextView tvCellEnd    = convertView.findViewById(R.id.tv_cell_end);
        TextView tvWork       = convertView.findViewById(R.id.tv_work_time);
        TextView tvLeaveBadge = convertView.findViewById(R.id.tv_leave_badge);

        String dayStr = days.get(position);
        if (dayStr == null || dayStr.isEmpty()) {
            tvDay.setText("");
            tvHoliday.setVisibility(View.GONE);
            tvCellStart.setVisibility(View.INVISIBLE);
            tvCellEnd.setVisibility(View.INVISIBLE);
            tvWork.setVisibility(View.INVISIBLE);
            tvLeaveBadge.setVisibility(View.GONE);
            convertView.setBackgroundColor(Color.parseColor("#F0F0F0"));
            return convertView;
        }

        int dayNum = Integer.parseInt(dayStr);
        tvDay.setText(dayStr);

        // 공휴일 이름 (상단 작은 글씨)
        String holidayName = holidays.get(dayNum);
        if (holidayName != null) {
            tvHoliday.setVisibility(View.VISIBLE);
            tvHoliday.setText(holidayName);
        } else {
            tvHoliday.setVisibility(View.GONE);
        }

        // 공휴일 "휴일" 배지 — 근무 기록 없을 때만
        boolean isHolidayDay = (holidayName != null);

        // 근무 기록
        String dateKey = String.format("%s-%02d", yearMonth, dayNum);
        WorkRecord record = records.get(dateKey);

        int leaveType = (record != null) ? record.getLeaveType() : WorkRecord.LEAVE_NONE;

        // 기본: 배지 숨김
        tvLeaveBadge.setVisibility(View.GONE);

        if (record != null && leaveType == WorkRecord.LEAVE_ANNUAL) {
            tvCellStart.setVisibility(View.INVISIBLE);
            tvCellEnd.setVisibility(View.INVISIBLE);
            tvWork.setVisibility(View.INVISIBLE);
            tvLeaveBadge.setText("연차");
            tvLeaveBadge.setTextColor(Color.parseColor("#C62828"));
            tvLeaveBadge.setVisibility(View.VISIBLE);
        } else if (record != null && leaveType == WorkRecord.LEAVE_HALF) {
            tvCellStart.setText(startTimeText(record));
            tvCellStart.setTextColor(Color.parseColor("#1565C0"));
            tvCellStart.setVisibility(View.VISIBLE);
            tvCellEnd.setText(endTimeText(record, dayNum));
            tvCellEnd.setVisibility(View.VISIBLE);
            tvWork.setText(workTimeText(record, dayNum));
            tvWork.setTextColor(Color.parseColor("#E65100"));
            tvWork.setVisibility(View.VISIBLE);
            tvLeaveBadge.setText("반차");
            tvLeaveBadge.setTextColor(Color.parseColor("#E65100"));
            tvLeaveBadge.setVisibility(View.VISIBLE);
        } else if (record != null && leaveType == WorkRecord.LEAVE_PUBLIC) {
            tvCellStart.setVisibility(View.INVISIBLE);
            tvCellEnd.setVisibility(View.INVISIBLE);
            tvWork.setVisibility(View.INVISIBLE);
            tvLeaveBadge.setText("공가");
            tvLeaveBadge.setTextColor(Color.parseColor("#1B5E20"));
            tvLeaveBadge.setVisibility(View.VISIBLE);
        } else if (record != null && leaveType == WorkRecord.LEAVE_REMOTE) {
            tvCellStart.setText(startTimeText(record));
            tvCellStart.setTextColor(Color.parseColor("#1565C0"));
            tvCellStart.setVisibility(View.VISIBLE);
            tvCellEnd.setText(endTimeText(record, dayNum));
            tvCellEnd.setVisibility(View.VISIBLE);
            tvWork.setText(workTimeText(record, dayNum));
            tvWork.setTextColor(Color.parseColor("#1565C0"));
            tvWork.setVisibility(View.VISIBLE);
            tvLeaveBadge.setText("재택");
            tvLeaveBadge.setTextColor(Color.parseColor("#1565C0"));
            tvLeaveBadge.setVisibility(View.VISIBLE);
        } else if (record != null) {
            // 일반 근무 기록
            tvCellStart.setText(startTimeText(record));
            tvCellStart.setTextColor(Color.parseColor("#1565C0"));
            tvCellStart.setVisibility(View.VISIBLE);
            tvCellEnd.setText(endTimeText(record, dayNum));
            tvCellEnd.setVisibility(View.VISIBLE);
            tvWork.setText(workTimeText(record, dayNum));
            tvWork.setTextColor(Color.parseColor("#2E7D32"));
            tvWork.setVisibility(View.VISIBLE);
            tvLeaveBadge.setText("근무");
            tvLeaveBadge.setTextColor(Color.parseColor("#2E7D32"));
            tvLeaveBadge.setVisibility(View.VISIBLE);
        } else {
            tvCellEnd.setVisibility(View.INVISIBLE);
            tvWork.setVisibility(View.INVISIBLE);
            if (isHolidayDay) {
                // 공휴일: "휴일" 을 출근시간 슬롯(상단)에 표시
                tvCellStart.setText("휴일");
                tvCellStart.setTextColor(Color.parseColor("#D32F2F"));
                tvCellStart.setVisibility(View.VISIBLE);
            } else {
                tvCellStart.setVisibility(View.INVISIBLE);
            }
        }

        // 배경 / 날짜 색상
        int col = position % 7;

        if (dayNum == selectedDay) {
            convertView.setBackgroundColor(Color.parseColor("#E3F2FD"));
            tvDay.setTextColor(Color.parseColor("#0D47A1"));
        } else if (dayNum == today) {
            convertView.setBackgroundColor(Color.parseColor("#FFF9C4"));
            if (col == 0 || isHolidayDay) {
                tvDay.setTextColor(Color.parseColor("#D32F2F"));
            } else if (col == 6) {
                tvDay.setTextColor(Color.parseColor("#1565C0"));
            } else {
                tvDay.setTextColor(Color.parseColor("#212121"));
            }
        } else if (record != null && leaveType == WorkRecord.LEAVE_ANNUAL) {
            convertView.setBackgroundColor(Color.parseColor("#FFEBEE"));
            tvDay.setTextColor(Color.parseColor("#C62828"));
        } else if (record != null && leaveType == WorkRecord.LEAVE_HALF) {
            convertView.setBackgroundColor(Color.parseColor("#FFE0B2")); // 연한 주황
            tvDay.setTextColor(Color.parseColor("#E65100"));
        } else if (record != null && leaveType == WorkRecord.LEAVE_PUBLIC) {
            convertView.setBackgroundColor(Color.parseColor("#E8F5E9"));
            tvDay.setTextColor(Color.parseColor("#1B5E20"));
        } else {
            if (isHolidayDay) {
                convertView.setBackgroundColor(Color.parseColor("#FFEBEE")); // 연차와 동일
            } else {
                convertView.setBackgroundColor(Color.WHITE);
            }
            if (col == 0 || isHolidayDay) {
                tvDay.setTextColor(Color.parseColor("#D32F2F"));
            } else if (col == 6) {
                tvDay.setTextColor(Color.parseColor("#1565C0"));
            } else {
                tvDay.setTextColor(Color.parseColor("#212121"));
            }
        }

        return convertView;
    }
}
