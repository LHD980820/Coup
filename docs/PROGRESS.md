# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | 🚧 | 런타임(권한자·좌석·로컬 세션) ✅ / AI EASY·NORMAL ✅ / 멀티 전송 계층 ⬜ ← 다음 |
| 3~7 | ⬜ | |

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

## 다음 할 일 (Phase 2 나머지)
1. **멀티 전송 계층**: `GameTransport.Host/Guest`(§6.4), `InMemoryTransport`, `HostGameSession`(권한자 + `RemoteSeat` + 좌석별 뷰 퍼블리시 + 명령 수신·ack), `RemoteGameSession`(엔진 없이 뷰 구독 + 명령 전송). 통합 테스트: 호스트 1 + 게스트 3 완주, 게스트가 받는 뷰에 타인 비공개 카드 없음, 호스트 재시작 시 `EngineJson` 백업으로 복구. 호스트 좌석에 AI 봇을 섞을 수 있어야 한다(`AiSeat` 재사용).
2. Phase 2 완료 후 Phase 3(신규 UI 셸 + 싱글플레이) — Android 빌드가 필요하므로 로컬 작업 또는 컴파일 미검증 상태로 진행 여부를 오너와 결정.

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

_마지막 갱신: 2026-10-04, Phase 2 AI(EASY/NORMAL) 완료_
