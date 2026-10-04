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
| 5 | 응답 창 + Pass + 효과 해결 + EndTurn | ⬜ ← 다음 |
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ⬜ 🧠 Opus 권장 |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ⬜ 🧠 Opus 권장 |
| 8 | 교환 | ⬜ |
| 9 | 탈락 / 게임 종료 / 기권 | ⬜ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 5단계: `ResolutionStep.OpenResponseWindow` 구현(eligible/allowed 계산: 주장 있으면 생존 타인 전원 도전 가능, BlockPolicy에 따라 막기 역할), `Command.Pass`, `DecisionRequest.Respond`, 전원 Pass 시 `ApplyEffect`+`EndTurn` push. 세금/해외원조/강탈/암살이 응답 없이 통과되는 경로까지.
2. `Transition.execute`의 `TODO(...)`는 단계별 미구현 표시다. 해당 단계 완료 시 제거.

## 이번 단계 메모 (4단계)
- 비용은 선언 시 지불(설계 §5.6 결정). 응답이 필요한 행동(주장 있음 또는 막기 가능)은 `OpenResponseWindow`를 push하고, 아니면 `ApplyEffect`→`EndTurn`.
- 강제 쿠: 코인 ≥ 임계값이면 선택지가 강제 행동 하나뿐(`forcedOnly=true`), 다른 행동은 `FORCED_ACTION_REQUIRED`.
- 영향력 상실: 미공개 1장이면 자동, 2장 이상이면 `AwaitingInfluenceLoss` + `Command.LoseInfluence`. 탈락 시 `eliminationOrder` 기록, 생존자 1명이면 즉시 `GameOver`(순위 = 승자 + 늦게 탈락한 순).
- 테스트 픽스처: `core/engine/src/test/.../testing/Scenario.kt` (`scenario { player(...); deckTop(...) }`, `declare/accept/reject` 헬퍼).
- CI: `core` 테스트 단계 추가.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 4단계 완료_
