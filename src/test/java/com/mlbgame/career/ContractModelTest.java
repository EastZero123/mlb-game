package com.mlbgame.career;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class ContractModelTest {

    private static final int SEASON = 2026;
    private static final double RATE = 2_000; // 가치 1점당 200만 달러
    private final SplittableRandom rng = new SplittableRandom(1);

    @Test
    void 마이너_계약은_연봉_75K_고정() {
        ContractModel.Contract c = ContractModel.contract(CareerFixtures.player(1, 6, 0, 1, 1, 0, 0, 45), SEASON, RATE, rng);
        assertThat(c.type()).isEqualTo(ContractModel.MINOR);
        assertThat(c.salary()).isEqualTo(ContractModel.MINOR_SALARY);
    }

    @Test
    void 서비스_3년_미만은_최저연봉_보유연수_6빼기_서비스() {
        ContractModel.Contract c = ContractModel.contract(CareerFixtures.player(1, 6, 2, 0, 0, 600, 1, 70), SEASON, RATE, rng);
        assertThat(c.type()).isEqualTo(ContractModel.PRE_ARB);
        assertThat(c.years()).isEqualTo(5);
        assertThat(c.salary()).isEqualTo(ContractModel.MIN_SALARY); // 서비스 연차와 관계없이 고정
    }

    @Test
    void 연봉조정은_연차가_쌓일수록_오른다() {
        int s3 = ContractModel.contract(CareerFixtures.player(1, 6, 2, 0, 0, 600, 3, 62), SEASON, RATE, rng).salary();
        int s5 = ContractModel.contract(CareerFixtures.player(1, 6, 2, 0, 0, 600, 5, 62), SEASON, RATE, rng).salary();
        assertThat(s5).isGreaterThan(s3);
    }

    @Test
    void 시장가치는_가치에_따라_오르고_상한이_있다() {
        assertThat(ContractModel.marketValue(60, 28, RATE)).isGreaterThan(ContractModel.marketValue(55, 28, RATE));
        assertThat(ContractModel.marketValue(90, 28, RATE)).isEqualTo(ContractModel.MAX_SALARY);
        assertThat(ContractModel.marketValue(30, 28, RATE)).isEqualTo(ContractModel.MIN_SALARY);
        assertThat(ContractModel.marketValue(65, 36, RATE)).isLessThan(ContractModel.marketValue(65, 30, RATE));
    }

    @Test
    void 단가는_40인_연봉_합계를_리그_목표에_맞춘다() {
        List<MasterPlayer> league = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) {
            int service = i % 10;            // 0~9년 고르게
            int value = 40 + (i * 7) % 25;   // 가치 40~64
            league.add(CareerFixtures.player(i, 6, i % 40 < 26 ? 2 : 1, 0, 0, 500, service, value));
        }
        double rate = ContractModel.calibrateRate(league, SEASON);
        long total = 0;
        for (MasterPlayer p : league) {
            total += ContractModel.contract(p, SEASON, rate, rng).salary();
        }
        assertThat((double) total).isCloseTo(ContractModel.LEAGUE_PAYROLL, org.assertj.core.data.Percentage.withPercentage(3));
    }

    @Test
    void 개막_배치와_연봉_합계() {
        Map<Integer, Integer> payroll = new HashMap<>();
        LocalDate opening = LocalDate.of(2026, 3, 25);
        LocalDate cur = opening.minusDays(1);

        Object[] active = CareerCreator.playerRow(CareerFixtures.player(1, 6, 2, 0, 0, 600, 8, 60), 7, SEASON, RATE, cur,
                opening, payroll);
        Object[] injured = CareerCreator.playerRow(CareerFixtures.player(2, 6, 1, -1, 0, 0, 8, 60), 7, SEASON, RATE, cur,
                opening, payroll);
        Object[] prospect = CareerCreator.playerRow(CareerFixtures.player(3, 6, 1, -1, 1, 0, 0, 45), 7, SEASON, RATE, cur,
                opening, payroll);
        Object[] minor = CareerCreator.playerRow(CareerFixtures.player(4, 6, 0, 1, 1, 0, 0, 45), 7, SEASON, RATE, cur,
                opening, payroll);

        // [3]=roster_status, [4]=level, 마지막=injury_until
        assertThat(active[3]).isEqualTo(CareerCreator.ST_ACTIVE);
        assertThat(active[4]).isEqualTo(CareerCreator.MLB);
        assertThat(injured[3]).isEqualTo(CareerCreator.ST_INJURED);
        assertThat(injured[injured.length - 1]).isNotNull();
        assertThat(prospect[4]).isEqualTo(CareerCreator.AA);
        assertThat(minor[3]).isEqualTo(CareerCreator.ST_MINOR);
        assertThat(minor[4]).isEqualTo(CareerCreator.AAA);
        // 마이너 계약은 연봉 합계에서 제외
        int expected = (int) active[7] + (int) injured[7] + (int) prospect[7];
        assertThat(payroll.get(147)).isEqualTo(expected);
    }
}
