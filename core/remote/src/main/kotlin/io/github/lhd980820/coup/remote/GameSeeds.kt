package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.engine.core.GameSetup

/**
 * 실제 게임 시드 = 방장이 고른 시드 + 게임 문서의 **서버 생성 시각**(밀리초)을 섞은 값(ADR 0016).
 * 방장은 게임 문서가 서버에 닿는 밀리초를 미리 알 수 없으므로, 시드를 미리 돌려 보고 좋은 패가 나오는 것을 고를 수 없다.
 * 서버 판정도 같은 방식으로 섞어 다시 돌리므로, 섞지 않은 시드로 둔 판은 기록이 맞지 않아 무효가 된다.
 */
public object GameSeeds {
    public fun mix(hostSeed: Long, createdAtMillis: Long): Long {
        // SplitMix64 마무리 단계: 입력의 작은 차이(1ms)도 시드 전체를 바꾼다
        var z = hostSeed xor (createdAtMillis * GOLDEN)
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** 문서에 적힌 실행 설정(방장 시드)으로 실제 게임을 만들 설정. */
    public fun effective(setup: GameSetup, createdAtMillis: Long): GameSetup = setup.copy(seed = mix(setup.seed, createdAtMillis))

    private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val MIX1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    private const val MIX2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
}
