package com.mlbgame.boxscore;

import java.util.List;

/**
 * 저장된 박스스코어를 디코딩한 결과 (경기 상세 화면용). side: 0 = 원정, 1 = 홈.
 *
 * @param lineScore [side][이닝] 득점, 말 공격 없음은 -1
 * @param lineupIds [side][타순] 선수 ID
 * @param batting   [side][타순][{@link com.mlbgame.sim.BatStat}]
 * @param pitching  [side] 등판 순서대로
 */
public record BoxScore(int innings, int[][] lineScore, int[][] lineupIds, int[][][] batting,
                       List<List<PitcherBox>> pitching) {

    public record PitcherBox(int playerId, boolean starter, char decision, int outs, int battersFaced,
                             int pitches, int hits, int runs, int earnedRuns, int walks, int strikeouts,
                             int homeRuns, int hitByPitch) {
    }
}
