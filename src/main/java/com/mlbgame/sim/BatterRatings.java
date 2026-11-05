package com.mlbgame.sim;

/**
 * 시뮬레이션에 쓰는 타자 능력치 (20~80).
 *
 * @param tendency 타구 성향. 50 초과 = 땅볼형, 50 미만 = 뜬공형
 */
public record BatterRatings(int contact, int power, int eye, int quality, int tendency,
                            int speed, int baserunning) {
}
