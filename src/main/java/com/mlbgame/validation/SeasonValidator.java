package com.mlbgame.validation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mlbgame.league.LeagueBaselineRepository;
import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.sim.BatStat;
import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.GameSimulator;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.Outcomes;
import com.mlbgame.sim.PitcherLine;
import com.mlbgame.sim.PitcherRatings;
import com.mlbgame.sim.RateCoefficients;
import com.mlbgame.sim.RateModel;
import com.mlbgame.sim.SimTeam;
import com.mlbgame.sim.Spread;

/**
 * 능력치 + 실제 개막 로스터 + 실제 일정으로 시즌을 반복 시뮬레이션해 실제 결과와 비교한다.
 * <ol>
 *   <li>보정 (반복): 리그 기준값 · 능력치 효과 배율 · 실책/폭투 배율을 함께 조정 → mst_league 저장
 *     <ul>
 *       <li>기준값: 선수마다 "실제 출전량 × 시뮬 비율" 합이 실제 기록 합과 같도록.
 *           리그 전체 평균에 맞추면 벤치·콜업 선수가 섞인 평균에 주전을 맞추게 되어 주전이 일괄 약해진다.</li>
 *       <li>효과 배율: 실제 기록을 시뮬 기록에 회귀한 기울기가 1 (능력치 차이를 과장도 축소도 없이 예측).</li>
 *       <li>실책 → 비자책 비율, 폭투·포일 → 득점 효율(득점 ÷ BaseRuns 기대 득점)을 실제와 같게.</li>
 *     </ul>
 *   </li>
 *   <li>검증: 보정된 값으로 N시즌 시뮬 → 리그/팀/선수 비교 리포트</li>
 * </ol>
 * <pre>
 * ./gradlew bootRun --args="--spring.profiles.active=local,validate --spring.main.web-application-type=none"
 * </pre>
 * 한계: 실제 시즌의 부상·트레이드·콜업·휴식·구장 효과는 반영되지 않음 (개막 로스터 고정).
 */
@Component
@Profile("validate")
class SeasonValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeasonValidator.class);
    private static final String[] PITCH = {"ff", "si", "fc", "sl", "st", "cu", "ch", "fs"};
    private static final String[] DEF = {"c", "1b", "2b", "3b", "ss", "lf", "cf", "rf"};
    private static final String[] OUTCOME_NAMES = {"삼진", "볼넷", "사구", "단타", "2루타", "3루타", "홈런", "인플레이 아웃"};
    private static final String[] SPREAD_NAMES = {"삼진", "볼넷", "홈런", "안타"};

    // 타자 누적 인덱스
    private static final int B_PA = 0, B_AB = 1, B_H = 2, B_2B = 3, B_3B = 4, B_HR = 5, B_BB = 6, B_HBP = 7,
            B_SF = 8, B_SO = 9, B_LEN = 10;
    // 투수 누적 인덱스
    private static final int P_OUTS = 0, P_ER = 1, P_SO = 2, P_BB = 3, P_BF = 4, P_H = 5, P_HR = 6, P_HBP = 7,
            P_R = 8, P_LEN = 9;

    /** 기울기 보정·리포트 대상: 시뮬 주전(시즌당 200타석 / 상대 타자 100명 이상) 중 실제 100타석(타자) 이상 */
    private static final long REGULAR_PA_PER_SEASON = 200;
    private static final long REGULAR_BF_PER_SEASON = 100;
    private static final int MIN_ACTUAL_PA = 100;
    private static final int MIN_ACTUAL_BF = 100;
    /** 계수 회귀 대상 최소 표본 (타석 / 상대 타자) 과 릿지 벌점 */
    private static final int FIT_MIN_SAMPLE = 20;
    private static final double FIT_RIDGE = 1.0;
    /** 폭투 배율 1회 조정 이득. 득점 중 폭투 비중이 작아 크게 움직여야 효율이 따라온다 */
    private static final double WP_GAIN = 20;

    private final JdbcTemplate jdbc;
    private final LeagueBaselineRepository baselines;
    private final ConfigurableApplicationContext context;
    private final int season;
    private final int seasons;
    private final boolean calibrate;
    private final int calibrationIterations;
    private final int calibrationSeasons;

    SeasonValidator(JdbcTemplate jdbc, LeagueBaselineRepository baselines, ConfigurableApplicationContext context,
                    @Value("${validate.season:2026}") int season,
                    @Value("${validate.seasons:20}") int seasons,
                    @Value("${validate.calibrate:true}") boolean calibrate,
                    @Value("${validate.calibration-iterations:10}") int calibrationIterations,
                    @Value("${validate.calibration-seasons:5}") int calibrationSeasons) {
        this.jdbc = jdbc;
        this.baselines = baselines;
        this.context = context;
        this.season = season;
        this.seasons = seasons;
        this.calibrate = calibrate;
        this.calibrationIterations = calibrationIterations;
        this.calibrationSeasons = calibrationSeasons;
    }

    // ------------------------------------------------------------------ 누적 구조

    private static class Acc {
        final Map<Integer, long[]> wins = new HashMap<>();
        final Map<Integer, long[]> bat = new HashMap<>();
        final Map<Integer, long[]> pit = new HashMap<>();
        long runs;
        long games;

        void merge(Acc o) {
            o.wins.forEach((k, v) -> add(wins, k, v));
            o.bat.forEach((k, v) -> add(bat, k, v));
            o.pit.forEach((k, v) -> add(pit, k, v));
            runs += o.runs;
            games += o.games;
        }

        private static void add(Map<Integer, long[]> m, int k, long[] v) {
            long[] a = m.computeIfAbsent(k, x -> new long[v.length]);
            for (int i = 0; i < v.length; i++) {
                a[i] += v[i];
            }
        }

        long[] batTotals() {
            long[] t = new long[B_LEN];
            for (long[] a : bat.values()) {
                for (int i = 0; i < B_LEN; i++) {
                    t[i] += a[i];
                }
            }
            return t;
        }

        /** {삼진, 볼넷, 사구, 단타, 2루타, 3루타, 홈런, 인플레이 아웃} / 타석 */
        double[] rates() {
            long[] t = batTotals();
            double pa = t[B_PA];
            double singles = t[B_H] - t[B_2B] - t[B_3B] - t[B_HR];
            double outs = pa - t[B_SO] - t[B_BB] - t[B_HBP] - t[B_H];
            return new double[] {t[B_SO] / pa, t[B_BB] / pa, t[B_HBP] / pa, singles / pa, t[B_2B] / pa,
                    t[B_3B] / pa, t[B_HR] / pa, outs / pa};
        }

        double runsPerTeamGame() {
            return runs / (games * 2.0);
        }

        /** 득점 ÷ BaseRuns 기대 득점 */
        double efficiency() {
            long[] t = batTotals();
            return runs / baseRuns(t[B_PA], t[B_H], t[B_2B], t[B_3B], t[B_HR], t[B_BB], t[B_HBP]);
        }
    }

    /** 전체 누적 + 시즌 단위 퍼짐 (실제 한 시즌 기록과 같은 조건으로 비교하기 위함) */
    private static final class Run extends Acc {
        double seasonOpsSd;
        double seasonEraSd;
        double seasonWinSd;
        int seasonCount;
        double seconds;
    }

    // ------------------------------------------------------------------ 실행

    @Override
    public void run(ApplicationArguments args) {
        List<PlayerCard> cards = loadCards(true);
        List<int[]> schedule = jdbc.query(
                "SELECT away_team_id, home_team_id FROM mst_schedule ORDER BY game_no",
                (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)});
        Actual actual = loadActual();
        StringBuilder out = new StringBuilder();

        LeagueBaseline baseline = baselines.find(season).orElse(LeagueBaseline.mlbDefault());
        if (calibrate) {
            RateCoefficients coef = fitCoefficients(loadCards(false), actual, out);
            baseline = baseline.withCoefficients(coef).withSpread(Spread.ONE, Spread.ONE);
            baseline = calibrate(baseline, cards, schedule, actual, out);
            baselines.save(season, baseline, "auto-calibrated: " + calibrationIterations + " iter x "
                    + calibrationSeasons + " seasons");
        }

        Run run = simulate(baseline, cards, schedule, seasons, actual);
        out.append(String.format("%n===== %d 시즌 재현 검증 (%d회 반복, %d경기, %.2f초) =====%n",
                season, seasons, run.games, run.seconds));
        leagueSection(out, run, actual, baseline);
        teamSection(out, run, actual);
        playerSection(out, run, actual);
        log.info(out.toString());

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    // ------------------------------------------------------------------ 보정

    private LeagueBaseline calibrate(LeagueBaseline start, List<PlayerCard> cards, List<int[]> schedule,
                                     Actual actual, StringBuilder out) {
        LeagueBaseline league = start;
        double errMul = start.errorRate() / LeagueBaseline.DEFAULT_ERROR_RATE;
        double wpMul = start.wildPitchRate() / LeagueBaseline.DEFAULT_WILD_PITCH_RATE;

        out.append(String.format("%n===== 리그 자동 보정 (반복 %d회 × %d시즌) =====%n",
                calibrationIterations, calibrationSeasons));
        out.append(String.format("반복  기준오차  득점   효율(시뮬/실제)  비자책(시뮬/실제)  실책   폭투%n"));
        for (int it = 1; it <= calibrationIterations; it++) {
            Run run = simulate(league, cards, schedule, calibrationSeasons, null);

            // 1) 기준값: 선수별 실제 출전량에 시뮬 비율을 적용한 기대치 = 실제 기록
            double[] factor = rateFactors(run, actual);
            double[] base = league.rates().clone();
            double maxErr = 0;
            for (int i = 0; i < 7; i++) {
                base[i] *= factor[i];
                maxErr = Math.max(maxErr, Math.abs(factor[i] - 1));
            }
            double inPlayOuts = (base[Outcomes.GROUND_OUT] + base[Outcomes.AIR_OUT]) * factor[7];
            base[Outcomes.GROUND_OUT] = inPlayOuts * actual.groundOutShare;
            base[Outcomes.AIR_OUT] = inPlayOuts * (1 - actual.groundOutShare);
            maxErr = Math.max(maxErr, Math.abs(factor[7] - 1));

            // 2) 실책 → 비자책 비율, 폭투·포일 → 득점 효율
            double unearned = unearnedShare(run);
            if (unearned > 0) {
                errMul = Math.clamp(errMul * Math.clamp(actual.unearnedShare / unearned, 0.67, 1.5), 0.25, 3.0);
            }
            double eff = run.efficiency();
            wpMul = Math.clamp(wpMul * Math.clamp(Math.pow(actual.efficiency / eff, WP_GAIN), 0.67, 1.5), 0.25, 3.0);

            out.append(String.format("%3d   %.4f   %.2f   %.3f / %.3f    %.3f / %.3f      x%.2f  x%.2f%n",
                    it, maxErr, run.runsPerTeamGame(), eff, actual.efficiency, unearned, actual.unearnedShare,
                    errMul, wpMul));

            league = league.withRates(base)
                    .withMisc(LeagueBaseline.DEFAULT_ERROR_RATE * errMul,
                            LeagueBaseline.DEFAULT_WILD_PITCH_RATE * wpMul);
        }

        out.append("보정 결과 (능력치 50 vs 50 기준 확률, mst_league 저장):\n ");
        String[] names = {"K", "BB", "HBP", "1B", "2B", "3B", "HR", "GO", "AO"};
        for (int i = 0; i < names.length; i++) {
            out.append(String.format(" %s %.4f", names[i], league.rates()[i]));
        }
        out.append(String.format("%n  실책 출루 %.4f/인플레이 아웃, 폭투·포일 %.4f/타석(주자 있을 때)%n",
                league.errorRate(), league.wildPitchRate()));
        if (errMul >= 2.99 || errMul <= 0.26 || wpMul >= 2.99 || wpMul <= 0.26) {
            out.append("  ※ 실책/폭투 배율이 한계에 닿음 → 주자 진루 확률 등 다른 득점 경로 점검 필요\n");
        }
        return league;
    }

    /**
     * 능력치 → 결과 계수를 실제 2026 기록으로 회귀 추정 (결과마다 "그 결과 vs 인플레이 아웃" 로지스틱 회귀).
     * 개막 로스터뿐 아니라 능력치가 있는 모든 선수 사용. 표본(타석)이 많은 선수일수록 자연히 비중이 커진다.
     */
    private static RateCoefficients fitCoefficients(List<PlayerCard> all, Actual a, StringBuilder out) {
        List<List<CoefficientFitter.Obs>> bat = new ArrayList<>();
        for (int i = 0; i < RateCoefficients.ROWS; i++) {
            bat.add(new ArrayList<>());
        }
        List<List<CoefficientFitter.Obs>> pit = new ArrayList<>();
        for (int g = 0; g < 5; g++) {
            pit.add(new ArrayList<>());
        }
        for (PlayerCard c : all) {
            long[] x = a.bat.get(c.playerId());
            if (c.batting() != null && x != null && x[B_PA] >= FIT_MIN_SAMPLE) {
                BatterRatings r = c.batting();
                double[] z = {RateModel.z(r.contact()), RateModel.z(r.power()), RateModel.z(r.eye()),
                        RateModel.z(r.quality()), RateModel.z(r.tendency()), RateModel.z(r.speed())};
                double[] cnt = batCounts(x);
                for (int i = 0; i < RateCoefficients.ROWS; i++) {
                    bat.get(i).add(new CoefficientFitter.Obs(cnt[i], cnt[7], z));
                }
            }
            double[] y = a.pit.get(c.playerId());
            if (c.pitching() != null && y != null && y[P_BF] >= FIT_MIN_SAMPLE) {
                PitcherRatings r = c.pitching();
                double[] z = {RateModel.z(r.stuff()), RateModel.z(r.control()), RateModel.z(r.suppression()),
                        RateModel.z(r.groundball())};
                double[] cnt = pitCounts(y[P_SO], y[P_BB], y[P_HBP], y[P_H], y[P_HR], y[P_BF]);
                for (int g = 0; g < 5; g++) {
                    pit.get(g).add(new CoefficientFitter.Obs(cnt[g], cnt[5], z));
                }
            }
        }

        double[][] bc = new double[RateCoefficients.ROWS][];
        for (int i = 0; i < RateCoefficients.ROWS; i++) {
            double[] th = CoefficientFitter.fit(bat.get(i), RateCoefficients.BAT_RATINGS, FIT_RIDGE);
            bc[i] = java.util.Arrays.copyOfRange(th, 1, th.length);
        }
        double[][] pg = new double[5][];
        for (int g = 0; g < 5; g++) {
            double[] th = CoefficientFitter.fit(pit.get(g), RateCoefficients.PIT_RATINGS, FIT_RIDGE);
            pg[g] = java.util.Arrays.copyOfRange(th, 1, th.length);
        }
        int[] rowToGroup = {0, 1, 2, 3, 3, 3, 4}; // 단타·2루타·3루타는 투수 쪽 "홈런 외 안타" 계수 공유
        double[][] pc = new double[RateCoefficients.ROWS][];
        for (int i = 0; i < RateCoefficients.ROWS; i++) {
            pc[i] = pg[rowToGroup[i]].clone();
        }
        RateCoefficients coef = new RateCoefficients(bc, pc);

        String[] rows = {"삼진", "볼넷", "사구", "단타", "2루타", "3루타", "홈런"};
        out.append(String.format("%n===== 능력치 → 결과 계수 (실제 2026 회귀, 타자 %d명 / 투수 %d명) =====%n",
                bat.get(0).size(), pit.get(0).size()));
        out.append("능력치 1표준편차(10점)당 로그 배율. 예: 0.20 → 약 22% 증가\n");
        out.append(String.format("타자    %s%n", String.join("  ", RateCoefficients.BAT_NAMES)));
        for (int i = 0; i < rows.length; i++) {
            out.append(String.format("%-5s", rows[i]));
            for (double v : bc[i]) {
                out.append(String.format(" %+.3f", v));
            }
            out.append(System.lineSeparator());
        }
        out.append(String.format("투수    %s%n", String.join("  ", RateCoefficients.PIT_NAMES)));
        String[] prow = {"삼진", "볼넷", "사구", "안타", "홈런"};
        for (int g = 0; g < 5; g++) {
            out.append(String.format("%-5s", prow[g]));
            for (double v : pg[g]) {
                out.append(String.format(" %+.3f", v));
            }
            out.append(System.lineSeparator());
        }
        return coef;
    }

    /**
     * 결과별 보정 배율. 타자 쪽: 각 타자의 실제 타석 수 × 시뮬 비율 의 합을 실제 횟수 합과 비교.
     * 투수 쪽도 같은 방식. 시뮬 상대(개막 로스터 주전)와 실제 상대(벤치·콜업 포함)의 수준 차이는
     * 타자 쪽과 투수 쪽에서 반대 방향으로 나타나므로 두 비율의 기하평균을 쓴다.
     * 실제 출전량으로 가중하므로, 시뮬에선 주전이지만 실제로는 부상·강등으로 거의 못 뛴 선수의 영향이 작다.
     */
    private static double[] rateFactors(Acc run, Actual a) {
        double[] bAct = new double[8];
        double[] bExp = new double[8];
        for (Map.Entry<Integer, long[]> e : run.bat.entrySet()) {
            long[] x = a.bat.get(e.getKey());
            long[] s = e.getValue();
            if (x == null || x[B_PA] == 0 || s[B_PA] == 0) {
                continue;
            }
            double[] ac = batCounts(x);
            double[] sc = batCounts(s);
            for (int i = 0; i < 8; i++) {
                bAct[i] += ac[i];
                bExp[i] += x[B_PA] * sc[i] / s[B_PA];
            }
        }
        // 투수: {삼진, 볼넷, 사구, 홈런 외 안타, 홈런, 인플레이 아웃}
        double[] pAct = new double[6];
        double[] pExp = new double[6];
        for (Map.Entry<Integer, long[]> e : run.pit.entrySet()) {
            double[] x = a.pit.get(e.getKey());
            long[] s = e.getValue();
            if (x == null || x[P_BF] <= 0 || s[P_BF] == 0) {
                continue;
            }
            double[] ac = pitCounts(x[P_SO], x[P_BB], x[P_HBP], x[P_H], x[P_HR], x[P_BF]);
            double[] sc = pitCounts(s[P_SO], s[P_BB], s[P_HBP], s[P_H], s[P_HR], s[P_BF]);
            for (int g = 0; g < 6; g++) {
                pAct[g] += ac[g];
                pExp[g] += x[P_BF] * sc[g] / s[P_BF];
            }
        }
        int[] pitGroup = {0, 1, 2, 3, 3, 3, 4, 5};
        double[] f = new double[8];
        for (int i = 0; i < 8; i++) {
            double rb = bAct[i] / bExp[i];
            double rp = pAct[pitGroup[i]] / pExp[pitGroup[i]];
            f[i] = Math.sqrt(rb * rp);
        }
        return f;
    }

    /** {삼진, 볼넷, 사구, 단타, 2루타, 3루타, 홈런, 인플레이 아웃} 횟수 */
    private static double[] batCounts(long[] x) {
        double singles = x[B_H] - x[B_2B] - x[B_3B] - x[B_HR];
        double outs = x[B_PA] - x[B_SO] - x[B_BB] - x[B_HBP] - x[B_H];
        return new double[] {x[B_SO], x[B_BB], x[B_HBP], singles, x[B_2B], x[B_3B], x[B_HR], outs};
    }

    private static double[] pitCounts(double so, double bb, double hbp, double h, double hr, double bf) {
        return new double[] {so, bb, hbp, h - hr, hr, Math.max(0, bf - so - bb - hbp - h)};
    }

    /** 결과 그룹 {삼진, 볼넷+사구, 홈런, 홈런 외 안타} 별 횟수 */
    private static final List<ToDoubleFunction<long[]>> BAT_GROUPS = List.of(
            s -> s[B_SO], s -> s[B_BB] + s[B_HBP], s -> s[B_HR], s -> s[B_H] - s[B_HR]);
    private static final List<ToDoubleFunction<double[]>> PIT_GROUPS = List.of(
            s -> s[P_SO], s -> s[P_BB] + s[P_HBP], s -> s[P_HR], s -> s[P_H] - s[P_HR]);

    /**
     * 그룹별 예측 기울기: 실제 비율을 시뮬 비율에 회귀 (실제 타석 가중, 시뮬 쪽 표본 운은 분모에서 제거).
     * 기울기 1 = 능력치 차이가 실제 기록 차이를 과장도 축소도 없이 예측. 선수 선택은 시뮬 쪽(주전) 기준이라
     * "실제로 잘해서 많이 뛴 선수만 남는" 선택 편향이 기울기를 왜곡하지 않는다.
     */
    private static double[] batterSlopes(Acc run, Actual a, int simSeasons) {
        double[] f = new double[4];
        for (int g = 0; g < 4; g++) {
            ToDoubleFunction<long[]> fn = BAT_GROUPS.get(g);
            List<double[]> rows = new ArrayList<>();
            run.bat.forEach((id, s) -> {
                long[] x = a.bat.get(id);
                if (x == null || x[B_PA] < MIN_ACTUAL_PA || s[B_PA] < REGULAR_PA_PER_SEASON * simSeasons) {
                    return;
                }
                double sr = fn.applyAsDouble(s) / s[B_PA];
                rows.add(new double[] {sr, fn.applyAsDouble(x) / x[B_PA], x[B_PA], sr * (1 - sr) / s[B_PA]});
            });
            f[g] = slope(rows);
        }
        return f;
    }

    private static double[] pitcherSlopes(Acc run, Actual a, int simSeasons) {
        double[] f = new double[4];
        for (int g = 0; g < 4; g++) {
            ToDoubleFunction<double[]> fn = PIT_GROUPS.get(g);
            List<double[]> rows = new ArrayList<>();
            run.pit.forEach((id, s) -> {
                double[] x = a.pit.get(id);
                if (x == null || x[P_BF] < MIN_ACTUAL_BF || s[P_BF] < REGULAR_BF_PER_SEASON * simSeasons) {
                    return;
                }
                double[] sd = new double[P_LEN];
                for (int i = 0; i < P_LEN; i++) {
                    sd[i] = s[i];
                }
                double sr = fn.applyAsDouble(sd) / sd[P_BF];
                rows.add(new double[] {sr, fn.applyAsDouble(x) / x[P_BF], x[P_BF], sr * (1 - sr) / sd[P_BF]});
            });
            f[g] = slope(rows);
        }
        return f;
    }

    /**
     * 가중 회귀 기울기. rows = {x(시뮬), y(실제), 가중치, x의 표본 분산}.
     * x 의 표본 운은 기울기를 0 쪽으로 깎으므로(희석) 분모에서 뺀다. 표본이 부족하면 1.
     */
    static double slope(List<double[]> rows) {
        if (rows.size() < 20) {
            return 1.0;
        }
        double sw = 0, mx = 0, my = 0;
        for (double[] r : rows) {
            sw += r[2];
            mx += r[2] * r[0];
            my += r[2] * r[1];
        }
        mx /= sw;
        my /= sw;
        double cov = 0, vx = 0, noise = 0;
        for (double[] r : rows) {
            cov += r[2] * (r[0] - mx) * (r[1] - my);
            vx += r[2] * (r[0] - mx) * (r[0] - mx);
            noise += r[2] * r[3];
        }
        double trueVar = Math.max(vx - noise, vx * 0.05);
        return trueVar > 0 ? cov / trueVar : 1.0;
    }

    private static double unearnedShare(Acc run) {
        long er = 0;
        long r = 0;
        for (long[] p : run.pit.values()) {
            er += p[P_ER];
            r += p[P_R];
        }
        return r > 0 ? 1 - (double) er / r : 0;
    }

    static double baseRuns(double pa, double h, double b2, double b3, double hr, double bb, double hbp) {
        double tb = h + b2 + 2 * b3 + 3 * hr;
        double a = h + bb + hbp - hr;
        double b = (1.4 * tb - 0.6 * h - 3 * hr + 0.1 * (bb + hbp)) * 1.02;
        double c = pa - h - bb - hbp;
        return a * b / (b + c) + hr;
    }

    private static String fmt(double[] s) {
        return String.format("%.2f/%.2f/%.2f/%.2f", s[0], s[1], s[2], s[3]);
    }

    // ------------------------------------------------------------------ 시뮬레이션

    /** @param probe null 이 아니면 시즌 단위 퍼짐(OPS·ERA·승수 표준편차)도 계산 */
    private Run simulate(LeagueBaseline league, List<PlayerCard> cards, List<int[]> schedule, int count,
                         Actual probe) {
        AutoManager manager = new AutoManager(new RateModel(league));
        Map<Integer, List<PlayerCard>> rosters = new HashMap<>();
        for (PlayerCard c : cards) {
            rosters.computeIfAbsent(c.teamId(), k -> new ArrayList<>()).add(c);
        }
        Map<Integer, AutoManager.TeamSetup> setups = new HashMap<>();
        rosters.forEach((team, list) -> setups.put(team, manager.build(team, list)));

        GameSimulator sim = new GameSimulator(league);
        Run run = new Run();
        long start = System.nanoTime();
        for (int s = 0; s < count; s++) {
            Acc acc = new Acc();
            Map<Integer, Integer> rotationIdx = new HashMap<>();
            for (int g = 0; g < schedule.size(); g++) {
                int awayId = schedule.get(g)[0];
                int homeId = schedule.get(g)[1];
                SimTeam away = nextStarter(setups.get(awayId), rotationIdx);
                SimTeam home = nextStarter(setups.get(homeId), rotationIdx);
                GameResult r = sim.simulate(away, home, (long) s * 1_000_000 + g);

                acc.games++;
                acc.runs += r.awayRuns() + r.homeRuns();
                acc.wins.computeIfAbsent(r.homeWin() ? homeId : awayId, k -> new long[1])[0]++;
                addBatting(acc.bat, away, r.batting()[0]);
                addBatting(acc.bat, home, r.batting()[1]);
                addPitching(acc.pit, r.awayPitching());
                addPitching(acc.pit, r.homePitching());
            }
            if (probe != null) {
                seasonSpread(run, acc, probe);
            }
            run.merge(acc);
        }
        run.seconds = (System.nanoTime() - start) / 1e9;
        return run;
    }

    private static void seasonSpread(Run run, Acc acc, Actual a) {
        List<double[]> ops = new ArrayList<>();
        acc.bat.forEach((id, s) -> {
            long[] x = a.bat.get(id);
            if (x != null && x[B_PA] >= 300 && s[B_PA] >= 300) {
                ops.add(new double[] {ops(s)});
            }
        });
        List<double[]> era = new ArrayList<>();
        acc.pit.forEach((id, s) -> {
            double[] x = a.pit.get(id);
            if (x != null && x[P_OUTS] >= 300 && s[P_OUTS] >= 300) {
                era.add(new double[] {27.0 * s[P_ER] / s[P_OUTS]});
            }
        });
        List<double[]> wins = new ArrayList<>();
        acc.wins.values().forEach(w -> wins.add(new double[] {w[0]}));
        run.seasonOpsSd += sd(ops, 0);
        run.seasonEraSd += sd(era, 0);
        run.seasonWinSd += sd(wins, 0);
        run.seasonCount++;
    }

    private static SimTeam nextStarter(AutoManager.TeamSetup setup, Map<Integer, Integer> rotationIdx) {
        int i = rotationIdx.merge(setup.teamId(), 1, Integer::sum) - 1;
        return setup.byStarter()[i % setup.byStarter().length];
    }

    private static void addBatting(Map<Integer, long[]> bat, SimTeam team, int[][] box) {
        for (int slot = 0; slot < 9; slot++) {
            int[] s = box[slot];
            long[] a = bat.computeIfAbsent((int) team.lineup()[slot].playerId(), k -> new long[B_LEN]);
            a[B_PA] += s[BatStat.PA];
            a[B_AB] += s[BatStat.AB];
            a[B_H] += s[BatStat.H];
            a[B_2B] += s[BatStat.B2];
            a[B_3B] += s[BatStat.B3];
            a[B_HR] += s[BatStat.HR];
            a[B_BB] += s[BatStat.BB];
            a[B_HBP] += s[BatStat.HBP];
            a[B_SF] += s[BatStat.SF];
            a[B_SO] += s[BatStat.SO];
        }
    }

    private static void addPitching(Map<Integer, long[]> pit, List<PitcherLine> lines) {
        for (PitcherLine l : lines) {
            long[] a = pit.computeIfAbsent((int) l.playerId(), k -> new long[P_LEN]);
            a[P_OUTS] += l.outs();
            a[P_ER] += l.earnedRuns();
            a[P_SO] += l.strikeouts();
            a[P_BB] += l.walks();
            a[P_BF] += l.battersFaced();
            a[P_H] += l.hits();
            a[P_HR] += l.homeRuns();
            a[P_HBP] += l.hitByPitch();
            a[P_R] += l.runs();
        }
    }

    // ------------------------------------------------------------------ 실제 값

    /**
     * @param rates      MLB 전체 {삼진, 볼넷, 사구, 단타, 2루타, 3루타, 홈런, 인플레이 아웃} / 타석
     * @param efficiency MLB 전체 득점 ÷ BaseRuns 기대 득점
     * @param bat        선수별 실제 타격 (B_ 인덱스)
     * @param pit        선수별 실제 투구 (P_ 인덱스, 상대 타자 수는 추정)
     */
    private record Actual(double[] rates, double groundOutShare, double runsPerTeamGame, double efficiency,
                          double unearnedShare, Map<Integer, Integer> wins, Map<Integer, String> names,
                          Map<Integer, long[]> bat, Map<Integer, double[]> pit) {
    }

    private Actual loadActual() {
        double[] lg = jdbc.query("""
                SELECT SUM(pa), SUM(so), SUM(bb), SUM(hbp), SUM(h), SUM(b2), SUM(b3), SUM(hr),
                       SUM(ground_outs), SUM(air_outs), SUM(runs_scored), SUM(wins + losses)
                FROM mst_team_season WHERE season = ?
                """, rs -> {
            if (!rs.next() || rs.getDouble(1) == 0) {
                throw new IllegalStateException("mst_team_season 에 " + season
                        + " 데이터가 없음. 임포트(stats)를 먼저 실행하세요.");
            }
            double[] v = new double[12];
            for (int i = 0; i < 12; i++) {
                v[i] = rs.getDouble(i + 1);
            }
            return v;
        }, season);
        double pa = lg[0], so = lg[1], bb = lg[2], hbp = lg[3], h = lg[4], b2 = lg[5], b3 = lg[6], hr = lg[7];
        double[] rates = {so / pa, bb / pa, hbp / pa, (h - b2 - b3 - hr) / pa, b2 / pa, b3 / pa, hr / pa,
                (pa - so - bb - hbp - h) / pa};
        double efficiency = lg[10] / baseRuns(pa, h, b2, b3, hr, bb, hbp);

        Map<Integer, Integer> wins = new HashMap<>();
        jdbc.query("SELECT team_id, wins FROM mst_team_season WHERE season = ?", rs -> {
            wins.put(rs.getInt(1), rs.getInt(2));
        }, season);
        Map<Integer, String> names = new HashMap<>();
        jdbc.query("SELECT team_id, abbrev FROM mst_team", rs -> {
            names.put(rs.getInt(1), rs.getString(2));
        });

        Map<Integer, long[]> bat = new HashMap<>();
        jdbc.query("""
                SELECT player_id, SUM(pa), SUM(ab), SUM(h), SUM(b2), SUM(b3), SUM(hr), SUM(bb), SUM(hbp), SUM(sf), SUM(so)
                FROM mst_bat_season WHERE season = ? GROUP BY player_id HAVING SUM(pa) > 0
                """, rs -> {
            bat.put(rs.getInt(1), actualBat(rs));
        }, season);

        Map<Integer, double[]> pit = new HashMap<>();
        double[] runsEr = new double[2];
        jdbc.query("""
                SELECT player_id, SUM(outs), SUM(er), SUM(so), SUM(bb), SUM(h), SUM(hr), SUM(hbp), SUM(r)
                FROM mst_pit_season WHERE season = ? GROUP BY player_id HAVING SUM(outs) > 0
                """, rs -> {
            double[] x = new double[P_LEN];
            x[P_OUTS] = rs.getDouble(2);
            x[P_ER] = rs.getDouble(3);
            x[P_SO] = rs.getDouble(4);
            x[P_BB] = rs.getDouble(5);
            x[P_H] = rs.getDouble(6);
            x[P_HR] = rs.getDouble(7);
            x[P_HBP] = rs.getDouble(8);
            x[P_R] = rs.getDouble(9);
            // 상대 타자 수 추정: 병살·도루자 등 타석 외 아웃(약 3.7%)을 빼고 실책 출루 등(약 1%)을 더함
            x[P_BF] = (0.963 * x[P_OUTS] + x[P_H] + x[P_BB] + x[P_HBP]) * 1.01;
            pit.put(rs.getInt(1), x);
            runsEr[0] += x[P_R];
            runsEr[1] += x[P_ER];
        }, season);

        return new Actual(rates, lg[8] / (lg[8] + lg[9]), lg[10] / lg[11], efficiency,
                runsEr[0] > 0 ? 1 - runsEr[1] / runsEr[0] : 0, wins, names, bat, pit);
    }

    // ------------------------------------------------------------------ 리포트

    private static void leagueSection(StringBuilder out, Run run, Actual actual, LeagueBaseline league) {
        double[] sim = run.rates();
        out.append(String.format("%n[리그]           시뮬     MLB 전체%n"));
        for (int i = 0; i < OUTCOME_NAMES.length; i++) {
            out.append(String.format("%-14s %.4f   %.4f%n", OUTCOME_NAMES[i] + "/타석", sim[i], actual.rates[i]));
        }
        out.append(String.format("%-14s %.2f     %.2f   (시뮬은 개막 주전만 뛰므로 조금 높은 게 정상)%n",
                "팀당 경기 득점", run.runsPerTeamGame(), actual.runsPerTeamGame));
        out.append(String.format("%-14s %.3f    %.3f   (득점 ÷ BaseRuns 기대 득점)%n",
                "득점 효율", run.efficiency(), actual.efficiency));
        out.append(String.format("%-14s %.3f    %.3f%n", "비자책 비율", unearnedShare(run), actual.unearnedShare));
    }

    private void teamSection(StringBuilder out, Run run, Actual actual) {
        List<double[]> pairs = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        actual.wins.forEach((team, w) -> {
            long[] sw = run.wins.get(team);
            if (sw == null) {
                return;
            }
            double simW = (double) sw[0] / seasons;
            pairs.add(new double[] {simW, w});
            rows.add(new Object[] {actual.names.getOrDefault(team, "?"), simW, w});
        });
        rows.sort(Comparator.comparingDouble((Object[] r) -> Math.abs((double) r[1] - (int) r[2])).reversed());

        double rmse = Math.sqrt(pairs.stream().mapToDouble(p -> (p[0] - p[1]) * (p[0] - p[1])).average().orElse(0));
        out.append(String.format("%n[팀 승수]  상관계수 %.3f, 평균 오차(RMSE) %.1f승%n", pearson(pairs), rmse));
        out.append(String.format("승수 표준편차  시즌 단위 시뮬 %.1f / 실제 %.1f   (실력만: 시뮬 %d시즌 평균 %.1f)%n",
                run.seasonWinSd / run.seasonCount, sd(pairs, 1), seasons, sd(pairs, 0)));
        out.append("차이가 큰 팀:  팀   시뮬   실제\n");
        for (Object[] r : rows.subList(0, Math.min(6, rows.size()))) {
            out.append(String.format("              %-4s %5.1f  %3d%n", r[0], (double) r[1], (int) r[2]));
        }
    }

    /**
     * 상관계수·시즌 표준편차: 실제 300타석(100이닝) 이상 — 이전 리포트와 비교 가능하도록 유지.
     * 가중 평균·기울기: 시뮬 주전 중 실제 100타석(30이닝) 이상, 실제 출전량 가중 — 선택 편향이 적은 기준.
     */
    private void playerSection(StringBuilder out, Run run, Actual actual) {
        List<double[]> ops = new ArrayList<>(), kRate = new ArrayList<>(), hrRate = new ArrayList<>(),
                bbRate = new ArrayList<>(), opsW = new ArrayList<>();
        actual.bat.forEach((id, a) -> {
            long[] s = run.bat.get(id);
            if (s == null || s[B_PA] < REGULAR_PA_PER_SEASON * seasons || a[B_PA] < MIN_ACTUAL_PA) {
                return;
            }
            opsW.add(new double[] {ops(s), ops(a), a[B_PA], 0});
            if (a[B_PA] < 300) {
                return;
            }
            ops.add(new double[] {ops(s), ops(a)});
            kRate.add(new double[] {(double) s[B_SO] / s[B_PA], (double) a[B_SO] / a[B_PA]});
            hrRate.add(new double[] {(double) s[B_HR] / s[B_PA], (double) a[B_HR] / a[B_PA]});
            bbRate.add(new double[] {(double) s[B_BB] / s[B_PA], (double) a[B_BB] / a[B_PA]});
        });

        List<double[]> era = new ArrayList<>(), pk = new ArrayList<>(), pbb = new ArrayList<>(),
                eraW = new ArrayList<>();
        actual.pit.forEach((id, a) -> {
            long[] s = run.pit.get(id);
            if (s == null || s[P_BF] < REGULAR_BF_PER_SEASON * seasons || s[P_OUTS] == 0 || a[P_OUTS] < 90) {
                return;
            }
            double simEra = 27.0 * s[P_ER] / s[P_OUTS];
            double actEra = 27.0 * a[P_ER] / a[P_OUTS];
            eraW.add(new double[] {simEra, actEra, a[P_OUTS], 0});
            if (a[P_OUTS] < 300) {
                return;
            }
            era.add(new double[] {simEra, actEra});
            pk.add(new double[] {(double) s[P_SO] / s[P_BF], a[P_SO] / a[P_BF]});
            pbb.add(new double[] {(double) s[P_BB] / s[P_BF], a[P_BB] / a[P_BF]});
        });

        out.append(String.format("%n[타자]  상관계수 (실제 300타석 이상 %d명, 1에 가까울수록 좋음)%n", ops.size()));
        out.append(String.format("OPS %.3f | 삼진율 %.3f | 볼넷률 %.3f | 홈런율 %.3f%n",
                pearson(ops), pearson(kRate), pearson(bbRate), pearson(hrRate)));
        out.append(String.format("시뮬 주전 %d명 (실제 타석 가중)  평균 OPS 시뮬 %.3f / 실제 %.3f,  OPS 기울기 %.2f (1이 이상적)%n",
                opsW.size(), weightedMean(opsW, 0), weightedMean(opsW, 1), slope(opsW)));
        out.append(String.format("표준편차(시즌 단위, 300타석 이상)  시뮬 %.3f / 실제 %.3f%n",
                run.seasonOpsSd / run.seasonCount, sd(ops, 1)));

        out.append(String.format("결과별 기울기(K/BB/HR/H, 1이 이상적)  %s%n", fmt(batterSlopes(run, actual, seasons))));

        out.append(String.format("%n[투수]  상관계수 (실제 100이닝 이상 %d명)%n", era.size()));
        out.append(String.format("ERA %.3f | 삼진율 %.3f | 볼넷률 %.3f%n", pearson(era), pearson(pk), pearson(pbb)));
        out.append(String.format("시뮬 등판 %d명 (실제 이닝 가중)  평균 ERA 시뮬 %.2f / 실제 %.2f,  ERA 기울기 %.2f (1이 이상적)%n",
                eraW.size(), weightedMean(eraW, 0), weightedMean(eraW, 1), slope(eraW)));
        out.append(String.format("표준편차(시즌 단위, 100이닝 이상)  시뮬 %.2f / 실제 %.2f%n",
                run.seasonEraSd / run.seasonCount, sd(era, 1)));
        out.append(String.format("결과별 기울기(K/BB/HR/H, 1이 이상적)  %s%n", fmt(pitcherSlopes(run, actual, seasons))));
    }

    private static double weightedMean(List<double[]> rows, int idx) {
        double sw = 0, sum = 0;
        for (double[] r : rows) {
            sw += r[2];
            sum += r[2] * r[idx];
        }
        return sw > 0 ? sum / sw : Double.NaN;
    }

    private static long[] actualBat(ResultSet rs) throws SQLException {
        long[] a = new long[B_LEN];
        a[B_PA] = rs.getLong(2);
        a[B_AB] = rs.getLong(3);
        a[B_H] = rs.getLong(4);
        a[B_2B] = rs.getLong(5);
        a[B_3B] = rs.getLong(6);
        a[B_HR] = rs.getLong(7);
        a[B_BB] = rs.getLong(8);
        a[B_HBP] = rs.getLong(9);
        a[B_SF] = rs.getLong(10);
        a[B_SO] = rs.getLong(11);
        return a;
    }

    private static double ops(long[] a) {
        double obpDen = a[B_AB] + a[B_BB] + a[B_HBP] + a[B_SF];
        double obp = obpDen == 0 ? 0 : (a[B_H] + a[B_BB] + a[B_HBP]) / obpDen;
        double tb = a[B_H] + a[B_2B] + 2.0 * a[B_3B] + 3.0 * a[B_HR];
        double slg = a[B_AB] == 0 ? 0 : tb / a[B_AB];
        return obp + slg;
    }

    // ------------------------------------------------------------------ 선수 카드 로드

    /**
     * @param activeOnly true: 개막 현역 로스터만 (시뮬용),
     *                   false: MLB 기록 기반 능력치 선수 전체 (계수 회귀용. 마이너 환산 능력치는 이 계수로 역산한 값이라 제외)
     */
    private List<PlayerCard> loadCards(boolean activeOnly) {
        StringBuilder cols = new StringBuilder();
        for (String p : PITCH) {
            cols.append(", r.").append(p).append("_use, r.").append(p).append("_stuff");
        }
        for (String d : DEF) {
            cols.append(", r.def_").append(d);
        }
        String sql = """
                SELECT p.player_id, p.team_id, p.primary_pos, p.bats, p.throws,
                       r.contact, r.power, r.eye, r.quality, r.bb_tendency, r.speed, r.baserunning,
                       r.control, r.suppression, r.groundball, r.stamina, r.arm %s
                FROM mst_player p JOIN mst_player_rating r USING (player_id)
                %s
                """.formatted(cols, activeOnly ? "WHERE p.roster_status = 2 AND p.team_id IS NOT NULL" : "WHERE r.source = 0");
        return jdbc.query(sql, (rs, n) -> card(rs));
    }

    private static PlayerCard card(ResultSet rs) throws SQLException {
        BatterRatings batting = null;
        Integer contact = rs.getObject("contact", Integer.class);
        if (contact != null) {
            batting = new BatterRatings(contact, rs.getInt("power"), rs.getInt("eye"), rs.getInt("quality"),
                    rs.getInt("bb_tendency"), rs.getInt("speed"), rs.getInt("baserunning"));
        }
        PitcherRatings pitching = null;
        Integer control = rs.getObject("control", Integer.class);
        if (control != null) {
            double use = 0, weighted = 0;
            for (String p : PITCH) {
                Integer u = rs.getObject(p + "_use", Integer.class);
                Integer s = rs.getObject(p + "_stuff", Integer.class);
                if (u != null && s != null) {
                    use += u;
                    weighted += u * s;
                }
            }
            int stuff = use > 0 ? (int) Math.round(weighted / use) : 40;
            pitching = new PitcherRatings(stuff, control, rs.getInt("suppression"), rs.getInt("groundball"),
                    rs.getInt("stamina"));
        }
        int[] def = new int[8];
        for (int i = 0; i < DEF.length; i++) {
            def[i] = rs.getInt("def_" + DEF[i]); // NULL → 0 (소화 불가)
        }
        Integer arm = rs.getObject("arm", Integer.class);
        return new PlayerCard(rs.getInt("player_id"), rs.getInt("team_id"), rs.getInt("primary_pos"),
                hand(rs.getString("bats")), pitchHand(rs.getString("throws")), batting, pitching, def,
                arm == null ? 50 : arm);
    }

    private static Hand hand(String code) {
        return switch (code) {
            case "L" -> Hand.LEFT;
            case "S" -> Hand.SWITCH;
            default -> Hand.RIGHT;
        };
    }

    private static Hand pitchHand(String code) {
        return "L".equals(code) ? Hand.LEFT : Hand.RIGHT;
    }

    // ------------------------------------------------------------------ 통계

    static double pearson(List<double[]> pairs) {
        int n = pairs.size();
        if (n < 3) {
            return Double.NaN;
        }
        double mx = mean(pairs, 0), my = mean(pairs, 1);
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] p : pairs) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
            syy += (p[1] - my) * (p[1] - my);
        }
        return sxx == 0 || syy == 0 ? Double.NaN : sxy / Math.sqrt(sxx * syy);
    }

    private static double mean(List<double[]> pairs, int idx) {
        return pairs.stream().mapToDouble(p -> p[idx]).average().orElse(Double.NaN);
    }

    private static double sd(List<double[]> pairs, int idx) {
        double m = mean(pairs, idx);
        return Math.sqrt(pairs.stream().mapToDouble(p -> (p[idx] - m) * (p[idx] - m)).average().orElse(Double.NaN));
    }
}
