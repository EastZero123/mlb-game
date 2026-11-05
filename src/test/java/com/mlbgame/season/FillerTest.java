package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mlbgame.career.MasterData;
import com.mlbgame.roster.AutoManager;
import com.mlbgame.sim.GameSimulator;

class FillerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 1);

    private static CareerRuntime runtime(long seed) {
        return new CareerRuntime(1, seed, 147, List.of(), new HashMap<>(), new HashMap<>(), new AutoManager[3],
                new int[3][0][5], new GameSimulator[3]);
    }

    @Test
    void 같은_커리어_같은_순번이면_같은_선수() {
        Filler.Signed a = Filler.sign(runtime(7), 147, 2, false, false, DAY);
        Filler.Signed b = Filler.sign(runtime(7), 147, 2, false, false, DAY);
        assertThat(a.player().id).isEqualTo(Filler.FIRST_ID);
        assertThat(a.playerRow()).isEqualTo(b.playerRow());
        assertThat(a.personRow()).isEqualTo(b.personRow());
    }

    @Test
    void 포수가_필요하면_포수_수비가_있는_타자() {
        Filler.Signed s = Filler.sign(runtime(3), 147, 2, false, true, DAY);
        assertThat(s.player().card.batting()).isNotNull();
        assertThat(s.player().card.pitching()).isNull();
        assertThat(s.player().card.def(2)).isPositive();
        assertThat(s.player().card.primaryPos()).isEqualTo(2);
    }

    @Test
    void 투수는_구종과_스태미나가_있고_마이너_계약() {
        CareerRuntime rt = runtime(5);
        Filler.Signed s = Filler.sign(rt, 147, 1, true, false, DAY);
        assertThat(s.player().card.batting()).isNull();
        assertThat(s.player().card.pitching().stuff()).isBetween(20, 80);
        assertThat(s.player().status).isEqualTo(CareerRuntime.ST_MINOR);
        assertThat(s.player().level).isEqualTo(1);
        assertThat(rt.nextPersonId).isEqualTo(Filler.FIRST_ID + 1);
        // c_player 행: [5] = 마이너 계약, [7] = 마이너 연봉 75K, 능력치 컬럼 수 일치 (뒤 6칸: 성격 3·상태일·부상·프로 연차)
        assertThat(s.playerRow()[5]).isEqualTo(com.mlbgame.career.ContractModel.MINOR);
        assertThat(s.playerRow()[7]).isEqualTo(com.mlbgame.career.ContractModel.MINOR_SALARY);
        assertThat(s.playerRow().length).isEqualTo(9 + MasterData.RATING_COLUMNS.length + 6);
        // 프로 연차 추정 = 나이(23~28) - 19
        assertThat((int) s.playerRow()[s.playerRow().length - 1]).isBetween(4, 9);
    }
}
