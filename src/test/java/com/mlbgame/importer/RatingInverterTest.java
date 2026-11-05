package com.mlbgame.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RatingInverterTest {

    // 결과 3개 (예: 삼진, 볼넷, 홈런) vs 기준 결과, 능력치 2개
    private static final double[] BASE = {Math.log(0.22 / 0.5), Math.log(0.09 / 0.5), Math.log(0.03 / 0.5)};
    private static final double[][] BETA = {{-0.4, 0.0}, {0.1, 0.3}, {0.0, 0.45}};

    @Test
    void 표본이_크면_기록을_만든_능력치를_복원한다() {
        double[] truth = {1.2, -0.7};
        double[] expected = expectedCounts(truth, 1_000_000);
        double[] z = RatingInverter.map(java.util.Arrays.copyOf(expected, 3), expected[3], BASE, BETA,
                new double[] {0, 0}, 1.0);
        assertThat(z[0]).isCloseTo(truth[0], within(0.01));
        assertThat(z[1]).isCloseTo(truth[1], within(0.01));
    }

    @Test
    void 표본이_없으면_사전평균() {
        double[] z = RatingInverter.map(new double[3], 0, BASE, BETA, new double[] {-1.0, -0.5}, 1.0);
        assertThat(z[0]).isCloseTo(-1.0, within(1e-9));
        assertThat(z[1]).isCloseTo(-0.5, within(1e-9));
    }

    @Test
    void 표본이_적으면_사전평균_쪽으로_당겨진다() {
        double[] truth = {2.0, 0};
        double[] big = expectedCounts(truth, 100_000);
        double[] small = expectedCounts(truth, 50);
        double[] zBig = RatingInverter.map(java.util.Arrays.copyOf(big, 3), big[3], BASE, BETA, new double[2], 1.0);
        double[] zSmall = RatingInverter.map(java.util.Arrays.copyOf(small, 3), small[3], BASE, BETA,
                new double[2], 1.0);
        assertThat(zSmall[0]).isBetween(0.0, zBig[0]);
    }

    /** {결과1, 결과2, 결과3, 기준} 기대 횟수 */
    private static double[] expectedCounts(double[] z, double n) {
        double[] w = new double[4];
        double sum = 1;
        for (int i = 0; i < 3; i++) {
            w[i] = Math.exp(BASE[i] + BETA[i][0] * z[0] + BETA[i][1] * z[1]);
            sum += w[i];
        }
        w[3] = 1;
        for (int i = 0; i < 4; i++) {
            w[i] = w[i] / sum * n;
        }
        return w;
    }
}
