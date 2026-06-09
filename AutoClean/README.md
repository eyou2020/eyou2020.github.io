# AutoClean

사용자가 지정한 폴더를 예약된 시간에 자동으로 청소하는 Android 앱입니다.

## 주요 기능

- **폴더 선택**: SAF(Storage Access Framework)를 통해 안전하게 폴더 선택
- **시간 예약**: 원하는 시간에 자동 실행 예약
- **반복 설정**: 매일 / 평일 / 주말 / 특정 요일 / 한 번만
- **재부팅 복원**: 기기 재시작 후에도 예약 자동 복원
- **실행 통계**: 마지막 실행 시각, 삭제된 파일 수 기록
- **즉시 실행**: 예약 없이 즉시 청소 가능

## 기술 스택

- **언어**: Kotlin
- **아키텍처**: MVVM
- **DB**: Room
- **스케줄링**: WorkManager
- **파일 접근**: Storage Access Framework (SAF)
- **UI**: Material Design 3 (다크 테마)

## 빌드 요구사항

- Android Studio Hedgehog 이상
- minSdk 26 (Android 8.0)
- targetSdk 34 (Android 14)
- JDK 17

## 프로젝트 구조

```
app/src/main/java/com/autoclean/app/
├── model/          - CleanSchedule 데이터 모델
├── data/           - Room DB, DAO, Repository
├── service/        - WorkManager Worker, ForegroundService, 알림
├── receiver/       - 재부팅 수신기
└── ui/             - Activity, ViewModel, Adapter
```

## 권한

| 권한 | 용도 |
|------|------|
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 예약 복원 |
| `FOREGROUND_SERVICE` | 파일 삭제 중 포그라운드 서비스 |
| `POST_NOTIFICATIONS` | 완료/오류 알림 표시 |
| `WAKE_LOCK` | 작업 중 절전모드 방지 |
