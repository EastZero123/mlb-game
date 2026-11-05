package com.mlbgame.season;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mlbgame.sim.BatStat;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.PitcherLine;
import com.mlbgame.sim.SimTeam;

/**
 * 하루치 결과를 메모리에서 합산. 저장 시 선수 1명 = 레벨별 1행 (같은 날 더블헤더도 1행으로 합쳐짐).
 */
final class DayBatch {

    /** 타격: [구단, 경기, 타석, 타수, 득점, 안타, 2루타, 3루타, 홈런, 타점, 볼넷, 삼진, 사구, 도루, 도루자, 희플, 병살] */
    static final int BAT_LEN = 2 + BatStat.COUNT;
    /** 투구: [구단, 경기, 선발, 승, 패, 세이브, 아웃, 상대타자, 투구수, 피안타, 실점, 자책, 볼넷, 삼진, 피홈런, 사구] */
    static final int PIT_LEN = 16;

    record Game(int gameNo, int awayRuns, int homeRuns, int innings, byte[] box) {
    }

    final Map<Long, int[]> bat = new HashMap<>(1024);
    final Map<Long, int[]> pit = new HashMap<>(512);
    final List<Game> games = new ArrayList<>(16);
    int minorGames;

    void addGame(int gameNo, GameResult r, byte[] box) {
        games.add(new Game(gameNo, r.awayRuns(), r.homeRuns(), r.innings(), box));
    }

    void record(int level, GameResult r, SimTeam away, SimTeam home, CareerRuntime rt) {
        batting(level, away, r.batting()[0], rt);
        batting(level, home, r.batting()[1], rt);
        pitching(level, r.awayPitching(), rt);
        pitching(level, r.homePitching(), rt);
    }

    private void batting(int level, SimTeam team, int[][] box, CareerRuntime rt) {
        for (int slot = 0; slot < 9; slot++) {
            int[] s = box[slot];
            if (s[BatStat.PA] == 0) {
                continue;
            }
            int id = (int) team.lineup()[slot].playerId();
            int[] a = bat.computeIfAbsent(key(level, id), k -> new int[BAT_LEN]);
            a[0] = rt.team(id);
            a[1]++;
            for (int i = 0; i < BatStat.COUNT; i++) {
                a[2 + i] += s[i];
            }
        }
    }

    private void pitching(int level, List<PitcherLine> lines, CareerRuntime rt) {
        for (PitcherLine l : lines) {
            int id = (int) l.playerId();
            int[] a = pit.computeIfAbsent(key(level, id), k -> new int[PIT_LEN]);
            a[0] = rt.team(id);
            a[1]++;
            if (l.starter()) {
                a[2]++;
            }
            switch (l.decision()) {
                case 'W' -> a[3]++;
                case 'L' -> a[4]++;
                case 'S' -> a[5]++;
                default -> {
                }
            }
            a[6] += l.outs();
            a[7] += l.battersFaced();
            a[8] += l.pitches();
            a[9] += l.hits();
            a[10] += l.runs();
            a[11] += l.earnedRuns();
            a[12] += l.walks();
            a[13] += l.strikeouts();
            a[14] += l.homeRuns();
            a[15] += l.hitByPitch();
        }
    }

    static long key(int level, int playerId) {
        return ((long) level << 32) | (playerId & 0xFFFFFFFFL);
    }

    static int level(long key) {
        return (int) (key >>> 32);
    }

    static int playerId(long key) {
        return (int) key;
    }
}
