package com.workhours;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite DB 헬퍼
 * v1 → v2 : break_minutes 컬럼 추가
 * v2 → v3 : is_annual_leave 컬럼 추가
 * v3 → v4 : leave_type 컬럼 추가 (is_annual_leave 값 마이그레이션),
 *            vacation_settings 테이블 추가
 * v4 → v5 : outing_records 테이블 추가
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "work_hours.db";
    private static final int    DB_VERSION = 5;

    // work_records 테이블
    private static final String TABLE_WORK      = "work_records";
    private static final String COL_DATE        = "date";
    private static final String COL_START_H     = "start_hour";
    private static final String COL_START_M     = "start_minute";
    private static final String COL_END_H       = "end_hour";
    private static final String COL_END_M       = "end_minute";
    private static final String COL_BREAK_MIN   = "break_minutes";
    private static final String COL_ANNUAL      = "is_annual_leave";   // 레거시 (읽기용)
    private static final String COL_LEAVE_TYPE  = "leave_type";        // 신규

    // monthly_target 테이블
    private static final String TABLE_MONTHLY   = "monthly_target";
    private static final String COL_MONTH       = "month";
    private static final String COL_TARGET_MIN  = "target_minutes";

    // outing_records 테이블
    private static final String TABLE_OUTING     = "outing_records";
    private static final String COL_OUT_ID       = "id";
    private static final String COL_OUT_DATE     = "date";
    private static final String COL_OUT_START    = "start_sec";
    private static final String COL_OUT_END      = "end_sec";

    // vacation_settings 테이블
    private static final String TABLE_VACATION       = "vacation_settings";
    private static final String COL_VAC_ID           = "id";
    private static final String COL_VAC_START        = "start_year_month";
    private static final String COL_VAC_END          = "end_year_month";
    private static final String COL_VAC_ANNUAL_DAYS  = "annual_days";

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_WORK + " ("
                + COL_DATE       + " TEXT PRIMARY KEY, "
                + COL_START_H    + " INTEGER, "
                + COL_START_M    + " INTEGER, "
                + COL_END_H      + " INTEGER, "
                + COL_END_M      + " INTEGER, "
                + COL_BREAK_MIN  + " INTEGER DEFAULT -1, "
                + COL_ANNUAL     + " INTEGER DEFAULT 0, "
                + COL_LEAVE_TYPE + " INTEGER DEFAULT 0"
                + ")");
        db.execSQL("CREATE TABLE " + TABLE_MONTHLY + " ("
                + COL_MONTH      + " TEXT PRIMARY KEY, "
                + COL_TARGET_MIN + " INTEGER"
                + ")");
        db.execSQL("CREATE TABLE " + TABLE_VACATION + " ("
                + COL_VAC_ID          + " INTEGER PRIMARY KEY, "
                + COL_VAC_START       + " TEXT, "
                + COL_VAC_END         + " TEXT, "
                + COL_VAC_ANNUAL_DAYS + " INTEGER DEFAULT 15"
                + ")");
        db.execSQL("CREATE TABLE " + TABLE_OUTING + " ("
                + COL_OUT_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_OUT_DATE  + " TEXT NOT NULL, "
                + COL_OUT_START + " INTEGER NOT NULL, "
                + COL_OUT_END   + " INTEGER DEFAULT -1"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_WORK
                    + " ADD COLUMN " + COL_BREAK_MIN + " INTEGER DEFAULT -1");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_WORK
                    + " ADD COLUMN " + COL_ANNUAL + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            // leave_type 컬럼 추가
            db.execSQL("ALTER TABLE " + TABLE_WORK
                    + " ADD COLUMN " + COL_LEAVE_TYPE + " INTEGER DEFAULT 0");
            // is_annual_leave(0/1) 값을 leave_type으로 마이그레이션
            // is_annual_leave=1 → LEAVE_ANNUAL=1 (값 동일)
            db.execSQL("UPDATE " + TABLE_WORK
                    + " SET " + COL_LEAVE_TYPE + " = " + COL_ANNUAL);
            // vacation_settings 테이블 추가
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_VACATION + " ("
                    + COL_VAC_ID          + " INTEGER PRIMARY KEY, "
                    + COL_VAC_START       + " TEXT, "
                    + COL_VAC_END         + " TEXT, "
                    + COL_VAC_ANNUAL_DAYS + " INTEGER DEFAULT 15"
                    + ")");
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_OUTING + " ("
                    + COL_OUT_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_OUT_DATE  + " TEXT NOT NULL, "
                    + COL_OUT_START + " INTEGER NOT NULL, "
                    + COL_OUT_END   + " INTEGER DEFAULT -1"
                    + ")");
        }
    }

    // ── 근무 기록 CRUD ────────────────────────────────────────

    public void saveWorkRecord(WorkRecord r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_DATE,       r.getDate());
        cv.put(COL_START_H,    r.getStartHour());
        cv.put(COL_START_M,    r.getStartMinute());
        cv.put(COL_END_H,      r.getEndHour());
        cv.put(COL_END_M,      r.getEndMinute());
        cv.put(COL_BREAK_MIN,  r.getCustomBreakMinutes());
        cv.put(COL_LEAVE_TYPE, r.getLeaveType());
        cv.put(COL_ANNUAL,     r.isAnnualLeave() ? 1 : 0); // 레거시 컬럼도 동기화
        db.insertWithOnConflict(TABLE_WORK, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    public WorkRecord getWorkRecord(String date) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_WORK, null, COL_DATE + "=?",
                new String[]{date}, null, null, null);
        WorkRecord record = null;
        if (c.moveToFirst()) record = fromCursor(c);
        c.close(); db.close();
        return record;
    }

    public Map<String, WorkRecord> getMonthRecords(String yearMonth) {
        Map<String, WorkRecord> map = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_WORK, null,
                COL_DATE + " LIKE ?", new String[]{yearMonth + "-%"},
                null, null, null);
        while (c.moveToNext()) {
            WorkRecord r = fromCursor(c);
            map.put(r.getDate(), r);
        }
        c.close(); db.close();
        return map;
    }

    public void deleteWorkRecord(String date) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_WORK, COL_DATE + "=?", new String[]{date});
        db.close();
    }

    /** 해당 월 근무 기록 전체 삭제 */
    public void deleteMonthRecords(String yearMonth) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_WORK, COL_DATE + " LIKE ?", new String[]{yearMonth + "-%"});
        db.close();
    }

    /** 지정 기간 내 휴가 기록(연차/반차/공가) 일자순 조회 */
    public List<WorkRecord> getLeaveRecordsInPeriod(String startYearMonth, String endYearMonth) {
        List<WorkRecord> list = new ArrayList<>();
        // 기간의 첫날/마지막날 문자열
        String startDate = startYearMonth + "-01";
        // 마지막달 말일 구하기: endYearMonth + "-31" 보다 크거나 같은 조건으로 처리
        // 간단히 "YYYY-MM-99" 로 비교하면 말일 이후를 커버 (날짜 정렬은 문자열 YYYY-MM-DD 기준)
        String endDate = endYearMonth + "-31";
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_WORK, null,
                COL_DATE + " >= ? AND " + COL_DATE + " <= ? AND " + COL_LEAVE_TYPE + " > 0",
                new String[]{startDate, endDate},
                null, null, COL_DATE + " ASC");
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close(); db.close();
        return list;
    }

    private WorkRecord fromCursor(Cursor c) {
        String date = c.getString(c.getColumnIndexOrThrow(COL_DATE));
        int sh  = c.getInt(c.getColumnIndexOrThrow(COL_START_H));
        int sm  = c.getInt(c.getColumnIndexOrThrow(COL_START_M));
        int eh  = c.getInt(c.getColumnIndexOrThrow(COL_END_H));
        int em  = c.getInt(c.getColumnIndexOrThrow(COL_END_M));
        int brk = c.getInt(c.getColumnIndexOrThrow(COL_BREAK_MIN));
        // leave_type 컬럼 우선 사용 (없으면 is_annual_leave 폴백)
        int leaveTypeIdx = c.getColumnIndex(COL_LEAVE_TYPE);
        int leaveType;
        if (leaveTypeIdx >= 0 && !c.isNull(leaveTypeIdx)) {
            leaveType = c.getInt(leaveTypeIdx);
        } else {
            int annualIdx = c.getColumnIndex(COL_ANNUAL);
            leaveType = (annualIdx >= 0 && c.getInt(annualIdx) == 1)
                    ? WorkRecord.LEAVE_ANNUAL : WorkRecord.LEAVE_NONE;
        }
        return new WorkRecord(date, sh, sm, eh, em, brk, leaveType);
    }

    // ── 월별 목표시간 ─────────────────────────────────────────

    public void saveMonthlyTarget(String yearMonth, int targetMinutes) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_MONTH,      yearMonth);
        cv.put(COL_TARGET_MIN, targetMinutes);
        db.insertWithOnConflict(TABLE_MONTHLY, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    /** 저장된 월 목표(분). 없으면 -1 */
    public int getMonthlyTarget(String yearMonth) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MONTHLY, null, COL_MONTH + "=?",
                new String[]{yearMonth}, null, null, null);
        int t = -1;
        if (c.moveToFirst()) t = c.getInt(c.getColumnIndexOrThrow(COL_TARGET_MIN));
        c.close(); db.close();
        return t;
    }

    /** 해당 월 의무 시간(수동 설정) 삭제 */
    public void deleteMonthlyTarget(String yearMonth) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MONTHLY, COL_MONTH + "=?", new String[]{yearMonth});
        db.close();
    }

    // ── 휴가 설정 ─────────────────────────────────────────────

    /** 휴가 설정 저장 (단일 행, id=1 고정) */
    public void saveVacationSettings(VacationSettings vs) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_VAC_ID,          1);
        cv.put(COL_VAC_START,       vs.startYearMonth);
        cv.put(COL_VAC_END,         vs.endYearMonth);
        cv.put(COL_VAC_ANNUAL_DAYS, vs.annualDays);
        db.insertWithOnConflict(TABLE_VACATION, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    /** 저장된 휴가 설정 반환. 없으면 null */
    public VacationSettings getVacationSettings() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_VACATION, null, COL_VAC_ID + "=1",
                null, null, null, null);
        VacationSettings vs = null;
        if (c.moveToFirst()) {
            vs = new VacationSettings(
                    c.getString(c.getColumnIndexOrThrow(COL_VAC_START)),
                    c.getString(c.getColumnIndexOrThrow(COL_VAC_END)),
                    c.getInt(c.getColumnIndexOrThrow(COL_VAC_ANNUAL_DAYS))
            );
        }
        c.close(); db.close();
        return vs;
    }

    // ── 외출 기록 CRUD ────────────────────────────────────────

    public long insertOuting(String date, int startSec) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUT_DATE,  date);
        cv.put(COL_OUT_START, startSec);
        cv.put(COL_OUT_END,   -1);
        long id = db.insert(TABLE_OUTING, null, cv);
        db.close();
        return id;
    }

    public void finishOuting(long id, int endSec) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUT_END, endSec);
        db.update(TABLE_OUTING, cv, COL_OUT_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public List<OutingRecord> getOutings(String date) {
        List<OutingRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_OUTING, null,
                COL_OUT_DATE + "=?", new String[]{date},
                null, null, COL_OUT_START + " ASC");
        while (c.moveToNext()) {
            list.add(new OutingRecord(
                    c.getLong(c.getColumnIndexOrThrow(COL_OUT_ID)),
                    date,
                    c.getInt(c.getColumnIndexOrThrow(COL_OUT_START)),
                    c.getInt(c.getColumnIndexOrThrow(COL_OUT_END))
            ));
        }
        c.close(); db.close();
        return list;
    }

    public void updateOuting(long id, int startSec, int endSec) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUT_START, startSec);
        cv.put(COL_OUT_END, endSec);
        db.update(TABLE_OUTING, cv, COL_OUT_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    public void deleteOuting(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_OUTING, COL_OUT_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }
}
