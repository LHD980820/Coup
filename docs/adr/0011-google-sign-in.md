# ADR 0011: 구글 로그인 (Credential Manager -> Firebase Auth)

## 상태
채택 (2026-10). 코어 모델·Firebase 어댑터(Auth 에뮬레이터)·화면(Robolectric)까지 검증했다. **실기기 확인 전 오너 작업이 하나 필요하다**(아래 "배포 전 필요").

## 맥락
- 새 로그인 화면(ADR 0010)은 이메일/비밀번호만 있었다. 기존 앱에는 구글 로그인 사용자가 있고, 이들은 같은 Firebase 프로젝트의 **구글 제공자 계정(uid)** 을 이미 가지고 있다.
- 기존 앱은 지원이 끝난 `GoogleSignIn`/`BeginSignInRequest`를 쓴다. 현재 권장 경로는 Credential Manager + `googleid`의 `GetSignInWithGoogleOption`이다.

## 결정
1. **코어(`:shell`)**: `AuthGateway.signInWithGoogle(idToken)`, `GoogleToken`(Ok / Cancelled / NoAccount / Unavailable / Failed), `GoogleTokenSource`(플랫폼이 구현),
   `AuthFormModel.signInWithGoogle(source)`. 사용자가 선택 창을 닫으면 **오류를 보이지 않는다**. 계정 없음·사용 불가·서버 거절은 이유를 보인다. 진행 중에는 다른 제출을 받지 않고, 예외가 나도 잠금이 풀린다.
   새 실패 사유: `CREDENTIAL_CONFLICT`(같은 이메일이 다른 방법으로 가입됨), `GOOGLE_NO_ACCOUNT`, `GOOGLE_UNAVAILABLE`.
2. **어댑터(`:data`)**: `GoogleAuthProvider.getCredential(idToken)` -> `signInWithCredential`. 구글 로그인에서의 충돌 예외는 `CREDENTIAL_CONFLICT`로 바꾼다(가입의 `EMAIL_IN_USE`와 구분).
3. **앱**: `CredentialManagerGoogleTokenSource(activity, default_web_client_id)`. 웹 클라이언트 ID는 기존 앱과 같은 값(google-services 플러그인 리소스)이라
   **기존 구글 사용자는 같은 uid로 들어오고**, 프로필이 없으면 `AccountSession`이 옛 `user/{email}` 기록을 이어받는다. 결과·오류 -> `GoogleToken` 변환은 `GoogleTokens`로 떼어 기기 없이 시험한다.
   버튼("Google 계정으로 계속하기")은 로그인·가입 모드에서만 보인다(처음이면 계정이 생기므로 같은 버튼). 구글을 쓸 수 없는 구성(소스 없음)에서는 숨긴다.

## 검증
- 코어 `AuthFormModelTest` +5(토큰으로 로그인, 닫으면 조용히, 사유별 안내, 진행 중 잠금, 예외 후 잠금 해제).
- Auth 에뮬레이터 4개(에뮬레이터는 서명 없는 JSON ID 토큰을 받는다): 처음 로그인 -> 계정 생성, 같은 구글 계정은 같은 uid, 다른 구글 계정은 다른 uid, 깨진 토큰 거절,
  **같은 이메일의 비밀번호 계정이 있어도 계정이 둘로 갈라지지 않음**(에뮬레이터는 같은 uid로 이었다. 실제 프로젝트에서 충돌로 나오면 `CREDENTIAL_CONFLICT`로 안내),
  **기존 앱 사용자 -> 구글 로그인 -> 옛 닉네임·레이팅 이어받기**(실제 규칙 통과).
- 앱 9개: 자격 증명 꺼내기(종류가 다르면 거절), 오류 종류별 변환, 버튼 노출 조건, 로그인 -> 로비, 닫으면 그대로, 계정 없음 안내, 진행 중 버튼 잠금. 변이 4종 모두 검출.

## 배포 전 필요 (오너)
- **`google-services.json`의 새 패키지 `io.github.lhd980820.coup`에는 Android OAuth 클라이언트(서명 인증서 SHA)가 없다.** 옛 `com.example.coup`에만 있다.
  이 상태로는 실기기에서 구글 로그인이 "개발자 구성 오류"로 실패한다(새 화면은 실패 안내, 기존 화면도 같은 패키지라 실패).
  -> Firebase 콘솔 > 프로젝트 설정 > Android 앱 `io.github.lhd980820.coup`에 **디버그 키스토어와 릴리스(또는 Play 앱 서명) 인증서의 SHA-1·SHA-256**을 추가하고,
  `google-services.json`을 다시 받아 `app/`에 넣는다. 웹 클라이언트 ID는 그대로이므로 기존 구글 사용자의 uid는 바뀌지 않는다.
- Firebase Auth에서 구글 제공자가 켜져 있어야 한다(기존 앱이 쓰고 있으므로 켜져 있을 것).

## 보안 메모
- 옛 기록 이어받기 규칙은 토큰의 이메일만 본다(`email_verified`는 보지 않는다). 안전한 이유는 Firebase Auth의 **"이메일당 계정 하나"** 설정이다 —
  남의 이메일로 비밀번호 계정을 새로 만들 수 없다(이미 그 이메일의 계정이 있으므로). 이 설정을 바꾸면 이어받기 규칙에 `email_verified`를 더해야 한다.
- 구글은 자기 이메일에 대해 신뢰할 수 있는 제공자라, 같은 이메일의 **미인증** 비밀번호 계정이 있으면 Firebase가 구글 로그인으로 그 계정을 가져간다(비밀번호는 사라질 수 있다). uid는 유지된다.
