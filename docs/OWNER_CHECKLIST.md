# 오너 배포 체크리스트

코드는 끝났고(테스트·린트·빌드 통과), 아래는 **오너만 할 수 있는 일**을 순서대로 모은 것이다. 근거와 자세한 설명은 괄호 안의 문서를 본다.
`<…>`는 본인 값으로 바꾼다. 체크 표시는 하지 않았다 — 하나씩 지우며 쓴다.

> 한 줄 요약: **① 구글 로그인 준비 → ② 서버 함수 두 개 배포 → ③ 규칙 배포 → ④ 앱 출시 → ⑤ 옛 데이터 정리.**
> ②와 ③을 거꾸로 하거나 ③만 하면 레이팅이 멈추고, ③과 ④는 같은 때에 한다(규칙을 올리면 옛 앱이 멈춘다).

---

## 0. 먼저 정할 것

| 확인 | 왜 | 근거 |
|---|---|---|
| **스토어에 옛 앱(`com.example.coup`)을 출시한 적이 있는가** | 앱 ID가 `io.github.lhd980820.coup`로 바뀌어 **새 앱으로 취급**된다. 옛 앱은 업데이트로 넘어올 수 없고, 사용자는 새로 설치해야 한다. 계정(uid)과 레이팅은 이어진다(첫 로그인에서 옛 기록을 가져옴). 출시한 적이 없다면 이 항목은 무시한다. | 설계 문서 R3, D1 |
| Firestore 데이터베이스 **위치(리전)** | 함수 배포 때 같은 리전을 써야 한다(Firestore 트리거 조건). 콘솔 > Firestore > 데이터에서 확인. | ADR 0015 |

---

## 1. 구글 로그인 준비 (앱을 기기에서 돌려 보기 전에 꼭)

지금 `app/google-services.json`에는 새 패키지가 **웹 클라이언트만** 있고 Android OAuth 클라이언트(서명 인증서)가 없다. 이대로는 실기기에서 구글 로그인이 "개발자 구성 오류"로 실패한다.

1. 서명 인증서 지문을 구한다.
   - 디버그: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android` 의 **SHA-1, SHA-256**
   - 릴리스: 업로드 키스토어의 SHA-1, SHA-256. Play 앱 서명을 쓰면 Play Console > 앱 무결성에 나오는 **앱 서명 키**의 지문도 추가한다.
2. Firebase 콘솔 > 프로젝트 설정 > 내 앱 > `io.github.lhd980820.coup` 에 위 지문들을 모두 추가한다.
3. `google-services.json`을 **다시 내려받아** `app/`에 덮어쓴다. 웹 클라이언트 ID는 그대로라 기존 구글 사용자의 uid는 바뀌지 않는다.
4. Firebase > Authentication > 로그인 방법에서 **이메일/비밀번호**와 **Google**이 켜져 있는지 확인한다.
5. Authentication > 설정에서 **"이메일당 계정 하나"** 를 바꾸지 않는다. 옛 기록 이어받기 규칙이 이 설정에 기대고 있다(바꾸면 규칙에 `email_verified` 검사를 더해야 한다).

(ADR 0011)

---

## 2. 서버 함수 배포 — 판정 함수와 정리 함수

### 2-1. 준비 (한 번)
1. 프로젝트를 **Blaze(종량제)** 로 바꾼다.
2. 다음 API를 켠다: Cloud Functions, Eventarc, Cloud Build, Artifact Registry, Cloud Run, **Cloud Scheduler**, Pub/Sub.
3. gcloud 로그인과 프로젝트 지정: `gcloud auth login && gcloud config set project <프로젝트 ID>`
4. 함수의 서비스 계정에 Firestore 읽기·쓰기(`roles/datastore.user`)가 있는지 본다(기본 컴퓨트 서비스 계정은 보통 이미 있다).

### 2-2. 빌드
JDK 21이 필요하다. 에뮬레이터를 띄워 두면 통합 테스트도 같이 돈다.
```
cd server && ./gradlew test stageDeploy
```
결과: `server/build/deploy/coup-judge.jar`

### 2-3. 판정 함수 (끝난 판을 다시 돌려 검증하고 레이팅 반영)
```
gcloud functions deploy coup-judge --gen2 --runtime=java21 --region=<Firestore 리전> \
  --source=server/build/deploy --entry-point=io.github.lhd980820.coup.server.JudgeFunction \
  --trigger-event-filters=type=google.cloud.firestore.document.v1.created \
  --trigger-event-filters=database='(default)' \
  --trigger-event-filters-path-pattern=document='results/{gameId}' \
  --retry --memory=512Mi
```

### 2-4. 정리 함수 (5분마다 버려진 판과 방을 정리)
```
gcloud pubsub topics create coup-sweep
gcloud functions deploy coup-sweep --gen2 --runtime=java21 --region=<Firestore 리전> \
  --source=server/build/deploy --entry-point=io.github.lhd980820.coup.server.SweepFunction \
  --trigger-topic=coup-sweep --memory=512Mi --timeout=540s
gcloud scheduler jobs create pubsub coup-sweep --location=<Firestore 리전> \
  --schedule="*/5 * * * *" --topic=coup-sweep --message-body=sweep
```

### 2-5. 기록 정리 (하루 한 번, ADR 0021)
같은 함수가 메시지 본문이 `purge`일 때만 오래된 기록을 지운다: 판정 7일 뒤(위조로 거부된 판은 30일)의 판 문서 전부와 결과, 닫힌 지 7일 지난 방.
전적과 프로필은 남는다. 판정이 없는 판은 지우지 않는다. 한 번에 300판까지(밀린 것은 다음 날).
```
gcloud scheduler jobs create pubsub coup-purge --location=<Firestore 리전> \
  --schedule="23 4 * * *" --time-zone="Asia/Seoul" --topic=coup-sweep --message-body=purge
```
함수 제한 시간은 위의 `--timeout=540s`(기록 정리가 몇 분 걸릴 수 있다). 이미 `coup-sweep`을 배포했다면 새 JAR로 **다시 배포**해야 한다(`purge`를 아는 버전).

(ADR 0015, 0016 · `firebase/README.md` "서버 판정 배포")

---

## 3. Firestore 규칙 배포 (함수 둘 **다음에**)

```
cd firebase && firebase deploy --only firestore:rules --project <프로젝트 ID>
```
- **함수 없이 규칙만 올리면 레이팅이 멈춘다.** 규칙이 클라이언트의 레이팅 쓰기를 막기 때문이다.
- 규칙을 올리는 순간 **옛 앱은 로그인과 전적 기록을 못 한다**(옛 `user/{email}`이 읽기 전용이 되고, 방 쓰기에 서버 시각이 필요해진다). → 새 앱 출시와 **같은 때** 한다.
- 별도 색인(`firestore.indexes.json`)은 필요 없다: 앱의 질의는 모두 필드 하나(정렬 또는 같음)라 자동 색인으로 충분하다.

---

## 4. 앱 출시

릴리스 서명은 **비밀을 받는 틀**이 코드에 들어 있다. 비밀(키스토어 파일, 비밀번호)은 저장소에 없고, 없으면 서명하지 않은 APK가 나온다(테스트·린트·디버그는 영향 없음).

| 할 일 | 메모 |
|---|---|
| 업로드 키스토어 만들기, 서명 정보 넣기 | `keytool -genkeypair -keystore coup-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000` 으로 만든다. 루트의 `keystore.properties.example`을 `keystore.properties`로 복사해 값을 채운다(둘 다 .gitignore라 커밋되지 않는다). CI에서는 환경 변수 `COUP_KEYSTORE_FILE`·`COUP_KEYSTORE_PASSWORD`·`COUP_KEY_ALIAS`·`COUP_KEY_PASSWORD`. 일부만 채우면 빌드가 멈추고 빠진 항목을 알려 준다. **키스토어 파일과 비밀번호는 안전한 곳에 따로 백업**(잃으면 업데이트를 못 낸다). Play 앱 서명 사용을 권장. 그 뒤 `./gradlew :app:assembleRelease` 로 서명된 `app-release.apk`가 나온다. |
| `versionCode` / `versionName` 올리기 | 지금 1 / "1.0". 방 문서가 앱 버전을 기록해 낮은 버전의 입장을 막는 장치가 있으니 업데이트마다 올린다(`app/build.gradle.kts`). |
| 코드 축소(R8) 켤지 결정 | 지금 꺼져 있다(`isMinifyEnabled = false`). 켜면 Firebase·직렬화 규칙을 `proguard-rules.pro`에 더하고 실기기에서 전체 흐름을 다시 확인해야 한다. |
| Play Console 항목 | 개인정보 처리방침 URL, 데이터 보안(이메일·닉네임·게임 기록 수집, Firebase Auth·Analytics), 콘텐츠 등급, 스토어 설명·스크린샷 |
| 새 앱 등록이 필요하면 | §0의 확인 결과에 따라. |

---

### 4-2. App Check (앱 코드는 들어 있다 — 콘솔에서 켜는 일)

앱은 시작할 때 App Check를 설치한다: **릴리스는 Play Integrity, 디버그는 디버그 제공자**(ADR 0020). 설치만으로는 아무것도 막지 않고, 콘솔에서 **적용(Enforce)** 을 켠 서비스만 증명 토큰 없는 요청을 거절한다. 그래서 **측정 → 적용** 순서로 한다.

1. **Play Integrity 등록** — Firebase 콘솔 > App Check > 앱 `io.github.lhd980820.coup` > Play Integrity.
   - 같은 GCP 프로젝트에서 **Play Integrity API**를 사용 설정하고, 프로젝트가 Play Console의 앱과 연결돼 있어야 한다.
   - 앱의 **SHA-256**(앱 서명 키)이 Firebase 앱 설정에 등록돼 있어야 한다(§1과 같은 지문).
2. **디버그 기기 등록** — 디버그 빌드를 처음 실행하면 logcat(태그 `DebugAppCheckProvider`)에
   `Enter this debug secret into the allow list in the Firebase Console for your project: <UUID>` 가 한 번 찍힌다.
   그 값을 콘솔 > App Check > 앱 > 점 세 개 > **디버그 토큰 관리**에 추가한다. 에뮬레이터·개인 테스트 기기마다 한 번씩. (토큰은 비밀이다 — 커밋하지 말 것.)
3. **측정** — 새 앱이 나간 뒤 며칠간 콘솔 > App Check > **API 지표**(Cloud Firestore, Authentication)에서 "확인됨" 비율을 본다. 거의 전부 확인됨이어야 한다.
4. **적용** — 지표가 깨끗하면 Cloud Firestore에 **적용**을 켠다. 이 뒤로는 앱이 아닌 곳(스크립트, 변조된 앱)의 직접 요청이 거절된다.
   Authentication 적용은 Identity Platform으로 올려야 해서 선택이다.
5. **알아 둘 점**
   - 적용 뒤에는 **Play를 거치지 않은 설치(사이드로딩)와 Google Play 서비스가 없는 기기**의 요청이 거절된다(앱이 "연결 오류"처럼 보인다). 테스터는 Play 내부 테스트로 배포하거나 디버그 토큰을 등록한다.
   - 서버 함수(`coup-judge`, `coup-sweep`)는 관리자 권한이라 App Check의 영향을 받지 않는다.
   - Firestore 보안 규칙은 App Check 여부를 볼 수 없다 — 적용은 서비스 단위 스위치다.
   - Play Integrity에는 일일 요청 한도가 있지만, 앱이 토큰을 캐시해(기본 약 1시간) 사용자당 하루 몇 번만 부른다.
   - API 키 제한(GCP 콘솔 > 사용자 인증 정보 > 해당 키 > 애플리케이션 제한: Android 앱, 패키지 + SHA-1)도 함께 건다.

---

## 5. 배포 후 확인 (테스트 계정 둘 이상, 기기 둘 이상)

1. **레이팅 판 한 판**을 끝까지 → 판정 함수 로그에 `game …: verified, ratings applied to […]`, 결과 화면에 레이팅 변화. (함수 로그: `gcloud functions logs read coup-judge --gen2 --region=<리전>`)
2. **정리 함수**: 로그에 `swept … games` 가 5분마다, `purged … ended games` 가 하루 한 번(새벽 4시 23분) 찍히는지 (`coup-sweep`). 처음 며칠은 밀린 판이 정리되며 숫자가 크다.
3. **방장 이어받기**: 게임 중 방장 앱을 강제 종료 → 약 65~75초 뒤 게스트가 이어받아 계속 두는지, 돌아온 옛 방장이 게스트로 이어 가는지. 판이 끝나면 판정이 `VERIFIED`인지.
4. **버려진 판**: 모두 앱을 끄고 10분 이상 → 게임이 `ABANDONED`로 끝나고(레이팅 판이면 방장만 패배), 방이 15분 뒤 닫히는지. 이 상태에서 앱을 켜면 안내 화면이 나오는지.
5. **옛 계정 이관**: 옛 앱을 쓰던 계정(이메일/구글)으로 로그인 → 옛 닉네임·레이팅을 이어받는지.
6. **구글 로그인**이 실기기에서 되는지(§1 완료 후).
7. **App Check**: 릴리스 빌드(Play 설치)에서 콘솔 App Check 지표에 "확인됨" 요청이 잡히는지, 디버그 기기는 토큰 등록 뒤 잡히는지.

---

## 6. 안정화 뒤 정리

| 할 일 | 때 | 메모 |
|---|---|---|
| 옛 컬렉션 삭제: `game_rooms`, `game_playing`, `game_result` | 새 앱 출시 후 | 규칙이 이미 쓰기를 막고 있다. 콘솔에서 삭제. |
| 옛 `user` 컬렉션 삭제 | **옛 사용자 이관 기간이 끝난 뒤** | 첫 로그인에서 이 문서를 읽어 레이팅을 옮긴다. 일찍 지우면 이관이 안 된다. |
| `google-services.json`의 옛 패키지(`com.example.coup`) 항목 정리 | 위 이후 | 콘솔에서 옛 앱을 지우고 파일을 다시 내려받는다. |
| 비용·로그 알림 | 배포 직후 권장 | GCP 예산 알림, 함수 오류 로그 알림(판정 실패는 결과 화면이 "확인 중"에 머무는 모습으로만 드러난다). |

---

## 7. 알아 둘 한계 (코드에 아직 없는 것)

- **App Check는 앱 쪽만 끝났다**: 코드는 들어 있지만(§4-2) 콘솔에서 Play Integrity 등록과 **적용**을 켜기 전까지는 아무것도 막지 않는다.
- **기록 보존 기간**(ADR 0021): 판정 7일 뒤(거부된 판 30일) 판 기록과 결과가 지워진다. 이의 제기·조사는 그 안에 해야 한다. 기간은 `RecordPurger`의 상수 하나씩으로 바꿀 수 있다.
- **방장은 남의 패를 볼 수 있다**(권한자 상태가 방장 기기에 있다). 방장 이어받기 후에는 이어받은 사람도 마찬가지다. 오너가 받아들인 위험(ADR 0015, 0017).
- **앱 링크(딥링크)로 방 코드 공유**는 없다: 도메인 검증과 `assetlinks.json` 배포가 필요한 오너 작업이라 코드 공유만 있다(ADR 0014).
- **실기기에서 직접 봐야 하는 것**: 효과음의 음색·크기, 카드 뒤집기 모양, 5초 이하 경고색, HARD AI의 체감 속도, targetSdk 36의 뒤로 가기·큰 화면 동작.
