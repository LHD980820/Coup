# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | ✅ | 런타임·AI·멀티 전송 계층(메모리 구현) 완료. `core/engine,ai,runtime` 255개 테스트 |
| 3. 신규 UI 셸 + 싱글플레이 | 🚧 (싱글플레이·튜토리얼 Compose 화면 ✅) | (C) 순수 Kotlin: 화면 상태 `core/presentation` ✅(ADR 0004), Firestore 전송 `core/remote` ✅(ADR 0005), 방/로비 `core/lobby` ✅(ADR 0006), 튜토리얼 `core/presentation/tutorial` ✅, 사용자·레이팅·랭킹 `core/account` + `FirestoreUserRepository` ✅(ADR 0007), Firestore 방 저장소·규칙 ✅(ADR 0008), Firebase 어댑터 `:data` + 에뮬레이터 통합 테스트 ✅(ADR 0009), 로그인·로비·대기실 `core/shell` + Compose 화면 ✅(ADR 0010), 구글 로그인 ✅(ADR 0011, 실기기 전 SHA 등록 필요), 재접속 ✅(ADR 0012), 랭킹·전적 화면 ✅(ADR 0013), 비공개 방 코드 공유 ✅(ADR 0014) / A안: Android 빌드 가능한 새 세션에서 Compose UI ⬜ |
| 4 | ✅ | 신규 멀티플레이(`:data`, 규칙, 로비·대기실·재접속·이어받기)는 Phase 3~7 작업에 함께 들어갔다 |
| 5. 전면 전환 & 정리 | ✅ | 옛 Activity·Fragment·XML 레이아웃·옛 의존성을 모두 제거하고 `MainActivity` 하나(Compose)로 전환, targetSdk 36 (ADR 0018) |
| 6. 폴리시 | 🚧 (게임 화면 폴리시·애니메이션 ✅) | 남은 시간 링·역할 능력 시트·대화면 2단·응답 시트·카드 뒤집기·코인·시트 등장 연출·설정(애니메이션 속도) ✅ / HARD AI ✅(ADR 0019) / 남은 것: 사운드(소리 자원 필요), 하우스룰 설정 UI |
| 7. 서버 판정 | 🚧 (코드·테스트 ✅, 배포는 오너 작업) | 검증형 방장 권위(오너 결정): 방장이 모든 수를 기록, Cloud Function(`server/`)이 끝난 판을 같은 엔진으로 다시 돌려 검증하고 레이팅을 반영. 클라이언트 레이팅 쓰기·정산 게이트 제거(ADR 0015). 시드 섞기·버려진 판(방장만 패배)·방 정리 + 5분마다 정리 함수(ADR 0016). 방장 이어받기(규칙만으로, ADR 0017) |

### Phase 1 세부 단계 (설계 §13)

| # | 단계 | 상태 |
|---|---|---|
| 1 | 식별자/카드/플레이어/RNG + 직렬화 | ✅ |
| 2 | RuleSet 정의 타입 + Classic + 검증기 | ✅ |
| 3 | GameState / Phase / ResolutionStep + newGame (+ RuleSetConfig/Registry) | ✅ |
| 4 | legalOptions(ChooseAction) + DeclareAction + 응답 없는 행동 (+ 영향력 상실 선택, 탈락/게임 종료 기본 판정) | ✅ |
| 5 | 응답 창 + Pass + 효과 해결 + EndTurn | ✅ |
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ✅ |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ✅ |
| 8 | 교환 | ✅ |
| 9 | 탈락 / 게임 종료 / 기권 | ✅ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ✅ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ✅ |

## 다음 할 일
계획 변경(오너, 2026-10-04): **C -> A안(Android 빌드가 가능한 새 클라우드 세션/환경에서 UI 작업) -> 현재 세션 복귀**. 아래 "A안 전제 조건" 참고.
1. **(C) 남은 순수 Kotlin 부분**:
   - ~~방/로비 도메인~~ ✅ 완료(아래 메모). 남은 후속: Firestore `RoomStore`(DocumentStore 트랜잭션 필요) + 방 접근 규칙.
   - ~~튜토리얼 시나리오~~ ✅ 완료(아래 메모).
   - ~~사용자/랭킹 도메인~~ ✅ 완료(아래 메모).
   - ~~정산 게이트~~ ✅(오너 결정: 제안대로). 규칙의 `isSettled(uid)`를 방 입장에 연결하는 것은 아래 방 규칙에서.
   - ~~Firestore `RoomStore` + 방 규칙(정산 게이트 연결, 게임 좌석 동의)~~ ✅ 완료(아래 메모).
   - ~~에뮬레이터 규칙 검증~~ ✅ 완료(이 클라우드 환경에서 가능했다 — 아래 메모).
   - **(C)의 순수 Kotlin 부분 일단락.** ~~실제 Firebase 어댑터(`DocumentStore` 구현) 통합 테스트~~ ✅ 완료(ADR 0009, 아래 메모). 남은 것: 아래 Android 작업(로그인·로비 UI에서 어댑터 연결).
2. **A안 전제 조건** (오너가 환경에서 설정): 새 세션이 Android 앱을 컴파일하려면 클라우드 환경의 **네트워크 허용 목록**에 `dl.google.com`, `maven.google.com`(AGP/Firebase 아티팩트와 Android SDK 다운로드), `services.gradle.org`(Gradle 배포판)이 있어야 하고, **설정 스크립트**로 JDK 17 + Android 명령줄 도구 + `platforms;android-<compileSdk>` + `build-tools`를 설치해야 한다. 환경 변경은 새 세션부터 적용된다. 새 세션의 첫 작업은 `./gradlew assembleDebug` 로 기준선을 확인하는 것.
3. **A안 작업 범위(새 세션)**: `:app`에 `core` 연결(`includeBuild("core")`), 단일 Activity Compose 셸, 디자인 시스템, `GameScreen`(= `GameController.state`를 그리기), 싱글플레이 설정/결과 화면, `RoleUiCatalog`/`ActionUiCatalog`, Firestore `DocumentStore` 어댑터. 로직은 모두 `core`에 있으므로 UI는 얇게.
4. **세션 간 인계 규칙**: 두 세션이 같은 브랜치를 동시에 건드리지 않는다. 새 세션은 `core/`를 **읽기 전용**으로 취급하고(필요한 변경은 이 문서에 요청으로 적는다) `app/` 아래만 수정, 이쪽 세션은 `core/`와 `docs/`만 수정. 브랜치는 따로(예: `claude/android-ui`), 병합은 순서대로.

## 이번 단계 메모 (Phase 6 4차 — HARD AI, ADR 0019)
- 결정화 몬테카를로: 표본 40개(결정당 400ms 상한) × 후보 수마다 14결정 롤아웃, 평균 보상 최대. NORMAL의 선택에 작은 사전값, 시뮬레이션 상대는 성격을 섞는다.
- 측정(NORMAL 상대): 2인 0.71, 3인 0.75, 4인 0.69, 6인 0.51(기준선 0.50/0.33/0.25/0.17). 판 끝까지 두는 롤아웃은 오히려 약했다(분산) — 측정표는 ADR 0019.
- 화면: 싱글플레이 설정의 난이도에 "어려움". 치팅 방지 정적 검사·뷰 동치·1만 결정 합법성 테스트가 HARD에도 적용된다.
- 테스트가 찾은 것: 표본이 하나도 안 될 때 NORMAL에게 두 번 물어 다른 수가 나올 수 있던 문제(이미 고른 수를 내도록 수정).

## 이전 단계 메모 (Phase 6 3차 — 애니메이션과 설정)
- **연출 3종**: 상대 카드가 공개되면 뒷면에서 앞면으로 **뒤집힌다**(`FlipCard`; 화면을 처음 그릴 때 이미 공개된 카드는 그대로, 그 뒤 공개되는 카드만). 코인 수가 바뀌면 숫자가 **올라가거나 내려가며** 바뀐다(`CoinsText`; 스크린리더에는 새 값 하나만). 응답 시트가 아래에서 **올라온다**(`SheetEntrance`). 탈락·공개로 흐려지는 투명도도 부드럽게 따라간다. 상태는 연출과 상관없이 바로 바뀌어 있고 연출은 덧입힘일 뿐이다.
- **속도 설정**: 홈의 "설정" -> 애니메이션 보통 / 빠르게 / 끄기(`SettingsScreen`, `AppSettings`, 기기 저장 `PrefsSettingsStore`; 없거나 모르는 값이면 보통). 기기의 "애니메이션 없음"(전환 애니메이션 배율 0)이 켜져 있으면 고른 값과 상관없이 끈다 — 움직임에 민감한 사용자 보호(`effectiveSpeed`).
- 연출 값은 `LocalMotion`으로 내려가고 **기본은 꺼짐**이다: 미리보기와 자동 테스트는 움직임 없이 결정적이고(기존 화면 테스트는 그대로 통과), 앱만 설정에 따라 켠다(`motionFor`).
- 테스트 15(`MotionTest`): 속도 규칙·저장·설정 화면 흐름, 뒤집기 도중 진행·빠른 속도·처음부터 공개된 카드·꺼짐, 코인 도중(옛/새 숫자 공존, 스크린리더는 새 값)·꺼짐, 시트 올라옴·꺼짐. 시계를 직접 돌려 연출 도중을 확인한다(`FlipProgress` 접근성 속성). 변이 15종 중 12 검출, 남은 3종: 동치 2(꺼짐일 때 길이 0 연출은 한 프레임에 끝남) + 눈으로만 보이는 것 1(뒤집힌 앞면의 거울상 보정).
- **하지 않은 것**: 사운드 — 소리 자원(효과음 파일)이 저장소에 없다. 자원이 정해지면 같은 설정 화면에 켜기/끄기와 음량을 더하면 된다. 코인이 한 플레이어에게서 다른 플레이어로 날아가는 연출은 숫자 올림/내림으로 대신했다(카드 위치 좌표 추적이 필요). 뒤집힌 앞면이 거울상이 아닌지, 부드러운지는 실기기에서 눈으로 확인한다.

## 이전 단계 메모 (Phase 6 2차 — 응답 시트)
- 응답할 차례(허용·도전·막기)가 오면 화면 맨 아래에 **응답 시트**가 붙는다(`ResponseSheet`). 설계 문서는 모달이라고 했지만 **비모달**로 했다: 도전·막기 판단에는 표·기록·내 카드가 필요한데 모달의 어두운 막이 그것을 가리고, 튜토리얼 안내 카드와도 겹친다.
- 시트에는 질문("OO의 세금에 어떻게 하시겠어요?"), 누가 어떤 역할을 주장하는지, 내 남은 시간, 허용/도전/막기 버튼(블러핑은 글자로도)이 들어 있다. 나타날 때 스크린리더가 "응답할 차례"를 읽는다(`paneTitle`). 보내는 중이면 잠기고, 튜토리얼에서는 안내가 가리키는 버튼만 켜진다. 남은 시간은 응답 중에 시트에만 보인다(배너와 중복 안 됨).
- 테스트가 찾은 것: 작은 화면(320x568dp)에서 막기 버튼이 둘이면 시트가 잘렸다. 기권 버튼을 숨겨 해결하려 했더니 멀티플레이 테스트가 "응답 중에 기권할 수 없다"는 퇴행을 잡았다 -> 기권을 별도 줄에서 **배너 줄 오른쪽**으로 옮겨(항상 닿고 자리를 안 먹음) 해결.
- 등장 애니메이션은 이 묶음에서는 넣지 않았고, 다음 묶음(애니메이션)에서 속도·끄기 설정과 함께 넣었다.
- 테스트 9 추가(`GamePolishTest` 합계 23): 시트 내용·의도 전달·도전 불가·블러핑 표시·터치 타깃 48dp·시간 한 번만·잠김·스크린리더 이름·주장 줄·작은 화면(기권 포함). 변이 9종 검출(터치 타깃 높이 지정은 Material 기본값과 같아 코드에서 뺐다).

## 이전 단계 메모 (Phase 6 1차 — 게임 화면 폴리시)
- **남은 시간 링**: 내가 결정할 차례(`myDeadline`)와 생각 중인 상대(`OpponentUi.deadline`)에 줄어드는 링 + "N초". 5초 이하는 경고색·굵게(숫자는 항상 글자로도 보임). 스크린리더는 "남은 시간 N초"를 읽되 매초 알리지 않는다. 링 전체 길이는 마감을 처음 본 순간의 남은 시간. 시계는 `LocalNowMillis`(기본 기기 시계, 테스트는 고정/전진).
- **역할 능력 시트**: 고르는 결정이 아닐 때 내 카드를 탭하면 바텀시트(카드 그림, 이름, 행동·막기 설명, "기본 규칙 기준" 안내). `RoleUiCatalog.Entry.ability`, 모르는 역할은 폴백 문구. 고르는 결정 중에는 탭이 선택이다.
- **대화면 2단**: 너비 840dp 이상이면 테이블(상대·배너·내 카드·결정) | 기록(최대 40줄) 두 단. 좁은 화면은 그대로(위 스크롤, 아래 고정). 화면 방향 고정이 없어졌으므로(ADR 0018) 가로·태블릿에서 바로 쓰인다.
- 테스트 14(`GamePolishTest`): 시간 표시·올림·0초·숨김 조건·시계 전진, 능력 시트 열기·닫기·역할별 내용·선택 중 비활성, 넓은/좁은 레이아웃. 변이 9종 모두 검출(중복이던 초기값 보정은 한 함수로 합쳐 검출 가능하게 함).
- 아직: 5초 이하 경고색·굵기 같은 시각 표현은 자동 검증하지 않았다(실기기 확인). 카드 뒤집기/코인 이동 애니메이션, 사운드·설정, HARD AI는 다음 묶음.

## 이전 단계 메모 (Phase 5 — 전면 전환과 정리, ADR 0018)
- 옛 화면(`MainActivity` 스플래시, 로그인·가입·재설정, 홈·방 목록·랭킹·내 정보 Fragment, 대기실, 게임방, 결과, 튜토리얼 이미지 Activity, 강제 종료 서비스)과 XML 레이아웃 28개를 지웠다. 새 `MainActivity` 하나가 Compose 흐름(`game/AppFlow.kt`)을 연다. 디버그 전용 런처도 없앴다.
- 안 쓰는 리소스(드로어블 80개, 폰트 7개, 색·치수, 옛 문자열)와 의존성(appcompat, material, constraintlayout, swiperefreshlayout, glide, circleimageview, firebase-storage, play-services-auth)·viewBinding을 제거. 테마는 플랫폼 `Theme.Material.Light.NoActionBar` 기반. APK(release) 약 27MB.
- targetSdk 33 -> 36, 가장자리까지 그리기(`enableEdgeToEdge`)를 켜고, 화면 방향 고정(원래 응용 프로그램 수준의 무효한 속성)을 없앴다.
- 검증: `test lint assembleDebug assembleRelease` 통과(app 87, data 19, 실패 0), lint 오류 0.
- **오너 작업**: 옛 컬렉션(`user`, `game_rooms`, `game_playing`, 옛 `game_result`)은 규칙이 이미 쓰기를 막는다 — 이관이 끝났다고 판단되면 콘솔에서 삭제. `google-services.json`에 옛 패키지(`com.example.coup`) 클라이언트가 남아 있으니 콘솔에서 정리 후 다시 내려받기.

## 이전 단계 메모 (Phase 7 — 방장 이어받기, ADR 0017)
- 방장 생존 신호가 서버 시각이 되고, 60초 넘게 조용하면 좌석의 게스트가 게임·방의 방장을 이어받는다(규칙만으로, 서버 함수 불필요). 이어받은 사람이 백업에서 판을 이어 간다.
- 기록마다 쓴 방장을 남겨, 서버 검증이 방장이 바뀐 판도 검증한다(그때의 방장 외 사람의 수는 명령 문서로 확인).
- 화면은 그대로: `HandoverSession`이 안에서 게스트·방장 세션을 바꾼다. 게스트는 끊김 배너를 본 뒤 자동으로 이어받아 계속 두고, 돌아온 옛 방장은 게스트로 이어 간다.
- 검증: 규칙 재생 78 시나리오 불일치 0, 새 규칙 조건 변이 17개 중 15개 검출(2개 중복 방어), 실제 Android SDK로 실제 서버 시각 62초 대기 확인, 셸 끝까지 2(검증·레이팅 반영까지), 화면 1, 클라이언트 변이 6종 검출.
- 테스트가 찾은 것: 게임만 이어받고 방은 못 받은 상태를 고치지 않던 버그.
- 한계: 이어받기까지 약 65~75초 판이 멈춘다. 이어받은 사람은 모든 패를 안다(방장과 같은 위험).

## 이전 단계 메모 (Phase 7 — 버려진 판·방 정리와 시드 섞기, ADR 0016)
- 시드 = 방장 시드 + 게임 문서의 서버 생성 시각(밀리초) — 방장이 미리 돌려 보고 좋은 패를 고를 수 없다. 검증기도 섞으므로 섞지 않은 판은 무효.
- 버려진 판: 마지막 진행(서버 시각)이 10분 넘은 판을 서버가 끝낸다. 레이팅 판이면 **방장만** 패배(살아 있었으면 꼴찌, 탈락했으면 그 순위). 방장은 결과 없이 "끝남"을 쓸 수 없다.
- 방: 모든 방 쓰기에 서버 시각, 대기실 방장은 3분마다 알림, 15분 조용한 대기실은 로비에서 숨기고 서버가 닫는다. 판이 끝난 방도 15분 뒤 닫는다(진행 중인 판의 방은 두지 않는다).
- `server/` `SweepFunction`: Cloud Scheduler -> Pub/Sub `coup-sweep` -> 5분마다 판 정리 -> 방 정리. 에뮬레이터 테스트 + 로컬 함수 실행기로 확인.
- 검증: 정리 변이 11종·새 규칙 조건 변이 7종 모두 검출(빈틈 2곳은 테스트를 더해 잡음), 규칙 재생 71 시나리오 불일치 0, 실제 Android SDK 18, 서버 7.
- **오너 작업**: 정리 함수 + Pub/Sub 주제 + Scheduler 작업 배포(명령은 ADR 0016 / `firebase/README.md`). 순서: 함수 둘 -> 규칙 -> 앱.
- 버려진 판 게스트 안내(후속): 게스트 화면이 "판이 끝났어요" 안내 + "로비로"(뒤로 가기도 같은 길), 로비에 알림, 기억한 방 삭제. 화면 2 + 전송 1, 변이 6종 검출.
- 버려진 판 방장 안내(후속): 방장 세션도 서버가 끝낸 것을 알고(`endedByServer`) 멈추며, 화면은 "연결이 오래 끊겨 서버가 판을 끝냈어요 — 방장은 패배로 처리돼요" + 본인의 패배 레이팅을 보인다. 런타임 2 + 전송 1 + 화면 1, 변이 14종 검출.

## 이전 단계 메모 (Phase 7 — 서버 판정, ADR 0015)
- 1단계: 권한자가 적용한 모든 명령을 `games/{id}/log/{version}`(서버 시각, 고칠 수 없음)에 뷰와 같은 배치로 기록, 실행 설정(시드)은 게임 생성 배치에서만 만들고 고칠 수 없음.
- 2단계: `core/judge` — `GameVerifier`(설정·빈틈·합법·게스트 명령 문서 묶임·시간 초과(서버 시각)·순위)와 `ServerJudge`(관리자 권한, 한 트랜잭션으로 전원 반영, 판정 표시로 한 번만). 조작 15종 각각 거부, 검증기 변이 22종 중 21종 검출(1종은 중복 방어).
- 3·4단계: 규칙 전환 — 클라이언트는 레이팅·반영 표시·판정을 쓸 수 없음, 정산 게이트(`pendingRatings`·`isSettled`·`UNSETTLED_GAME`) 제거. 앱은 판정을 지켜봄(`observeSettlement`, 결과 화면 "서버가 확인하는 중 / 레이팅 n (±d) / 제외 / 무효").
- 5단계: `server/`(따로 빌드, `includeBuild("../core")`) — `JudgeFunction`(Cloud Functions 2세대, Java 21, `results/{gameId}` 생성 이벤트), `AdminDocumentStore`(서버 클라이언트 라이브러리). 에뮬레이터 통합 테스트 5 + 로컬 함수 실행기로 배포 JAR 확인. CI에 추가.
- 실제 Android SDK가 쓴 기록도 검증됨(`:data` 에뮬레이터 테스트). 재접속(방장 앱 재시작) 판도 검증됨.
- **오너 작업**: Blaze 요금제 + 함수 배포(명령은 ADR 0015 / `firebase/README.md`). 순서: 함수 -> 규칙 -> 앱 — 규칙만 올리면 레이팅이 멈춘다.
- 남는 위험: 방장이 남의 패를 봄(수용), 게스트 명령을 일부러 처리하지 않기(= 시간 초과). 시드 고르기와 버려진 판은 ADR 0016에서 해결.

## 이전 단계 메모 (Phase 3 (A) — 비공개 방 코드 공유)
- 대기실 코드 줄에 복사·공유(`CodeSharer`), 로비 코드 칸에 공유 메시지를 통째로 붙여 넣어도 코드만 추출(`JoinCodes.extract`: "코드:/code:" 이름표 뒤 6글자만). 테스트: 코어 2, 화면 6 + 변이 6종. ADR 0014.
- 아직: 딥링크(앱 링크) 공유 — 도메인 검증이 필요한 오너 작업.

## 이전 단계 메모 (Phase 3 (A) — 랭킹·전적 화면)
- 코어 `RankingModel`/`HistoryModel`(구독이 끊기면 오프라인 표시 + 재구독), 앱 `RankingScreen`/`HistoryScreen`(홈 프로필 카드에서 진입). 테스트: 코어 7, 에뮬레이터 2(실제 규칙에서 정렬 쿼리·남의 전적 거부), 화면 8.
- 알려진 한계: 신규 계정(레이팅 1000, 미플레이)이 많으면 서버의 상위 N명 안에서 걸러져 목록이 짧아질 수 있다 — `plays > 0` 색인 또는 서버 집계로 해결. ADR 0013.
- 아직: 랭킹 상세(상대 프로필 등).

## 이전 단계 메모 (Phase 3 (A) — 재접속)
- 방장 복구(권한자 백업에서 `resumeHost`), 게스트 재입장(대기실이 진행 중인 게임으로 이어 줌, "게임으로 돌아가기"), 앱 재시작 시 기억한 방으로 복귀, 연결 배너, 뒤로 가기 = 기권 확인.
- 런타임 끊김 내성: 방장 게시 실패 시 최신 상태 다시 게시(결과 기록도 재시도), 게스트 구독이 끊기면 다시 구독.
- 테스트가 찾은 제품 버그 3개(초기화 순서 NPE로 게스트 화면 멈춤, "여는 중" 해제 순서, 결과 완료 표시 순서)를 고쳤다. 실제 규칙 위 복구는 에뮬레이터로 확인. ADR 0012.
- 아직: 방장 이전 없음(방장이 안 돌아오면 그 판은 끝나지 않음), 버려진 방 정리는 서버(Phase 7).

## 이전 단계 메모 (Phase 3 (A) — 구글 로그인)
- Credential Manager(`GetSignInWithGoogleOption`) -> 구글 ID 토큰 -> Firebase `signInWithCredential`. 웹 클라이언트 ID가 기존 앱과 같아 **기존 구글 사용자는 같은 uid**로 들어오고 옛 기록을 이어받는다(에뮬레이터로 확인).
- 코어 `AuthFormModel.signInWithGoogle` + `GoogleTokenSource`, 어댑터 `FirebaseAuthGateway.signInWithGoogle`, 앱 `CredentialManagerGoogleTokenSource`/`GoogleTokens`, 로그인 화면 버튼. 테스트: 코어 +5, 에뮬레이터 4, 앱 9.
- **오너 작업 필요**: 새 패키지 `io.github.lhd980820.coup`의 서명 SHA-1·SHA-256을 Firebase에 등록하고 `google-services.json`을 다시 받을 것(지금은 옛 패키지에만 등록돼 있어 실기기에서 실패한다). ADR 0011.

## 이전 단계 메모 (Phase 3 (A) — 로그인·로비 UI)
- **코어 `:shell`**(순수 Kotlin, ADR 0010): `AuthFormModel`, `AccountSession`, `LobbyModel`, `WaitingRoomModel`, `GameLauncher`. 규칙이 켜진 메모리 Firestore(`FakeFirestore`, 이제 `:remote` testFixtures) 위에서 방 만들기 -> 입장 -> 준비 -> 시작 -> 3인 게임 -> 정산 -> 대기실 복귀까지 테스트.
- **`:data`**: `FirebaseAuthGateway`(Auth 에뮬레이터 테스트 4개).
- **앱**: `multiplayer/` — `LoginScreen`(로그인·가입·재설정), `ProfileCard`(닉네임 인라인 편집·로그아웃), `LobbyScreen`(목록·코드 입장), `CreateRoomScreen`(룰 선택기 재사용), `WaitingRoomScreen`(방장/게스트 컨트롤), `MultiplayerFlow`·`MultiplayerGame`(싱글플레이와 같은 `GameScreen` + 결과의 레이팅 변화), `ShellViewModel`, `Backend`(Firebase / 테스트용 가짜). 홈에 "멀티플레이" 버튼과 프로필 카드. ko/en 문구와 번역 검사.
- 테스트가 찾은 제품 버그 2개(계정 준비 상태와 프로필을 따로 받아 로비가 안 열림, 방장의 명령 수신이 전송 오류 한 번에 멈춤)와 테스트 도구 버그 1개(`FakeFirestore` 비원자적 커밋)를 고쳤다 — ADR 0010.
- 아직: 게임 중 뒤로 가기/재접속, 코드 공유, 랭킹·전적 화면. (구글 로그인은 다음 단계에서 추가)

## 이전 단계 메모 (Phase 3 (A) — Firebase 어댑터)
- 새 모듈 `:data`(Android 라이브러리): `FirebaseDocumentStore`(배치·서버 읽기·구독·쿼리·트랜잭션), `FirestoreJson`(값 변환). 결정과 근거는 ADR 0009.
- 코어 변경: `DocumentStore.get`(판단용 읽기) 추가, `FirestoreUserRepository`·`FirestoreRoomStore`·`FirestoreGameTransport`가 결정에 쓰는 읽기를 `get`으로(실제 구현은 캐시가 아니라 서버에서 읽는다).
- **에뮬레이터 통합 테스트 7개**(Robolectric + 실제 Firebase SDK + Auth/Firestore 에뮬레이터 + 실제 규칙): 프로필·거부, 옛 계정 이관, 동시 입장 경쟁, 게임 좌석 동의, **3인 멀티플레이 한 판 전체(정산·랭킹까지)**.
  CI에 `firebase-emulator` 작업 추가(`COUP_REQUIRE_EMULATOR=true`면 에뮬레이터가 없을 때 건너뛰지 않고 실패).
- 찾은 것: (1) Firestore 기본 merge는 중첩 맵을 깊게 합친다 -> 최상위 필드 교체(`mergeFieldPaths`)로 모델과 맞춤. (2) 동시 트랜잭션에서 진 쪽은 `ABORTED`가 아니라 규칙 거부(`PERMISSION_DENIED`)로 끝난다 -> 어댑터가 새로 읽어 다시 시도(최대 5번).
  (3) Robolectric 기본 루퍼 모드에서는 Firebase 콜백이 교착된다 -> `@LooperMode(INSTRUMENTATION_TEST)`.
- 로컬 실행: `firebase/rules-test`에서 `npx firebase --config ../firebase.json emulators:start --only firestore,auth --project demo-coup`(Java 21) 후 루트에서 `LC_ALL=C.UTF-8 ./gradlew :data:testDebugUnitTest`.
- 아직: 오프라인 영속 캐시·재연결 시나리오, 앱의 로그인/DI 연결(로비 UI 때).

## 이번 단계 메모 (Phase 3 (A) — 룰셋·하우스룰 선택 UI)
- **코어**: `presentation/rules/RuleSetEditor`(순수 로직) — 등록된 하우스룰(`registry.availableHouseRules`)과 조절 항목(시작 코인 1~7, 쿠 강제 코인 7~12, 도전 성공 시 환불, 교환 후 덱 섞기)의 목록·범위·기본값을 주고, `RuleSelection` <-> `RuleSetConfig` 변환, 검증(`issues`), 인원 범위, 레이팅 여부를 맡는다. 기본값은 기반 룰셋에서 읽는다(중복 정의 없음). **기본값으로 되돌린 항목은 override에서 뺀다** — 그래야 표준 규칙이 설정으로도 표준이고(D5 레이팅 대상) 방 설정 비교가 정확하다.
- 코어 테스트 10개: 모든 조합(하우스룰 4가지 x 시작 코인 3 x 쿠 강제 3 x 불리언 2x2 = 144개)이 룰셋으로 만들어지고, 극단 조합에서 2명/6명 **무작위 에이전트끼리 끝까지 둔다**(멈춤·거절된 수 없음). 변이 검증 3종(기본값 override 유지 / 범위 맞춤 제거 / 범위 확대) 모두 검출.
- **앱**: `RuleSetPicker`(접힌 요약 "표준 규칙 / 바꾼 규칙 N개" -> 펼치면 하우스룰 스위치 + 세부 설정 스테퍼/스위치 + "표준 규칙으로"), `RuleTexts`(코어 키 -> 문자열, 모르는 키는 키 그대로), ko/en 문구, `RuleSelectionSaver`(화면 재생성에도 유지). 싱글플레이 설정 화면에 붙였고 `SinglePlayerConfig.ruleSet`으로 코어에 전달된다. 설정 화면은 스크롤된다. 검증에 실패하는 조합이면 이유를 보여주고 시작을 막는다. 접근성: 스위치 행은 `toggleable(Role.Switch)`, 스테퍼 버튼에 내용 설명.
- `unratedNote` 옵션: 방(멀티플레이) 화면에서 같은 컴포넌트를 쓸 때 "레이팅에 반영되지 않아요" 안내를 규칙이 바뀐 때만 보인다(싱글플레이는 끔).
- 앱 테스트(Robolectric+Compose) 11개 추가: 접힘/펼침, 하우스룰·세부 설정 변경 -> 요약과 시작 설정, 기본값 복귀 -> 표준, 범위 끝에서 버튼 잠금, 되돌리기 버튼, **고른 시작 코인이 실제 게임에 적용**(내 코인·상대 코인 4), 레이팅 안내, 상태 복원, 저장 형식 왕복, 코어의 모든 하우스룰·항목에 ko/en 문구. 변이 검증 4종(시작 설정에 미반영 / 요약 고정 / 번역 누락 / 상태 저장 안 함) 모두 검출.
- 아직: 방 만들기 화면의 룰 선택 연결(로비 UI 때), 기반 룰셋이 늘어날 때의 선택 UI(지금은 Classic 하나라 선택 없음), 인원 수와 룰의 인원 범위 연동.

## 이번 단계 메모 (Phase 3 (A) — 튜토리얼 화면)
- 홈(`HomeScreen`: AI와 대전 / 튜토리얼) -> 튜토리얼 목록(`TutorialListScreen`, 완료 표시) -> 과정(`TutorialGame`). 코어의 `GuidedSession`/`TutorialLessons`를 그대로 쓰고, 화면은 얇다.
- 과정 화면 = 일반 `GameScreen` + 안내 카드(`CoachCard`: 단계 n/m, 문구). **안내가 가리키는 컨트롤만 활성**(테두리로 강조), 나머지 행동·허용·도전·막기는 잠긴다. 지시 밖 수를 시도하면 안내 카드 테두리가 붉어진다(`offScript`). 튜토리얼에서는 기권 대신 "목록으로".
- 과정을 끝내면 완료 대화상자(완료 문구, 다음 과정 / 목록으로)와 기기 저장(`TutorialStore`: SharedPreferences, 테스트는 메모리). 마지막 과정에는 "다음 과정"이 없다. 목록 카드는 TalkBack이 "완료"를 읽는다(`stateDescription`).
- 문구 키 -> 문자열 리소스는 `TutorialTexts`(명시적 맵). 코어에 새 키가 생기면 `TutorialTextsTest`가 ko/en 모두에서 누락을 잡는다.
- `GameViewModel`이 튜토리얼 시작/완료를 맡는다. 디버그 런처 "Coup (new)"는 이제 홈 화면으로 열린다.
- 테스트(Robolectric+Compose): 4개 과정을 **안내대로 눌러 끝까지**(안내 밖 컨트롤이 실제로 잠겨 있는지 단계마다 확인), 완료 기록·표시, 다음 과정으로 이어짐, 마지막 과정, 저장소 영속, 문구 키 누락. 변이 검증 3종(잠금 해제 / 완료 기록 누락 / 번역 키 누락)을 모두 잡는다.
- 아직: 기존 안내 이미지 9장(`Tutorial.kt`)은 새 흐름에 연결하지 않았다(필요하면 1단계 소개 화면으로 재사용). 튜토리얼 중 상대(선생)의 행동은 즉시 일어난다 — 연출용 지연/말풍선은 애니메이션 작업에서.

## 이번 단계 메모 (Phase 3 (A) — Android: 싱글플레이 Compose 화면)
- **환경**: `dl.google.com`이 허용되자 이 세션 안에서 Android 빌드가 가능해졌다. 이 컨테이너에는 SDK/JDK 17이 미리 깔려 있지 않았으므로 `scripts/setup-android-cloud.sh`(JDK 17 + 명령줄 도구 + platform 37 + build-tools)로 설치했다. 새 세션도 이 스크립트를 먼저 돌리면 된다(`source scripts/setup-android-cloud.sh`, 한글 테스트 이름 때문에 `LC_ALL=C.UTF-8`). Maven Central이 가끔 429(속도 제한)를 돌려주면 잠시 뒤 재시도.
- 기준선: `./gradlew assembleDebug`, `test lint` 모두 통과. 새 세션을 따로 만들지 않고 이 세션에서 진행했다(`core/`와 `app/`을 함께 고칠 수 있어 더 빠르다).
- `settings.gradle.kts`에 `includeBuild("core")`, `:app`이 `io.github.lhd980820.coup:presentation`에 의존(엔진·AI·런타임·화면 상태가 따라온다).
- `app/.../game/`: `GameViewModel`(수명·의도 전달만; 규칙은 코어의 `GameController`), `GameScreen`(상대 줄·진행 배너·로그·내 카드·결정 패널·결과), `SinglePlayerSetupScreen`(AI 수·난이도), `SinglePlayerActivity`, `Catalogs`(RoleUiCatalog/ActionUiCatalog: ID -> 이름·카드 그림, 모르는 ID는 폴백), `GameTexts`(구조화된 상태 -> 문장, ko/en). 문자열은 `values/strings_game.xml`(ko), `values-en/strings_game.xml`.
- (Phase 5에서 해결) 정식 진입점은 `MainActivity` 하나다. 디버그 전용 런처는 없앴다.
- 테마: 다이내믹 컬러 끄고 다크 우선 브랜드 팔레트(설계 §10.1). 상태 막대 색 직접 지정(deprecated) 제거.
- **테스트(Robolectric + Compose, 에뮬레이터 없이)**: 실제 화면을 띄워 **버튼만 눌러 AI와 한 판을 끝까지** 둔다(AI 1·2·3·5명, 작은 화면 320x568dp, 큰 글씨 1.6배 포함). 눌러야 하는 컨트롤은 `assertIsDisplayed`로 실제 화면 안에 있어야 한다. 문구 테스트는 모든 배너/로그 종류가 ko/en에서 자리표시자 없이 채워지는지, 모르는 역할·행동이 깨지지 않는지 본다. 변이 검증: 결정 패널을 화면 밖으로 밀기 / 허용 버튼 제거 / 확정 버튼 비활성 -> 모두 테스트가 실패.
- 테스트가 찾은 것: (1) 작은 화면에서 하단 컨트롤이 잘림 -> 위(상대·기록)만 스크롤, 아래(배너·내 카드·결정)는 고정하도록 레이아웃 수정. 대상 선택은 상대 카드가 아니라 결정 패널의 버튼으로. (2) 누르자마자 다음 판단을 하면 옛 상태를 보는 경쟁(테스트 쪽) — 사람은 `GameController`의 busy 잠금이 막는다.
- (Phase 5에서 해결) 옛 `strings.xml`은 `app_name`만 남기고 정리했다.
- 아직 안 한 것(설계 §10.3): 응답 모달 바텀시트(지금은 인라인 패널), 카드 뒤집기/코인 이동 애니메이션(`UiEffect`), 남은 시간 링, 역할 능력 시트, 대화면 2단 레이아웃, 룰셋/하우스룰 선택 UI, 튜토리얼 화면, 사운드/설정.

## 이번 단계 메모 (Phase 3 (C) — 규칙 에뮬레이터 검증)
- **이 환경에서 Firestore 에뮬레이터가 돈다**(Node 22 + Java 21 + `storage.googleapis.com` 접근 가능). 그래서 규칙을 직접 실행했다. (Android SDK는 여전히 `dl.google.com` 차단으로 불가)
- `firebase/rules-test/`: 모델의 모든 판단을 `-Pruletrace`로 기록 -> `replay.mjs`가 실제 규칙에 재생(`npm test`), `rules-only.mjs`(모델이 모르는 동작), `mutate.mjs`+`make-mutants.py`(규칙 조건 변이).
- 결과: 3,171건 불일치 0건. 변이 98개 중 82개 검출(16개는 중복 방어, 근거는 `firebase/README.md`).
- 찾아서 고친 것: (1) 보낸 직후 자기 명령 문서 구독이 거부될 수 있던 문제, (2) 닉네임 길이 단위(규칙 UTF-16 vs 앱 코드 포인트) 불일치, (3) 모델 테스트의 빈틈(6인 방 뒤쪽 좌석 정산·준비, 좌석 바꿔치기, 룰 파라미터 방의 rated 거짓말, 명단 바꿔치기, 입장 항목 모양 등).
- 한계: 모델이 시도한 시나리오만 비교한다. Firebase Android SDK 어댑터(트랜잭션·배치·오프라인)는 Android 세션에서 같은 에뮬레이터로 통합 테스트해야 한다.
- 새 세션 생성 가능 여부: 이 환경은 환경 목록이 `Default` 하나뿐이고 환경 설정(허용 목록·설정 스크립트)은 도구로 바꿀 수 없다. 같은 환경으로 새 세션을 만들어도 `dl.google.com`이 막혀 Android 빌드는 안 된다.

## 이번 단계 메모 (Phase 3 (C) — Firestore 방, ADR 0008)
- `DocumentStore.transaction`(읽기 -> 쓰기, 바뀌면 재실행) 추가. `FakeFirestore`는 낙관적 트랜잭션 + 커밋 직전 끼어들기 훅.
- `FirestoreRoomStore`(`RoomStore` 구현): 방 문서를 구조화된 필드로(`members`/`memberOrder`/`bots`/`ruleSet`/`configVersion`). 게스트 준비 = `readyVersion == configVersion` — 룰셋·봇 변경 시 방장이 게스트 항목을 건드리지 않고도 준비가 풀린다. 바뀐 필드만 바꿔 쓴다.
- 규칙: 게스트는 자기 항목만, 방장은 사람을 빼기만, 룰셋·봇 변경 시 configVersion +1(되돌리기 불가), 시작은 전원 준비 시. **게임 생성은 방에 묶임**: 좌석 = 방 사람 전원, rated = 방 구성, 좌석 전원 정산 완료. 레이팅 결과는 rated 게임만.
- 정산 게이트 연결 완료: 방 만들기·입장(`RoomError.UNSETTLED_GAME`)·게임 생성.
- `createGame`에 `roomId` 추가(+ `createGame(plan)`). 기존 전송/사용자 테스트는 관리자 권한으로 시작된 방을 심는 `seedStartedRoom`을 쓴다.
- 테스트: 방 22개(+ 사용자 3개 추가). 접근 모델 검사 89개 변이 검증 -> 79개 검출, 10개는 중복 방어(ADR 0008 "검증").
- 변이 검증이 찾은 위험: 방장이 configVersion을 되돌려 게스트의 옛 준비를 되살리기 -> "+1만" 규칙과 테스트.

## 이번 단계 메모 (Phase 3 (C) — 사용자·레이팅·랭킹, ADR 0007)
- 새 모듈 `core/account`(순수): `UserProfile`(시작 1000), `Nicknames`, `RatingLedger`(변동량을 **레이팅 표에서 다시 계산**, 결과 문서와 다르면 반영 안 함), `Rankings`(동점 공동 순위), `LegacyMigration`(옛 `user/{email}` -> `users/{uid}`, 이메일 선점 `legacyClaims/{email}`).
- `core/remote`: `FirestoreUserRepository`(`ensureProfile`, `updateNickname`, `applyResult`, `observeTop`, `observeHistory`), `DocumentStore.observeOrdered` 추가, 스키마에 사용자 경로.
- "한 번만"은 배열 대신 **표시 문서** `users/{uid}/appliedResults/{gameId}`(= 전적). 반영은 프로필 갱신 + 표시 문서를 한 배치로, 규칙은 `exists`/`existsAfter`로 검증. 동시 반영은 규칙의 `rating == before + delta`로 한쪽만 성공 -> 재시도.
- `FakeFirestore` 배치 평가를 실제처럼 "배치 전(get)/배치 후(getAfter)"로 바꿈. `firestore.rules`에 사용자·레이팅·이관 규칙 추가(⚠ 미검증).
- **테스트 인프라 결함 수정**: `= runBlocking { ... assertThat(..) }` 형태의 테스트는 반환형이 Unit이 아니어서 **JUnit이 조용히 건너뛰었다**(로비 테스트 32개 중 1개가 실행되지 않고 있었음). 모두 `: Unit`으로 고치고, 루트 빌드에 `junit.platform.discovery.issue.severity.critical=WARNING`을 넣어 다시 생기면 빌드가 실패하게 했다.
- 테스트: account 9개, 사용자 저장소 15개(이관 1회, 조작된 생성/닉네임/레이팅 거절, 부풀린 결과, 두 기기 동시 반영, 다른 게임 동시 반영 재시도, 진 게임 표시 선점 거절, 전적 위조 거절, 레이팅 게임 순위=좌석, 랭킹). 규칙 모델의 각 검사를 하나씩 끄는 변이 검증으로 검사마다 잡는 테스트가 있음을 확인(중복 방어 2개 — 표시 문서·선점 문서 재생성 — 는 문서 불변 규칙이 먼저 막는다).
- 남는 위험: 방장의 순위 조작 -> Phase 7(서버).
- **정산 게이트**(후속 커밋, 오너 결정): rated 결과 배치에 `pendingRatings/{uid}`, 반영 배치에서 삭제, `settlePending()`(앱 시작 시). 정산 = 읽기 5 + 쓰기 1(테스트 고정).
  결과 변동량이 표와 다르면 결과 쓰기를 거절(정산 불능 방지). 대기 표시 위조·남의 표시 삭제·정산 없이 삭제를 막는 테스트 + 검사별 변이 검증. `WriteOp.Delete` 추가.

## 이번 단계 메모 (Phase 3 (C) — 튜토리얼)
- `core/presentation/tutorial`: `TutorialLesson`(고정 seed·손패·선 플레이어·선생 대본·사용자 단계), `ScriptedAgent`(대본대로 두는 `AiAgent`, 어긋나면 합법적 기본 수), `GuidedSession`(어떤 `GameSession`이든 감싸 단계에 맞는 수만 통과, 기권은 항상 허용, `progress` 흐름 제공), `TutorialSessionFactory`, 내장 4과정 `TutorialLessons`(기본 행동 / 역할 주장 / 도전 / 막기).
- 조작된 덱은 `determinize` 대신 **손패가 맞는 seed를 찾아 고정**하는 방식으로 했다(엔진·세션을 건드리지 않고 `LocalGameSession` 그대로 사용). 테스트가 과정별 손패·선 플레이어를 검증하므로 엔진 변경으로 어긋나면 바로 실패한다.
- 문구는 `messageKey`만 갖는다(앱 리소스가 번역). 지시와 다른 수는 `Rejected(NOT_YOUR_DECISION)` + `offScript` 증가. `GameController`는 변경 없이 그대로 사용.
- 테스트 7개(손패 검증, 4과정 완주, 도전 시 선생 영향력 상실, 오프스크립트 거절, 기권, 컨트롤러 버튼 흐름, 대본 이탈 시 진행) + 가드 해제 변이 검증.
- 앱 쪽(Android 세션) 할 일: 과정 선택 화면, 안내 말풍선(`progress.messageKey`), 기존 이미지 9장은 소개용으로 재사용.

## 이번 단계 메모 (Phase 3 (C) — 방/로비, ADR 0006)
- 새 모듈 `core/lobby`: `Room`/`RoomSeat`/`RoomError`, `RoomRules`(순수 함수: 생성·입장·퇴장·강퇴·준비·봇·규칙 변경·시작·종료), `RoomStore`(원자적 `update`) + `InMemoryRoomStore`, `RoomService`, `GameStartPlan`(`setup()`·`hostSeats()`), `JoinCodes`(6자리, 헷갈리는 글자 제외).
- 규칙: 방장 퇴장=방 닫힘, 강퇴자 재입장 불가, 비공개 방은 코드(대소문자/공백 무시), 앱 버전·룰셋 지원 게이트, 규칙/봇 변경 시 게스트 준비 초기화, 봇만 있어도 시작 가능, 종료 후 WAITING 복귀.
- 공개 목록: WAITING + PUBLIC만 최신순, 요약에 봇/커스텀 룰/레이팅 여부.
- 테스트 32개(이전 커밋에서는 1개가 조용히 건너뛰어지고 있었음 — 아래 사용자·레이팅 메모 참고, 마지막 자리 동시 입장 20라운드 x 8명 -> 항상 정확히 1명 성공, 시드 결정성, 레이팅 플래그 등). 전체 `core` 341개 통과, kover 게이트 통과.

## 이번 단계 메모 (Phase 3 (C) — Firestore 전송, ADR 0005)
- 새 모듈 `core/remote`: `DocumentStore`(write 배치/observe/observeWhere), `FirestoreSchema`(경로·필드 상수), `FirestoreGameTransport`(host/guest). 게시는 좌석별 뷰 + 관전 뷰 + 권한자 백업 + 메타를 **한 배치**로 쓴다. 방장 본인 뷰는 문서로 나가지 않는다.
- 명령: 게스트가 `commands/{id}`를 PENDING으로 쓰고 방장이 APPLIED(적용 버전)/REJECTED(사유)로 바꿀 때까지 구독. 형식이 잘못된 명령 문서는 방장이 REJECTED 처리하고 건너뛴다. 발신자(`senderUid`)와 명령의 `actor`가 다르면 방장이 `NOT_YOUR_DECISION`.
- 연결 상태: 방장 생존 신호(10초 주기, `GameTransport.Host.heartbeat` 신설) -> 게스트는 30초 초과 시 `HOST_LOST`, 게임 FINISHED면 `CLOSED`.
- 접근 규칙: 테스트 `FakeFirestore.AccessPolicy`가 설계 §8.3 매트릭스를 실행 가능한 모델로 구현. 실제 `firebase/firestore.rules`는 그 손 번역이며 **미검증**(`firebase/README.md`: 검증 절차, 알려진 위험).
- 15개 테스트(4인 완주, 복구, 명령 수명주기, 동시 응답, 사칭, 접근 거부 6종 등) + 변이 검증.

## 이번 단계 메모 (Phase 3 (C) — presentation)
- 새 모듈 `core/presentation` (ADR 0004). `GameUiState`(구조화된 값), `GameUiMapper`(순수 함수), `GameController`(의도 처리), `EventMapper`(로그·애니메이션 신호), `SinglePlayerConfig`/`SinglePlayerSessionFactory`.
- 컨트롤러 의도: `chooseAction`(대상 필요하면 `PickTarget` 단계), `chooseTarget`, `cancelTarget`, `pass`, `challenge`, `block(role)`, `selectCard`+`confirmCard`, `toggleExchange`(정원 초과 무시)+`confirmExchange`, `concede`. 사용할 수 없는 의도는 조용히 무시. 결정이 바뀌면 선택 상태 자동 초기화. 거절/네트워크 오류는 `messages`로.
- 통합 테스트가 찾은 결함 수정: 수락 후 새 상태 도착 전 입력 잠금(최대 3초) — 없으면 두 번째 탭이 `STALE_VERSION`으로 거절되어 사용자에게 오류가 보였음.
- 세션 `events`에 replay 64 추가(구독 시차로 로그 유실 방지, `EVENT_REPLAY`).
- 테스트: 매퍼(블러핑/비용 부족 표시, 응답/도전/막기/교환/결과 화면 상태, 타인 시점에서는 교환 후보 숨김), 컨트롤러, **UI 버튼만으로 AI 1~5명(쉬움/보통, 하우스룰·파라미터 변경 룰셋 포함)과 끝까지 플레이**, 아무 때나 기권, 늦게 만든 컨트롤러, 설정 검증/결정성, 화면 모델에 내 카드 외의 카드 ID 없음.

## 이번 단계 메모 (Phase 2 — 멀티 전송 계층)
- `GameTransport.Host/Guest` (runtime/transport): `Publication`(원격 사람 좌석별 `ViewEnvelope` + 관전 뷰 + 권한자 백업), `IncomingCommand`(전송 계층이 인증한 `senderUid` 포함), `AckResult`, `GameResultRecord`(순위, 룰 설정, `rated`, 레이팅 변동), `TransportException`.
- `InMemoryTransport`: 모든 메시지를 JSON 왕복해 전달(객체 공유 없음), 게스트는 자기 좌석 뷰만 구독 가능(`require(me.value == uid)`), `lastBackup`/`result`/`dropHost()` 테스트 훅. Firestore 구현(`:data`)이 같은 계약을 따른다.
- `HostGameSession`: `GameAuthority` + 방장(`HostSeat.Local`, 정확히 1명) + 원격 사람(`Remote`, uid 대조) + 봇(`Bot`). 상태 변경마다 좌석별 투영 뷰를 게시, 종료 시 `finish`(순위, `rated`, 레이팅 변동). 레이팅 대상(D5): 봇·하우스룰·파라미터 변경이 모두 없을 때만. 게시 실패는 삼키고 `RECONNECTING`으로 표시(다음 게시가 전체 뷰로 따라잡음). `initialState`로 새 게임 또는 `EngineJson` 백업 복구.
- **명령 사칭 방지**: 명령의 `actor`는 발신자 uid에 대응하는 원격 좌석이어야 한다. 모르는 발신자, 봇/방장 사칭, 다른 사람 이름의 명령은 `NOT_YOUR_DECISION`으로 거절.
- `RemoteGameSession`: 엔진 없음. 호스트가 투영해 보낸 뷰만 표시, 순서가 뒤바뀐 갱신 폐기, 명령은 ack까지 대기(타임아웃 10초 → `NetworkError`).
- `GameAuthority.submitFrom(player, command)`: 버전 불일치(`STALE_VERSION`)로만 거절됐고 그 플레이어의 결정이 명령이 근거한 버전 때와 같으면(최근 64버전의 결정 서명 보관) 버전 조건 없이 한 번 더 적용. 로컬 AI와 원격 사람 모두 같은 경로 → 사람 응답자 여럿의 동시 "허용"이 전부 반영된다. 도전이 동시에 몰리면 하나만 수락.
- 엔진에 `RatingPolicy`/`TableRatingPolicy` 추가(Phase 1에서 누락됐던 항목, 기존 앱 표와 동일).
- 검증: 호스트 1 + 게스트 3 완주(전원 같은 결과), 봇 혼합, 동시 응답, 사칭, 타인 뷰 구독 차단, **게스트가 받은 모든 갱신에 알 수 없는 카드 ID 없음**(호스트 상태 백업과 대조 — 일부러 호스트 뷰를 게스트에 보내는 버그를 심어 이 테스트가 실패함을 확인), 호스트 백업 복구 후 이어하기, 호스트 소실 감지, 전송 실패/지연 → `NetworkError`.

## 이번 단계 메모 (Phase 2 — AI)
- `AiFactory.create(AiDifficulty.EASY|NORMAL, Personality, seed)`. HARD(결정화 탐색)는 Phase 6.
- 신념: `CardCounter`(미확인 풀 = 구성 − 내 손패 − 공개 카드 − 교환 중 본 카드, 초기하 확률), `ClaimHistory`(주장/막기 기록, 증명·교환·상실 시 리셋, 블러핑 발각률 평활화), `Beliefs`(사전확률 x 주장 우도비 1/블러핑률, 미확인 0장이면 확정 블러핑).
- 정책(`HeuristicAgent`): 행동 점수 = 이득 + 위협 대상 보너스 − 막힐 위험 − 블러핑 위험. 응답: 역할 있으면 막기, 마지막 카드로 암살당하면 블러핑 막기, 도전은 기대값(EV) 기반. 공개/상실/교환은 역할 가치(`Valuation`: 알려진 행동 표 + 처음 보는 행동은 룰 요약으로 추정).
- 보정: 블러핑 위험을 낮게 잡으면 NORMAL이 EASY에게 진다(0.42). 의심 0.35 / 신중 2.0으로 보정해 EASY 상대 2인 0.80.
- 버그 수정(회귀 테스트 포함): 마지막 카드끼리 "암살 → 블러핑 막기 → 통과"가 무한 반복 → 잃을 게 없는 막기의 주장은 무시하고 사전확률로 판단.
- 대결 승률표(`cd core && ./gradlew :ai:tournament`, 500판/칸): 보통 vs 무작위 2인 0.93 / 4인 0.84 / 6인 0.84, 보통 vs 쉬움 0.80 / 0.76 / 0.78, 쉬움 vs 무작위 0.89 / 0.64 / 0.48. 같은 난이도끼리는 기준선과 일치(측정 검산).
- 테스트: 실력 서열(기본 실행), 합법성(난이도별 1만 결정 이상, 하우스룰·파라미터 조합·성격 3종), **뷰 동치**(무작위 게임 매 결정마다 상대 손패·덱을 섞은 "다른 세계"를 만들어 같은 결정인지 1천 회 이상 검사), 확정 블러핑 시 반드시 도전, 귀부인 보유 시 암살 막기, 교환 시 역할 다양성.

## 이번 단계 메모 (Phase 2 — 런타임)
- 모듈: `core/ai`(`:engine`에만 의존), `core/runtime`(`:engine`, `:ai`, coroutines). `core/settings.gradle.kts`에 포함, CI의 `core` 단계가 자동으로 함께 테스트.
- `:ai`: `AiAgent` 인터페이스(입력은 `PlayerView`/`VisibleEvent`/`DecisionRequest`뿐), 기준선 `RandomAgent`. 정적 검사 테스트로 AI 소스의 `GameState` 참조 금지(주석 제외).
- `GameAuthority`: Mutex로 명령 직렬화, 결정권자 좌석에 결정 요청(같은 결정은 1회), D4 마감(응답 15초/카드 선택 20초/행동 30초, `TimeoutPolicy.standard(unlimitedLocalHuman)`), 마감 시 `engine.timeoutCommand` 제출. **같은 결정이 이어지는 동안 마감 유지**(다른 사람이 통과해도 내 마감 그대로). 좌석·리스너 통지는 단일 전달 코루틴에서 순서대로.
- 버그 수정(재현 테스트 포함): AI 응답자 여럿이 같은 뷰로 동시에 결정하면 뒤의 명령이 STALE로 거절되고 재요청이 없어 타임아웃까지 멈췄다 → 좌석 명령이 STALE로만 거절됐고 결정이 그대로면 버전 조건 없이 재적용.
- `AiSeat`: 결정은 별도 디스패처에서, 연출 지연(`thinkTime`) 지원, 에이전트 예외 시 아무것도 내지 않음(타임아웃 기본 수가 대신). observe/decide 동시 호출 방지.
- `LocalGameSession`(`GameSession` 구현): 사람 1 + AI들, 내 시점 스냅샷/이벤트만 노출, 다른 좌석 명령 거부.
- 테스트는 코루틴 가상 시간(`runTest` + `backgroundScope`). 주의: `advanceUntilIdle()`은 backgroundScope 작업만 남으면 멈추므로 `runUntil { 조건 }` 도우미(`runtime/src/test/.../TestTime.kt`)를 쓴다.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-18, Phase 3 (A) 룰셋·하우스룰 선택 UI_
