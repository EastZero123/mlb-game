package com.mlbgame.roster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeamPlanTest {

    private static TeamPlan.Slot[] lineup() {
        TeamPlan.Slot[] s = new TeamPlan.Slot[9];
        for (int i = 0; i < 9; i++) {
            s[i] = new TeamPlan.Slot(1_000_000_000 + i, i + 2); // 대체 선수 ID 대역도 저장 가능
        }
        return s;
    }

    @Test
    void 저장_형식을_왕복한다() {
        TeamPlan.Slot[] s = lineup();
        assertThat(TeamPlan.decodeSlots(TeamPlan.encodeSlots(s))).containsExactly(s);
        assertThat(TeamPlan.encodeSlots(s).length()).isLessThanOrEqualTo(255);
        assertThat(TeamPlan.decodeIds(TeamPlan.encodeIds(new int[] {5, 4, 3}))).containsExactly(5, 4, 3);
        assertThat(TeamPlan.decodeSlots(null)).isNull();
        assertThat(TeamPlan.decodeIds("")).isNull();
        assertThat(TeamPlan.encodeIds(new int[0])).isNull();
    }

    @Test
    void 정상_지정은_오류가_없다() {
        TeamPlan plan = new TeamPlan(lineup(), lineup(), new int[] {1, 2, 3, 4, 5}, new int[] {6, 7}, true, true);
        assertThat(plan.formatErrors()).isEmpty();
        assertThat(new TeamPlan(null, null, null, null, false, true).formatErrors()).isEmpty();
    }

    @Test
    void 위치_중복_선수_중복_로테이션_인원_불펜_겹침을_잡는다() {
        TeamPlan.Slot[] dupPos = lineup();
        dupPos[1] = new TeamPlan.Slot(dupPos[1].playerId(), 2);
        assertThat(new TeamPlan(dupPos, null, null, null, true, true).formatErrors()).hasSize(1);

        TeamPlan.Slot[] dupPlayer = lineup();
        dupPlayer[1] = new TeamPlan.Slot(dupPlayer[0].playerId(), 3);
        assertThat(new TeamPlan(dupPlayer, null, null, null, true, true).formatErrors()).hasSize(1);

        assertThat(new TeamPlan(null, null, new int[] {1, 2, 3, 4}, null, true, true).formatErrors()).hasSize(1);
        assertThat(new TeamPlan(null, null, new int[] {1, 2, 3, 4, 5}, new int[] {5}, true, true).formatErrors())
                .hasSize(1);
        assertThat(new TeamPlan(null, lineup(), null, null, true, true).formatErrors()).isNotEmpty();
    }
}
