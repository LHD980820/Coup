# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | ✅ | 런타임·AI·멀티 전송 계층(메모리 구현) 완료. `core/engine,ai,runtime` 255개 테스트 |
| 3. 신규 UI 셸 + 싱글플레이 | 🚧 | (C) 순수 Kotlin: 화면 상태 `core/presentation` ✅(ADR 0004), Firestore 전송 `core/remote` ✅(ADR 0005), 방/로비 `core/lobby` ✅(ADR 0006), 튜토리얼 `core/presentation/tutorial` ✅, 사용자·레이팅·랭킹 `core/account` + `FirestoreUserRepository` ✅(ADR 0007) / A안: Android 빌드 가능한 새 세션에서 Compose UI ⬜ |
| 4~7 | ⬜ | |

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
   - **다음**: Firestore `RoomStore`(+ `DocumentStore` 트랜잭션) + 방 접근 규칙. 반드시 포함: (1) 방 입장·rated 게임 생성에 `isSettled(uid)`,
     (2) **게임 좌석 = 방에 스스로 들어온 사람**(지금은 방장이 아무 uid나 좌석에 넣을 수 있어 가짜 패배를 씌울 수 있다 — 배포 전 필수).
2. **A안 전제 조건** (오너가 환경에서 설정): 새 세션이 Android 앱을 컴파일하려면 클라우드 환경의 **네트워크 허용 목록**에 `dl.google.com`, `maven.google.com`(AGP/Firebase 아티팩트와 Android SDK 다운로드), `services.gradle.org`(Gradle 배포판)이 있어야 하고, **설정 스크립트**로 JDK 17 + Android 명령줄 도구 + `platforms;android-<compileSdk>` + `build-tools`를 설치해야 한다. 환경 변경은 새 세션부터 적용된다. 새 세션의 첫 작업은 `./gradlew assembleDebug` 로 기준선을 확인하는 것.
3. **A안 작업 범위(새 세션)**: `:app`에 `core` 연결(`includeBuild("core")`), 단일 Activity Compose 셸, 디자인 시스템, `GameScreen`(= `GameController.state`를 그리기), 싱글플레이 설정/결과 화면, `RoleUiCatalog`/`ActionUiCatalog`, Firestore `DocumentStore` 어댑터. 로직은 모두 `core`에 있으므로 UI는 얇게.
4. **세션 간 인계 규칙**: 두 세션이 같은 브랜치를 동시에 건드리지 않는다. 새 세션은 `core/`를 **읽기 전용**으로 취급하고(필요한 변경은 이 문서에 요청으로 적는다) `app/` 아래만 수정, 이쪽 세션은 `core/`와 `docs/`만 수정. 브랜치는 따로(예: `claude/android-ui`), 병합은 순서대로.

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

_마지막 갱신: 2026-10-17, Phase 3 (C) 사용자·레이팅·랭킹 완료_
