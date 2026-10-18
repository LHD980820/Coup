# ADR 0009: Firebase `DocumentStore` 어댑터(`:data`)와 에뮬레이터 통합 테스트

## 상태
채택 (2026-10). 실제 Firebase Android SDK + Firestore·Auth 에뮬레이터 + 실제 `firestore.rules`로 검증했다.

## 맥락
- 코어(`:remote`)의 전송·방·사용자 저장소는 `DocumentStore`(ADR 0005, 0008) 위에서 돌고, 그 의미는 `FakeFirestore` + `AccessPolicy`(실행 가능한 규칙 모델)로 고정돼 있다.
- 규칙은 에뮬레이터 재생으로 모델과 일치함을 확인했지만(`firebase/README.md`), **Firebase SDK 쪽 동작**(배치·트랜잭션·리스너·병합 의미)은 아직 확인하지 않았다.

## 결정
1. **새 Android 라이브러리 모듈 `:data`**. `FirebaseDocumentStore(db)`가 `DocumentStore`를 구현한다. 로그인한 사용자 하나당 `FirebaseFirestore` 하나를 받는다(규칙이 그 사용자 기준으로 적용된다).
   `FirestoreJson`이 `JsonObject` <-> Firestore 값(문자열·불리언·정수(Long)·실수·맵·목록, 읽을 때 Int -> Long, Timestamp -> 밀리초)을 바꾼다.
2. **모델과 같은 의미를 지킨다**
   - `WriteOp.Merge`는 `SetOptions.mergeFieldPaths(최상위 키)`로 쓴다: **최상위 필드 통째 교체**. Firestore 기본 merge는 중첩 맵을 깊게 합쳐서 모델과 다르다(예: `members` 맵에서 사람 하나를 빼는 쓰기가 무시된다).
   - **판단에 쓰는 읽기는 `DocumentStore.get`**(새로 추가, 기본 구현 = `observe(path).first()`). Firebase 구현은 `Source.SERVER`로 읽는다. 구독의 첫 값은 캐시일 수 있어서,
     정산(`pendingRatings`)·레이팅 반영·결과 확인처럼 "지금 서버에 무엇이 있는가"로 결정하는 곳은 모두 `get`으로 바꿨다(`FirestoreUserRepository`, `FirestoreRoomStore`, `FirestoreGameTransport`).
   - 구독(`observe`, `observeWhere`, `observeOrdered`)은 `callbackFlow` + **`buffer(CONFLATED)`** + `distinctUntilChanged`. 스냅숏은 문서·쿼리 전체를 담으므로 중간 것을 건너뛰어도 잃는 것이 없다.
     명령 대기열 쿼리(`status == PENDING`)도 안전하다: 명령이 PENDING에서 빠지는 길은 방장의 확인 하나뿐이다.
   - 실패는 모두 `TransportException`(규칙 거부는 `FirestoreFailure(code = PERMISSION_DENIED)`). 트랜잭션 블록 안에서 던진 예외는 Firestore가 감싸므로 원인 사슬에서 꺼낸다.
3. **트랜잭션 경합 재시도**(에뮬레이터에서 찾은 것): 마지막 자리에 둘이 동시에 들어오면 진 쪽이 `ABORTED`가 아니라 **`PERMISSION_DENIED`** 로 끝났다 —
   진 쪽의 쓰기는 옛 읽기로 만든 값이라 규칙(`version == 이전 + 1`, 좌석 수 등)에 먼저 걸리기 때문이다. 그래서 어댑터는 `PERMISSION_DENIED`·`ABORTED`·`FAILED_PRECONDITION`이면
   **처음부터(새로 읽어서) 최대 5번** 다시 시도한다. 진짜 거부는 5번 뒤 그대로 올라간다(비용: 거부되는 트랜잭션은 읽기 5배 — 정상 흐름에서는 일어나지 않는다).
   코어의 트랜잭션 블록은 부수 효과가 없으므로 다시 실행해도 된다.

## 테스트 방법
- Robolectric(`@Config(sdk = [35])`) 위에서 **실제 Firebase Android SDK**를 에뮬레이터에 붙인다. 사용자마다 이름이 다른 `FirebaseApp`(Auth + Firestore 한 쌍, 메모리 캐시)을 만들고
  Auth 에뮬레이터에 가입시켜 서로 다른 인증 컨텍스트로 규칙을 통과/거부시킨다. 테스트마다 에뮬레이터 REST로 문서·계정을 지운다(`Bearer owner`).
- **`@LooperMode(INSTRUMENTATION_TEST)` 필수**: 메인 루퍼가 실제 스레드에서 실제 시간으로 돈다. 기본(PAUSED) 모드에서는 테스트 스레드가 메인 루퍼라서
  막고 기다리면 Firebase 콜백이 오지 않고(교착), 가상 시간 때문에 `delay`가 어긋난다. 본문은 `runBlocking(Dispatchers.IO)`에서 돈다.
- 에뮬레이터가 없으면 테스트를 건너뛴다(로컬·일반 CI). `COUP_REQUIRE_EMULATOR=true`면 실패한다(CI의 `firebase-emulator` 작업).
- 테스트(`FirebaseIntegrationTest`): 프로필 만들기·서버 읽기·구독·남의 문서/레이팅 직접 쓰기 거부, 옛 계정 이관 1회, **실제 트랜잭션으로 마지막 자리 동시 입장 -> 정확히 한 명**,
  방에 없는 사람을 게임 좌석에 넣기 거부, 그리고 **멀티플레이 한 판 전체**(방 -> 게임 문서 -> 방장 권위 세션 + 게스트 원격 세션 3인이 무작위 에이전트로 끝까지 ->
  좌석별 뷰 격리 -> 결과·정산 대기 -> 각자 레이팅 반영(표의 변동량) -> 두 번째 반영 거부 -> 랭킹 쿼리 -> 방 대기실 복귀). 7개 테스트가 10초 안팎.

## 결과와 한계
- 코어의 전송·방·계정 코드는 수정 없이(읽기를 `get`으로 바꾼 것 외) 실제 Firebase에서 돈다.
- 오프라인 캐시(디스크 영속)와 네트워크 끊김·재연결은 아직 다루지 않았다. 테스트는 메모리 캐시를 쓴다. 앱 쪽 연결(로그인·DI)은 로비 UI 때.
