package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 나이별 능력치 변화 곡선 — 실제 MLB 연도별 기록에서 추정 (delta method).
 * <ol>
 *   <li>같은 선수의 연속 두 시즌(y, y+1, 각 100타석/상대 100타자 이상) 짝마다 지표 변화를 구한다</li>
 *   <li>평균 회귀 편향 제거: 앞 시즌 기록만 지표별 안정화 표본만큼 그 나이 평균 쪽으로 당겨 "앞 시즌 실력"으로 쓰고,
 *       뒤 시즌 기록(운이 선택과 무관)과의 차이를 낸다. 운 좋은 해에 다음 시즌 짝이 지어져 하락으로 보이는 문제 방지</li>
 *   <li>변화량을 "실력 표준편차" 단위로 바꾼다: 실력 분산 = 관측 분산 - 평균 표본 분산 p(1-p)/n (기존 보정과 같은 원칙).
 *       능력치 10점 = 실력 1 표준편차이므로 능력치 변화 = 10 × 실력 단위 변화</li>
 *   <li>y 시즌 나이별로 두 표본의 조화평균 가중 평균 → ±2세 삼각 이동 평균으로 평활, 표본이 적은 나이는 가까운 나이 값으로</li>
 * </ol>
 * 지표 → 능력치: 삼진율(-)→컨택, 볼넷률→선구, 장타율(ISO)→파워, 인플레이 안타율→타구질, 도루 시도율→스피드,
 * 투수 삼진율→구위, 볼넷률(-)→제구, 피홈런율(-)→장타억제.
 * <p>
 * 한계: 은퇴한 선수의 기록이 없어(현역 선수의 과거 기록만 보유) 노년 하락이 약간 덜 가파르게 나올 수 있다 (생존 편향).
 * 마스터 데이터에서 한 번 계산해 메모리에 둔다.
 */
@Component
public class AgingCurves {

    public static final int CONTACT = 0, EYE = 1, POWER = 2, QUALITY = 3, SPEED = 4, STUFF = 5, CONTROL = 6,
            SUPPRESSION = 7, GROUPS = 8;
    public static final String[] NAMES = {"컨택", "선구", "파워", "타구질", "스피드", "구위", "제구", "장타억제"};
    public static final int MIN_AGE = 18, MAX_AGE = 45;
    private static final int MIN_SAMPLE = 100;
    private static final double MIN_AGE_WEIGHT = 2_000; // 나이별 가중치 합(조화평균 타석)이 이보다 작으면 이웃 나이 값 사용

    /** [그룹][나이 - MIN_AGE] = 1년 능력치 변화 (평활 후), [그룹][나이] 짝 가중치 합 */
    public record Curves(double[][] delta, double[][] weight) {
        public double at(int group, int age) {
            return delta[group][Math.clamp(age, MIN_AGE, MAX_AGE) - MIN_AGE];
        }
    }

    private final JdbcTemplate jdbc;
    private volatile Curves curves;

    public AgingCurves(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Curves get() {
        Curves c = curves;
        if (c == null) {
            synchronized (this) {
                c = curves;
                if (c == null) {
                    c = estimate();
                    curves = c;
                }
            }
        }
        return c;
    }

    /** 시즌 단위 지표: {n, 값...} */
    private record Season(int playerId, int season, int age, double n, double[] values) {
    }

    private Curves estimate() {
        Map<Integer, LocalDate> birth = new HashMap<>();
        jdbc.query("SELECT player_id, birth_date FROM mst_player", rs -> {
            birth.put(rs.getInt(1), rs.getObject(2, LocalDate.class));
        });

        // 타자: {삼진율, 볼넷률, ISO, 인플레이 안타율, 도루 시도율}
        List<Season> bat = new ArrayList<>();
        jdbc.query("""
                SELECT player_id, season, SUM(pa), SUM(ab), SUM(h), SUM(b2), SUM(b3), SUM(hr), SUM(bb), SUM(so),
                       SUM(hbp), SUM(sb), SUM(cs)
                FROM mst_bat_season GROUP BY player_id, season HAVING SUM(pa) >= ?
                """, rs -> {
            LocalDate b = birth.get(rs.getInt(1));
            if (b == null) {
                return;
            }
            double pa = rs.getDouble(3), ab = rs.getDouble(4), h = rs.getDouble(5), b2 = rs.getDouble(6);
            double b3 = rs.getDouble(7), hr = rs.getDouble(8), bb = rs.getDouble(9), so = rs.getDouble(10);
            double hbp = rs.getDouble(11), sb = rs.getDouble(12), cs = rs.getDouble(13);
            double bip = Math.max(1, ab - so - hr);
            double opp = Math.max(1, (h - b2 - b3 - hr) + bb + hbp);
            bat.add(new Season(rs.getInt(1), rs.getInt(2), age(b, rs.getInt(2)), pa, new double[] {
                    -so / pa, bb / pa, (b2 + 2 * b3 + 3 * hr) / Math.max(1, ab), (h - hr) / bip, (sb + cs) / opp}));
        }, MIN_SAMPLE);

        // 투수: {삼진율, 볼넷률(-), 피홈런율(-)}
        List<Season> pit = new ArrayList<>();
        jdbc.query("""
                SELECT player_id, season, SUM(outs), SUM(h), SUM(bb), SUM(so), SUM(hr), SUM(hbp)
                FROM mst_pit_season GROUP BY player_id, season
                """, rs -> {
            LocalDate b = birth.get(rs.getInt(1));
            double bf = rs.getDouble(3) + rs.getDouble(4) + rs.getDouble(5) + rs.getDouble(8);
            if (b == null || bf < MIN_SAMPLE) {
                return;
            }
            pit.add(new Season(rs.getInt(1), rs.getInt(2), age(b, rs.getInt(2)), bf, new double[] {
                    rs.getDouble(6) / bf, -rs.getDouble(5) / bf, -rs.getDouble(7) / bf}));
        });

        double[][] delta = new double[GROUPS][MAX_AGE - MIN_AGE + 1];
        double[][] weight = new double[GROUPS][MAX_AGE - MIN_AGE + 1];
        accumulate(bat, new int[] {CONTACT, EYE, POWER, QUALITY, SPEED}, new boolean[] {true, true, false, true, true},
                new double[] {60, 120, 160, 1200, 200}, delta, weight);
        accumulate(pit, new int[] {STUFF, CONTROL, SUPPRESSION}, new boolean[] {true, true, true},
                new double[] {70, 170, 1300}, delta, weight);
        for (int g = 0; g < GROUPS; g++) {
            delta[g] = smooth(delta[g], weight[g]);
        }
        return new Curves(delta, weight);
    }

    /**
     * @param binomial true 면 지표가 비율(0~1)이라 표본 분산 p(1-p)/n 으로 실력 분산을 구함 (ISO 는 근사로 관측 분산의 60%)
     */
    private static void accumulate(List<Season> seasons, int[] groups, boolean[] binomial, double[] stabilizer,
                                   double[][] delta, double[][] weight) {
        int k = groups.length;
        // 실력 표준편차 (300 이상 시즌 기준)
        double[] talentSd = new double[k];
        for (int j = 0; j < k; j++) {
            double sum = 0, sq = 0, noise = 0;
            int n = 0;
            for (Season s : seasons) {
                if (s.n() >= 300) {
                    double v = s.values()[j];
                    sum += v;
                    sq += v * v;
                    double p = Math.abs(v);
                    noise += binomial[j] ? p * (1 - p) / s.n() : 0;
                    n++;
                }
            }
            double mean = sum / Math.max(1, n);
            double var = sq / Math.max(1, n) - mean * mean;
            double trueVar = binomial[j] ? var - noise / Math.max(1, n) : var * 0.6;
            talentSd[j] = Math.sqrt(Math.max(var * 0.1, trueVar));
        }

        // 나이별 평균 (표본 가중) — 평균 회귀의 기준. 표본이 적은 나이는 전체 평균
        int ages = MAX_AGE - MIN_AGE + 1;
        double[][] ageSum = new double[k][ages];
        double[] ageN = new double[ages];
        double[] allSum = new double[k];
        double allN = 0;
        for (Season s : seasons) {
            int idx = Math.clamp(s.age(), MIN_AGE, MAX_AGE) - MIN_AGE;
            ageN[idx] += s.n();
            allN += s.n();
            for (int j = 0; j < k; j++) {
                ageSum[j][idx] += s.n() * s.values()[j];
                allSum[j] += s.n() * s.values()[j];
            }
        }
        double[][] ageMean = new double[k][ages];
        for (int j = 0; j < k; j++) {
            for (int i = 0; i < ages; i++) {
                ageMean[j][i] = ageN[i] >= 5_000 ? ageSum[j][i] / ageN[i] : allSum[j] / Math.max(1, allN);
            }
        }

        Map<Long, Season> byKey = new HashMap<>(seasons.size() * 2);
        for (Season s : seasons) {
            byKey.put(((long) s.playerId() << 16) | s.season(), s);
        }
        double[][] sum = new double[k][MAX_AGE - MIN_AGE + 1];
        double[] w = new double[MAX_AGE - MIN_AGE + 1];
        for (Season a : seasons) {
            Season b = byKey.get(((long) a.playerId() << 16) | (a.season() + 1));
            if (b == null || a.age() < MIN_AGE || a.age() > MAX_AGE) {
                continue;
            }
            double h = 2 / (1 / a.n() + 1 / b.n());
            int idx = a.age() - MIN_AGE;
            w[idx] += h;
            for (int j = 0; j < k; j++) {
                // 평균 회귀 편향 제거: 앞 시즌만 그 나이 평균 쪽으로 당겨 "앞 시즌 실력 추정치"로 쓴다.
                // 짝이 지어지는 건 앞 시즌 성적(실력 + 운) 때문이라 앞 시즌의 운만 걸러내면 되고,
                // 뒤 시즌의 운은 선택과 무관하므로 그대로 둔다 (당기면 오히려 변화가 줄어 보임).
                double ka = stabilizer[j];
                double talentA = (a.n() * a.values()[j] + ka * ageMean[j][idx]) / (a.n() + ka);
                sum[j][idx] += h * (b.values()[j] - talentA) / talentSd[j];
            }
        }
        for (int j = 0; j < k; j++) {
            for (int i = 0; i < w.length; i++) {
                delta[groups[j]][i] = w[i] > 0 ? 10 * sum[j][i] / w[i] : 0;
                weight[groups[j]][i] = w[i];
            }
        }
    }

    /** ±2세 삼각 가중 이동 평균 (가중치 1-2-3-2-1 × 표본), 표본이 적은 나이는 가장 가까운 충분한 나이 값 */
    static double[] smooth(double[] raw, double[] w) {
        int n = raw.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0, ws = 0;
            for (int d = -2; d <= 2; d++) {
                int j = i + d;
                if (j >= 0 && j < n) {
                    double tw = (3 - Math.abs(d)) * w[j];
                    s += raw[j] * tw;
                    ws += tw;
                }
            }
            out[i] = ws > 0 ? s / ws : 0;
        }
        for (int i = 0; i < n; i++) {
            if (w[i] < MIN_AGE_WEIGHT) {
                int near = -1;
                for (int d = 1; d < n && near < 0; d++) {
                    if (i - d >= 0 && w[i - d] >= MIN_AGE_WEIGHT) {
                        near = i - d;
                    } else if (i + d < n && w[i + d] >= MIN_AGE_WEIGHT) {
                        near = i + d;
                    }
                }
                if (near >= 0) {
                    out[i] = out[near];
                }
            }
        }
        // 1년 변화는 ±5점으로 제한 (표본이 얇은 극단 나이 보호)
        for (int i = 0; i < n; i++) {
            out[i] = Math.clamp(out[i], -5, 5);
        }
        return out;
    }

    /** 시즌 중반(7월 1일) 기준 나이 */
    static int age(LocalDate birth, int season) {
        return java.time.Period.between(birth, LocalDate.of(season, 7, 1)).getYears();
    }
}
