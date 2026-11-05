package com.mlbgame.offseason;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 구단 예산 = 리그 시장 규모 × 구단 점유율. 매 오프시즌 다음 시즌 예산을 정한다.
 * <pre>
 * 리그 시장 규모 L (= 30구단 예산 합)   매년 +2~6% (커리어 seed + 시즌으로 결정, 하락 없음)
 * 구단 비율 r (리그 평균 = 1.0)          하한 0.40, 상한 2.50  (실제 개막 연봉: 최하 약 0.4배, 최고 약 2.2배)
 * 기준 비율 base                         시장 규모 지수(연고지·매출 규모)와 커리어 시작 예산 비율의 평균. 처음 계산 때 저장
 *
 * 성적: 승패 차 1승당 +0.3% (이길 때) / -0.4% (질 때, 리빌딩 감축이 더 빠름)
 *       포스트시즌 진출 +2%, 시리즈 승리 WC +1%, DS +1.5%, CS +2%, WS +2.5% (우승팀 최대 +9%)
 *
 * 구간 (사치세 기준선 약 1.5배를 경계로)   플러스 요인   회귀 (매년)
 *   ~ 1.5   (사치세 아래)                  ×1.0          기준 비율 쪽 10%
 *   1.5 ~ 1.9 (사치세 1~3단계)              ×0.6          min(기준, 1.5) 쪽 20%
 *   1.9 ~ 2.5 (최고 단계)                   ×0.3          min(기준, 1.5) 쪽 30%
 * 마이너스 요인은 구간과 관계없이 그대로. 적용 → 합 30 으로 맞춤 → 상·하한 자르기를 반복.
 * </pre>
 * 결과 (대략): 평범한 빅마켓 1.5배 근처, 빅마켓 왕조 1.9배 안팎, 스몰마켓 왕조 1.5배까지, 평범한 스몰마켓 0.6~0.8배.
 */
public final class LeagueBudget {

    static final double GROWTH_MIN = 0.02, GROWTH_MAX = 0.06;
    static final double FLOOR = 0.40, CAP = 2.50;
    static final double WIN_UP = 0.003, WIN_DOWN = 0.004;
    static final double BERTH = 0.02;
    /** 라운드별 시리즈 승리 보너스 (1 WC, 2 DS, 3 CS, 4 WS) */
    static final double[] SERIES_WIN = {0, 0.01, 0.015, 0.02, 0.025};
    /** 구간 경계·플러스 배율·회귀 강도 */
    static final double TAX_LINE = 1.5, TOP_LINE = 1.9;
    static final double[] GAIN = {1.0, 0.6, 0.3};
    static final double[] PULL = {0.10, 0.20, 0.30};

    /** 시장 규모 지수 (연고지 인구·구단 매출 규모 근사, 평균 약 1.0). 없는 구단은 1.0 */
    static final Map<Integer, Double> MARKET = Map.ofEntries(
            Map.entry(147, 1.75), Map.entry(119, 1.75), Map.entry(121, 1.45), Map.entry(111, 1.40),
            Map.entry(112, 1.35), Map.entry(137, 1.25), Map.entry(143, 1.25), Map.entry(117, 1.15),
            Map.entry(144, 1.15), Map.entry(108, 1.10), Map.entry(140, 1.10), Map.entry(141, 1.05),
            Map.entry(138, 1.00), Map.entry(136, 1.00), Map.entry(135, 1.00), Map.entry(120, 0.95),
            Map.entry(110, 0.90), Map.entry(109, 0.85), Map.entry(116, 0.85), Map.entry(145, 0.85),
            Map.entry(142, 0.85), Map.entry(115, 0.80), Map.entry(158, 0.80), Map.entry(113, 0.75),
            Map.entry(114, 0.75), Map.entry(118, 0.75), Map.entry(134, 0.70), Map.entry(139, 0.65),
            Map.entry(146, 0.65), Map.entry(133, 0.65));

    /**
     * @param market     이번 시즌 리그 시장 규모 (천 달러)
     * @param nextMarket 다음 시즌
     * @param next       다음 시즌 구단 예산 (천 달러)
     * @param ratio      다음 시즌 리그 평균 대비 비율
     * @param base       기준 비율 (처음 계산이면 새로 정한 값)
     */
    record Result(long market, long nextMarket, double growth, Map<Integer, Integer> next,
                  Map<Integer, Double> ratio, Map<Integer, Double> base) {

        /** 다음 시즌 리그 연봉 총액 목표 (예산의 1/1.1 = 커리어 생성 때 예산 여유와 같은 비율) */
        double payrollTarget() {
            return nextMarket / 1.10;
        }
    }

    /** 구단 1곳의 시즌 결과 */
    /** @param lastRoundWon 이긴 마지막 시리즈 라운드 (0 = 없음). 와일드카드 면제 시드는 WC 승리로 친다 */
    record Season(int wins, int losses, boolean playoff, int lastRoundWon) {
    }

    private LeagueBudget() {
    }

    /** 구간 0 / 1 / 2 */
    static int tier(double r) {
        return r < TAX_LINE ? 0 : r < TOP_LINE ? 1 : 2;
    }

    static double growth(long seed, int season) {
        SplittableRandom rng = OffseasonRules.rng(seed, season, 12, 0);
        return GROWTH_MIN + (GROWTH_MAX - GROWTH_MIN) * rng.nextDouble();
    }

    /**
     * @param budgets 이번 시즌 예산
     * @param seasons 이번 시즌 성적 (없는 구단은 .500 · 포스트시즌 없음)
     * @param base    저장된 기준 비율 (없으면 시장 지수와 지금 비율로 정함)
     */
    static Result compute(Map<Integer, Integer> budgets, Map<Integer, Season> seasons, Map<Integer, Double> base,
                          long seed, int season) {
        int n = budgets.size();
        long market = 0;
        for (int b : budgets.values()) {
            market += b;
        }
        double avg = market / (double) Math.max(1, n);
        Map<Integer, Double> baseOut = new HashMap<>();
        Map<Integer, Double> r = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : budgets.entrySet()) {
            int team = e.getKey();
            double now = avg > 0 ? e.getValue() / avg : 1.0;
            Double b = base.get(team);
            if (b == null) {
                b = Math.clamp(0.5 * MARKET.getOrDefault(team, 1.0) + 0.5 * now, FLOOR, 2.2);
            }
            baseOut.put(team, b);

            Season s = seasons.getOrDefault(team, new Season(81, 81, false, 0));
            double diff = (s.wins() - s.losses()) / 2.0;
            double plus = diff > 0 ? diff * WIN_UP : 0;
            double minus = diff < 0 ? diff * WIN_DOWN : 0;
            if (s.playoff()) {
                plus += BERTH;
            }
            for (int round = 1; round <= s.lastRoundWon(); round++) {
                plus += SERIES_WIN[round];
            }
            int tier = tier(now);
            double moved = now * (1 + GAIN[tier] * plus + minus);
            double target = tier == 0 ? b : Math.min(b, TAX_LINE);
            r.put(team, moved + PULL[tier] * (target - moved));
        }
        // 합 = 구단 수 (평균 1.0) 로 맞추고 상·하한: 자르면 합이 바뀌므로 몇 번 반복
        for (int it = 0; it < 20; it++) {
            double sum = r.values().stream().mapToDouble(Double::doubleValue).sum();
            double k = n / sum;
            boolean clipped = false;
            for (Map.Entry<Integer, Double> e : r.entrySet()) {
                double v = e.getValue() * k;
                double c = Math.clamp(v, FLOOR, CAP);
                clipped |= c != v;
                e.setValue(c);
            }
            if (!clipped && Math.abs(k - 1) < 1e-9) {
                break;
            }
        }
        double g = growth(seed, season);
        long nextMarket = Math.round(market * (1 + g));
        double nextAvg = nextMarket / (double) Math.max(1, n);
        Map<Integer, Integer> next = new HashMap<>();
        for (Map.Entry<Integer, Double> e : r.entrySet()) {
            next.put(e.getKey(), (int) Math.round(nextAvg * e.getValue()));
        }
        return new Result(market, nextMarket, g, next, r, baseOut);
    }

    /** 다음 시즌 구단 예산 (오프시즌 트레이드 예산 확인용, 천 달러) */
    public static Map<Integer, Integer> nextBudgets(JdbcTemplate jdbc, long careerId, int season, long seed) {
        return load(jdbc, careerId, season, seed).next();
    }

    /** DB 에서 이번 시즌 예산·성적·포스트시즌·기준 비율을 읽어 계산 (오프시즌 단계마다 같은 결과) */
    static Result load(JdbcTemplate jdbc, long careerId, int season, long seed) {
        Map<Integer, Integer> budgets = new HashMap<>();
        Map<Integer, int[]> record = new HashMap<>();
        Map<Integer, Double> base = new HashMap<>();
        jdbc.query("SELECT team_id, budget, wins, losses, base_share FROM c_team WHERE career_id = ?", rs -> {
            int team = rs.getInt(1);
            budgets.put(team, rs.getInt(2));
            record.put(team, new int[] {rs.getInt(3), rs.getInt(4)});
            double b = rs.getDouble(5);
            if (!rs.wasNull()) {
                base.put(team, b);
            }
        }, careerId);
        Map<Integer, Integer> lastWon = new HashMap<>();
        java.util.Set<Integer> playoff = new java.util.HashSet<>();
        jdbc.query("SELECT round, high_team, low_team, winner FROM c_series WHERE career_id = ? AND season = ?",
                rs -> {
                    playoff.add(rs.getInt(2));
                    playoff.add(rs.getInt(3));
                    int winner = rs.getInt(4);
                    if (!rs.wasNull() && winner != 0) {
                        lastWon.merge(winner, rs.getInt(1), Math::max);
                    }
                }, careerId, season);
        Map<Integer, Season> seasons = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : record.entrySet()) {
            int team = e.getKey();
            seasons.put(team, new Season(e.getValue()[0], e.getValue()[1], playoff.contains(team),
                    lastWon.getOrDefault(team, 0)));
        }
        return compute(budgets, seasons, base, seed, season);
    }

    /** "$5.21B" */
    static String billions(long thousands) {
        return String.format("$%.2fB", thousands / 1_000_000.0);
    }

    static String tierName(double r) {
        return switch (tier(r)) {
            case 0 -> "사치세 아래";
            case 1 -> "사치세 구간";
            default -> "최고 구간";
        };
    }
}
