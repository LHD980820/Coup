# ADR 0018: 전면 전환과 정리 (단일 Activity)

## 상태
채택 (2026-10). 오너 요청("Phase 5 정리 진행하자"). 설계 문서 Phase 5의 완료 조건(구 Activity·XML 레이아웃 0, targetSdk 상향)을 따른다.

## 맥락
- 새 Compose 화면이 홈·싱글·튜토리얼·로그인(가입·재설정·구글)·로비·대기실·게임·랭킹·전적을 모두 갖췄다(ADR 0004~0017).
  옛 XML 화면은 이미 쓸 수 없다: 새 규칙이 옛 `user` 문서를 읽기 전용으로 두고 옛 컬렉션 쓰기를 막는다(ADR 0007·0008).
- 옛 사용자 이관은 이미 있다: 첫 로그인에서 `legacyClaims/{email}` 선점과 함께 레이팅·판수를 옮긴다(ADR 0007). 진행 중이던 옛 판은 옮기지 않는다(설계 R1).

## 결정
1. **Activity는 `MainActivity` 하나**(Compose, 런처). 화면 흐름은 `game/AppFlow.kt`(이전 `SinglePlayerFlow`)가 맡는다. 스플래시는 시스템 스플래시에 맡긴다.
2. **옛 코드·리소스 삭제**: 옛 Activity·Fragment·Dialog, `FirebaseManager`·`FirestoreManager`, `ForcedTerminationService`, XML 레이아웃·메뉴·애니메이션, 안 쓰는 드로어블·폰트·색·치수·문자열.
   남기는 리소스는 코드와 다른 리소스가 실제로 참조하는 것뿐이다(참조 그래프를 따라 고정점까지 계산해 지웠다).
3. **옛 의존성 제거**: appcompat, material, constraintlayout, swiperefreshlayout, glide, circleimageview, firebase-storage, play-services-auth(구글 로그인은 Credential Manager), viewBinding.
   테마는 플랫폼 `android:Theme.Material.Light.NoActionBar`(창 배경·시스템 막대만; 화면은 `CoupTheme`).
4. **targetSdk 36**(compileSdk 37 그대로). 가장자리까지 그리기를 켜고(`enableEdgeToEdge`) 화면은 이미 `safeDrawingPadding`을 쓴다. 화면 방향 고정은 없앤다 —
   응용 프로그램 수준 속성은 무효였고, 상태는 ViewModel이 지킨다(회전 유지). 큰 화면 배치는 Phase 6.
5. 정식 내비게이션 라이브러리(Navigation Compose)는 도입하지 않는다: 화면이 `AppFlow` 안의 상태 몇 개로 충분하고, 게임 중 뒤로 가기는 화면별 `BackHandler`가 이미 다룬다.

## 검증
- `COUP_REQUIRE_EMULATOR=true ./gradlew test lint assembleDebug assembleRelease` 통과(app 87·data 19 테스트 실패 0, lint 오류 0). 옛 패키지 클래스·XML 레이아웃 0.

## 결과와 한계
- 옛 컬렉션 데이터와 `google-services.json`의 옛 패키지 클라이언트는 코드가 아니라 콘솔 작업이다(오너): 이관이 끝나면 `user`·`game_rooms`·`game_playing`·`game_result`를 삭제하고 옛 패키지 앱을 정리한다.
- targetSdk 36 동작(예측형 뒤로 가기, 큰 화면 방향 규칙)은 실기기에서 한 번 확인한다. 테스트는 SDK 35 Robolectric이다.
