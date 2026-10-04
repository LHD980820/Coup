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
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ✅ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ ← 다음 |

## 다음 할 일
1. 11단계(Phase 1 마지막): 
   - `RuleModifier`(설계 §5.1, 훅 4개) 도입 + `RuleSetBuilder` + `HouseRule` 등록(`RuleSetRegistry.registerHouseRule`, `availableHouseRules`). 현재 `build()`는 하우스룰이 있으면 거절한다.
   - 하우스룰 3종: `no_steal_from_broke`(대상 필터), 파라미터 오버라이드(이미 동작 — 테스트만), `last_stand`(transformEffect 훅).
   - 속성 기반 테스트 확장: 모든 하우스룰 ON/OFF 조합에서 무작위 완주 1천 판, 같은 seed+명령 로그 → 같은 최종 상태(리플레이 결정성).
   - 골든 파일: `GameState`/`PlayerView`/`Command` JSON 샘플을 `src/test/resources/golden/v1/`에 저장하고 디코딩 호환성 검사.
   - 테스트 전용 확장 룰셋(은행가)으로 엔진 코어 무수정 확장 시나리오 테스트(이미 검증기 수준은 있음 — 실제 플레이까지).
2. Phase 1 완료 후: 엔진 커버리지 측정(설계 DoD 90%+, Kover 등), 설계 문서 §4 시그니처와 실제 구현의 차이 정리(ADR 또는 문서 갱신). 그다음 Phase 2(`:runtime`, `:ai`).

## 이번 단계 메모 (10단계)
- `GameEngine`에 `view` / `projectEvents` / `timeoutCommand` / `determinize` 추가 — 설계 §4.3 인터페이스 완성.
- `PlayerView`·`VisibleEvent`는 생성자가 `internal`(엔진만 생성). 상대는 `OpponentView`(코인, 미공개 장수, 공개 역할, 생존)만 — **카드 ID도 노출하지 않는다**. 덱은 장수만, RNG 없음.
- `PublicPhase`: 교환 중이면 후보 대신 `candidateCount`/`keepCount`만. 당사자는 `myDecision`(ChooseExchange)으로 후보를 본다.
- 해결 스택은 비밀이 아니므로 `PlayerView.pendingSteps`(internal)에 실어 `determinize`가 정확히 재개 가능한 상태를 만든다.
- 이벤트 투영: `CardReplaced`→타인에게 `CardReplacedHidden`, `ExchangeDrawn`→`ExchangeDrawnHidden(count)`. 나머지는 원래 공개 정보.
- `determinize`: 내가 아는 카드(내 손패, 내 교환 후보)는 실제 ID 유지, 나머지는 새 ID. 가정이 공개 정보와 모순되면(장수, 덱 크기, 역할 구성, 모르는 플레이어) `IllegalArgumentException`.
- `timeoutCommand`(D4): 응답 Pass / 공개·상실 첫 미공개 카드 / 교환 현재 손패 / 행동은 강제면 좌석 순서 첫 대상에게, 아니면 무료·무주장·무대상 행동(수입).
- 검증: 무작위 게임 모든 상태·모든 시점에서 (1) 뷰 JSON에 알 수 없는 카드 ID와 `deck`/`rng` 키 없음 (2) `view(determinize(view, 정답 가정)) == view` 왕복 (3) 기본 명령 항상 수락 (4) 비공개 이벤트 당사자 외 전달 없음. 누출 검사기 자체도 실제 누출을 잡는지 테스트.
- `RuleParams`를 `@Serializable`로 변경(뷰의 룰 요약에 포함).

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 10단계 완료_
