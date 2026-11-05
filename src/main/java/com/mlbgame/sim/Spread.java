package com.mlbgame.sim;

/**
 * 능력치 효과 크기 배율. 1.0 이면 {@link RateModel} 계수 그대로, 0.5 면 능력치 차이의 효과가 절반.
 * <p>
 * 능력치(20~80)는 "순서"를, 이 배율은 "실제 기록이 얼마나 벌어지는가"를 맡는다.
 * 검증기가 선수 기록의 퍼짐(표준편차)이 실제와 같아지도록 결과 그룹별로 자동 보정한다.
 *
 * @param strikeout 삼진
 * @param walk      볼넷·사구
 * @param homeRun   홈런
 * @param hit       홈런 외 안타 (단타·2루타·3루타)
 */
public record Spread(double strikeout, double walk, double homeRun, double hit) {

    public static final Spread ONE = new Spread(1, 1, 1, 1);

    public Spread {
        if (!(strikeout > 0 && walk > 0 && homeRun > 0 && hit > 0)) {
            throw new IllegalArgumentException("spread 는 0보다 커야 함");
        }
    }

    public double[] toArray() {
        return new double[] {strikeout, walk, homeRun, hit};
    }

    public static Spread of(double[] a) {
        return new Spread(a[0], a[1], a[2], a[3]);
    }
}
