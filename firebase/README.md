# Firebase 설정 (Firestore 보안 규칙)

## 상태
| 파일 | 상태 |
|---|---|
| `firestore.rules` | ⚠ **미검증** — 클라우드 개발 환경에서는 Firebase 에뮬레이터를 돌릴 수 없어 규칙을 실행해 보지 못했다. |

## 규칙의 근거
`core/remote/src/test/kotlin/io/github/lhd980820/coup/remote/FakeFirestore.kt` 의 `AccessPolicy`가 같은 접근 매트릭스를
**실행 가능한 모델**로 구현하고, `FirestoreTransportTest`가 (1) 전송 계층이 허용된 경로만 쓰는지 (2) 게스트가 남의 뷰·권한자 상태·
게임 문서에 접근하지 못하는지를 검증한다. 모델을 느슨하게 바꾸면 해당 테스트가 실패하는 것도 확인했다.
`firestore.rules`는 이 모델의 손 번역이므로 **둘은 항상 함께 고친다.**

## 접근 매트릭스 (설계 문서 §8.3)
| 경로 | 읽기 | 쓰기 |
|---|---|---|
| `games/{id}` | 좌석 uid | 만들기: 방장 본인 / 수정: 방장 |
| `games/{id}/views/{uid}` | 해당 uid | 방장 |
| `games/{id}/views/spectator` | 좌석 uid | 방장 |
| `games/{id}/authority/state` | 방장 | 방장 |
| `games/{id}/commands/{cid}` | 방장(목록 포함), 보낸 사람(개별) | 만들기: 방장이 아닌 좌석이 자기 uid + PENDING으로 / 수정: 방장 |
| `results/{id}` | 로그인한 누구나 | 방장이 1회. 레이팅 게임이면 순위 = 사람 좌석 전원, 변동량 = 레이팅 표 |
| `users/{uid}` | 로그인한 누구나(랭킹 조회 포함) | 만들기: 본인, 시작값(레이팅 1000·0판) 또는 옛 문서 값 + 같은 배치의 이메일 선점 / 수정: 본인, 닉네임만 또는 레이팅 반영(아래) |
| `users/{uid}/appliedResults/{gid}` | 본인 | 만들기: 본인, 같은 배치에서 프로필이 이 게임을 반영할 때만 / 수정·삭제 불가 |
| `pendingRatings/{uid}` | 로그인한 누구나 | 만들기: 그 게임의 방장이 rated 결과를 만드는 같은 배치에서, 순위에 있는 uid에게만 / 지우기: 본인, 그 게임을 반영하는 배치에서 / 수정 불가 |
| `legacyClaims/{email}` | 토큰 이메일이 같을 때 | 만들기: 토큰 이메일이 같고 uid가 본인 / 수정·삭제 불가 |
| `user/{email}` (옛 앱) | 토큰 이메일이 같을 때 | 불가 |

레이팅 반영 수정은 `rating`·`plays`·`wins`·`lastAppliedResult`·`lastAppliedRank`만 바꿀 수 있고, 변동량은 결과 문서의 순위·인원수로
**레이팅 표에서 다시 계산**하며(결과 문서의 값과도 같아야 함), 반영 표시 문서가 전에는 없고 배치 뒤에는 있어야 한다(ADR 0007).

## 배포 전 검증 절차 (아직 안 함)
1. `npm i -D @firebase/rules-unit-testing` 와 Firebase CLI 설치, `firebase emulators:start --only firestore`.
2. `FakeFirestore.kt` 의 매트릭스를 그대로 옮긴 규칙 테스트(Node)를 작성: 각 경로 x 역할(방장/게스트/외부인) x 동작(get/list/create/update/delete)에 대해 허용·거부를 단언.
3. 특히 확인할 것: (a) `get(...)`으로 게임 문서를 읽는 규칙 함수의 비용(문서 읽기 한도), (b) `commands`의 `list`가 방장에게만 열리는지, (c) 아직 없는 명령 문서를 구독(`get`)할 때 보낸 직후 자기 문서가 거부되지 않는지 — 모델은 "존재하지 않는 문서의 구독은 허용"으로 단순화했고, 실제 Firestore는 `resource`가 null이면 `resource.data` 접근이 실패해 **거부될 수 있다**(아래 참고).
   (d) `getAfter`/`existsAfter`를 쓰는 레이팅 반영·이관 규칙이 배치 하나로 통과하는지, (e) `string.size()`가 이모지·한글을 몇 글자로 세는지(앱은 코드 포인트), (f) `ratingTable()[string(n)][rank]`의 범위 밖 접근이 거부로 끝나는지.
4. 통과하면 이 README의 상태를 ✅로 바꾼다.

## 알려진 위험 (검증 시 우선 확인)
- **보낸 직후 자기 명령 문서 구독**: 게스트는 명령 문서를 만든 뒤 그 문서를 `get` 구독해 승인/거절을 기다린다.
  규칙 `allow get: ... resource.data.senderUid == request.auth.uid` 는 문서가 생성된 뒤에는 통과하지만, 구독 시작 시점에 문서가 아직 로컬 캐시에만 있으면 일시적으로 `resource == null`이 될 수 있다.
  거부되면 게스트는 `TransportException`을 받는다 → 필요하면 문서 ID를 예측 불가능한 값으로 쓰고 `allow get`을 보낸 사람 또는 방장 + 문서 ID 추측 불가에 의존하도록 완화하거나, 승인 결과를 `views/{uid}` 문서의 최근 ack 필드로 돌려주는 방식으로 바꾼다.
- **레이팅은 클라이언트가 반영한다(Phase 7 전까지)**: 규칙은 "잘못 반영"(부풀리기, 두 번 반영, 남의 순위)은 막지만
  **"반영하지 않기"는 막지 못한다** — 수정된 앱은 진 게임을 반영하지 않을 수 있다. 방장이 순위 자체를 조작하는 것도 막지 못한다.
  → **정산 게이트**로 완화: rated 게임이 끝나면 `pendingRatings/{uid}`가 생기고, 정산(반영)해야만 지워진다. 방 입장 규칙이
  `isSettled(uid)`를 요구하면(Firestore 방 규칙 작성 시 연결) 진 게임을 반영하지 않은 사람은 다음 rated 게임을 할 수 없다.
  방장의 순위 조작은 서버 판정(Cloud Function)으로만 해결된다. ADR 0007 참고.
- **게임 좌석 동의**: 지금은 방장이 게임 문서의 `seats`에 아무 uid나 넣을 수 있다. 정산 게이트가 있으면 이것으로 남에게 가짜 패배를
  씌우고 정산을 강요할 수 있으므로, **Firestore 방 규칙에서 게임 좌석 = 방에 스스로 들어온 사람으로 묶어야 한다**(다음 작업).
- **배포하면 옛 앱이 멈춘다**: `user/{email}`을 읽기 전용으로 막으므로 옛 앱은 로그인·전적 기록을 할 수 없다. 새 앱 출시와 같은 시점에 배포할 것.
- **방장 단일 장애점**: 방장이 사라지면 게임이 멈춘다(설계 R6). 하트비트로 게스트에게 `HOST_LOST`를 알릴 뿐 방장 이전(host migration)은 없다.
- **비용**: 명령 1건당 쓰기 ≈ 좌석 수 + 관전 1 + 백업 1 + 메타 1 + 명령 상태 1 (설계 R7).
