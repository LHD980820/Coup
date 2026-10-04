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
| 8 | 교환 | ⬜ ← 다음 |
| 9 | 탈락 / 게임 종료 / 기권 | ⬜ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 8단계: 교환 — `Primitive.Exchange` 해결(덱 위에서 drawCount장 → `Phase.AwaitingExchange(candidates = 미공개 손패 + 드로우, keepCount = 미공개 장수)`), `Command.ChooseExchange` 검증(`INVALID_EXCHANGE_SELECTION`: 개수, 중복, 후보 밖 카드), 돌려놓는 카드는 덱에 넣고 셔플(`exchangeReturnShuffles`), `DecisionRequest.ChooseExchange`. 교환 후보는 당사자만 보는 비공개 정보.
   - 덱 장수가 drawCount보다 적을 때 처리(가능한 만큼만 드로우) 결정 및 테스트.
   - 무작위 완주 테스트에서 교환 제외 조건 제거.
2. 9단계: 기권(`Concede`) — 응답 창/공개/상실/교환 대기 중 각각 진행 막힘 없이 처리.

## 이번 단계 메모 (7단계)
- `Command.Block`: 응답 창(ACTION 또는 BLOCK_ONLY)에서 아직 응답하지 않았고 그 역할로 막을 자격이 있을 때만. 아니면 `ROLE_CANNOT_BLOCK`. 첫 막기가 창을 닫는다(해외원조의 다중 막기 경합).
- 막기 → `PendingAction.blockedBy` 설정 + `BLOCK_CHALLENGE` 창(막은 사람 제외 생존자 전원, 행위자 포함, 도전만 가능).
- 전원 Pass → 막기 성립 `ActionResolved(BLOCKED)`, 비용 환불 없음, `EndTurn`.
- 막기 도전 증명 → 막은 사람 카드 교체 → 도전자 상실 → `BLOCKED` → `EndTurn`.
- 막기 블러핑 발각 → 막은 사람 카드 상실 → `ApplyEffect` → `EndTurn`(암살 대상이면 추가 상실로 총 2장, 1장뿐이었으면 탈락 + 암살 fizzle).
- 새 이벤트: `BlockDeclared`. 무작위 완주 테스트에 막기(약 1/3) 추가.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 7단계 완료_
