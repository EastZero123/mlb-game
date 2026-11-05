package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mlbgame.season.NextDayService;

class ScoutingTest {

    @Test
    void 단계가_높을수록_비용이_크고_1단계는_무료() {
        assertThat(Scouting.cost(1)).isZero();
        for (int lv = 2; lv <= 5; lv++) {
            assertThat(Scouting.cost(lv)).isGreaterThan(Scouting.cost(lv - 1));
        }
        assertThat(Scouting.cost(9)).isEqualTo(Scouting.cost(5)); // 범위 밖은 끝값
    }

    @Test
    void 오프시즌_FA_시장_개장_전까지만_바꿀_수_있다() {
        int off = NextDayService.PHASE_OFFSEASON;
        assertThat(Scouting.changeable(off, 0)).isTrue();
        assertThat(Scouting.changeable(off, OffseasonService.Stage.SALARY.no())).isTrue();   // 4단계 끝
        assertThat(Scouting.changeable(off, OffseasonService.Stage.MARKET.no())).isFalse();  // 5단계(개장) 끝
        assertThat(Scouting.changeable(NextDayService.PHASE_REGULAR, 0)).isFalse();
    }
}
