package com.mlbgame.roster;

import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.PitcherRatings;

/**
 * 로스터 구성에 필요한 선수 정보 (DB 와 무관한 순수 값).
 *
 * @param batting  타자 능력치, 투수 전용이면 null
 * @param pitching 투수 능력치 (구위 = 구종별 구위의 구사율 가중 평균), 야수면 null
 * @param defense  포지션별 수비 [C, 1B, 2B, 3B, SS, LF, CF, RF], 0 = 소화 불가
 * @param arm      송구 (야수), 없으면 50
 */
public record PlayerCard(int playerId, int teamId, int primaryPos, Hand bats, Hand throwsHand,
                         BatterRatings batting, PitcherRatings pitching, int[] defense, int arm) {

    public PlayerCard {
        if (defense == null || defense.length != 8) {
            throw new IllegalArgumentException("defense 는 8칸");
        }
    }

    /** @param pos 2=C ... 9=RF. 0 이면 소화 불가 */
    public int def(int pos) {
        return defense[pos - 2];
    }

    public double offense() {
        BatterRatings b = batting;
        return b == null ? 0 : 0.30 * b.contact() + 0.30 * b.power() + 0.20 * b.eye() + 0.20 * b.quality();
    }

    public double pitchingQuality() {
        PitcherRatings p = pitching;
        return p == null ? 0 : 0.40 * p.stuff() + 0.30 * p.control() + 0.30 * p.suppression();
    }
}
