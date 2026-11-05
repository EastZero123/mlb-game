package com.mlbgame.sim;

/**
 * 한 경기에 나서는 팀 구성.
 *
 * @param lineup        타순 9명
 * @param starter       선발 투수
 * @param bullpen       불펜. [0] = 마무리, [1..] = 투입 순서
 * @param defenseFactor 인플레이 안타 배율 ({@link RateModel#defenseFactor}). 1 미만 = 수비 좋음
 * @param catcherArmZ   포수 송구 z 점수 (도루 저지)
 */
public record SimTeam(long teamId, SimBatter[] lineup, SimPitcher starter, SimPitcher[] bullpen,
                      double defenseFactor, double catcherArmZ) {

    public SimTeam {
        if (lineup == null || lineup.length != 9) {
            throw new IllegalArgumentException("타순은 9명");
        }
        if (starter == null) {
            throw new IllegalArgumentException("선발 투수 필수");
        }
        if (bullpen == null) {
            bullpen = new SimPitcher[0];
        }
    }
}
