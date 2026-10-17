# ADR 0007: 사용자·레이팅·랭킹 (`core/account` + `FirestoreUserRepository`)

## 상태
채택 (2026-10). 보안 규칙은 에뮬레이터 미검증(`firebase/README.md`).

## 맥락
설계 §8.4: 서버(Phase 7) 전까지 각 참가자 기기가 **자기 레이팅만** 결과 문서(`results/{gameId}`)를 보고 1회 반영하고,
규칙이 그 변경을 교차 검증한다. 설계는 `users/{uid}.appliedResults`(배열)에 반영한 게임 ID를 쌓는 방식이었다.

## 결정
1. **순수 모듈 `:account`**(엔진만 의존): `UserProfile`, `Nicknames`(2~12 코드 포인트, 제어·서식 문자 금지), `RatingLedger`
   (결과 -> 반영/건너뜀), `Rankings`(동점 공동 순위, 0판 제외), `LegacyMigration`(옛 `user/{email}` 이관 계획).
2. **변동량은 결과 문서 값을 믿지 않고 레이팅 표에서 다시 계산**한다(순위·인원수 -> `TableRatingPolicy`). 결과 문서 값과 다르면
   클라이언트도 반영하지 않고(`INCONSISTENT`) 규칙도 거절한다. 방장 기기가 변동량을 부풀려도 효과가 없다.
3. **"한 번만"은 배열 대신 표시 문서**로: 반영은 한 배치로 `users/{uid}` 갱신(+`lastAppliedResult`, `lastAppliedRank`) +
   `users/{uid}/appliedResults/{gameId}` 생성. 규칙은 표시 문서가 **전에는 없고(`exists`) 배치 뒤에는 있을 때(`existsAfter`)** 만
   레이팅 변경을 허용하고, 표시 문서는 같은 배치에서 프로필이 그 게임을 반영할 때만 만들 수 있으며 수정·삭제 불가.
   - 배열 방식은 문서가 계속 커지고(1MB 한도) 규칙에서 "새 원소가 정확히 이 게임 하나"를 검증하기 번거롭다.
   - 표시 문서는 그대로 **전적**이 된다(`observeHistory`).
   - 규칙의 `rating == before + delta` 검사가 낙관적 동시성 제어 역할을 한다: 두 기기가 같은 게임을 동시에 반영하면 한쪽만 성공,
     다른 게임을 동시에 반영하면 늦은 쪽이 거절 -> 저장소가 다시 읽어 재시도(최대 3회).
4. **rating/plays/wins는 rated 게임만 센다**(D5). 하한 없음(옛 앱과 같음). 시작 레이팅 1000(옛 앱과 같음). 옛 앱에 없던 `wins`는 0에서 시작.
5. **결과 문서 생성 규칙 강화**: rated면 순위가 정확히 게임 문서의 사람 좌석 전원이어야 한다(가짜 uid 끼워 넣기 방지).
6. **공개 프로필에 개인 정보 없음**: 설계의 `legacyEmailId`는 넣지 않는다(`users/*`는 누구나 읽는다). 이관 여부는
   `migratedFromLegacy`, 이메일 선점은 본인만 읽는 `legacyClaims/{email}`. 이관 값은 규칙이 옛 문서와 같은지 검사한다.
   이메일 인증 여부는 요구하지 않는다 — Firebase Auth는 한 이메일에 계정 하나이고, 옛 문서가 있는 이메일은 이미 그 계정의 것이다.
7. `DocumentStore`에 `observeOrdered(collection, orderBy, descending, limit)` 추가(랭킹·전적).
8. 테스트용 `FakeFirestore`의 배치 평가를 실제와 같게 바꿨다: 각 쓰기를 **배치 전 상태(`get`)와 배치 후 상태(`getAfter`)** 로 판단.
   (이전에는 앞선 쓰기만 반영된 중간 상태로 판단했다.) 기존 전송 테스트는 그대로 통과한다.

## 남는 위험 (Phase 7 전까지)
- **반영하지 않기**: 규칙은 잘못된 반영은 막지만, 수정된 앱이 **진 게임을 반영하지 않는 것**은 막지 못한다.
  - 제안(오너 결정 필요): Firestore 방 저장소를 구현할 때 "정산 전에는 다음 rated 게임 불가" 게이트를 넣는다 —
    방장이 결과 배치에서 `pendingRatings/{uid}`(gameId)를 쓰고, 참가자는 반영 배치에서 이를 지우며(해당 게임 표시 문서가 배치 뒤 존재할 때만),
    방 입장/게임 생성 규칙은 `!exists(pendingRatings/{uid})`를 요구한다. 완전한 해결은 Cloud Function이 모든 참가자를 갱신하는 것.
- **순위 조작**: 방장 기기가 순위 자체를 바꿔 쓰는 것은 막지 못한다(방장 권위 모델의 한계, D2). 서버 판정으로만 해결.
- 규칙 언어의 `string.size()`가 닉네임 길이를 앱(코드 포인트)과 같게 세는지 에뮬레이터로 확인해야 한다.

## 후속
- 앱(Android 세션): 결과 화면에서 `applyResult(gameId)` 호출(`NoResultYet`이면 잠시 뒤 재시도), 로그인 직후 `ensureProfile`.
- 반영 누락 복구: 앱 시작 시 마지막 게임(`activeGameId` 등)의 결과를 다시 `applyResult` — 앱 쪽에서 구현.
