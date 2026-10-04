# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | ✅ | 런타임·AI·멀티 전송 계층(메모리 구현) 완료. `core/engine,ai,runtime` 255개 테스트 |
| 3. 신규 UI 셸 + 싱글플레이 | ⬜ ← 다음 | **Android 빌드 필요** — 오너 결정 필요(아래) |
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
Phase 2 완료. 다음은 Phase 3(설계 §10, §13) — Compose 단일 Activity 셸 + 디자인 시스템 + `GameScreen` + 싱글플레이 설정/결과 화면. **클라우드에서는 Android 컴파일을 검증할 수 없다**(Google Maven/SDK 차단). 선택지:
- (A) 로컬에서 진행(권장: 빌드·실행 확인 가능). `core`는 `settings.gradle.kts`의 `includeBuild("core")`로 연결(ADR 0001), 앱은 `io.github.lhd980820.coup:runtime`/`:ai`/`:engine`에 의존.
- (B) 클라우드에서 UI 코드를 작성하되 컴파일 미검증으로 push → 로컬에서 오류 수정 왕복.
- (C) 그 전에 `:data`(Firestore 전송 구현)나 `RoleUiCatalog`/`GameUiMapper` 같은 **순수 Kotlin 부분**을 `core`에 먼저 만든다. `GameUiMapper`(SessionSnapshot → GameUiState)는 JVM 단위 테스트 대상이라 클라우드에서 검증 가능(설계 §10.4, §12.9).

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

_마지막 갱신: 2026-10-04, Phase 2 완료_
