# ADR 0003: Phase 1 구현이 설계 문서(REDESIGN_ARCHITECTURE.md)와 달라진 점

- 상태: 채택 (2026-10-04)
- 이후 단계(Phase 2~)의 구현자는 설계 문서보다 **이 문서와 코드를 우선**한다.

| # | 설계 문서 | 실제 구현 | 이유 |
|---|---|---|---|
| 1 | 5개 모듈이 한 Gradle 빌드 (§3.1) | 순수 Kotlin 모듈은 `core/` 독립 빌드 (ADR 0001) | 클라우드에서 Android SDK/Google Maven 불가 |
| 2 | `Phase.AwaitingResponses(pending, window)` (§4.1) | `GameState.currentAction`에 한 번만 저장 (ADR 0002) | 페이즈를 오가며 복사/불일치 방지 |
| 3 | `PublicPendingAction` 별도 타입 (§4.4) | `PendingAction`을 그대로 `Respond`에 사용 | 이미 공개 정보만 담고 있음 |
| 4 | 증명 성공 시 상실 → 교체 (§4.5) | **교체 → 상실** | 도전자가 고르는 동안 공개된 카드가 상대 손에 남지 않게. 결과는 동일 |
| 5 | `RuleModifier` 훅 4개 (§5.1) | 훅 없음. `RuleSetBuilder.modifyAction`으로 효과를 감싼다 | `last_stand`까지 훅 없이 구현됨. 필요해질 때(L2) 추가 |
| 6 | 교환 시 드로우한 카드를 덱에서 제거 | 선택이 끝날 때까지 **덱에 둔 채 엿본다** | 카드 총량 불변식이 모든 시점에 성립 |
| 7 | `Rejection` 목록 (§4.2) | `CHALLENGE_NOT_ALLOWED` 추가 | 주장 없는 행동에 도전 시도 |
| 8 | `GameEvent` 목록 (§4.7) | `CardReplacedHidden`, `ExchangeDrawnHidden`(투영 결과), `PlayerConceded`, `BlockDeclared` 등 추가. `CardsDealt`는 미도입 | 투영 타입을 이벤트 계층에 포함 |
| 9 | 기권은 일반 명령 (§4.2) | 기권은 해결 루프를 돌리지 않고 상태별로 정리 | 다른 사람의 대기 중 결정을 건너뛰지 않기 위해 |
| 10 | `ActionOutcome` | `CANCELLED`(행위자 기권) 추가 | |
| 11 | 응답 타임아웃 값은 런타임 | `timeoutCommand`가 기본 *명령*을 만든다. 시간 정책은 `:runtime` | 설계와 동일하나 명시 |
| 12 | `RuleSetConfig.houseRules`는 순서 있음 | **ID 정렬 순서로 적용** | 참가자 간 결정성 |

## 엔진이 보장하는 것 (Phase 2 이후 신뢰해도 되는 불변식)
- 카드 총량 보존(덱 + 모든 손패, 공개 포함), CardId 중복 없음, 코인 ≥ 0, 탈락 기록 일관, 종료 시 스택 비어 있음.
- 진행 중인 게임에서 `pendingDeciders`는 비어 있지 않고 각 결정권자의 `legalOptions`는 null이 아니다.
- `legalOptions`가 만든 명령은 항상 수락되고, `timeoutCommand`도 항상 수락된다.
- 같은 seed + 같은 명령 로그 → 같은 최종 상태. JSON 백업/복원 후에도 같은 결과.
- `view()`는 시점에서 알 수 없는 카드 ID를 담지 않는다. `determinize(view, 정답 가정)`을 다시 `view()`하면 원래 뷰와 같다.
- 검증: 하우스룰 6가지 조합 x 2~6인 x 60판 무작위 완주, 엔진 라인 커버리지 97.9% (CI `koverVerify` 90% 게이트).
