package com.mlbgame.sim;

/**
 * 시뮬레이션용 투수. {@link RateModel#pitcher} 로 만들고 능력치가 바뀌기 전까지 재사용한다.
 *
 * @param ratios      결과별 (투수 확률 / 리그 평균). 읽기 전용으로 취급
 * @param performance 발휘율 (자동으로 {@link Performance} 범위로 제한)
 * @param pitchLimit  교체 기준 투구 수
 */
public record SimPitcher(long playerId, double[] ratios, Hand hand, double performance, int pitchLimit) {

    public SimPitcher {
        if (ratios == null || ratios.length != Outcomes.COUNT) {
            throw new IllegalArgumentException("ratios 길이 오류");
        }
        if (hand != Hand.LEFT && hand != Hand.RIGHT) {
            throw new IllegalArgumentException("투수는 LEFT/RIGHT 만 가능");
        }
        performance = Performance.clamp(performance);
    }

    public SimPitcher withPerformance(double newPerformance) {
        return new SimPitcher(playerId, ratios, hand, newPerformance, pitchLimit);
    }
}
