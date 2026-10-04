# 진행 로그

설계: [REDESIGN_ARCHITECTURE.md](REDESIGN_ARCHITECTURE.md) · 결정 기록: [adr/](adr/)

## 현재 상태

| Phase | 상태 | 비고 |
|---|---|---|
| 0. 빌드 기반 | ✅ | 오너가 로컬에서 진행. Gradle 9.8 / AGP 9.4 / Kotlin 2.4 / Firebase BOM 34.x, applicationId `io.github.lhd980820.coup`, CI(`./gradlew test lint`) 녹색 |
| 1. 엔진 | ✅ | `core/engine`. 207개 테스트, 라인 커버리지 97.9% (ADR 0001, 0003) |
| 2. 런타임 + AI(EASY/NORMAL) | ⬜ ← 다음 | `core/runtime`, `core/ai` 모듈 추가 |
| 3~7 | ⬜ | |

### Phase 1 세부 단계 (설계 §13)

| # | 단계 | 상태 |
|---|---|---|
| 1 | 식별자/카드/플레이어/RNG + 직렬화 | ✅ |
| 2 | RuleSet 정의 타입 + Classic + 검증기 | ✅ |
| 3 | GameState / Phase / ResolutionStep + newGame (+ RuleSetConfig/Registry) | ✅ |
| 4 | legalOptions(ChooseAction) + DeclareAction + 응답 없는 행동 (+ 영향력 상실 선택, 탈락/게임 종료 기본 판정) | ✅ |
| 5 | 응답 창 + Pass + 효과 해결 + EndTurn | ✅ |
| 6 | 도전 / 공개 / 영향력 상실 / 카드 교체 | ✅ |
| 7 | 막기 / 막기 도전 / BLOCK_ONLY | ✅ |
| 8 | 교환 | ✅ |
| 9 | 탈락 / 게임 종료 / 기권 | ✅ |
| 10 | 뷰·이벤트 투영, timeoutCommand, determinize | ✅ |
| 11 | 하우스룰, 속성 기반 테스트, 골든 파일 | ✅ |

## 다음 할 일 (Phase 2)
설계 §6, §7, §13. 구현 전에 `docs/adr/0003-phase1-deviations.md`를 읽을 것.
1. `core/settings.gradle.kts`에 `:runtime`, `:ai` 추가(Kotlin JVM, `:runtime`은 coroutines). `:ai`는 `:engine`에만 의존하고 `GameState` 심볼을 쓰지 않는다(소스 스캔 테스트로 강제, 설계 §7.2).
2. `:runtime`: `GameAuthority`(Mutex로 명령 직렬화, 결정권자 좌석 구동, 타임아웃 D4: 응답 15초/카드 선택 20초/행동 30초, 가상 `Clock` 주입), `SeatController`(`LocalHumanSeat`/`AiSeat`/`RemoteSeat`), `GameSession`(`LocalGameSession`부터), `InMemoryTransport`.
3. `:ai` EASY/NORMAL: `AiAgent`, `CardCounter`, `ClaimHistory`, 정책 5종(§7.4). 성능 평가 하네스(`@Tag("slow")`).
4. 필수 테스트: AI 합법성(1만 결정 거절 0), **뷰 동치 테스트**(히든 정보만 다른 두 상태에서 같은 seed의 AI는 같은 명령), 타임아웃 기본 수, AI 5명 완주(가상 시간).

## 이번 단계 메모 (11단계)
- `RuleSetBuilder`(addRole/replaceRole/removeRole/addAction/modifyAction/removeAction/updateParams), `HouseRule`, `RuleSetRegistry.registerHouseRule/availableHouseRules`. 적용 순서: 기반 → 하우스룰(ID 정렬) → 파라미터 오버라이드 → 검증.
- 내장 하우스룰: `no_steal_from_broke`(기존 앱 동작 재현), `last_stand`. 파라미터 오버라이드(`forcedActionThreshold` 등)는 하우스룰 없이 동작.
- 테스트 전용 `TestRules`: 가상 역할 "은행가" 하우스룰로 **엔진 코어 무수정 확장이 끝까지 플레이됨**을 지속 검증. 테스트 엔진(`testEngine`)은 이 레지스트리를 쓴다.
- 속성 기반 테스트(`PropertyTest`): 6개 룰 조합 x 2~6인 x 60판 불변식, 리플레이 결정성, 중간 백업/복원 후 이어하기, 불법 명령 거절.
- 골든 파일(`core/engine/src/test/resources/golden/v1/`): `game_state.json`, `player_view.json`, `commands.json`. 직렬화 형식이 바뀌어 깨지면 `ENGINE_SCHEMA_VERSION`을 올리고 이전 골든을 유지하며 마이그레이션 추가. 의도적 재생성: `UPDATE_GOLDEN=1`.
- Kover: `cd core && ./gradlew test koverVerify` (라인 90% 게이트, CI 포함). 리포트: `core/engine/build/reports/kover/`. 현재 라인 97.9%, 분기 64.2%.

## 알려진 사항
- 클라우드 샌드박스는 기본 로케일이 UTF-8이 아니라 한글 테스트명 컴파일이 실패한다 → `LC_ALL=C.UTF-8`로 실행. (CI/Windows는 영향 없음)
- 클라우드에서는 `dl.google.com` 차단으로 `:app` 빌드 불가(ADR 0001).

## 실행
```
cd core && ./gradlew test
```

_마지막 갱신: 2026-10-04, Phase 1(엔진) 완료_
