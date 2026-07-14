# WelfareApp — 개발 가이드

복지포인트/식대 결제 UI 프로토타입 앱 (Kotlin, ViewBinding). 실제 결제·서버 연동 없이 화면 흐름만 구현된 데모 단계.

---

## Git 저장소 설정

- **로컬 개발 경로**: `C:\claude\WelfareApp\` (이 폴더, Android Studio 프로젝트 루트)
- **Git 저장소**: `C:\claude\ParkingApp\` (git root — eyou2020.github.io 모노레포 클론)
- **소스 커밋 위치**: `C:\claude\ParkingApp\WelfareApp\` (이 폴더의 소스를 복사해 커밋)
- **원격 저장소**: https://github.com/eyou2020/eyou2020.github.io
- **원격 경로**: https://github.com/eyou2020/eyou2020.github.io/tree/master/WelfareApp
- **브랜치**: master

같은 모노레포에 ParkingApp, CarManager, WorkHoursApp도 함께 들어있다. (`C:\claude\WelfareApp` 자체에도 독립 git 저장소가 남아있지만, 실제 게시는 이 경로를 통해 이루어진다.)

### 작업 흐름
1. 이 폴더(`C:\claude\WelfareApp`)에서 Android Studio로 개발
2. 변경된 소스 파일을 `C:\claude\ParkingApp\WelfareApp\`에 복사
3. 커밋/푸시는 `C:\claude\ParkingApp`에서 실행 (`.gradle`, `.idea`, `local.properties`, `app/build`는 `.gitignore`로 제외됨)

---

## 프로젝트 구조

```
WelfareApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/welfare/app/
│   │   ├── MainActivity.kt              # 홈 화면 — 포인트 카드, 카테고리 그리드, 배너, 상품 리스트, 하단 네비
│   │   ├── RestaurantDetailActivity.kt  # 식당(급식) 상세 화면
│   │   ├── AmountInputActivity.kt       # 결제 금액 numpad 입력
│   │   ├── PaymentActivity.kt           # 결제 확인/완료 화면
│   │   ├── IntentKeys.kt                # Intent extra 키 상수
│   │   ├── Product.kt / ProductAdapter.kt
│   │   └── BannerAdapter.kt             # ViewPager2 배너 어댑터
│   └── res/
│       ├── layout/                      # activity_*, item_* XML
│       ├── drawable/                    # 아이콘·배경 벡터/셰이프 리소스
│       └── values/                      # colors, strings, themes
├── build.gradle / app/build.gradle
├── settings.gradle / gradle.properties
└── CLAUDE.md                            # 이 파일
```

화면 흐름: `MainActivity` → `RestaurantDetailActivity` → `AmountInputActivity` → `PaymentActivity`(완료 시 `MainActivity`로 복귀).

---

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Android Gradle Plugin | 8.2.2 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| Java/Kotlin target | 17 |

의존성: `core-ktx`, `appcompat`, `material`, `constraintlayout`, `recyclerview`, `viewpager2` (Firebase·네트워킹 없음).

---

## 핵심 로직 메모

- **아직 미구현**: 카테고리 그리드, 배너 클릭, 쿠폰 선택, 포인트 사용, 카드 변경 등은 전부 "준비 중입니다" Toast만 표시(`showComingSoon`)
- **AmountInputActivity**: numpad로 금액 누적 입력. `+`/`-` 버튼과 퀵버튼(±100/±1000)으로 조정, 백스페이스는 자릿수 삭제 후 총액 나눗셈으로 처리
- **PaymentActivity**: 포인트는 항상 0으로 표시(`point_available_format`), 실제 결제 API 연동 없이 Toast로 완료 처리 후 홈으로 복귀

---

## 주의사항

- `local.properties`는 절대 커밋하지 않는다 (로컬 SDK 경로 포함)
- 실제 결제/포인트 연동 전 단계이므로 새 기능 추가 시 기존 "준비 중" 패턴과의 정합성 확인
