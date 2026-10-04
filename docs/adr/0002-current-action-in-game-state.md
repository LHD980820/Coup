# ADR 0002: 진행 중 행동(`PendingAction`)을 페이즈가 아닌 `GameState.currentAction`에 둔다

- 상태: 채택 (2026-10-04)
- 관련: 설계 문서 §4.1 (`Phase.AwaitingResponses(pending, window)`)

## 배경
설계 문서는 `AwaitingResponses` 페이즈가 `pending: PendingAction`을 갖도록 했다. 그러나 행동 해결 중에는
`AwaitingReveal`, `AwaitingInfluenceLoss` 등 다른 페이즈를 거친 뒤 다시 행동으로 돌아와야 한다(예: 도전 실패 → 영향력 상실 → 막기 전용 창 → 효과 적용).
페이즈마다 행동을 복사해 들고 다니면 중복과 불일치가 생긴다.

## 결정
- `GameState.currentAction: PendingAction?` 하나에 저장한다. 행동 선언 시 설정, `EndTurn`에서 null로 비운다.
- `Phase.AwaitingResponses`는 `window: ResponseWindow`만 갖는다.
- 뷰 투영(10단계)에서 `currentAction`을 공개 정보로 노출한다(주장 내용은 원래 공개 정보).

## 결과
해결 스택의 단계(`ApplyEffect`, `RefundCost` 등)는 인자 없이 `currentAction`을 참조한다.
