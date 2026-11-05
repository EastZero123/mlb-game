package com.mlbgame.sim;

/**
 * 타석 결과 종류. 값이 확률 배열의 인덱스이므로 순서를 바꾸지 않는다.
 */
public final class Outcomes {

    public static final int STRIKEOUT = 0;
    public static final int WALK = 1;
    public static final int HIT_BY_PITCH = 2;
    public static final int SINGLE = 3;
    public static final int DOUBLE = 4;
    public static final int TRIPLE = 5;
    public static final int HOME_RUN = 6;
    public static final int GROUND_OUT = 7;
    public static final int AIR_OUT = 8;

    public static final int COUNT = 9;

    private Outcomes() {
    }
}
