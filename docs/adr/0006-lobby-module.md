# ADR 0006: 방/로비 모듈 (`core/lobby`)

## 상태
채택 (2026-10)

## 맥락
설계 §8.2는 방(`rooms/{roomId}`)의 생성·입장·준비·시작을 정의한다. 규칙(누가 언제 무엇을 할 수 있는가)은 UI·Firestore와 무관하게 JVM 테스트로 검증되어야 한다.

## 결정
- 새 모듈 `:lobby` (순수 Kotlin, `:runtime`·`:ai`·`:engine`에 의존).
- **규칙은 순수 함수**(`RoomRules`: `(방, 행위자, ...) -> RoomResult<Room>`). 위반은 예외가 아니라 `RoomError` 값.
- **동시성은 저장소가 맡는다**: `RoomStore.update(id, transform)`은 원자적 읽기-수정-쓰기(메모리 구현은 Mutex, Firestore 구현은 트랜잭션). 마지막 자리에 동시 입장해도 정확히 한 명만 성공하는 것을 테스트로 고정.
- `RoomService`가 규칙과 저장소를 이어 ID·참가 코드·시드를 만든다. `start`는 `GameStartPlan`(섞인 좌석 순서, `GameSetup`, `HostSeat` 목록, 레이팅 여부)을 돌려주고, 방장 기기가 `HostGameSession`을 연다. 같은 방 + 같은 seed -> 같은 계획.
- **게임에 영향을 주는 변경(룰셋·봇 추가/제거·게임 종료)은 게스트의 `ready`를 초기화**한다. 그러지 않으면 모두 준비한 뒤 방장이 규칙을 바꿔 동의하지 않은 게임이 시작된다. 최대 인원 변경은 게임 내용이 아니므로 유지.
- 앱 버전 게이트: `minAppVersion`(방을 만든 클라이언트 버전)과 룰셋 지원 여부(`registry.build`)를 모두 검사 -> `APP_UPDATE_REQUIRED`.
- 레이팅 여부(D5)는 `Room.isRated`: 봇·하우스룰·파라미터 변경이 모두 없을 때.

## 설계 대비 차이
- 상태는 `WAITING / PLAYING / CLOSED` 3종(설계의 STARTING/FINISHED/ABANDONED 대신). 게임 상태는 `games/{id}`가 가지므로 방은 "게임 중인가"만 알면 충분하고, 종료 후에는 `endGame`으로 WAITING에 복귀한다(다시 하기).
- 방장 퇴장 = 방 닫힘(옛 앱의 동작). 게임 중 게스트 퇴장은 거절(기권으로 처리).

## 후속
- Firestore `RoomStore`는 `DocumentStore`에 트랜잭션 지원을 추가한 뒤 구현하고, 방 접근 규칙(`firestore.rules`)은 실행 가능한 `AccessPolicy` 모델과 함께 작성한다.
- 입장 대기 시간 초과(방장 무응답 방 정리)는 서버 TTL/Cloud Function 영역이라 Phase 7로 미룬다.
