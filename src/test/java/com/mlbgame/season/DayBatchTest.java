package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DayBatchTest {

    @Test
    void 레벨과_선수_키는_되돌릴_수_있다() {
        long k = DayBatch.key(2, 691_406);
        assertThat(DayBatch.level(k)).isEqualTo(2);
        assertThat(DayBatch.playerId(k)).isEqualTo(691_406);
        assertThat(DayBatch.key(0, 691_406)).isNotEqualTo(k);
    }

    @Test
    void 경기_seed는_결정적이고_경기마다_다르다() {
        long a = NextDayService.gameSeed(42, 2026, 0, 1);
        assertThat(NextDayService.gameSeed(42, 2026, 0, 1)).isEqualTo(a);
        assertThat(NextDayService.gameSeed(42, 2026, 0, 2)).isNotEqualTo(a);
        assertThat(NextDayService.gameSeed(42, 2026, 1, 1)).isNotEqualTo(a);
        assertThat(NextDayService.gameSeed(42, 2027, 0, 1)).isNotEqualTo(a);
        assertThat(NextDayService.gameSeed(43, 2026, 0, 1)).isNotEqualTo(a);
    }

    @Test
    void 가산_upsert는_기존_값을_테이블명으로_한정한다() {
        String sql = NextDayService.upsert("c_bat_season", "g, pa");
        assertThat(sql).contains("g = c_bat_season.g + n.g", "pa = c_bat_season.pa + n.pa", "team_id = n.team_id");
        assertThat(sql).doesNotContain("= g + n.g");
    }
}
