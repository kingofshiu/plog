# P.Log — 폴스타4 전기차 주행·충전 로그 (Android Automotive OS)

폴스타4 차량 화면에서 직접 동작하는 개인용 전기차 주행·충전 로그 앱입니다.
USB 사이드로드로 설치하며, 외부 라이브러리 0개(플랫폼 API만)로 만들어졌습니다.

## 주요 기능

- **주행 자동 기록** — GPS 기반 자동 감지 (출발/도착, 거리, 경로, 속도)
- **충전 자동 기록** — 충전 감지, kWh·요금 계산, 급속/완속 구분, 충전 속도 곡선
- **충전소 자동 인식** — 충전 시작 시 GPS 좌표로 한국환경공단 전기자동차 충전소 정보
  OpenAPI를 조회해 충전소명·운영사를 자동 식별하고 요금을 자동 계산
- **충전소별 단가 기억** — 기록에서 실제 결제 단가를 수정하면 그 충전소에 기억되어 다음부터 자동 적용
- **주차 위치** — 주행 종료 지점 자동 저장 + 지도 표시 (VWorld 다크 지도)
- **소모품 관리** — 타이어·브레이크 패드·필터 등 교체 주기 추적
- **웹 로그** — 기록을 Supabase(무료)에 업로드해 폰/PC 브라우저에서 조회 (`web/p4log-web.html`)

## 구조

```
app/                     차량 앱 (Android Automotive OS, Kotlin)
web/p4log-web.html       웹 로그 (단일 HTML, Supabase 직접 조회)
supabase/                서버 테이블 생성 SQL
```

- 차량 데이터는 `android.car` 리플렉션으로 읽음 (일반 SDK로 빌드 가능)
- 주행거리는 GPS 합산 (적산거리 속성은 사이드로드 앱에 비공개)
- DB는 SQLite, 동기화는 Supabase REST(PostgREST) upsert

## 빌드 전 필요한 키 3개

이 저장소에는 API 키가 포함되어 있지 않습니다. 본인 키를 발급받아 입력하세요.

| 키 | 발급처 | 입력 위치 |
|---|---|---|
| Supabase URL·anon 키 | supabase.com (무료) | `Prefs.kt`의 `DEFAULT_SB_URL/KEY` 또는 앱 설정 화면 |
| VWorld 지도 키 | vworld.kr (무료) | `app/src/main/assets/map.html`, `web/p4log-web.html` |
| 충전소 정보 API 키 | data.go.kr "한국환경공단_전기자동차 충전소 정보" (무료) | `EvStations.kt` |

Supabase는 `supabase/supabase_setup.sql`을 SQL Editor에서 실행해 테이블을 만들면 됩니다.
키를 입력하지 않아도 GPS 주행 기록 등 로컬 기능은 동작합니다.

## 빌드

```
gradlew assembleDebug
```

산출물: `app/build/outputs/apk/debug/app-debug.apk` → USB(FAT32)로 차량에 사이드로드.

## 참고

- minSdk 29 / targetSdk 34 / Kotlin / AGP 8.5.2
- 개인 프로젝트이며 폴스타·볼보와 무관합니다. 사용에 따른 책임은 사용자에게 있습니다.
