package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LeagueBudgetTest {

    /** 30구단, 시장 지수에 비례한 시작 예산 (평균 1.6억 달러) */
    private static Map<Integer, Integer> start() {
        Map<Integer, Integer> b = new HashMap<>();
        LeagueBudget.MARKET.forEach((team, m) -> b.put(team, (int) Math.round(160_000 * m)));
        return b;
    }

    /** champion 이 매년 100승 + 월드시리즈 우승, 나머지 81승 → years 년 뒤 비율 */
    private static LeagueBudget.Result simulate(int champion, int years) {
        Map<Integer, Integer> budgets = start();
        Map<Integer, Double> base = new HashMap<>();
        LeagueBudget.Result r = null;
        for (int y = 0; y < years; y++) {
            Map<Integer, LeagueBudget.Season> seasons = Map.of(champion,
                    new LeagueBudget.Season(100, 62, true, 4));
            r = LeagueBudget.compute(budgets, seasons, base, 42, 2026 + y);
            budgets = r.next();
            base = r.base();
        }
        return r;
    }

    @Test
    void 리그_시장은_매년_2에서_6퍼센트_성장하고_예산_합은_시장_규모() {
        LeagueBudget.Result r = LeagueBudget.compute(start(), Map.of(), Map.of(), 42, 2026);
        assertThat(r.growth()).isBetween(LeagueBudget.GROWTH_MIN, LeagueBudget.GROWTH_MAX);
        long sum = r.next().values().stream().mapToLong(Integer::longValue).sum();
        assertThat((double) sum).isCloseTo(r.nextMarket(), within(30.0)); // 반올림 오차만
        assertThat(r.ratio().values()).allMatch(v -> v >= LeagueBudget.FLOOR && v <= LeagueBudget.CAP);
    }

    @Test
    void 성적이_좋으면_비율이_오르고_나쁘면_더_크게_내린다() {
        // 시장 지수가 같은 두 구단 (1.0): 95승 vs 67승, 둘 다 포스트시즌 없음
        Map<Integer, LeagueBudget.Season> seasons = Map.of(
                138, new LeagueBudget.Season(95, 67, false, 0),
                136, new LeagueBudget.Season(67, 95, false, 0));
        LeagueBudget.Result r = LeagueBudget.compute(start(), seasons, Map.of(), 42, 2026);
        double before = 1.0 / averageMarket();
        assertThat(r.ratio().get(138)).isGreaterThan(before);
        assertThat(r.ratio().get(136)).isLessThan(before);
        // 같은 14승 차이라도 내려가는 폭이 더 큼 (리빌딩 감축)
        assertThat(before - r.ratio().get(136)).isGreaterThan(r.ratio().get(138) - before);

        Map<Integer, LeagueBudget.Season> champion = Map.of(138, new LeagueBudget.Season(95, 67, true, 4));
        assertThat(LeagueBudget.compute(start(), champion, Map.of(), 42, 2026).ratio().get(138))
                .isGreaterThan(r.ratio().get(138)); // 포스트시즌 보너스
    }

    @Test
    void 빅마켓_왕조도_최고_구간에_오래_머물지_못하고_1점9배_안팎() {
        LeagueBudget.Result r = simulate(119, 30);
        assertThat(r.ratio().get(119)).isBetween(1.6, 2.1);
        assertThat(r.ratio().get(147)).isBetween(1.35, 1.65); // 평범한 빅마켓은 사치세 기준선 근처
    }

    @Test
    void 스몰마켓_왕조는_사치세_기준선까지_오른다() {
        LeagueBudget.Result r = simulate(139, 30);
        assertThat(r.ratio().get(139)).isBetween(1.2, 1.7);
        assertThat(r.ratio().get(146)).isBetween(0.5, 0.8); // 평범한 스몰마켓은 제자리
    }

    @Test
    void 구간_경계() {
        assertThat(LeagueBudget.tier(1.49)).isZero();
        assertThat(LeagueBudget.tier(1.5)).isEqualTo(1);
        assertThat(LeagueBudget.tier(2.2)).isEqualTo(2);
    }

    private static double averageMarket() {
        return LeagueBudget.MARKET.values().stream().mapToDouble(Double::doubleValue).sum() / 30;
    }
}
