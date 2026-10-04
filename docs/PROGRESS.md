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
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ✅ |
| 8 | 교환 | ✅ |
| 9 | 탈락 / 게임 종료 / 기권 | ✅ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ ← 다음 🧠 정보 은닉 검증은 Opus 권장 |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 10단계: `view(state, viewer)` → `PlayerView`(설계 §4.6), `projectEvents(events, viewer)`(§4.7), `timeoutCommand(state, player)`(D4: 응답 Pass / 공개·상실 첫 미공개 카드 / 교환 현재 손패 유지 / 행동 수입 또는 강제 쿠의 첫 대상), `determinize(view, assignment, seed)`(§4.3).
   - 마스킹 필수: 타인의 미공개 카드 역할·CardId, 덱 내용, RNG, `CardReplaced.newCard`, `ExchangeDrawn.cards`, `Phase.AwaitingExchange.candidates`, `CardsDealt`(도입 시).
   - 정보 누출 테스트(§12.4): 뷰를 JSON으로 직렬화해 타인 미공개 CardId/역할이 없음을 검사. 히든 정보만 다른 두 상태의 뷰가 같은지 검사.
2. 11단계: 하우스룰 3종(`no_steal_from_broke`, 파라미터 오버라이드 확인, `last_stand` 훅 — RuleModifier 도입), 속성 기반 테스트 확장, 골든 파일.

## 이번 단계 메모 (9단계)
- `Command.Concede`: 생존자는 결정권과 무관하게 언제든 가능(`pendingDeciders` 검사 예외). 남은 카드 전부 공개(`LossReason.Concede`) → 탈락. 생존자 1명이면 즉시 종료.
- **기권은 일반 해결 루프(`resolve`)를 무조건 돌리지 않는다.** 다른 사람의 결정을 기다리는 중이면 아무것도 진행하지 않아야 그 결정을 건너뛰지 않는다. 기권 직전 페이즈가 기권자를 기다렸던 경우에만 정리 후 진행:
  - 자기 턴 → `EndTurn`.
  - 응답 창: 행위자 기권 → 행동 취소(`ActionOutcome.CANCELLED` 신설). 막기 도전 창에서 막은 사람 기권 → 막기 무효, 행동 해결. 응답자 기권 → 창에서 제외, 대기자 0명이면 전원 통과 처리.
  - 공개 대기: 도전받은 사람 기권 → 주장 불성립(행동 도전이면 취소, 막기 도전이면 막기 무효·행동 해결), 도전자는 잃지 않음. 도전자 기권 → 공개는 계속.
  - 상실/교환 대기 중 본인 기권 → 이어서 진행(교환은 엿보기 방식이라 덱 그대로).
- 안전장치: 행위자가 탈락한 상태로 응답 창이 열리려 하면 열지 않고, `ApplyEffect`는 행위자가 탈락했으면 `CANCELLED` 처리. (예: 증명에 진 도전자가 고르는 동안 행위자가 기권 → 도전자는 그대로 잃고 행동은 취소)
- 새 이벤트: `PlayerConceded`. 무작위 완주 테스트에 매 단계 약 3% 기권 추가.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 9단계 완료_
