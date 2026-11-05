package com.mlbgame.sim;

import java.util.Arrays;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * 능력치 z 점수 → 타석 결과 로그 배율 계수. 기준 결과는 인플레이 아웃.
 * <pre>
 * 가중치_i = 리그_i × exp(Σ_k 계수[i][k] × z_k)    (i = 삼진, 볼넷, 사구, 단타, 2루타, 3루타, 홈런)
 * 가중치_아웃 = 리그_아웃 (땅볼/뜬공 비율만 성향 능력치로 나눔)
 * </pre>
 * 검증기가 실제 기록으로 회귀 추정해 mst_league 에 저장한다. 없으면 {@link #defaults()} (수작업 초기값).
 *
 * @param batter  [7 결과][타자 능력치 6: 컨택, 파워, 선구, 타구질, 땅볼성향, 스피드]
 * @param pitcher [7 결과][투수 능력치 4: 구위, 제구, 장타억제, 땅볼유도]
 */
public record RateCoefficients(double[][] batter, double[][] pitcher) {

    public static final int ROWS = 7;
    public static final int BAT_RATINGS = 6;
    public static final int PIT_RATINGS = 4;
    public static final String[] BAT_NAMES = {"컨택", "파워", "선구", "타구질", "땅볼성향", "스피드"};
    public static final String[] PIT_NAMES = {"구위", "제구", "장타억제", "땅볼유도"};

    public RateCoefficients {
        check(batter, BAT_RATINGS);
        check(pitcher, PIT_RATINGS);
        batter = deepCopy(batter);
        pitcher = deepCopy(pitcher);
    }

    public static RateCoefficients defaults() {
        return new RateCoefficients(new double[][] {
                {-0.22, 0, -0.05, 0, 0, 0},     // 삼진
                {0, 0, 0.28, 0, 0, 0},          // 볼넷
                {0, 0, 0, 0, 0, 0},             // 사구
                {0.12, 0, 0, 0.10, 0, 0.05},    // 단타
                {0, 0.12, 0, 0.15, 0, 0.03},    // 2루타
                {0, 0.05, 0, 0, 0, 0.35},       // 3루타
                {0, 0.33, 0, 0.07, 0, 0}        // 홈런
        }, new double[][] {
                {0.28, 0, 0, 0},                // 삼진
                {0, -0.30, 0, 0},               // 볼넷
                {0, -0.15, 0, 0},               // 사구
                {-0.05, 0, -0.08, 0},           // 단타
                {0, 0, -0.15, 0},               // 2루타
                {0, 0, -0.15, 0},               // 3루타
                {-0.08, 0, -0.30, -0.10}        // 홈런
        });
    }

    /** DB 저장용: 숫자 70개를 쉼표로 (타자 7×6, 투수 7×4 순) */
    public String encode() {
        StringJoiner j = new StringJoiner(",");
        for (double[] row : batter) {
            for (double v : row) {
                j.add(String.format(Locale.ROOT, "%.6f", v));
            }
        }
        for (double[] row : pitcher) {
            for (double v : row) {
                j.add(String.format(Locale.ROOT, "%.6f", v));
            }
        }
        return j.toString();
    }

    public static RateCoefficients decode(String s) {
        String[] parts = s.split(",");
        if (parts.length != ROWS * (BAT_RATINGS + PIT_RATINGS)) {
            throw new IllegalArgumentException("계수 개수 오류: " + parts.length);
        }
        double[][] b = new double[ROWS][BAT_RATINGS];
        double[][] p = new double[ROWS][PIT_RATINGS];
        int k = 0;
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < BAT_RATINGS; j++) {
                b[i][j] = Double.parseDouble(parts[k++].trim());
            }
        }
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < PIT_RATINGS; j++) {
                p[i][j] = Double.parseDouble(parts[k++].trim());
            }
        }
        return new RateCoefficients(b, p);
    }

    private static void check(double[][] m, int cols) {
        if (m == null || m.length != ROWS) {
            throw new IllegalArgumentException("행 수는 " + ROWS);
        }
        for (double[] row : m) {
            if (row == null || row.length != cols) {
                throw new IllegalArgumentException("열 수는 " + cols);
            }
            for (double v : row) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("계수는 유한해야 함");
                }
            }
        }
    }

    private static double[][] deepCopy(double[][] m) {
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = Arrays.copyOf(m[i], m[i].length);
        }
        return c;
    }
}
