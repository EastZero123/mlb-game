package com.mlbgame.career;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class RatingSamplerTest {

    private static final int SEASON = 2026;

    @Test
    void 같은_seed와_선수면_같은_값() {
        MasterPlayer p = CareerFixtures.player(1, 6, 2, 0, 0, 600, 5, 55);
        short[] a = RatingSampler.sample(p, SEASON, RatingSampler.rng(42, p.id()));
        short[] b = RatingSampler.sample(p, SEASON, RatingSampler.rng(42, p.id()));
        short[] c = RatingSampler.sample(p, SEASON, RatingSampler.rng(43, p.id()));
        assertThat(a).isEqualTo(b);
        assertThat(Arrays.equals(a, c)).isFalse();
    }

    @Test
    void 범위는_마스터_값_기준_2시그마_이내() {
        MasterPlayer p = CareerFixtures.player(7, 6, 2, 0, 2, 0, 0, 50); // 무작위 출처 = 최대 σ
        double maxSigma = RatingSampler.BASE_SIGMA * 1.6;
        for (long seed = 0; seed < 2_000; seed++) {
            short[] r = RatingSampler.sample(p, SEASON, RatingSampler.rng(seed, p.id()));
            for (short v : r) {
                assertThat((double) Math.abs(v - 50)).isLessThanOrEqualTo(2 * maxSigma + 0.5);
            }
        }
    }

    @Test
    void 평균은_마스터_값() {
        MasterPlayer p = CareerFixtures.player(3, 6, 2, 0, 0, 600, 5, 60);
        double sum = 0;
        int n = 20_000;
        for (long seed = 0; seed < n; seed++) {
            sum += RatingSampler.sample(p, SEASON, RatingSampler.rng(seed, p.id()))[MasterData.CONTACT];
        }
        assertThat(sum / n).isCloseTo(60, within(0.1));
    }

    @Test
    void 구사율과_NULL은_그대로() {
        MasterPlayer p = CareerFixtures.player(4, 1, 2, 0, 0, 600, 5, 30);
        p.ratings()[MasterData.CONTACT] = -1;
        short[] r = RatingSampler.sample(p, SEASON, RatingSampler.rng(9, p.id()));
        assertThat(r[MasterData.CONTACT]).isEqualTo((short) -1);
        assertThat(r[MasterData.FIRST_PITCH]).isEqualTo((short) 30); // ff_use
    }

    @Test
    void 표본이_적거나_젊을수록_변동이_크다() {
        assertThat(RatingSampler.sampleMultiplier(CareerFixtures.player(1, 6, 2, 0, 0, 0, 0, 50)))
                .isGreaterThan(RatingSampler.sampleMultiplier(CareerFixtures.player(1, 6, 2, 0, 0, 600, 0, 50)));
        assertThat(RatingSampler.ageMultiplier(21)).isGreaterThan(RatingSampler.ageMultiplier(28));
        assertThat(RatingSampler.ageMultiplier(28)).isGreaterThan(RatingSampler.ageMultiplier(36));
    }
}
