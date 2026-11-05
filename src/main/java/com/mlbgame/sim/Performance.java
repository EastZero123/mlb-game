package com.mlbgame.sim;

/**
 * 발휘율(능력치를 경기에서 얼마나 끌어내는가). 상·하한을 고정해 극단적인 결과를 막는다.
 */
public final class Performance {

    public static final double MIN = 0.90;
    public static final double MAX = 1.05;
    public static final double NEUTRAL = 1.0;

    private Performance() {
    }

    public static double clamp(double value) {
        return Math.clamp(value, MIN, MAX);
    }
}
