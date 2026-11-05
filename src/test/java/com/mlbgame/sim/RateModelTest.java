package com.mlbgame.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RateModelTest {

    private final RateModel model = SimFixtures.MODEL;
    private final double[] league = SimFixtures.LEAGUE.rates();

    @Test
    void 평균_타자는_리그_평균과_같다() {
        SimBatter b = model.batter(1, new BatterRatings(50, 50, 50, 50, 50, 50, 50), Hand.RIGHT, 1.0);
        for (int i = 0; i < Outcomes.COUNT; i++) {
            assertThat(b.rates()[i]).isCloseTo(league[i], within(1e-12));
        }
    }

    @Test
    void 평균_투수의_비율은_모두_1이다() {
        SimPitcher p = model.pitcher(1, new PitcherRatings(50, 50, 50, 50, 50), Hand.RIGHT, 1.0);
        for (int i = 0; i < Outcomes.COUNT; i++) {
            assertThat(p.ratios()[i]).isCloseTo(1.0, within(1e-12));
        }
    }

    @Test
    void 효과_배율이_작으면_리그_평균에_가까워진다() {
        BatterRatings slugger = new BatterRatings(50, 75, 50, 50, 50, 50, 50);
        RateModel half = new RateModel(SimFixtures.LEAGUE.withSpread(new Spread(1, 1, 0.5, 1), Spread.ONE));
        double full = model.batter(1, slugger, Hand.RIGHT, 1.0).rates()[Outcomes.HOME_RUN];
        double damped = half.batter(1, slugger, Hand.RIGHT, 1.0).rates()[Outcomes.HOME_RUN];
        assertThat(damped).isLessThan(full).isGreaterThan(league[Outcomes.HOME_RUN]);
    }

    @Test
    void 확률_합은_1이다() {
        SimBatter b = model.batter(1, new BatterRatings(80, 20, 65, 35, 70, 80, 20), Hand.LEFT, 1.0);
        double sum = 0;
        for (double v : b.rates()) {
            sum += v;
        }
        assertThat(sum).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void 능력치_방향이_맞다() {
        BatterRatings avg = new BatterRatings(50, 50, 50, 50, 50, 50, 50);
        SimBatter base = model.batter(1, avg, Hand.RIGHT, 1.0);
        SimBatter contact = model.batter(2, new BatterRatings(70, 50, 50, 50, 50, 50, 50), Hand.RIGHT, 1.0);
        SimBatter power = model.batter(3, new BatterRatings(50, 70, 50, 50, 50, 50, 50), Hand.RIGHT, 1.0);
        SimBatter eye = model.batter(4, new BatterRatings(50, 50, 70, 50, 50, 50, 50), Hand.RIGHT, 1.0);

        assertThat(contact.rates()[Outcomes.STRIKEOUT]).isLessThan(base.rates()[Outcomes.STRIKEOUT]);
        assertThat(power.rates()[Outcomes.HOME_RUN]).isGreaterThan(base.rates()[Outcomes.HOME_RUN]);
        assertThat(eye.rates()[Outcomes.WALK]).isGreaterThan(base.rates()[Outcomes.WALK]);
    }

    @Test
    void 발휘율은_범위로_제한된다() {
        SimBatter b = model.batter(1, new BatterRatings(50, 50, 50, 50, 50, 50, 50), Hand.RIGHT, 2.0);
        assertThat(b.performance()).isEqualTo(Performance.MAX);
        assertThat(b.withPerformance(0.1).performance()).isEqualTo(Performance.MIN);
    }
}
