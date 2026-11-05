package com.mlbgame.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class CoefficientFitterTest {

    @Test
    void 알려진_계수를_복원한다() {
        double[] truth = {-0.8, 0.30, -0.15};
        SplittableRandom rng = new SplittableRandom(7);
        List<CoefficientFitter.Obs> data = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            double[] z = {rng.nextDouble(-2, 2), rng.nextDouble(-2, 2)};
            double p = 1 / (1 + Math.exp(-(truth[0] + truth[1] * z[0] + truth[2] * z[1])));
            int n = 400;
            int s = 0;
            for (int t = 0; t < n; t++) {
                if (rng.nextDouble() < p) {
                    s++;
                }
            }
            data.add(new CoefficientFitter.Obs(s, n - s, z));
        }
        double[] fit = CoefficientFitter.fit(data, 2, 0);
        assertThat(fit[0]).isCloseTo(truth[0], within(0.02));
        assertThat(fit[1]).isCloseTo(truth[1], within(0.02));
        assertThat(fit[2]).isCloseTo(truth[2], within(0.02));
    }

    @Test
    void 연립방정식() {
        double[] x = CoefficientFitter.solve(new double[][] {{2, 1}, {1, 3}}, new double[] {3, 5});
        assertThat(x[0]).isCloseTo(0.8, within(1e-12));
        assertThat(x[1]).isCloseTo(1.4, within(1e-12));
    }
}
