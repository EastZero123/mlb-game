package com.mlbgame.sim;

/**
 * 시뮬레이션용 타자. {@link RateModel#batter} 로 만들고 능력치가 바뀌기 전까지 재사용한다.
 *
 * @param rates       결과별 확률 (합 1). 읽기 전용으로 취급
 * @param speedZ      스피드 z 점수
 * @param stealZ      주루(도루) z 점수
 * @param performance 발휘율 (자동으로 {@link Performance} 범위로 제한)
 */
public record SimBatter(long playerId, double[] rates, Hand hand, double speedZ, double stealZ,
                        double performance) {

    public SimBatter {
        if (rates == null || rates.length != Outcomes.COUNT) {
            throw new IllegalArgumentException("rates 길이 오류");
        }
        if (hand == null) {
            throw new IllegalArgumentException("hand 필수");
        }
        performance = Performance.clamp(performance);
    }

    /** 확률표는 그대로 두고 발휘율만 바꾼 사본 (확률표 재계산 없음). */
    public SimBatter withPerformance(double newPerformance) {
        return new SimBatter(playerId, rates, hand, speedZ, stealZ, newPerformance);
    }
}
