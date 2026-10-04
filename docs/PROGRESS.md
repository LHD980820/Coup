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
| 9 | 탈락 / 게임 종료 / 기권 (탈락·종료는 4단계에서 구현됨, 남은 것: 기권) | ⬜ ← 다음 |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 9단계: `Command.Concede` — 생존자는 언제든 기권 가능. 남은 미공개 카드를 전부 공개 처리(`LossReason.Concede` 신설)하고 탈락 순서에 기록. 상태별 처리 필수:
   - 자기 턴(`AwaitingAction`) → 다음 생존자로 턴 이동.
   - 응답 창 대기 중 → 창의 `eligible`/`allowed`에서 제거 후, 대기자가 없어지면 `closeAllPassed`. 기권자가 **행위자**이면 현재 행동 취소 + 턴 종료.
   - `AwaitingReveal`/`AwaitingInfluenceLoss`/`AwaitingExchange`에서 기권 → 해당 단계를 자동 해결 또는 취소 후 스택 계속.
   - 기권으로 생존자 1명이 되면 즉시 `GameOver`.
   - 막기 창에서 **막은 사람**이 기권하면 막기 무효 처리 여부 결정(권장: 막기 취소, 행동 해결).
   - `Concede`는 `pendingDeciders`가 아니어도 허용되는 유일한 명령. `NOT_YOUR_DECISION` 검사 예외.
2. 10단계: `view()` / `projectEvents()` / `timeoutCommand()` / `determinize()`. `CardReplaced.newCard`, `ExchangeDrawn.cards`는 당사자 외에는 마스킹 필수. 🧠 정보 은닉 검증은 Opus 권장.
3. 11단계: 하우스룰 3종, 속성 기반 테스트 확장(합법 수만 쓰는 에이전트 1만 판), 골든 파일.

## 이번 단계 메모 (8단계)
- 교환은 `Primitive.Exchange` 해결 시 덱 위 `drawCount`장을 **엿본다**: 선택이 끝날 때까지 카드는 덱에 그대로 있어 카드 총량 불변식이 항상 성립한다. 후보 = 내 미공개 카드 + 엿본 카드, `keepCount` = 내 미공개 장수.
- `ChooseExchange` 검증: 장수 일치, 중복 없음, 후보 안의 카드만 → 아니면 `INVALID_EXCHANGE_SELECTION`.
- 선택 완료: 엿본 카드를 덱에서 빼고 돌려보낼 카드를 넣는다. `exchangeReturnShuffles`면 덱 전체 셔플, 아니면 맨 아래에 후보 순서대로. 남긴 카드는 미공개 자리에 `keep` 순서대로 채운다(공개된 카드는 그대로).
- 덱이 비었거나 미공개 카드가 없으면 교환을 건너뛴다. 덱이 모자라면 가능한 만큼만 엿본다.
- 새 이벤트: `ExchangeDrawn`(비공개), `ExchangeCompleted`. `DecisionRequest.ChooseExchange`.
- 무작위 완주 테스트가 이제 모든 행동·응답을 포함한다.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 7단계 완료_
