# ADR 0001: 순수 Kotlin 모듈을 독립 Gradle 빌드(`core/`)로 분리

- 상태: 채택 (2026-10-04)
- 관련: 설계 문서 §3.1

## 배경
설계 문서는 `:engine :ai :runtime :data :app`을 하나의 Gradle 빌드로 두는 것을 전제로 했다.
그러나 클라우드 작업 환경에서는 Google Maven(`dl.google.com`)이 차단되어 AGP가 해석되지 않으므로,
루트 빌드는 구성(configuration) 단계부터 실패한다. 엔진 작업을 클라우드에서 검증하려면 Android 의존 없이 빌드되어야 한다.

## 결정
`:engine`, `:ai`, `:runtime`은 `core/` 아래 독립 Gradle 빌드(`coup-core`)로 둔다.
- 그룹: `io.github.lhd980820.coup`, 자체 버전 카탈로그 `core/gradle/libs.versions.toml`.
- 실행: `cd core && ./gradlew test`.
- Android 쪽(`:app`, `:data`)은 연결 시점에 루트 `settings.gradle.kts`에서 `includeBuild("core")` 하고 좌표(`io.github.lhd980820.coup:engine`)로 의존한다. Gradle 복합 빌드의 의존성 치환이 자동 적용된다.

## 결과
- 클라우드/CI 어디서든 Android SDK 없이 엔진 테스트 가능.
- 두 빌드의 Kotlin 버전은 맞춰 관리해야 한다(현재 둘 다 2.4.x).
- CI 워크플로에 `core` 테스트 단계를 추가해야 한다(Phase 2 이전).
