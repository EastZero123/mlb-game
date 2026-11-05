package com.mlbgame.sim;

/**
 * 투수 1명의 한 경기 기록. 시뮬레이터 내부에서만 값을 바꾼다.
 */
public final class PitcherLine {

    public static final char NO_DECISION = ' ';

    final long playerId;
    final boolean starter;
    /** 등판 시점 리드 (+ 이기는 중, 세이브 판정용) */
    final int leadOnEntry;

    int outs;
    int battersFaced;
    int pitches;
    int hits;
    int runs;
    int earnedRuns;
    int walks;
    int strikeouts;
    int homeRuns;
    int hitByPitch;
    char decision = NO_DECISION;

    PitcherLine(long playerId, boolean starter, int leadOnEntry) {
        this.playerId = playerId;
        this.starter = starter;
        this.leadOnEntry = leadOnEntry;
    }

    public long playerId() {
        return playerId;
    }

    public boolean starter() {
        return starter;
    }

    public int outs() {
        return outs;
    }

    public int battersFaced() {
        return battersFaced;
    }

    public int pitches() {
        return pitches;
    }

    public int hits() {
        return hits;
    }

    public int runs() {
        return runs;
    }

    public int earnedRuns() {
        return earnedRuns;
    }

    public int walks() {
        return walks;
    }

    public int strikeouts() {
        return strikeouts;
    }

    public int homeRuns() {
        return homeRuns;
    }

    public int hitByPitch() {
        return hitByPitch;
    }

    /** 'W', 'L', 'S' 또는 {@link #NO_DECISION} */
    public char decision() {
        return decision;
    }
}
