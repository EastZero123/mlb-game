package com.mlbgame.sim;

/**
 * 타자 박스스코어 항목. {@code GameResult.batting()[side][타순][항목]} 의 마지막 인덱스.
 */
public final class BatStat {

    public static final int PA = 0;
    public static final int AB = 1;
    public static final int R = 2;
    public static final int H = 3;
    public static final int B2 = 4;
    public static final int B3 = 5;
    public static final int HR = 6;
    public static final int RBI = 7;
    public static final int BB = 8;
    public static final int SO = 9;
    public static final int HBP = 10;
    public static final int SB = 11;
    public static final int CS = 12;
    public static final int SF = 13;
    public static final int GIDP = 14;

    public static final int COUNT = 15;

    private BatStat() {
    }
}
