package com.mlbgame.sim;

import java.util.List;

/**
 * 경기 결과 (박스스코어). side 인덱스: 0 = 원정, 1 = 홈.
 *
 * @param innings      진행한 이닝 수
 * @param lineScore    [side][이닝] 득점. 홈팀이 말 공격을 하지 않은 이닝은 -1
 * @param batting      [side][타순 0~8][{@link BatStat}]
 * @param awayPitching 원정 투수 기록 (등판 순서)
 * @param homePitching 홈 투수 기록 (등판 순서)
 * @param savePitcherId 세이브 투수 ID, 없으면 0
 */
public record GameResult(int awayRuns, int homeRuns, int innings,
                         int[][] lineScore, int[][][] batting,
                         List<PitcherLine> awayPitching, List<PitcherLine> homePitching,
                         long winningPitcherId, long losingPitcherId, long savePitcherId) {

    public boolean homeWin() {
        return homeRuns > awayRuns;
    }
}
