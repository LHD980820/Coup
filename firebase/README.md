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
| `results/{id}` | 로그인한 누구나 | 방장이 1회 |

## 배포 전 검증 절차 (아직 안 함)
1. `npm i -D @firebase/rules-unit-testing` 와 Firebase CLI 설치, `firebase emulators:start --only firestore`.
2. `FakeFirestore.kt` 의 매트릭스를 그대로 옮긴 규칙 테스트(Node)를 작성: 각 경로 x 역할(방장/게스트/외부인) x 동작(get/list/create/update/delete)에 대해 허용·거부를 단언.
3. 특히 확인할 것: (a) `get(...)`으로 게임 문서를 읽는 규칙 함수의 비용(문서 읽기 한도), (b) `commands`의 `list`가 방장에게만 열리는지, (c) 아직 없는 명령 문서를 구독(`get`)할 때 보낸 직후 자기 문서가 거부되지 않는지 — 모델은 "존재하지 않는 문서의 구독은 허용"으로 단순화했고, 실제 Firestore는 `resource`가 null이면 `resource.data` 접근이 실패해 **거부될 수 있다**(아래 참고).
4. 통과하면 이 README의 상태를 ✅로 바꾼다.

## 알려진 위험 (검증 시 우선 확인)
- **보낸 직후 자기 명령 문서 구독**: 게스트는 명령 문서를 만든 뒤 그 문서를 `get` 구독해 승인/거절을 기다린다.
  규칙 `allow get: ... resource.data.senderUid == request.auth.uid` 는 문서가 생성된 뒤에는 통과하지만, 구독 시작 시점에 문서가 아직 로컬 캐시에만 있으면 일시적으로 `resource == null`이 될 수 있다.
  거부되면 게스트는 `TransportException`을 받는다 → 필요하면 문서 ID를 예측 불가능한 값으로 쓰고 `allow get`을 보낸 사람 또는 방장 + 문서 ID 추측 불가에 의존하도록 완화하거나, 승인 결과를 `views/{uid}` 문서의 최근 ack 필드로 돌려주는 방식으로 바꾼다.
- **방장 단일 장애점**: 방장이 사라지면 게임이 멈춘다(설계 R6). 하트비트로 게스트에게 `HOST_LOST`를 알릴 뿐 방장 이전(host migration)은 없다.
- **비용**: 명령 1건당 쓰기 ≈ 좌석 수 + 관전 1 + 백업 1 + 메타 1 + 명령 상태 1 (설계 R7).
