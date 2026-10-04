# ADR 0005: Firestore 전송을 `DocumentStore` 추상화 위의 순수 Kotlin 모듈 `:remote`로 구현

- 상태: 채택 (2026-10-04)
- 관련: 설계 §8 (멀티플레이어 & 데이터), ADR 0001, ADR 0004

## 배경
설계 문서는 `FirestoreGameTransport`를 Android 라이브러리 `:data`에 두었다. 그러나 클라우드 환경에서는 Firebase SDK(Google Maven)를
받을 수 없고, 전송 계층의 위험(좌석별 뷰 분리, 명령 수명주기, 사칭 방지, 방장 소실 감지)은 SDK와 무관하다.

## 결정
- `core/remote`(`:remote`, 의존: `:runtime`, `:engine`)에 `DocumentStore`(문서 쓰기 배치 / 문서 구독 / 컬렉션 조건 구독) 추상화와
  `FirestoreGameTransport`(`GameTransport.Host/Guest` 구현)를 둔다. Firebase SDK 의존 없음. 경로·필드 이름은 `FirestoreSchema`에 모았다.
- Android 쪽 `:data`는 `DocumentStore`의 **얇은 Firebase 어댑터**(Firestore 맵 <-> `JsonObject`, 오류를 `TransportException`으로)만 구현한다.
- 엔진 객체(뷰 봉투, 명령, 룰 설정, 권한자 백업)는 **JSON 문자열 필드**로 저장한다 — 엔진 스키마가 바뀌어도 문서 구조는 유지된다.
- 보안: 접근 매트릭스를 테스트의 `FakeFirestore.AccessPolicy`로 **실행 가능한 모델**로 만들고 모든 읽기·쓰기에 적용한다.
  `firebase/firestore.rules`는 그 손 번역이다. **규칙 파일 자체는 미검증**(에뮬레이터 없음) — `firebase/README.md`에 검증 절차와 알려진 위험을 적었다.
- `GameTransport.Host.heartbeat(gameId)`를 추가했다(기본 no-op). `HostGameSession`이 10초마다 보내고, 게스트의 `connection`은
  `hostHeartbeatAt`가 30초 넘게 갱신되지 않으면 `HOST_LOST`, 게임 문서가 FINISHED면 `CLOSED`가 된다.
- `TransportException`을 `open`으로 바꿨다(저장소 구현이 하위 예외를 던질 수 있도록).

## 결과
- 15개 테스트: 4인 완주, 좌석별 뷰 문서만 쓰기, 백업 복구 후 이어하기, 명령 PENDING->APPLIED/REJECTED, 동시 응답, 형식 오류 명령 격리,
  사칭 거절, 게스트의 접근 거부 6종(남의 뷰/권한자 상태/게임 문서/결과/명령 목록/남의 명령), 외부인 차단, 생존 신호.
- 정책을 느슨하게 바꾸면 해당 테스트가 실패함을 변이로 확인.
- 남은 일: Android 어댑터(`:data`), 규칙 에뮬레이터 검증, 게임 시작 시 `createGame` 호출 연결(방/로비 구현과 함께).
