package com.mlbgame.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScoutViewTest {

    private static int[] parse(String range) {
        String[] p = range.split("–");
        int lo = Integer.parseInt(p[0]);
        return new int[] {lo, p.length > 1 ? Integer.parseInt(p[1]) : lo};
    }

    @Test
    void 범위는_항상_실제값을_포함하고_폭은_스카우트_단계만큼() {
        for (int level = 1; level <= 5; level++) {
            int w = ScoutView.width(level, false);
            for (int id = 1; id <= 500; id++) {
                int[] r = parse(ScoutView.range(55, w, 42, id, 0));
                assertThat(r[0]).isLessThanOrEqualTo(55);
                assertThat(r[1]).isGreaterThanOrEqualTo(55);
                assertThat(r[1] - r[0]).isLessThanOrEqualTo(2 * w + 1);
            }
        }
    }

    @Test
    void 같은_선수_같은_항목은_새로고침해도_같다() {
        assertThat(ScoutView.range(60, 9, 7, 123, 3)).isEqualTo(ScoutView.range(60, 9, 7, 123, 3));
        assertThat(ScoutView.range(60, 9, 7, 123, 3)).isNotEqualTo(ScoutView.range(60, 9, 8, 123, 3));
    }

    @Test
    void 자기_팀은_한_단계_정확하고_값이_없으면_대시() {
        assertThat(ScoutView.width(1, true)).isEqualTo(ScoutView.width(2, false));
        assertThat(ScoutView.width(5, true)).isEqualTo(2);
        assertThat(ScoutView.range(-1, 6, 1, 1, 1)).isEqualTo("-");
    }
}
