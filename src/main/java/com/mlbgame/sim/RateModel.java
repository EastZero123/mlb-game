package com.mlbgame.sim;

import static com.mlbgame.sim.Outcomes.AIR_OUT;
import static com.mlbgame.sim.Outcomes.COUNT;
import static com.mlbgame.sim.Outcomes.GROUND_OUT;
import static com.mlbgame.sim.Outcomes.HIT_BY_PITCH;
import static com.mlbgame.sim.Outcomes.HOME_RUN;
import static com.mlbgame.sim.Outcomes.STRIKEOUT;
import static com.mlbgame.sim.Outcomes.WALK;

/**
 * 능력치(20~80) → 타석 결과 확률 변환.
 * <p>
 * 결과별로 {@code 리그평균 × exp(Σ 계수 × z)} 를 구한 뒤 정규화한다. 모든 능력치가 50이면 리그 평균과 같다.
 * 비용이 있는 계산(exp)은 여기서만 하고, 결과는 선수 캐시에 넣어 경기마다 재사용한다.
 */
public final class RateModel {

    private final LeagueBaseline league;

    public RateModel(LeagueBaseline league) {
        this.league = league;
    }

    public LeagueBaseline league() {
        return league;
    }

    /** 20~80 능력치 → z 점수 (10점 = 1 표준편차). */
    public static double z(int rating) {
        return (rating - 50) / 10.0;
    }

    public SimBatter batter(long playerId, BatterRatings r, Hand bats, double performance) {
        double zs = z(r.speed());
        double zt = z(r.tendency());
        double[] zv = {z(r.contact()), z(r.power()), z(r.eye()), z(r.quality()), zt, zs};
        double[] w = weights(league.coefficients().batter(), zv, league.batterSpread(), 0.25 * zt);
        return new SimBatter(playerId, w, bats, zs, z(r.baserunning()), performance);
    }

    public SimPitcher pitcher(long playerId, PitcherRatings r, Hand throwsHand, double performance) {
        double zg = z(r.groundball());
        double[] zv = {z(r.stuff()), z(r.control()), z(r.suppression()), zg};
        double[] w = weights(league.coefficients().pitcher(), zv, league.pitcherSpread(), 0.25 * zg);
        double[] l = league.rates();
        for (int i = 0; i < COUNT; i++) {
            w[i] /= l[i];
        }
        return new SimPitcher(playerId, w, throwsHand, performance, pitchLimit(r.stamina()));
    }

    /**
     * 결과 0~6: 리그 × exp(배율 × 계수·z). 인플레이 아웃은 리그 합계를 유지한 채 땅볼/뜬공 비율만 groundTilt 로 기울인다
     * (아웃이 회귀 추정의 기준 결과이므로 아웃 총량은 능력치와 무관해야 계수가 그대로 맞는다).
     */
    private double[] weights(double[][] coef, double[] zv, Spread spread, double groundTilt) {
        double[] l = league.rates();
        double[] w = new double[COUNT];
        for (int i = 0; i < RateCoefficients.ROWS; i++) {
            double eta = 0;
            for (int k = 0; k < zv.length; k++) {
                eta += coef[i][k] * zv[k];
            }
            w[i] = l[i] * Math.exp(spreadFor(spread, i) * eta);
        }
        double out = l[GROUND_OUT] + l[AIR_OUT];
        double g = l[GROUND_OUT] * Math.exp(groundTilt);
        double a = l[AIR_OUT] * Math.exp(-groundTilt);
        w[GROUND_OUT] = out * g / (g + a);
        w[AIR_OUT] = out * a / (g + a);
        return normalize(w);
    }

    private static double spreadFor(Spread s, int outcome) {
        return switch (outcome) {
            case STRIKEOUT -> s.strikeout();
            case WALK, HIT_BY_PITCH -> s.walk();
            case HOME_RUN -> s.homeRun();
            default -> s.hit();
        };
    }

    /**
     * 스태미나 → 교체 기준 투구 수. 선발·불펜 구분 없이 한 공식 (불펜 투수를 선발로 쓰면 일찍 내려감).
     * 20 → 15구, 35 → 39구, 50 → 63구, 65 → 87구, 76 이상 → 105구 (현대 MLB 선발 관행상 상한)
     */
    public static int pitchLimit(int stamina) {
        return (int) Math.min(MAX_PITCHES, Math.round(15 + (stamina - 20) * 1.6));
    }

    public static final int MAX_PITCHES = 105;

    /** 수비 능력치 평균 → 인플레이 안타 배율. 50 = 1.0, 높을수록 안타 감소. */
    public static double defenseFactor(double avgDefenseRating) {
        return Math.exp(-0.08 * (avgDefenseRating - 50) / 10.0);
    }

    static double[] normalize(double[] w) {
        double sum = 0;
        for (double v : w) {
            if (!(v >= 0)) {
                throw new IllegalArgumentException("확률은 0 이상이어야 함");
            }
            sum += v;
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("확률 합이 0");
        }
        for (int i = 0; i < w.length; i++) {
            w[i] /= sum;
        }
        return w;
    }
}
