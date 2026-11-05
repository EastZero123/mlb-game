package com.mlbgame.season;

import java.time.LocalDate;

/** 포스트시즌 시리즈 1개 (메모리 상태, c_series 1행). high = 상위 시드 = 홈 어드밴티지 */
final class Series {

    /** 홈 순서 (true = 상위 시드 홈): 3전 전부 상위 / 5전 2-2-1 / 7전 2-3-2 */
    private static final boolean[] BEST_OF_3 = {true, true, true};
    private static final boolean[] BEST_OF_5 = {true, true, false, false, true};
    private static final boolean[] BEST_OF_7 = {true, true, false, false, false, true, true};

    final int id;
    final int round;
    final String league;
    final int bestOf;
    final int high;
    final int low;
    final int highSeed;
    final int lowSeed;
    int highWins;
    int lowWins;
    LocalDate nextDate;
    int winner;
    boolean dirty;

    Series(int id, int round, String league, int bestOf, int high, int low, int highSeed, int lowSeed,
           LocalDate nextDate) {
        this.id = id;
        this.round = round;
        this.league = league;
        this.bestOf = bestOf;
        this.high = high;
        this.low = low;
        this.highSeed = highSeed;
        this.lowSeed = lowSeed;
        this.nextDate = nextDate;
    }

    int played() {
        return highWins + lowWins;
    }

    boolean done() {
        return winner != 0;
    }

    boolean involves(int team) {
        return high == team || low == team;
    }

    /** 다음 경기 홈 팀 */
    int homeTeam() {
        return pattern()[played()] ? high : low;
    }

    int awayTeam() {
        return homeTeam() == high ? low : high;
    }

    /** 경기 결과 반영. 시리즈가 끝나면 winner 설정, 아니면 다음 경기일 (홈이 바뀌면 이동일 하루) */
    void record(int winnerTeam, LocalDate date) {
        if (winnerTeam == high) {
            highWins++;
        } else {
            lowWins++;
        }
        int need = bestOf / 2 + 1;
        if (highWins == need || lowWins == need) {
            winner = highWins == need ? high : low;
            nextDate = null;
        } else {
            boolean[] p = pattern();
            int g = played();
            nextDate = date.plusDays(p[g] != p[g - 1] ? 2 : 1);
        }
        dirty = true;
    }

    int loser() {
        return winner == high ? low : high;
    }

    int seedOf(int team) {
        return team == high ? highSeed : lowSeed;
    }

    private boolean[] pattern() {
        return switch (bestOf) {
            case 3 -> BEST_OF_3;
            case 5 -> BEST_OF_5;
            default -> BEST_OF_7;
        };
    }
}
