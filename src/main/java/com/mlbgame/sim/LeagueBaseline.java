package com.mlbgame.sim;

/**
 * 리그 환경: 평균 타석 결과 확률과 리그 공통 상수. 검증기의 자동 보정 결과가 mst_league 에 저장된다.
 *
 * @param rates         {@link Outcomes} 순서의 확률 (생성 시 합 1로 정규화). 읽기 전용으로 취급.
 *                      능력치 50 타자 vs 능력치 50 투수의 결과 확률
 * @param platoonEdge   투타 상성 보정 (유리 시 1+edge, 불리 시 1-edge)
 * @param coefficients  능력치 → 결과 로그 배율 계수
 * @param batterSpread  타자 능력치 효과 배율 (게임 밸런스용 손잡이, 기본 1)
 * @param pitcherSpread 투수 능력치 효과 배율 (기본 1)
 * @param errorRate     인플레이 아웃이 실책 출루가 될 확률 (땅볼 ×1.4, 뜬공 ×0.6, 수비력 반영)
 * @param wildPitchRate 주자가 있을 때 타석마다 폭투·포일로 모든 주자가 한 베이스 진루할 확률
 */
public record LeagueBaseline(double[] rates, double platoonEdge, RateCoefficients coefficients,
                             Spread batterSpread, Spread pitcherSpread, double errorRate, double wildPitchRate) {

    public static final double DEFAULT_ERROR_RATE = 0.019;
    public static final double DEFAULT_WILD_PITCH_RATE = 0.024;

    public LeagueBaseline {
        if (rates == null || rates.length != Outcomes.COUNT) {
            throw new IllegalArgumentException("rates 길이는 " + Outcomes.COUNT + " 이어야 함");
        }
        if (!(errorRate >= 0 && errorRate < 0.2 && wildPitchRate >= 0 && wildPitchRate < 0.2)) {
            throw new IllegalArgumentException("errorRate/wildPitchRate 범위 오류");
        }
        if (coefficients == null || batterSpread == null || pitcherSpread == null) {
            throw new IllegalArgumentException("coefficients/spread 필수");
        }
        rates = RateModel.normalize(rates.clone());
    }

    public LeagueBaseline(double[] rates, double platoonEdge) {
        this(rates, platoonEdge, RateCoefficients.defaults(), Spread.ONE, Spread.ONE,
                DEFAULT_ERROR_RATE, DEFAULT_WILD_PITCH_RATE);
    }

    public LeagueBaseline withRates(double[] newRates) {
        return new LeagueBaseline(newRates, platoonEdge, coefficients, batterSpread, pitcherSpread, errorRate,
                wildPitchRate);
    }

    public LeagueBaseline withCoefficients(RateCoefficients c) {
        return new LeagueBaseline(rates, platoonEdge, c, batterSpread, pitcherSpread, errorRate, wildPitchRate);
    }

    public LeagueBaseline withSpread(Spread batter, Spread pitcher) {
        return new LeagueBaseline(rates, platoonEdge, coefficients, batter, pitcher, errorRate, wildPitchRate);
    }

    public LeagueBaseline withMisc(double newErrorRate, double newWildPitchRate) {
        return new LeagueBaseline(rates, platoonEdge, coefficients, batterSpread, pitcherSpread, newErrorRate,
                newWildPitchRate);
    }

    /**
     * 2026 MLB 실측 (임포터 집계, 개막 로스터 선수 기준).
     * 인플레이 아웃 .462 는 땅볼/뜬공 비율 약 44:56 으로 나눔.
     */
    public static LeagueBaseline mlbDefault() {
        return new LeagueBaseline(new double[] {
                0.220, // 삼진
                0.090, // 볼넷
                0.011, // 사구
                0.142, // 단타
                0.041, // 2루타
                0.004, // 3루타
                0.031, // 홈런
                0.203, // 땅볼 아웃
                0.259  // 뜬공 아웃
        }, 0.04);
    }
}
