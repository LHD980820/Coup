# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | 🚧 | 아래 세부 단계 참고. 위치: `core/engine` (ADR 0001) |
| 2~7 | ⬜ | |

### Phase 1 세부 단계 (설계 §13)

| # | 단계 | 상태 |
|---|---|---|
| 1 | 식별자/카드/플레이어/RNG + 직렬화 | ✅ |
| 2 | RuleSet 정의 타입 + Classic + 검증기 | ✅ |
| 3 | GameState / Phase / ResolutionStep + newGame (+ RuleSetConfig/Registry) | ✅ |
| 4 | legalOptions(ChooseAction) + DeclareAction + 응답 없는 행동 (+ 영향력 상실 선택, 탈락/게임 종료 기본 판정) | ✅ |
| 5 | 응답 창 + Pass + 효과 해결 + EndTurn | ✅ |
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ✅ |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ⬜ ← 다음 🧠 Opus 권장 |
| 8 | 교환 | ⬜ |
| 9 | 탈락 / 게임 종료 / 기권 | ⬜ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 7단계(🧠 Opus 권장): `Command.Block` → `PendingAction.blockedBy` 설정 + `BLOCK_CHALLENGE` 창(막은 사람 제외 생존자 전원, 행위자 포함 도전 가능). 전원 Pass → 막기 성립(`ActionResolved(BLOCKED)`, 비용 환불 없음, `EndTurn`). 막기 도전 → `startChallenge(context = BLOCK)`: 증명 시 도전자 상실 + 막은 사람 카드 교체 + 막기 성립, 블러핑 발각 시 막은 사람 카드 상실 + 행동 해결(`ApplyEffect`, `EndTurn`).
   - `Transition`의 `TODO("Phase 1 step 7")` 4곳(창 열기/닫기 `BLOCK_CHALLENGE`, `resolveReveal`의 `BLOCK` 분기 2곳)과 `DefaultGameEngine.challenge`의 `BLOCK_CHALLENGE` 분기.
   - `ResponseWindowTest`의 "막기 명령은 7단계 전까지 거절된다" 테스트를 실제 동작 테스트로 교체.
   - 무작위 완주 테스트에 막기 추가.
2. 8단계: 교환(⚠ 현재 `NotImplementedError`).

## 이번 단계 메모 (6단계)
- 도전은 응답 창에서 아직 응답하지 않은 사람만, `canChallenge`일 때만 가능(아니면 `CHALLENGE_NOT_ALLOWED` — 새 Rejection). 첫 도전이 창을 닫는다.
- 도전받은 사람의 미공개 카드가 1장이면 고를 것이 없으므로 자동 공개.
- 증명 성공: **카드 교체 → 도전자 상실 → 행동 계속** 순서(설계 §4.5는 상실→교체였음. 결과는 같고, 도전자가 고르는 동안 공개된 카드가 손패에 남지 않게 하려고 바꿈). 행동 계속 = 막을 수 있으면 `BLOCK_ONLY` 창, 아니면 효과 적용.
- `BLOCK_ONLY` 창은 정책상 막을 수 있는 생존자만(예: 강탈의 대상). 도전에 실패해 탈락한 대상은 들어가지 않고 효과는 fizzle.
- 블러핑 발각: 공개한 카드 상실(`LossReason.BluffExposed` 신설) → `ActionResolved(FAILED)` → `RefundCost`(파라미터 `refundCostWhenActionChallengeLost`, 기본 true) → `EndTurn`. 스택을 먼저 쌓고 공개해야 게임 종료 시 스택이 비워진다.
- 새 이벤트: `ChallengeIssued`, `CardRevealed(proven)`, `CardReplaced(returned, newCard)` — `newCard`는 비공개 정보이므로 10단계 투영에서 타인에게 마스킹해야 한다.
- 메모: 블러핑 발각으로 탈락한 행위자에게도 비용이 환불된다(무해하지만 결과 화면 코인 표시 시 참고).

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 6단계 완료_
