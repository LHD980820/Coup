# ADR 0020: Firebase App Check

## 상태
채택 (2026-10). 오너 요청("App Check 진행하자"). 앱 쪽 구현·테스트 완료, **콘솔 등록과 적용은 오너 작업**(`OWNER_CHECKLIST.md` §4-2).

## 맥락
- `google-services.json`의 API 키는 공개 설정이라 앱 밖(스크립트, 변조된 앱)에서도 Firestore·Auth에 요청을 보낼 수 있다. 보안 규칙이 막는 것은 권한이지 "정상 앱에서 왔는가"가 아니다.
- 설계 문서(T25)는 App Check(Play Integrity)와 API 키 제한을 권장했다.

## 결정
1. **설치**: `CoupApplication.onCreate`가 `AppCheckSetup.install(BuildAppCheck.factory())`를 호출한다. Firebase 초기화(자동) 뒤, 어떤 요청보다 먼저다. 설치하면 Firestore·Auth SDK가 요청에 증명 토큰을 자동으로 붙인다.
2. **증명 방식은 빌드가 정한다**: 릴리스 `PlayIntegrityAppCheckProviderFactory`, 디버그 `DebugAppCheckProviderFactory`.
   `BuildAppCheck`를 `src/debug`와 `src/release`에 각각 두고 의존성도 `debugImplementation`/`releaseImplementation`으로 나눠, **디버그 제공자(콘솔에 등록한 비밀 토큰만 통과)가 릴리스 APK에 들어가지 않는다.**
3. **실패해도 앱은 멈추지 않는다**: 설치가 예외를 던지면 기록만 하고 계속한다(토큰 없이 요청이 나간다). 적용을 켠 서비스만 그 요청을 거절한다. `Exception`만 잡고 `Error`(메모리 부족 등)는 그대로 둔다.
4. **적용은 콘솔 스위치**다. 코드는 측정(지표)만 가능하게 해 두고, 오너가 지표를 보고 서비스별로 켠다. 규칙은 App Check 여부를 보지 못한다.

## 다른 선택지
- **SafetyNet**: 지원 종료. **App Attest/DeviceCheck**: iOS용.
- **커스텀 제공자(자체 서버 발급)**: 서버가 필요하고 얻는 것이 없다.
- **적용을 코드로 강제**(토큰 없으면 앱이 요청을 보내지 않음): 서버 측 방어가 아니라서 의미가 없다.

## 검증
- 단위 테스트 10: 증명 방식을 대상에 정확히 한 번 설치, 실패해도 던지지 않고 알림(그리고 `Error`는 삼키지 않음), 진짜 설치 경로가 제공자를 실제로 만들게 함(Firebase가 없는 프로세스에서는 실패를 삼킴), 디버그 빌드는 디버그 제공자,
  매니페스트의 앱 클래스 연결, **디버그 제공자는 `debugImplementation`에만·Play Integrity는 `releaseImplementation`에만, release/main 소스는 디버그 제공자를 참조하지 않음**(구성 검사).
- 변이 11종 중 10종 검출, 1종은 컴파일 오류(디버그에 없는 클래스).
- 만들어진 APK의 dex를 직접 확인: 릴리스에는 `DebugAppCheckProviderFactory`가 0건·`PlayIntegrityAppCheckProviderFactory`가 있고, 디버그는 그 반대.
- 전체 `test lint assembleDebug assembleRelease` 통과(app 187, data 19). 실제 증명(Play Integrity)과 적용 효과는 기기와 콘솔이 필요해 자동으로 확인하지 못한다.

## 한계
- 적용 뒤에는 Play를 거치지 않은 설치와 Google Play 서비스가 없는 기기가 거절된다.
- 토큰 요청이 실패한 사용자는 "연결 오류"로 보인다 — 전용 안내 화면은 없다(지표를 보며 필요하면 후속).
