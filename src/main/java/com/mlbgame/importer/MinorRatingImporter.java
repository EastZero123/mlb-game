package com.mlbgame.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.league.LeagueBaselineRepository;
import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.Outcomes;
import com.mlbgame.sim.RateCoefficients;

/**
 * MLB 기록이 부족한 선수(마이너 선수, 40인 유망주)의 능력치. {@link RatingImporter} 다음에 실행한다.
 * <ol>
 *   <li>레벨 환산 계수: 같은 시즌 두 레벨(AAA·MLB, AA·AAA)을 함께 뛴 선수 짝으로 결과별 비율 추정</li>
 *   <li>최근 3시즌(3:2:1) 마이너 기록을 MLB 환산 기록으로 바꾸고 MLB 기록과 합침</li>
 *   <li>시뮬레이터 계수(mst_league.rate_coef)로 능력치를 역산 ({@link RatingInverter}). 사전평균 = 레벨 평균</li>
 *   <li>환산 기록도 부족하면 레벨 분포에서 무작위 배분 (선수 ID 시드, 재실행해도 같은 값)</li>
 * </ol>
 * MLB 기록이 충분한 선수(가중 150타석 / 150타자 이상)는 RatingImporter 결과(Statcast 포함)를 그대로 둔다.
 */
@Component
@Profile("import")
class MinorRatingImporter {

    private static final Logger log = LoggerFactory.getLogger(MinorRatingImporter.class);

    /** 이 이상 MLB 표본이 있으면 MLB 기반 능력치 유지 */
    private static final double KEEP_MLB_SAMPLE = 150;
    /** 환산 표본이 이보다 적으면 무작위 배분 */
    private static final double MIN_TRANSLATED = 60;
    /** 레벨 평균 추정에 쓰는 표본 기준 */
    private static final double PRIOR_SAMPLE = 300;
    private static final double PAIR_MIN = 30;
    private static final double PRIOR_SD = 1.0;
    private static final double FIRST_PASS_PRIOR = -1.0;

    // 타자 누적 인덱스
    private static final int B_PA = 0, B_SO = 1, B_BB = 2, B_HBP = 3, B_1B = 4, B_2B = 5, B_3B = 6, B_HR = 7,
            B_GO = 8, B_AO = 9, B_SB = 10, B_CS = 11, B_LEN = 12;
    // 투수 누적 인덱스
    private static final int P_BF = 0, P_SO = 1, P_BB = 2, P_HBP = 3, P_H = 4, P_HR = 5, P_GO = 6, P_AO = 7,
            P_G = 8, P_GS = 9, P_OUTS = 10, P_LEN = 11;
    private static final int[] BAT_OUTCOMES = {B_SO, B_BB, B_HBP, B_1B, B_2B, B_3B, B_HR};
    private static final int[] PIT_OUTCOMES = {P_SO, P_BB, P_HBP, P_H, P_HR};
    /**
     * 결과별 안정화 표본 (이만큼 뛰면 기록의 절반이 실력). 환산 계수 추정 시 아래 레벨 기록을 레벨 평균 쪽으로
     * 이만큼 당겨 "운 좋게 잘해서 콜업된" 선택 편향을 제거한다.
     */
    private static final double[] BAT_STAB = {60, 120, 300, 600, 1000, 1000, 170};
    private static final double[] PIT_STAB = {70, 170, 600, 800, 1300};
    /** 투수 결과 → RateCoefficients 행 (안타는 단타 행 = 2루타·3루타와 공유) */
    private static final int[] PIT_COEF_ROW = {0, 1, 2, 3, 6};
    /** 타자 추정 차원 (계수 열): 컨택, 파워, 선구, 타구질, 스피드. 땅볼성향(4)은 땅볼 비율로 고정 */
    private static final int[] BAT_DIMS = {0, 1, 2, 3, 5};
    private static final int BAT_TENDENCY = 4;
    /** 투수 추정 차원: 구위, 제구, 장타억제. 땅볼유도(3)는 땅볼 비율로 고정 */
    private static final int[] PIT_DIMS = {0, 1, 2};
    private static final int PIT_GROUNDBALL = 3;

    private static final String[] COLUMNS = {"player_id",
            "contact", "power", "eye", "quality", "bb_tendency", "speed", "baserunning",
            "control", "suppression", "groundball", "stamina",
            "ff_use", "ff_stuff", "si_use", "si_stuff", "fc_use", "fc_stuff", "sl_use", "sl_stuff",
            "st_use", "st_stuff", "cu_use", "cu_stuff", "ch_use", "ch_stuff", "fs_use", "fs_stuff",
            "def_c", "def_1b", "def_2b", "def_3b", "def_ss", "def_lf", "def_cf", "def_rf",
            "arm", "framing", "blocking", "durability", "potential", "sample_pa", "sample_bf", "source"};

    private static final int C_ID = 0, C_CONTACT = 1, C_CONTROL = 8, C_SUPPRESSION = 9, C_GROUNDBALL = 10,
            C_STAMINA = 11, C_FF = 12, C_SL = 18, C_CH = 24, C_DEF = 28, C_ARM = 36, C_DURABILITY = 39,
            C_SAMPLE_PA = 41, C_SAMPLE_BF = 42, C_SOURCE = 43;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LeagueBaselineRepository baselines;

    MinorRatingImporter(JdbcTemplate jdbc, TransactionTemplate tx, LeagueBaselineRepository baselines) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.baselines = baselines;
    }

    record Summary(int keptMlb, int translated, int random, String report) {
    }

    private record Player(int id, LocalDate birth, int pos, int level) {
    }

    /** 선수별 환산 표본 */
    private static final class Sample {
        final double[] t; // 환산 결과 횟수 (타자 7 / 투수 5)
        double n;         // 환산 표본 (타석 / 상대 타자)
        double mlbN;      // MLB 가중 표본
        int n2026;        // 2026 실제 표본 (MLB + 마이너)
        final double[] raw; // 레벨 무관 원 기록 합 (땅볼·뜬공·도루·등판)

        Sample(int outcomes, int rawLen) {
            t = new double[outcomes];
            raw = new double[rawLen];
        }
    }

    Summary importRatings(int season) {
        LeagueBaseline league = baselines.find(season).orElse(LeagueBaseline.mlbDefault());
        Map<Integer, Player> players = loadPlayers();
        Map<Integer, Map<Integer, double[]>> bat = loadBatting(season);
        Map<Integer, Map<Integer, double[]>> pit = loadPitching(season);

        // 1) 환산 계수
        double[][] batF = new double[3][];
        double[][] pitF = new double[3][];
        int[] batPairs = new int[3];
        int[] pitPairs = new int[3];
        batF[1] = factor(bat, 0, 1, season, BAT_OUTCOMES, BAT_STAB, B_PA, batPairs, 1);
        double[] batAaToAaa = factor(bat, 1, 2, season, BAT_OUTCOMES, BAT_STAB, B_PA, batPairs, 2);
        batF[2] = multiply(batAaToAaa, batF[1]);
        pitF[1] = factor(pit, 0, 1, season, PIT_OUTCOMES, PIT_STAB, P_BF, pitPairs, 1);
        double[] pitAaToAaa = factor(pit, 1, 2, season, PIT_OUTCOMES, PIT_STAB, P_BF, pitPairs, 2);
        pitF[2] = multiply(pitAaToAaa, pitF[1]);
        saveFactors(season, batF, pitF, batPairs, pitPairs);

        // 2) 환산 표본
        Map<Integer, Sample> batS = new HashMap<>();
        Map<Integer, Sample> pitS = new HashMap<>();
        for (Player p : players.values()) {
            if (p.pos() == 1) {
                pitS.put(p.id(), translate(pit.get(p.id()), pitF, season, PIT_OUTCOMES, P_BF, P_LEN));
            } else {
                batS.put(p.id(), translate(bat.get(p.id()), batF, season, BAT_OUTCOMES, B_PA, B_LEN));
            }
        }

        // 땅볼 성향·도루 표준화 (마이너 + MLB 원 기록 모집단)
        double[] batGo = population(batS, s -> share(s.raw[B_GO], s.raw[B_AO]), s -> s.raw[B_GO] + s.raw[B_AO], 100);
        double[] pitGo = population(pitS, s -> share(s.raw[P_GO], s.raw[P_AO]), s -> s.raw[P_GO] + s.raw[P_AO], 100);
        double[] sbAtt = population(batS, MinorRatingImporter::sbAttempt, MinorRatingImporter::sbOpp, 50);
        double[] sbPct = population(batS, s -> share(s.raw[B_SB], s.raw[B_CS]), s -> s.raw[B_SB] + s.raw[B_CS], 10);

        // 3) 역산: 1차(사전평균 -1) → 레벨 평균 → 2차
        RateCoefficients coef = league.coefficients();
        double[] l = league.rates();
        double[][] batBeta = columns(coef.batter(), BAT_DIMS, new int[] {0, 1, 2, 3, 4, 5, 6});
        double[][] pitBeta = columns(coef.pitcher(), PIT_DIMS, PIT_COEF_ROW);
        double out = l[Outcomes.GROUND_OUT] + l[Outcomes.AIR_OUT];
        double[] batBase0 = new double[7];
        for (int i = 0; i < 7; i++) {
            batBase0[i] = Math.log(l[i] / out);
        }
        double hits = l[Outcomes.SINGLE] + l[Outcomes.DOUBLE] + l[Outcomes.TRIPLE];
        double[] pitBase0 = {Math.log(l[Outcomes.STRIKEOUT] / out), Math.log(l[Outcomes.WALK] / out),
                Math.log(l[Outcomes.HIT_BY_PITCH] / out), Math.log(hits / out), Math.log(l[Outcomes.HOME_RUN] / out)};

        Map<Integer, double[]> batZ = new HashMap<>();
        Map<Integer, double[]> pitZ = new HashMap<>();
        double[][][] batPrior = levelPriors(BAT_DIMS.length);
        double[][][] pitPrior = levelPriors(PIT_DIMS.length);
        for (int pass = 0; pass < 2; pass++) {
            batZ.clear();
            pitZ.clear();
            for (Player p : players.values()) {
                int lv = priorLevel(p);
                if (p.pos() == 1) {
                    Sample s = pitS.get(p.id());
                    if (usesTranslation(s)) {
                        double zg = goZ(s.raw[P_GO], s.raw[P_AO], pitGo);
                        double[] base = withFixed(pitBase0, coef.pitcher(), PIT_COEF_ROW, PIT_GROUNDBALL, zg);
                        pitZ.put(p.id(), RatingInverter.map(s.t, Math.max(0, s.n - sum(s.t)), base, pitBeta,
                                pitPrior[lv][0], PRIOR_SD));
                    }
                } else {
                    Sample s = batS.get(p.id());
                    if (usesTranslation(s)) {
                        double zt = goZ(s.raw[B_GO], s.raw[B_AO], batGo);
                        double[] base = withFixed(batBase0, coef.batter(), new int[] {0, 1, 2, 3, 4, 5, 6},
                                BAT_TENDENCY, zt);
                        double[] prior = batPrior[lv][0].clone();
                        prior[4] += 0.7 * shrunkZ(sbAttempt(s), sbOpp(s), sbAtt, 60); // 스피드 사전값에 도루 정보
                        batZ.put(p.id(), RatingInverter.map(s.t, Math.max(0, s.n - sum(s.t)), base, batBeta,
                                prior, PRIOR_SD));
                    }
                }
            }
            if (pass == 0) {
                batPrior = levelStats(players, batS, batZ, BAT_DIMS.length);
                pitPrior = levelStats(players, pitS, pitZ, PIT_DIMS.length);
            }
        }

        // 4) 행 구성
        List<Object[]> rows = new ArrayList<>();
        int kept = 0;
        int translated = 0;
        int random = 0;
        for (Player p : players.values()) {
            boolean pitcher = p.pos() == 1;
            Sample s = pitcher ? pitS.get(p.id()) : batS.get(p.id());
            if (keepsMlb(s)) {
                kept++;
                continue;
            }
            SplittableRandom rng = new SplittableRandom(p.id() * 0x9E3779B97F4A7C15L);
            int lv = priorLevel(p);
            double[] z = pitcher ? pitZ.get(p.id()) : batZ.get(p.id());
            boolean real = z != null;
            if (!real) {
                z = draw(pitcher ? pitPrior[lv] : batPrior[lv], rng);
            }

            Object[] r = new Object[COLUMNS.length];
            r[C_ID] = p.id();
            if (pitcher) {
                int stuff = rating(z[0]);
                r[C_CONTROL] = rating(z[1]);
                r[C_SUPPRESSION] = rating(z[2]);
                r[C_GROUNDBALL] = rating(real ? goZ(s.raw[P_GO], s.raw[P_AO], pitGo) : rng.nextGaussian());
                r[C_STAMINA] = s.raw[P_G] > 0 ? RatingImporter.stamina(s.raw[P_G], s.raw[P_GS], s.raw[P_OUTS])
                        : (rng.nextBoolean() ? 55 + rng.nextInt(11) : 30 + rng.nextInt(11));
                // 구종 데이터 없음: 포심 55 / 슬라이더 30 / 체인지업 15, 구위는 공통
                r[C_FF] = 55;
                r[C_FF + 1] = stuff;
                r[C_SL] = 30;
                r[C_SL + 1] = stuff;
                r[C_CH] = 15;
                r[C_CH + 1] = stuff;
                r[C_SAMPLE_PA] = 0;
                r[C_SAMPLE_BF] = s.n2026;
            } else {
                double zt = real ? goZ(s.raw[B_GO], s.raw[B_AO], batGo) : rng.nextGaussian();
                double zPct = real ? shrunkZ(share(s.raw[B_SB], s.raw[B_CS]), s.raw[B_SB] + s.raw[B_CS], sbPct, 20)
                        : 0;
                r[C_CONTACT] = rating(z[0]);
                r[C_CONTACT + 1] = rating(z[1]); // power
                r[C_CONTACT + 2] = rating(z[2]); // eye
                r[C_CONTACT + 3] = rating(z[3]); // quality
                r[C_CONTACT + 4] = rating(zt);   // bb_tendency
                r[C_CONTACT + 5] = rating(z[4]); // speed
                r[C_CONTACT + 6] = rating(0.7 * z[4] + 0.3 * zPct); // baserunning
                Integer[] def = RatingImporter.deriveDefense(p.pos(), (int) Math.round(45 + 5 * rng.nextGaussian()));
                for (int i = 0; i < 8; i++) {
                    r[C_DEF + i] = def[i];
                }
                r[C_ARM] = Composite.clampRating((int) Math.round(45 + 6 * rng.nextGaussian()));
                r[C_ARM + 1] = p.pos() == 2 ? 45 : null; // framing
                r[C_ARM + 2] = p.pos() == 2 ? 45 : null; // blocking
                r[C_SAMPLE_PA] = s.n2026;
                r[C_SAMPLE_BF] = 0;
            }
            r[C_DURABILITY] = 50;
            r[C_DURABILITY + 1] = RatingImporter.potential(p.birth(), season);
            r[C_SOURCE] = real ? 1 : 2;
            if (real) {
                translated++;
            } else {
                random++;
            }
            rows.add(r);
        }

        String placeholders = String.join(", ", java.util.Collections.nCopies(COLUMNS.length, "?"));
        StringBuilder update = new StringBuilder();
        for (int i = 1; i < COLUMNS.length; i++) {
            update.append(i > 1 ? ", " : "").append(COLUMNS[i]).append(" = n.").append(COLUMNS[i]);
        }
        String sql = "INSERT INTO mst_player_rating (" + String.join(", ", COLUMNS) + ") VALUES (" + placeholders
                + ") AS n ON DUPLICATE KEY UPDATE " + update;
        tx.executeWithoutResult(st -> jdbc.batchUpdate(sql, rows));

        String report = String.format("""
                환산 계수 (MLB ÷ 마이너, 결과별)          K     BB    HBP   1B    2B    3B    HR   (짝)
                  AAA→MLB 타자   %s (%d)
                  AA →MLB 타자   %s (%d, AA→AAA %d)
                  AAA→MLB 투수   %s (%d)
                  AA →MLB 투수   %s (%d, AA→AAA %d)
                레벨 평균 z (사전값)  타자 AAA %s / AA %s,  투수 AAA %s / AA %s""",
                fmt(batF[1]), batPairs[1], fmt(batF[2]), batPairs[1], batPairs[2],
                fmt(pitToBat(pitF[1])), pitPairs[1], fmt(pitToBat(pitF[2])), pitPairs[1], pitPairs[2],
                fmt(batPrior[1][0]), fmt(batPrior[2][0]), fmt(pitPrior[1][0]), fmt(pitPrior[2][0]));
        log.info("마이너 능력치: MLB 기반 유지 {}명, 마이너 환산 {}명, 무작위 {}명", kept, translated, random);
        return new Summary(kept, translated, random, report);
    }

    // ------------------------------------------------------------------ 환산

    /**
     * 위 레벨 ÷ 아래 레벨 비율. 같은 선수·같은 시즌에 두 레벨 모두 30 이상 뛴 짝만, 두 표본의 조화평균으로 가중.
     * (실력이 같은 사람이 레벨만 바뀌었을 때의 기록 변화)
     * <p>
     * 아래 레벨 기록은 레벨 평균 쪽으로 회귀시킨 값을 쓴다: 콜업은 아래 레벨 성적(실력 + 운)으로 결정되므로,
     * 원 기록으로 나누면 운만큼 위 레벨 성적이 떨어져 보여 환산이 과하게 가혹해진다.
     */
    private static double[] factor(Map<Integer, Map<Integer, double[]>> data, int upper, int lower, int season,
                                   int[] outcomes, double[] stab, int nIdx, int[] pairsOut, int slot) {
        double[] mean = new double[outcomes.length];
        double meanN = 0;
        for (Map<Integer, double[]> byKey : data.values()) {
            for (int s = season - 2; s <= season; s++) {
                double[] w = byKey.get(key(s, lower));
                if (w != null) {
                    meanN += w[nIdx];
                    for (int i = 0; i < outcomes.length; i++) {
                        mean[i] += w[outcomes[i]];
                    }
                }
            }
        }
        for (int i = 0; i < outcomes.length; i++) {
            mean[i] = meanN > 0 ? mean[i] / meanN : 0;
        }

        double[] num = new double[outcomes.length];
        double[] den = new double[outcomes.length];
        int pairs = 0;
        for (Map<Integer, double[]> byKey : data.values()) {
            for (int s = season - 2; s <= season; s++) {
                double[] u = byKey.get(key(s, upper));
                double[] w = byKey.get(key(s, lower));
                if (u == null || w == null || u[nIdx] < PAIR_MIN || w[nIdx] < PAIR_MIN) {
                    continue;
                }
                double h = 2 / (1 / u[nIdx] + 1 / w[nIdx]);
                for (int i = 0; i < outcomes.length; i++) {
                    num[i] += h * u[outcomes[i]] / u[nIdx];
                    den[i] += h * (w[outcomes[i]] + stab[i] * mean[i]) / (w[nIdx] + stab[i]);
                }
                pairs++;
            }
        }
        double[] f = new double[outcomes.length];
        for (int i = 0; i < f.length; i++) {
            f[i] = den[i] > 0 && num[i] > 0 ? Math.clamp(num[i] / den[i], 0.3, 3.0) : 1.0;
        }
        pairsOut[slot] = pairs;
        return f;
    }

    /** 최근 3시즌 (3:2:1) 기록을 MLB 환산 횟수로 합산 */
    private static Sample translate(Map<Integer, double[]> byKey, double[][] f, int season, int[] outcomes,
                                    int nIdx, int len) {
        Sample s = new Sample(outcomes.length, len);
        if (byKey == null) {
            return s;
        }
        for (int yr = season - 2; yr <= season; yr++) {
            double w = (3 - (season - yr)) / 3.0;
            for (int level = 0; level <= 2; level++) {
                double[] x = byKey.get(key(yr, level));
                if (x == null) {
                    continue;
                }
                s.n += w * x[nIdx];
                for (int i = 0; i < outcomes.length; i++) {
                    s.t[i] += w * x[outcomes[i]] * (level == 0 ? 1 : f[level][i]);
                }
                if (level == 0) {
                    s.mlbN += w * x[nIdx];
                }
                if (yr == season) {
                    s.n2026 += (int) x[nIdx];
                }
                for (int i = 0; i < len; i++) {
                    if (yr >= season - 1) {
                        s.raw[i] += x[i];
                    }
                }
            }
        }
        return s;
    }

    private static boolean keepsMlb(Sample s) {
        return s.mlbN >= KEEP_MLB_SAMPLE || (s.n < MIN_TRANSLATED && s.mlbN > 0);
    }

    private static boolean usesTranslation(Sample s) {
        return !keepsMlb(s) && s.n >= MIN_TRANSLATED;
    }

    // ------------------------------------------------------------------ 사전값 (레벨 평균)

    /** 0=MLB(표본 부족 유망주 → AAA 기준), 1=AAA, 2=AA, NULL → AA */
    private static int priorLevel(Player p) {
        return p.level() == 2 || p.level() < 0 ? 2 : 1;
    }

    /** [레벨][0=평균, 1=표준편차][차원] */
    private static double[][][] levelPriors(int dims) {
        double[][][] out = new double[3][2][dims];
        for (int lv = 0; lv < 3; lv++) {
            java.util.Arrays.fill(out[lv][0], FIRST_PASS_PRIOR);
            java.util.Arrays.fill(out[lv][1], 0.8);
        }
        return out;
    }

    /** 표본이 큰 선수들의 역산 z 로 레벨별 평균·표준편차 */
    private static double[][][] levelStats(Map<Integer, Player> players, Map<Integer, Sample> samples,
                                           Map<Integer, double[]> zs, int dims) {
        double[][][] out = levelPriors(dims);
        for (int lv = 1; lv <= 2; lv++) {
            List<double[]> list = new ArrayList<>();
            for (Map.Entry<Integer, double[]> e : zs.entrySet()) {
                Player p = players.get(e.getKey());
                if (priorLevel(p) == lv && samples.get(e.getKey()).n >= PRIOR_SAMPLE) {
                    list.add(e.getValue());
                }
            }
            if (list.size() < 20) {
                continue;
            }
            for (int d = 0; d < dims; d++) {
                double m = 0;
                for (double[] z : list) {
                    m += z[d];
                }
                m /= list.size();
                double v = 0;
                for (double[] z : list) {
                    v += (z[d] - m) * (z[d] - m);
                }
                out[lv][0][d] = m;
                out[lv][1][d] = Math.clamp(Math.sqrt(v / list.size()), 0.5, 1.2);
            }
        }
        out[0] = out[1];
        return out;
    }

    private static double[] draw(double[][] prior, SplittableRandom rng) {
        double[] z = new double[prior[0].length];
        for (int d = 0; d < z.length; d++) {
            z[d] = prior[0][d] + prior[1][d] * rng.nextGaussian();
        }
        return z;
    }

    // ------------------------------------------------------------------ 땅볼·도루

    /** 모집단 {평균, 표준편차} (표본 minN 이상) */
    private static double[] population(Map<Integer, Sample> samples,
                                       java.util.function.ToDoubleFunction<Sample> value,
                                       java.util.function.ToDoubleFunction<Sample> n, double minN) {
        List<Double> v = new ArrayList<>();
        for (Sample s : samples.values()) {
            if (n.applyAsDouble(s) >= minN) {
                v.add(value.applyAsDouble(s));
            }
        }
        if (v.size() < 20) {
            return new double[] {0, 1};
        }
        double m = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sq = v.stream().mapToDouble(x -> (x - m) * (x - m)).average().orElse(1);
        return new double[] {m, Math.max(1e-6, Math.sqrt(sq))};
    }

    private static double shrunkZ(double value, double n, double[] pop, double k) {
        if (n <= 0) {
            return 0;
        }
        return (value - pop[0]) / pop[1] * n / (n + k);
    }

    private static double goZ(double go, double ao, double[] pop) {
        return shrunkZ(share(go, ao), go + ao, pop, 80);
    }

    private static double share(double a, double b) {
        return a + b > 0 ? a / (a + b) : 0;
    }

    private static double sbOpp(Sample s) {
        return s.raw[B_1B] + s.raw[B_BB] + s.raw[B_HBP];
    }

    private static double sbAttempt(Sample s) {
        double opp = sbOpp(s);
        return opp > 0 ? (s.raw[B_SB] + s.raw[B_CS]) / opp : 0;
    }

    // ------------------------------------------------------------------ 헬퍼

    /** 고정 차원(땅볼 성향)의 기여를 기본 로그 배율에 더한다 */
    private static double[] withFixed(double[] base0, double[][] coef, int[] rows, int fixedCol, double z) {
        double[] b = base0.clone();
        for (int i = 0; i < rows.length; i++) {
            b[i] += coef[rows[i]][fixedCol] * z;
        }
        return b;
    }

    private static double[][] columns(double[][] coef, int[] cols, int[] rows) {
        double[][] out = new double[rows.length][cols.length];
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < cols.length; j++) {
                out[i][j] = coef[rows[i]][cols[j]];
            }
        }
        return out;
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] c = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] * b[i];
        }
        return c;
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s;
    }

    private static int rating(double z) {
        return Composite.toRating(z);
    }

    private static int key(int season, int level) {
        return season * 10 + level;
    }

    /** 투수 계수 5개(K, BB, HBP, 안타, HR) → 타자 표 형식 7개 */
    private static double[] pitToBat(double[] f) {
        return new double[] {f[0], f[1], f[2], f[3], f[3], f[3], f[4]};
    }

    private static String fmt(double[] a) {
        StringBuilder b = new StringBuilder();
        for (double v : a) {
            b.append(String.format(" %+.2f", v));
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ 저장 / 로드

    private void saveFactors(int season, double[][] batF, double[][] pitF, int[] batPairs, int[] pitPairs) {
        List<Object[]> rows = new ArrayList<>();
        for (int lv = 1; lv <= 2; lv++) {
            double[] b = batF[lv];
            double[] p = pitToBat(pitF[lv]);
            rows.add(new Object[] {season, lv, "B", b[0], b[1], b[2], b[3], b[4], b[5], b[6], batPairs[lv]});
            rows.add(new Object[] {season, lv, "P", p[0], p[1], p[2], p[3], p[4], p[5], p[6], pitPairs[lv]});
        }
        tx.executeWithoutResult(st -> jdbc.batchUpdate("""
                INSERT INTO mst_level_factor (season, level, side, f_so, f_bb, f_hbp, f_1b, f_2b, f_3b, f_hr, pairs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE f_so = n.f_so, f_bb = n.f_bb, f_hbp = n.f_hbp, f_1b = n.f_1b,
                    f_2b = n.f_2b, f_3b = n.f_3b, f_hr = n.f_hr, pairs = n.pairs
                """, rows));
    }

    private Map<Integer, Player> loadPlayers() {
        Map<Integer, Player> out = new HashMap<>();
        jdbc.query("SELECT player_id, birth_date, primary_pos, level FROM mst_player", rs -> {
            int level = rs.getInt(4);
            out.put(rs.getInt(1), new Player(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getInt(3),
                    rs.wasNull() ? -1 : level));
        });
        return out;
    }

    /** 선수 → (시즌×10 + 레벨) → 누적 (B_ 인덱스). 레벨 0 = MLB */
    private Map<Integer, Map<Integer, double[]>> loadBatting(int season) {
        Map<Integer, Map<Integer, double[]>> out = new HashMap<>();
        jdbc.query("""
                SELECT player_id, season, 0, pa, so, bb, hbp, h, b2, b3, hr, 0, 0, sb, cs
                FROM mst_bat_season WHERE season BETWEEN ? AND ?
                UNION ALL
                SELECT player_id, season, level, pa, so, bb, hbp, h, b2, b3, hr, go, ao, sb, cs
                FROM mst_bat_season_minor WHERE season BETWEEN ? AND ?
                """, rs -> {
            double[] a = out.computeIfAbsent(rs.getInt(1), k -> new HashMap<>())
                    .computeIfAbsent(key(rs.getInt(2), rs.getInt(3)), k -> new double[B_LEN]);
            double h = rs.getDouble(8), b2 = rs.getDouble(9), b3 = rs.getDouble(10), hr = rs.getDouble(11);
            a[B_PA] += rs.getDouble(4);
            a[B_SO] += rs.getDouble(5);
            a[B_BB] += rs.getDouble(6);
            a[B_HBP] += rs.getDouble(7);
            a[B_1B] += h - b2 - b3 - hr;
            a[B_2B] += b2;
            a[B_3B] += b3;
            a[B_HR] += hr;
            a[B_GO] += rs.getDouble(12);
            a[B_AO] += rs.getDouble(13);
            a[B_SB] += rs.getDouble(14);
            a[B_CS] += rs.getDouble(15);
        }, season - 2, season, season - 2, season);
        return out;
    }

    /** 상대 타자 수는 MLB·마이너 모두 아웃 + 피안타 + 볼넷 + 사구 근사 (환산 계수가 같은 정의로 계산되도록) */
    private Map<Integer, Map<Integer, double[]>> loadPitching(int season) {
        Map<Integer, Map<Integer, double[]>> out = new HashMap<>();
        jdbc.query("""
                SELECT player_id, season, 0, g, gs, outs, h, bb, so, hr, hbp, 0, 0
                FROM mst_pit_season WHERE season BETWEEN ? AND ?
                UNION ALL
                SELECT player_id, season, level, g, gs, outs, h, bb, so, hr, hbp, go, ao
                FROM mst_pit_season_minor WHERE season BETWEEN ? AND ?
                """, rs -> {
            double[] a = out.computeIfAbsent(rs.getInt(1), k -> new HashMap<>())
                    .computeIfAbsent(key(rs.getInt(2), rs.getInt(3)), k -> new double[P_LEN]);
            double outs = rs.getDouble(6), h = rs.getDouble(7), bb = rs.getDouble(8), hr = rs.getDouble(10);
            double hbp = rs.getDouble(11);
            a[P_BF] += outs + h + bb + hbp;
            a[P_SO] += rs.getDouble(9);
            a[P_BB] += bb;
            a[P_HBP] += hbp;
            a[P_H] += h - hr;
            a[P_HR] += hr;
            a[P_GO] += rs.getDouble(12);
            a[P_AO] += rs.getDouble(13);
            a[P_G] += rs.getDouble(4);
            a[P_GS] += rs.getDouble(5);
            a[P_OUTS] += outs;
        }, season - 2, season, season - 2, season);
        return out;
    }
}
