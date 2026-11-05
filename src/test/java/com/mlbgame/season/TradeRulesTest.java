package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mlbgame.career.ContractModel;

class TradeRulesTest {

    private static final double RATE = 1_000; // 가치 1점당 100만 달러
    private static final TradeRules.TeamCtx MID = new TradeRules.TeamCtx(1, TradeRules.MID, new int[] {14, 14});

    /** 25세 연봉조정 전 주전 (가치 65, 최저연봉, 4년 보유) */
    private static TradeRules.Asset star(int id, int team) {
        return new TradeRules.Asset(id, team, false, 25, 65, 70, ContractModel.PRE_ARB, 4, 780, 2, true);
    }

    /** 35세 고액 장기 계약 (가치 55, 연 3,000만 3년) */
    private static TradeRules.Asset overpaid(int id, int team) {
        return new TradeRules.Asset(id, team, false, 35, 55, 50, ContractModel.GUARANTEED, 3, 30_000, 12, true);
    }

    /** 20세 마이너 유망주 (가치 42, 잠재력 65) */
    private static TradeRules.Asset prospect(int id, int team) {
        return new TradeRules.Asset(id, team, false, 20, 42, 65, ContractModel.MINOR, 6, 75, 0, false);
    }

    /** 31세 FA 1년 남은 선수 (가치 60, 연 1,500만) = 단기 임대 */
    private static TradeRules.Asset rental(int id, int team) {
        return new TradeRules.Asset(id, team, false, 31, 60, 55, ContractModel.GUARANTEED, 1, 15_000, 8, true);
    }

    @Test
    void 싸고_젊은_주전은_가치가_크고_비싼_노장_장기계약은_마이너스() {
        assertThat(TradeRules.worth(star(1, 1), MID, RATE, 1.0)).isGreaterThan(30_000);
        assertThat(TradeRules.worth(overpaid(2, 1), MID, RATE, 1.0)).isNegative();
        assertThat(TradeRules.worth(prospect(3, 1), MID, RATE, 1.0)).isPositive();
    }

    @Test
    void AI_는_악성_계약을_받지_않지만_떠넘기기는_받아들인다() {
        TradeRules.Verdict take = TradeRules.judge(List.of(overpaid(2, 9)), List.of(), MID, RATE, 1.0);
        assertThat(take.accept()).isFalse();
        TradeRules.Verdict dump = TradeRules.judge(List.of(), List.of(overpaid(2, 1)), MID, RATE, 1.0);
        assertThat(dump.accept()).isTrue();
    }

    @Test
    void 같은_가치_맞교환은_거절하고_유망주를_얹으면_수락() {
        TradeRules.Verdict even = TradeRules.judge(List.of(star(1, 9)), List.of(star(2, 1)), MID, RATE, 1.0);
        assertThat(even.accept()).isFalse();
        assertThat(even.reason()).contains("조금 부족");
        TradeRules.Verdict plus = TradeRules.judge(List.of(star(1, 9), prospect(3, 9)), List.of(star(2, 1)), MID,
                RATE, 1.0);
        assertThat(plus.accept()).isTrue();
    }

    @Test
    void 윈나우_팀은_단기_임대를_리빌딩_팀보다_높게_본다() {
        TradeRules.TeamCtx win = new TradeRules.TeamCtx(1, TradeRules.WIN, new int[] {14, 14});
        TradeRules.TeamCtx rebuild = new TradeRules.TeamCtx(1, TradeRules.REBUILD, new int[] {14, 14});
        double w = TradeRules.worth(rental(5, 1), win, RATE, 1.0);
        double r = TradeRules.worth(rental(5, 1), rebuild, RATE, 1.0);
        assertThat(w).isGreaterThan(r);
        // 시즌이 얼마 안 남으면 임대 가치가 줄어든다
        assertThat(Math.abs(TradeRules.worth(rental(5, 1), win, RATE, 0.3))).isLessThan(Math.abs(w));
    }

    @Test
    void 승률로_팀_상황_판단_초반_10경기_전은_중립() {
        assertThat(TradeRules.mode(5, 3)).isEqualTo(TradeRules.MID);
        assertThat(TradeRules.mode(60, 40)).isEqualTo(TradeRules.WIN);
        assertThat(TradeRules.mode(40, 60)).isEqualTo(TradeRules.REBUILD);
        assertThat(TradeRules.mode(50, 50)).isEqualTo(TradeRules.MID);
    }
}
