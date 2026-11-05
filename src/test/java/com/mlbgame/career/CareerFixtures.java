package com.mlbgame.career;

import java.time.LocalDate;
import java.util.Arrays;

import com.mlbgame.sim.Hand;

final class CareerFixtures {

    private CareerFixtures() {
    }

    /** 모든 능력치 value (구사율 포함), 28세 */
    static MasterPlayer player(int id, int pos, int rosterStatus, int level, int source, int sample, int service,
                               int value) {
        short[] r = new short[MasterData.RATING_COLUMNS.length];
        Arrays.fill(r, (short) value);
        return new MasterPlayer(id, LocalDate.of(1998, 1, 1), pos, 147, rosterStatus, level, source, sample, service,
                r, Hand.RIGHT, Hand.RIGHT);
    }
}
