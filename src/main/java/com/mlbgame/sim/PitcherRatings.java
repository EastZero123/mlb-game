package com.mlbgame.sim;

/**
 * 시뮬레이션에 쓰는 투수 능력치 (20~80).
 *
 * @param stuff       구위. 구종별 구위를 구사율로 가중 평균한 값
 * @param suppression 피안타 억제 (강한 타구 허용 억제)
 * @param groundball  땅볼 유도 성향
 */
public record PitcherRatings(int stuff, int control, int suppression, int groundball, int stamina) {
}
