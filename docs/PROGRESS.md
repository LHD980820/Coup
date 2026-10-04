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
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ⬜ ← 다음 🧠 Opus 권장 |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ⬜ 🧠 Opus 권장 |
| 8 | 교환 | ⬜ |
| 9 | 탈락 / 게임 종료 / 기권 | ⬜ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 6단계(🧠 Opus 권장): `Command.Challenge` / `Command.RevealCard`, `Phase.AwaitingReveal`, `DecisionRequest.ChooseRevealCard`, `ReplaceProvenCard`(증명 카드를 덱에 넣고 셔플 후 1장 드로우), `ContinueAfterActionChallengeFailed`, `RefundCost`. 설계 §4.5 상태도 참고. 도전 실패 시 행동 계속(막기 가능하면 `BLOCK_ONLY` 창), 도전 성공 시 행위자 카드 상실 + 비용 환불 + 턴 종료.
2. `Transition.execute`의 `TODO(...)`는 미구현 단계 표시. 해당 단계 완료 시 제거.

## 이번 단계 메모 (5단계)
- 응답 자격(`AllowedResponses`)은 창을 열 때 계산: 주장 있는 행동은 생존 타인 전원이 도전 가능, `BlockPolicy`에 따라 막기 역할 부여(TARGET_ONLY는 대상만). 도전도 막기도 못 하는 사람은 창에 포함하지 않는다. 응답자가 한 명도 없으면 창을 열지 않고 곧바로 통과 처리.
- 전원 `Pass` → `ApplyEffect` + `EndTurn`. 암살/쿠 등은 효과가 `RequireInfluenceLoss`로 이어진다.
- `DecisionRequest.Respond`는 공개 정보인 `PendingAction`을 그대로 담는다(별도 `PublicPendingAction` 타입은 만들지 않음 — 설계 §4.4와 다른 점).
- `Command.Challenge`/`Block`은 응답 창에서도 `WRONG_PHASE`로 거절된다(6~7단계 전까지의 임시 동작; 테스트로 고정되어 있으니 6단계에서 제거/수정할 것).
- 무작위 완주 테스트(`RandomPlayoutTest`): 2~6인 x 40판, 응답은 항상 Pass. 6~11단계에서 도전/막기/교환/기권을 추가하며 확장한다.
- ⚠ **알려진 공백**: 교환(`exchange`)을 선언해 전원 통과하면 `NotImplementedError`가 난다(8단계 전까지). 앱 연결 전에 반드시 해결.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 5단계 완료_
