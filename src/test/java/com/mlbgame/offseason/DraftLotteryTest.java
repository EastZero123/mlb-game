package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DraftLotteryTest {

    /** 구단 t 는 승 50 + t → 1번이 최저 성적, 30번이 최고. 19~30 이 포스트시즌 진출 */
    private static Map<Integer, int[]> standings() {
        Map<Integer, int[]> s = new HashMap<>();
        for (int t = 1; t <= 30; t++) {
            s.put(t, new int[] {50 + t, 112 - t, 600, 600});
        }
        return s;
    }

    private static Set<Integer> playoff() {
        Set<Integer> p = new HashSet<>();
        for (int t = 19; t <= 30; t++) {
            p.add(t);
        }
        return p;
    }

    @Test
    void 열_라운드_삼백_장_구단당_열_장() {
        List<DraftLottery.Pick> picks = DraftLottery.order(standings(), playoff(), 42, 2026, 10);
        assertThat(picks).hasSize(300);
        for (int t = 1; t <= 30; t++) {
            int team = t;
            assertThat(picks.stream().filter(p -> p.teamId() == team)).hasSize(10);
        }
        for (int i = 0; i < 300; i++) {
            assertThat(picks.get(i).pickNo()).isEqualTo(i + 1);
            assertThat(picks.get(i).round()).isEqualTo(i / 30 + 1);
        }
        // 2라운드부터는 성적 역순 그대로
        assertThat(picks.get(30).teamId()).isEqualTo(1);
        assertThat(picks.get(59).teamId()).isEqualTo(30);
    }

    @Test
    void 포스트시즌_진출_구단은_추첨_없이_1라운드_뒤쪽() {
        List<DraftLottery.Pick> round1 = DraftLottery.order(standings(), playoff(), 7, 2026, 10).subList(0, 30);
        for (int i = 0; i < 18; i++) {
            assertThat(round1.get(i).teamId()).isLessThanOrEqualTo(18);
            assertThat(round1.get(i).preRank()).isEqualTo(round1.get(i).teamId());
        }
        for (int i = 18; i < 30; i++) {
            assertThat(round1.get(i).teamId()).isEqualTo(i + 1);
            assertThat(round1.get(i).preRank()).isZero();
        }
        // 추첨에서 안 뽑힌 구단은 성적 역순
        for (int i = 7; i < 18; i++) {
            assertThat(round1.get(i).teamId()).isGreaterThan(round1.get(i - 1).teamId());
        }
    }

    @Test
    void 같은_커리어_같은_시즌이면_같은_결과() {
        assertThat(DraftLottery.order(standings(), playoff(), 9, 2027, 10))
                .isEqualTo(DraftLottery.order(standings(), playoff(), 9, 2027, 10));
    }

    @Test
    void 확률은_성적_역순_가중치를_따른다() {
        int trials = 4000;
        int worstFirst = 0;
        int eighteenthInTopSix = 0;
        int worstWorst = 0;
        for (long seed = 1; seed <= trials; seed++) {
            List<DraftLottery.Pick> r1 = DraftLottery.order(standings(), playoff(), seed, 2026, 1);
            if (r1.getFirst().teamId() == 1) {
                worstFirst++;
            }
            for (int i = 0; i < 6; i++) {
                if (r1.get(i).teamId() == 18) {
                    eighteenthInTopSix++;
                }
            }
            for (int i = 0; i < 30; i++) {
                if (r1.get(i).teamId() == 1) {
                    worstWorst = Math.max(worstWorst, i + 1);
                }
            }
        }
        assertThat(worstFirst / (double) trials).isBetween(0.14, 0.19); // 16.5%
        assertThat(eighteenthInTopSix / (double) trials).isLessThan(0.03);
        assertThat(worstWorst).isLessThanOrEqualTo(7); // 최저 성적 구단은 최악이라도 7순위
    }
}
