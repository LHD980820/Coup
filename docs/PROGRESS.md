# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | 🚧 | 런타임(권한자·좌석·로컬 세션) ✅ / 멀티 전송 계층 ⬜ / AI EASY·NORMAL ⬜ |
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
1. **AI EASY/NORMAL** (🧠 신념 모델은 Opus 권장): `core/ai`에 `CardCounter`(미확인 풀 계산 — 확정 블러핑이면 도전), `ClaimHistory`(이벤트에서 주장 기록, 증명/교환 시 리셋), 정책 5종(§7.4), `AiFactory.create(difficulty, personality, seed)`. `RandomAgent`는 기준선으로 유지.
   - 필수 테스트: 합법성(1만 결정 거절 0 — `AiBoundaryTest` 패턴 재사용), **뷰 동치 테스트**(히든 정보만 다른 두 상태에서 같은 seed의 AI는 같은 명령 — 엔진 `determinize`/시나리오로 상태 쌍 생성), 확정 블러핑 상황에서 NORMAL은 반드시 도전, 토너먼트 하네스(`@Tag("slow")`, CI 제외): RANDOM < EASY < NORMAL.
2. **멀티 전송 계층**: `GameTransport.Host/Guest`(§6.4), `InMemoryTransport`, `HostGameSession`(권한자 + `RemoteSeat` + 좌석별 뷰 퍼블리시 + 명령 수신·ack), `RemoteGameSession`(엔진 없이 뷰 구독 + 명령 전송). 통합 테스트: 호스트 1 + 게스트 3 완주, 게스트가 받는 뷰에 타인 비공개 카드 없음, 호스트 재시작 시 `EngineJson` 백업으로 복구.

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

_마지막 갱신: 2026-10-04, Phase 2 런타임(로컬) 완료_
