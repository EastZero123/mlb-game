package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FaMarketTest {

    private static final int USER = 999;

    private static FaMarket.Fa fa(int id, double value, int age, int market, double askRatio, int pref, int holdout) {
        FaMarket.Fa f = new FaMarket.Fa();
        f.id = id;
        f.value = value;
        f.age = age;
        f.market = market;
        f.ask = (int) Math.round(market * askRatio);
        f.reserve = (int) Math.round(market * 0.9);
        f.prefYears = pref;
        f.holdout = holdout;
        return f;
    }

    private static FaMarket.Team team(int id, long room, int capable, double worst) {
        FaMarket.Team t = new FaMarket.Team();
        t.id = id;
        t.room = room;
        t.capable[0] = capable;
        t.worst[0] = worst;
        return t;
    }

    /** 계약될 때까지 라운드 진행 (최대 6), 마지막 처리 라운드 */
    private static int runUntilSigned(FaMarket.Fa f, Map<Integer, FaMarket.Team> teams, Map<Integer, FaMarket.Offer> mine) {
        for (int round = 1; round <= FaMarket.ROUNDS; round++) {
            FaMarket.round(round, List.of(f), teams, mine, USER, 42, 2027);
            if (f.status != FaMarket.OPEN) {
                return round;
            }
        }
        return FaMarket.ROUNDS;
    }

    @Test
    void 대어에_빅마켓_여러_곳이_붙으면_요구액보다_높게_장기_계약() {
        FaMarket.Fa star = fa(1, 75, 26, 40_000, 1.3, 12, 3);
        Map<Integer, FaMarket.Team> teams = new HashMap<>();
        for (int t = 1; t <= 5; t++) {
            teams.put(t, team(t, 150_000, 8, 50)); // 타자 부족, 예산 넉넉
        }
        int round = runUntilSigned(star, teams, Map.of());

        assertThat(star.status).isEqualTo(FaMarket.SIGNED);
        assertThat(round).isGreaterThanOrEqualTo(star.holdout); // 경쟁 중이면 버티다 계약
        assertThat(star.salary).isGreaterThan((int) (star.market * 1.3)); // 처음 요구액(1.3배)보다 위
        assertThat(star.years).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 관심_구단이_하나뿐인_평범한_선수는_요구액이_내려가_싸게_계약() {
        FaMarket.Fa avg = fa(2, 52, 30, 8_000, 1.0, 3, 1);
        Map<Integer, FaMarket.Team> teams = new HashMap<>();
        teams.put(1, team(1, 50_000, 10, 50)); // 부족 → 관심
        for (int t = 2; t <= 6; t++) {
            teams.put(t, team(t, 50_000, 14, 60)); // 꽉 참·더 좋음 → 관심 없음
        }
        runUntilSigned(avg, teams, Map.of());

        assertThat(avg.status).isEqualTo(FaMarket.SIGNED);
        assertThat(avg.team).isEqualTo(1);
        assertThat(avg.salary).isLessThan(avg.market);
    }

    @Test
    void 요구액의_115퍼센트_이상이면_1라운드에_바로_계약() {
        FaMarket.Fa f = fa(3, 60, 29, 20_000, 1.1, 5, 2);
        Map<Integer, FaMarket.Team> teams = new HashMap<>();
        teams.put(USER, team(USER, 100_000, 14, 60));
        teams.put(1, team(1, 100_000, 8, 50));
        Map<Integer, FaMarket.Offer> mine = Map.of(3, new FaMarket.Offer(USER, (int) (f.ask * 1.2), 5));

        List<FaMarket.Reaction> r = FaMarket.round(1, List.of(f), teams, mine, USER, 42, 2027);

        assertThat(f.team).isEqualTo(USER);
        assertThat(f.signedRound).isEqualTo(1);
        assertThat(r).containsExactly(new FaMarket.Reaction(3, FaMarket.R_SIGNED));
        assertThat(teams.get(USER).room).isEqualTo(100_000 - f.salary);
    }

    @Test
    void 낮은_제안은_요구액에_못_미침_예산이_모자라면_무효() {
        FaMarket.Fa f = fa(4, 52, 30, 8_000, 1.0, 3, 1);
        Map<Integer, FaMarket.Team> teams = new HashMap<>();
        teams.put(USER, team(USER, 100_000, 14, 60));
        List<FaMarket.Reaction> low = FaMarket.round(1, List.of(f), teams,
                Map.of(4, new FaMarket.Offer(USER, 4_000, 3)), USER, 42, 2027);
        assertThat(low).containsExactly(new FaMarket.Reaction(4, FaMarket.R_BELOW));

        FaMarket.Fa g = fa(5, 52, 30, 8_000, 1.0, 3, 1);
        teams.get(USER).room = 1_000;
        List<FaMarket.Reaction> broke = FaMarket.round(1, List.of(g), teams,
                Map.of(5, new FaMarket.Offer(USER, 9_000, 3)), USER, 42, 2027);
        assertThat(broke).containsExactly(new FaMarket.Reaction(5, FaMarket.R_BUDGET));
    }

    @Test
    void 원하는_연수보다_짧으면_깎이고_길면_조금_오른다() {
        assertThat(FaMarket.yearsFactor(10, 12)).isEqualTo(0.92, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FaMarket.yearsFactor(14, 12)).isEqualTo(1.02, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FaMarket.yearsFactor(1, 15)).isEqualTo(0.6);
        assertThat(FaMarket.maxYears(40)).isEqualTo(6); // 45세 시즌까지
    }
}
