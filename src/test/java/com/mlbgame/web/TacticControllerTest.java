package com.mlbgame.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mlbgame.roster.TeamPlan;
import com.mlbgame.season.TacticService;

class TacticControllerTest {

    private static List<Views.LineupRow> rows(boolean blank) {
        List<Views.LineupRow> out = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            out.add(new Views.LineupRow(i + 1, blank && i == 3 ? 0 : 10 + i, i + 2));
        }
        return out;
    }

    @Test
    void 꺼진_항목은_자동_null_켜진_항목만_지정() {
        Views.TacticForm f = new Views.TacticForm(rows(false), rows(false), List.of(1, 2, 3, 4, 5),
                List.of(6, 0, 7), true, false, false, true, false, false);
        List<String> errors = new ArrayList<>();
        TeamPlan plan = TacticController.toPlan(f, errors);

        assertThat(errors).isEmpty();
        assertThat(plan.vsRight()).hasSize(9);
        assertThat(plan.vsLeft()).isNull();
        assertThat(plan.rotation()).isNull();
        assertThat(plan.bullpen()).containsExactly(6, 7); // 빈 칸은 건너뜀
        assertThat(plan.autoRest()).isFalse();
        assertThat(plan.autoSwap()).isFalse();
    }

    @Test
    void 빈_타순_빈_로테이션은_오류() {
        Views.TacticForm f = new Views.TacticForm(rows(true), rows(false), List.of(1, 2, 0, 4, 5), List.of(0),
                true, false, true, true, true, true);
        List<String> errors = new ArrayList<>();
        TacticController.toPlan(f, errors);
        assertThat(errors).hasSize(3);
    }

    @Test
    void 저장된_지정이_없으면_지금_구성을_보여주고_불펜_칸을_채운다() {
        List<TeamPlan.Slot> eff = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            eff.add(new TeamPlan.Slot(10 + i, i + 2));
        }
        TacticService.Effective e = new TacticService.Effective(eff, null, List.of(1, 2, 3, 4, 5), List.of(6, 7), 2);
        Views.TacticForm f = TacticController.fromState(new TacticService.State(null, e), 8);

        assertThat(f.lineupOn()).isFalse();
        assertThat(f.autoRest()).isTrue();
        assertThat(f.autoSwap()).isTrue();
        assertThat(f.vsLeft()).isEqualTo(f.vsRight());
        assertThat(f.bullpen()).hasSize(8).startsWith(6, 7);
        assertThat(f.vsRight().getFirst()).isEqualTo(new Views.LineupRow(1, 10, 2));
    }
}
