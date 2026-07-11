# WorkHoursApp — 개발 가이드

Android 근무시간 기록·계산 앱 (Java / SQLite).

---

## 프로젝트 구조

```
WorkHoursApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/workhours/
│   │   ├── MainActivity.java          # 캘린더 메인 화면
│   │   ├── DayDetailActivity.java     # 하루 상세 입력/편집
│   │   ├── WorkSettingsActivity.java  # 요일별 출퇴근 기본값 설정
│   │   ├── WorkRecord.java            # 데이터 모델 (하루 근무 기록)
│   │   ├── DatabaseHelper.java        # SQLite CRUD
│   │   ├── CalendarAdapter.java       # 달력 RecyclerView 어댑터
│   │   ├── WorkSettings.java          # SharedPreferences 래퍼 (기본 시간 설정)
│   │   └── KoreanHolidays.java        # 공휴일 데이터
│   └── res/
│       ├── layout/                    # XML 레이아웃
│       ├── drawable/                  # 버튼 배경 등 벡터 리소스
│       └── values/                    # colors, strings, styles
├── build.gradle                       # 루트 Gradle (AGP 8.13.2)
├── app/build.gradle                   # 앱 Gradle (compileSdk 36, minSdk 23)
├── settings.gradle
├── gradle.properties
└── CLAUDE.md                          # 이 파일
```

---

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Android Gradle Plugin | 8.13.2 |
| compileSdkVersion | 36 |
| minSdkVersion | 23 |
| targetSdkVersion | 36 |
| Java | 1.8 (SOURCE / TARGET) |
| Gradle Wrapper | `gradle/wrapper/gradle-wrapper.properties` 참조 |

의존성:
- `androidx.appcompat:appcompat:1.6.1`
- `androidx.core:core:1.10.1`
- `androidx.multidex:multidex:2.0.1`
- Firebase BOM `34.14.0` (analytics, database, auth)
- `com.google.android.gms:play-services-auth:21.2.0`

---

## 새 환경에서 세팅하는 법

1. **레포 클론**
   ```
   git clone <repo-url>
   cd WorkHoursApp
   ```

2. **`local.properties` 생성** (`.gitignore`에 포함되므로 직접 생성 필요)
   ```
   sdk.dir=C:\\Users\\<사용자명>\\AppData\\Local\\Android\\Sdk
   ```
   Android Studio에서 프로젝트를 열면 자동 생성됨.

3. **`app/google-services.json` 배치** (Firebase 사용 시)
   - Firebase 콘솔 → 프로젝트 설정 → 앱 등록 후 파일 다운로드
   - `app/` 폴더에 복사
   - Firebase 미사용 시: `app/build.gradle`에서 `com.google.gms.google-services` 플러그인 및 Firebase 의존성 제거

4. **빌드**
   ```
   ./gradlew assembleDebug
   ```
   또는 Android Studio → Run (▶)

---

## 핵심 로직 메모

### WorkRecord.java
- `customBreakMinutes = -1` → 휴게시간 자동계산
- `customBreakMinutes >= 0` → 사용자 지정값 사용
- 자동계산 규칙: 체류 540분↑ → 60분 / 270분↑ → 30분 / 그 외 → 0분
- `getBreakMinutes()` : 위 둘을 합쳐서 실제 사용값 반환
- `getNetWorkMinutes()` : 연차 시 0, 아니면 `체류 - 휴게`

### DayDetailActivity.java
- `updateDisplay()` : 출퇴근 변경 시 화면 갱신. **customBreakMinutes가 설정돼 있으면 그 값 우선 표시**
- TextWatcher : 사용자가 휴게시간 EditText를 수정하면 즉시 `record.setCustomBreakMinutes()` 반영
- `updatingBreak` 플래그 : 코드에서 EditText를 programmatically 변경할 때 TextWatcher 피드백 루프 방지

### DatabaseHelper.java
- SQLite, 단일 테이블 `work_records`
- 컬럼: `date(PK), start_hour, start_minute, end_hour, end_minute, custom_break_minutes, is_annual_leave`

---

## 주의사항

- `local.properties`는 절대 커밋하지 않는다 (로컬 SDK 경로 포함).
- `google-services.json`도 커밋하지 않는다 (Firebase 인증 정보 포함).
- Firebase를 쓰지 않는 환경이면 `app/build.gradle`에서 관련 의존성을 제거해야 빌드 오류 없음.
