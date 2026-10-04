# ADR 0004: 화면 상태·사용자 의도 계층을 순수 Kotlin 모듈 `:presentation`으로 분리

- 상태: 채택 (2026-10-04)
- 관련: 설계 §10.4 (`GameUiState`, `GameUiMapper`), §12.9 (UI 테스트), ADR 0001

## 배경
설계 문서는 `GameUiMapper`와 ViewModel을 `:app`(Android)에 두었다. 그러나 클라우드 환경에서는 Android 빌드를 검증할 수 없고,
게임 화면의 핵심 위험(버튼 활성 조건, 여러 단계 입력, 중복 탭, 상태 도착 시차)은 Android와 무관한 로직이다.

## 결정
`core/presentation`(`:presentation`, 의존: `:runtime`, `:engine`, `:ai`)에 다음을 둔다. Android 의존 없음.
- `GameUiState` 계열: **문자열이 아니라 구조화된 값**(`Banner`, `LogEntry`, `DecisionUi`, `UiEffect`, `UiMessage`).
  번역·아이콘·카드 아트는 `:app`이 문자열 리소스와 역할/행동 카탈로그(없으면 제네릭 카드 폴백)로 해결한다.
- `GameUiMapper`: `SessionSnapshot` -> `GameUiState` 순수 함수. 버튼 활성 여부·블러핑 표시는 엔진 `DecisionRequest`에서만 나온다.
- `GameController`: 세션을 구독하고 사용자 의도를 엔진 명령으로 바꾼다. "행동->대상", "카드 선택->확정", 교환 토글 같은 화면 위 선택 상태는
  여기서만 관리한다. Android `ViewModel`은 이것을 감싸 `viewModelScope`로 의도를 호출하기만 하면 된다.
- `SinglePlayerConfig`/`SinglePlayerSessionFactory`: 싱글플레이 설정 -> `LocalGameSession`.

`:app`의 ViewModel/Compose는 이 계층의 `StateFlow<GameUiState?>`를 그리고 `GameController` 메서드를 호출하는 얇은 어댑터가 된다.

## 결과
- 화면 로직 전체가 JVM 테스트 대상: 매퍼 14개, 컨트롤러 17개, **UI 버튼만으로 AI 1~5명과 끝까지 플레이**하는 통합 테스트 등.
- 통합 테스트로 발견한 결함: 명령이 수락돼도 새 화면 상태가 도착하기 전 짧은 틈에 버튼이 풀려 옛 버전으로 두 번째 탭이 나가고
  "이미 지난 버전" 오류가 사용자에게 보임 -> 컨트롤러가 수락 후 새 상태가 올 때까지(최대 3초) 입력을 잠그도록 수정.
- 세션의 `events` 흐름에 최근 64개 재생(replay)을 추가: 화면이 구독하기 전에 AI가 먼저 행동해도 로그가 빠지지 않는다.
