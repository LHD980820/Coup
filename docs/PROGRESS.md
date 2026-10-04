# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | 🚧 | 아래 세부 단계 참고. 위치: `core/engine` (ADR 0001) |
| 2~7 | ⬜ | |

### Phase 1 세부 단계 (설계 §13)

| # | 단계 | 상태 |
|---|---|---|
| 1 | 식별자/카드/플레이어/RNG + 직렬화 | ✅ |
| 2 | RuleSet 정의 타입 + Classic + 검증기 | ✅ |
| 3 | GameState / Phase / ResolutionStep + newGame (+ RuleSetConfig/Registry) | ✅ |
| 4 | legalOptions(ChooseAction) + DeclareAction + 응답 없는 행동 | ⬜ ← 다음 |
| 5 | 응답 창 + Pass + 효과 해결 + EndTurn | ⬜ |
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ⬜ 🧠 Opus 권장 |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ⬜ 🧠 Opus 권장 |
| 8 | 교환 | ⬜ |
| 9 | 탈락 / 게임 종료 / 기권 | ⬜ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ⬜ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ⬜ |

## 다음 할 일
1. 4단계: `Command`/`ApplyResult`/`Rejection`, `DecisionRequest.ChooseAction`, `DeclareAction` 처리(비용 지불, 강제 쿠, 대상 검증), 수입/쿠 해결 경로.
2. 테스트 픽스처 `ScenarioBuilder`(설계 §12.2) 도입 — 손패/덱 순서를 지정해 상태 생성.
3. CI에 `core` 테스트 단계 추가.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, 엔진 3단계 완료_
