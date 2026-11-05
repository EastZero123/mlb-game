package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class EnvFeedbackTest {

    private static final EnvFeedback.Rates REAL = new EnvFeedback.Rates(0.089, 0.221, 0.030, 0.288);

    private static EnvFeedback.Rates sim(double bb, double k, double hr, double babip) {
        return new EnvFeedback.Rates(bb, k, hr, babip);
    }

    @Test
    void 첫_오프시즌은_초기값에서_시작하고_결과가_실제와_같으면_그대로() {
        double[] b = EnvFeedback.next(null, REAL, REAL);
        assertThat(b).containsExactly(EnvFeedback.PRIOR);
    }

    @Test
    void 볼넷이_많으면_제구_홈런이_많으면_장타억제를_올리고_삼진이_많으면_구위_BABIP_가_낮으면_수비를_내린다() {
        double[] zero = new double[EnvFeedback.SIZE];
        double[] b = EnvFeedback.next(zero, sim(0.092, 0.224, 0.0308, 0.282), REAL);
        assertThat(b[EnvFeedback.CONTROL]).isPositive();
        assertThat(b[EnvFeedback.SUPPRESSION]).isPositive();
        assertThat(b[EnvFeedback.STUFF]).isNegative();
        assertThat(b[EnvFeedback.DEFENSE]).isNegative();
        assertThat(b[EnvFeedback.CONTROL]).isCloseTo(0.7 * 0.003 / EnvFeedback.SENS_BB, within(1e-9));
    }

    @Test
    void 한_해_변화와_누적에_상한이_있다() {
        double[] b = EnvFeedback.next(new double[] {5.5, 0, 0, 0}, sim(0.120, 0.221, 0.030, 0.288), REAL);
        assertThat(b[EnvFeedback.CONTROL]).isEqualTo(EnvFeedback.LIMIT); // +2 단계 상한, 누적 6 상한
        double[] c = EnvFeedback.next(new double[EnvFeedback.SIZE], sim(0.120, 0.221, 0.030, 0.288), REAL);
        assertThat(c[EnvFeedback.CONTROL]).isEqualTo(EnvFeedback.STEP);
    }

    @Test
    void 볼넷_오차가_해마다_줄어든다() {
        // 단순 모형: 볼넷% = 실제 + 감도 × (구조적 하락 3 - 보정치)
        double[] bias = new double[EnvFeedback.SIZE];
        double prevErr = Double.MAX_VALUE;
        for (int y = 0; y < 5; y++) {
            double bb = REAL.bb() + EnvFeedback.SENS_BB * (3.0 - bias[EnvFeedback.CONTROL]);
            double err = Math.abs(bb - REAL.bb());
            assertThat(err).isLessThanOrEqualTo(prevErr);
            prevErr = err;
            bias = EnvFeedback.next(bias, sim(bb, REAL.k(), REAL.hr(), REAL.babip()), REAL);
        }
        assertThat(prevErr).isLessThan(0.0005);
    }

    @Test
    void BABIP_근사는_인플레이_타구_기준() {
        // 타석 100, 볼넷 10, 삼진 20, 홈런 3, 안타 25, 사구 2 → (25-3) / (100-10-2-20-3) = 22/65
        EnvFeedback.Rates r = EnvFeedback.Rates.of(100, 10, 20, 3, 25, 2);
        assertThat(r.babip()).isCloseTo(22.0 / 65, within(1e-9));
        assertThat(EnvFeedback.Rates.of(0, 0, 0, 0, 0, 0)).isNull();
    }

    @Test
    void 저장_문자열_왕복_수비_이전_3칸도_읽는다() {
        double[] b = {2.5, -0.25, 1.0, -0.5};
        assertThat(EnvFeedback.decode(EnvFeedback.encode(b))).containsExactly(b);
        assertThat(EnvFeedback.decode("2.500,0.500,1.000")).containsExactly(2.5, 0.5, 1.0, 0.0);
        assertThat(EnvFeedback.decode("x")).isNull();
        assertThat(EnvFeedback.decode(null)).isNull();
    }
}
