# Firebase 설정 (Firestore 보안 규칙)

## 상태
| 파일 | 상태 |
|---|---|
| `firebase.json` | 에뮬레이터: Firestore 8080, Auth 9099(어댑터 통합 테스트용) |
| `firestore.rules` | ✅ **에뮬레이터 검증됨**(2026-10-24, 방장 이어받기 추가 후) — 모델의 허용/거부 판단 3,061건(거부 194건, 시나리오 78개)을 실제 규칙에 재생해 **불일치 0건**. 규칙 조건 변이: 전체 86개 중 71개 검출(ADR 0016 시점) + 이어받기 관련 새 조건 17개 중 15개 검출, 나머지는 다른 조건과 겹치는 중복 방어(아래, ADR 0017). 이어받기의 실제 서버 시각(Timestamp) 비교는 `:data` 에뮬레이터 테스트가 60초를 기다려 확인한다. 배포 전에는 한 번 더 돌릴 것. |
| `../server/` | 서버 판정·정리 Cloud Function(ADR 0015, 0016). 배포는 아래 "서버 판정 배포". |

## 규칙의 근거
`core/remote/src/test/kotlin/io/github/lhd980820/coup/remote/FakeFirestore.kt` 의 `AccessPolicy`가 같은 접근 매트릭스를
**실행 가능한 모델**로 구현하고, `FirestoreTransportTest`가 (1) 전송 계층이 허용된 경로만 쓰는지 (2) 게스트가 남의 뷰·권한자 상태·
게임 문서에 접근하지 못하는지를 검증한다. 모델을 느슨하게 바꾸면 해당 테스트가 실패하는 것도 확인했다.
`firestore.rules`는 이 모델의 손 번역이므로 **둘은 항상 함께 고친다.**

## 접근 매트릭스 (설계 문서 §8.3)
| 경로 | 읽기 | 쓰기 |
|---|---|---|
| `games/{id}` | 좌석 uid | 만들기: 방장, 방이 이 gameId로 시작됨 + 좌석 = 방 사람 전원 + rated = 방 구성 + `createdAt`·`hostHeartbeatAt` = 서버 시각 / 수정: 방장, 진행 중인 판만, `status`·`version`·`hostHeartbeatAt`(서버 시각)만, 끝남(`FINISHED`)은 결과를 만드는 같은 배치에서만 / **이어받기**(ADR 0017): 좌석의 다른 사람이 방장 생존 신호가 60초 넘게 오래됐을 때 `hostUid`를 자기로(생존 신호 = 지금) / 판정·버려진 판 처리는 서버만 |
| `games/{id}/views/{uid}` | 해당 uid | 방장 |
| `games/{id}/views/spectator` | 좌석 uid | 방장 |
| `games/{id}/authority/state` | 방장 | 방장 |
| `games/{id}/authority/setup` | 방장 | 게임 문서를 만드는 같은 배치에서 방장이 만들기만 / 수정·삭제 불가 |
| `games/{id}/log/{version}` | 방장 | 방장이 만들기만, ID = 버전, `at` = 서버 시각, `host` = 쓴 방장 본인 / 수정·삭제 불가 |
| `games/{id}/commands/{cid}` | 방장(목록 포함), 보낸 사람(개별) | 만들기: 방장이 아닌 좌석이 자기 uid + PENDING으로 / 수정: 방장 |
| `results/{id}` | 로그인한 누구나 | 방장이 1회, 진행 중인 판에서만. 레이팅 결과는 게임이 rated일 때만, 순위 = 사람 좌석 전원, 변동량 = 레이팅 표 |
| `rooms/{id}` | 로그인한 누구나(목록은 공개 방만) | 모든 쓰기에 `updatedAt` = 서버 시각(ADR 0016) / 만들기: 방장 혼자 앉은 새 방 / 게스트: 대기 중 자기 항목만(입장·준비·퇴장) / 방장: 사람은 빼기만, 룰셋·봇 변경 시 configVersion+1, 시작은 전원 준비 시 / 게임 중인 방의 방장은 그 방 게임의 방장이 된 사람이 이어받는다(ADR 0017) / 닫힌 방 불변 |
| `joinCodes/{code}` | 로그인한 누구나(개별 조회만) | 만들기: 그 방을 같은 배치에서 만드는 방장 / 지우기: 방을 닫는 방장 |
| `users/{uid}` | 로그인한 누구나(랭킹 조회 포함) | 만들기: 본인, 시작값(레이팅 1000·0판) 또는 옛 문서 값 + 같은 배치의 이메일 선점 / 수정: 본인, 닉네임만(레이팅은 서버 판정만) |
| `users/{uid}/appliedResults/{gid}` | 본인 | 서버 판정만 |
| `pendingRatings/{uid}` (옛 정산 대기) | 불가 | 불가(서버 판정이 남은 것을 지운다) |
| `legacyClaims/{email}` | 토큰 이메일이 같을 때 | 만들기: 토큰 이메일이 같고 uid가 본인 / 수정·삭제 불가 |
| `user/{email}` (옛 앱) | 토큰 이메일이 같을 때 | 불가 |

레이팅은 **서버 판정**(Cloud Function, 관리자 권한 — 규칙을 거치지 않는다)이 끝난 게임을 기록대로 다시 돌려 검증한 뒤 참가자 전원에게 한 트랜잭션으로 반영한다(ADR 0015).
예전의 클라이언트 반영 규칙(ADR 0007)과 정산 게이트(`pendingRatings`, `isSettled`)는 지웠다.

## 검증 방법 (`rules-test/`)
`core/remote`의 접근 테스트가 모델([`AccessPolicy`])의 모든 판단을 `-Pruletrace=<dir>`로 기록하고, `rules-test/replay.mjs`가 같은 순서로 **실제 규칙을 에뮬레이터에서** 실행해
허용/거부가 같은지 비교한다. 자세한 사용법은 [rules-test/README.md](rules-test/README.md).
- `npm test` — 재생 비교. `npm run test:extra` — 모델이 단순화했거나 모르는 동작을 규칙에서만 확인.
- `npm run mutate` — 규칙의 각 `&&`/`||` 조건을 무력화해도 불일치가 생기는지(= 시나리오가 그 조건을 검증하는지).

### 검증이 찾은 것 (모두 수정됨)
- **보낸 직후 자기 명령 문서를 구독하면 거부될 수 있었다**: `resource == null`일 때 `resource.data` 접근이 오류 -> 거부. `resource == null`을 명시적으로 허용(좌석인 사람만).
- **닉네임 길이**: 규칙의 `size()`는 UTF-16 단위(이모지 하나 = 2)인데 앱은 코드 포인트로 세고 있었다 -> 앱의 `Nicknames`를 `String.length`로 맞췄다(이모지 최대 6개).
- 변이 검증이 모델 테스트의 빈틈 15곳 이상을 드러냈다(6인 방 뒤쪽 좌석의 정산·준비, 게임 좌석 바꿔치기, 룰 파라미터 변경 방의 rated 거짓말, 방 명단 바꿔치기, 입장 항목 모양 등) — 모두 테스트 추가.

### 살아남은 변이 15개 (모두 중복 방어 — 같은 쓰기를 다른 조건이 막는다)
서버 판정 전환 때 처음 돌린 18개 중 3개는 빈틈이었다(게임 생성의 `createdAt == request.time`, 4~6인 결과의 순위별 변동량, 방장이 명령 문서를 만드는 것) — 테스트를 더해 검출한다.
| 조건 | 왜 중복인가 |
|---|---|
| 옛 계정 선점 `!exists(claim)` | 이미 있는 문서를 다시 만드는 쓰기는 `update`가 되고, 선점 문서는 수정 불가 |
| 선점 문서 `uid == auth.uid` | 이관 규칙이 `getAfter(claim).uid == auth.uid`를 다시 요구한다 |
| 게임 생성 `room.status == 'PLAYING'` | `room.gameId == gameId`가 시작 전 방(gameId = null)을 이미 거른다 |
| 결과 `ranking.size() == seats.size()` | 중복이 낀 순위는 변동량 검사(순위별 값이 서로 어긋남)가 막는다 |
| 실행 설정 `getAfter(game).hostUid == auth.uid` | 설정은 게임 문서와 같은 배치에서만 만들 수 있고, 게임 생성 규칙이 만드는 사람 = 방장을 요구한다 |
| 기록 `version is int` | `string(version) == 문서 ID`가 정수가 아닌 값을 거른다 |
| `PLAYING -> PLAYING` 무변경 갱신 허용 | 아무도 쓰지 않는 무의미한 쓰기를 막을 뿐 |
| 게스트 변경 키 `affectedKeys().hasOnly([uid])`·`size() == 1`, 입장 항목 `hasAll`, 퇴장 시 명단 `removeAll`, 준비 값 `-1 또는 configVersion` | 같은 줄 주변의 타입·모양 검사와 명단 = members 검사가 막는다 |
| 닫힌 방 불변 `status != 'CLOSED'` | `hostRoomUpdate`의 상태 기계가 닫힌 방에서 나가는 전환을 허용하지 않는다 |
| 참가 코드 `hostUid == auth.uid`(만들기·지우기) | 방 문서 자체가 방장만 만들 수 있고, 닫는 사람도 방장뿐 |

## 알려진 위험 (검증 시 우선 확인)
- **재생 비교는 "모델이 시도한 것"만 본다**: 모델에 없는 시도(새 필드, 새 경로)는 비교되지 않는다. 규칙을 바꾸면 모델·테스트도 함께 바꿀 것.
- **Android SDK 쪽 동작**: `:data`의 `FirebaseIntegrationTest`가 실제 Firebase SDK로 같은 에뮬레이터·규칙에 붙어 프로필·이관·방 경쟁·좌석 동의·멀티플레이 한 판(정산까지)을 확인한다(ADR 0009).
  에뮬레이터(`--only firestore,auth`)를 띄운 뒤 루트에서 `./gradlew :data:testDebugUnitTest`. 오프라인 영속 캐시와 재연결은 아직 안 봤다.
- **레이팅은 서버 판정이 반영한다(ADR 0015)**: 방장의 순위 조작·수 위조·이른 시간 초과·진 게임 반영 안 하기는 서버가 막는다.
  시드는 서버 생성 시각과 섞여 방장이 고를 수 없고, 판을 버린 방장은 서버 정리가 패배로 반영한다(ADR 0016).
  남는 위험: 방장이 남의 패를 보는 것, 게스트 명령을 일부러 처리하지 않기(시간 초과와 같음). **규칙만 배포하고 함수를 배포하지 않으면 레이팅이 멈춘다.**
- **게임 좌석 동의**: 게임 문서 생성 규칙이 좌석 = 방에 스스로 들어온 사람 전원을 요구한다(ADR 0008). 방장은 방에 사람을 넣을 수 없다.
- **규칙 문서 읽기 한도**: 게임 생성 규칙은 방 1번을 읽는다(정산 게이트를 지워 예전의 최대 7번에서 줄었다, 한도 10).
- **배포하면 옛 앱이 멈춘다**: `user/{email}`을 읽기 전용으로 막으므로 옛 앱은 로그인·전적 기록을 할 수 없다. 새 앱 출시와 같은 시점에 배포할 것.
- **방장 이어받기**(ADR 0017): 방장이 60초 넘게 조용하면 좌석의 게스트가 게임·방의 방장을 이어받아 백업에서 판을 이어 간다(서버 함수 불필요).
  이어받은 사람은 모든 패를 안다(방장과 같은 위험). 이어받을 사람이 없으면 10분 뒤 서버 정리가 끝내고(그때의 방장만 패배), 방은 15분 뒤 닫힌다(ADR 0016).
- **비용**: 명령 1건당 쓰기 ≈ 좌석 수 + 관전 1 + 백업 1 + 메타 1 + 명령 상태 1 + 기록 1 (설계 R7). 판정은 판마다 함수 1회 + 기록 수만큼 읽기.

## 서버 판정 배포 (오너 작업, ADR 0015)
> 배포 전체(구글 로그인 준비·출시·정리까지)의 순서는 [`../docs/OWNER_CHECKLIST.md`](../docs/OWNER_CHECKLIST.md)에 모았다.

1. 프로젝트를 **Blaze** 요금제로 바꾸고 Cloud Functions·Eventarc·Cloud Build·Artifact Registry·Cloud Run API를 켠다.
2. `cd server && ./gradlew test stageDeploy`(에뮬레이터가 떠 있으면 통합 테스트도 돈다) -> `server/build/deploy/coup-judge.jar`.
3. 리전은 Firestore 데이터베이스 위치와 같게:
   ```
   gcloud functions deploy coup-judge --gen2 --runtime=java21 --region=<Firestore 위치> \
     --source=server/build/deploy --entry-point=io.github.lhd980820.coup.server.JudgeFunction \
     --trigger-event-filters=type=google.cloud.firestore.document.v1.created \
     --trigger-event-filters=database='(default)' \
     --trigger-event-filters-path-pattern=document='results/{gameId}' \
     --retry --memory=512Mi
   ```
4. 정리 함수(ADR 0016): 버려진 판·방을 5분마다 정리한다.
   ```
   gcloud pubsub topics create coup-sweep
   gcloud functions deploy coup-sweep --gen2 --runtime=java21 --region=<Firestore 위치> \
     --source=server/build/deploy --entry-point=io.github.lhd980820.coup.server.SweepFunction \
     --trigger-topic=coup-sweep --memory=512Mi
   gcloud scheduler jobs create pubsub coup-sweep --location=<Firestore 위치> \
     --schedule="*/5 * * * *" --topic=coup-sweep --message-body=sweep
   ```
5. 순서: **함수 둘 -> 규칙(`firebase deploy --only firestore:rules`) -> 앱**. 판정 함수 로그에 `game …: verified, ratings applied to […]`, 정리 함수 로그에 `swept … games`가 찍히는지 확인.
