package com.mlbgame.validation;

import java.util.List;

import com.mlbgame.util.Linear;

/**
 * 능력치 z → 결과 계수 추정. 결과 i 마다 "i vs 인플레이 아웃" 이항 로지스틱 회귀를 따로 푼다
 * (기준 범주 다항 로짓을 범주별로 나눠 푸는 방식, 추정치는 일치).
 * <p>
 * log(p_i / p_아웃) = 절편 + Σ 계수·z  →  RateModel 의 {@code 리그_i × exp(계수·z)} 구조와 정확히 같다.
 * 절편은 버리고(리그 기준값 보정이 맡음) 계수만 쓴다. 표본이 적은 결과(3루타·사구)는 릿지 벌점으로 안정화.
 */
final class CoefficientFitter {

    /** 관측 1건: 결과 횟수, 기준(아웃) 횟수, 능력치 z 벡터 */
    record Obs(double successes, double reference, double[] z) {
    }

    private static final int MAX_ITER = 50;

    private CoefficientFitter() {
    }

    /** @return {절편, 계수1..k} */
    static double[] fit(List<Obs> data, int k, double ridge) {
        int m = k + 1;
        double[] theta = new double[m];
        double[] x = new double[m];
        for (int iter = 0; iter < MAX_ITER; iter++) {
            double[] grad = new double[m];
            double[][] hess = new double[m][m];
            for (Obs o : data) {
                double n = o.successes() + o.reference();
                if (n <= 0) {
                    continue;
                }
                x[0] = 1;
                System.arraycopy(o.z(), 0, x, 1, k);
                double eta = 0;
                for (int j = 0; j < m; j++) {
                    eta += theta[j] * x[j];
                }
                double p = 1 / (1 + Math.exp(-eta));
                double r = o.successes() - n * p;
                double wgt = n * p * (1 - p);
                for (int a = 0; a < m; a++) {
                    grad[a] += r * x[a];
                    for (int b = 0; b <= a; b++) {
                        hess[a][b] += wgt * x[a] * x[b];
                    }
                }
            }
            for (int a = 0; a < m; a++) {
                for (int b = a + 1; b < m; b++) {
                    hess[a][b] = hess[b][a];
                }
            }
            for (int j = 1; j < m; j++) {
                grad[j] -= ridge * theta[j];
                hess[j][j] += ridge;
            }
            double[] step = solve(hess, grad);
            double max = 0;
            for (int j = 0; j < m; j++) {
                theta[j] += step[j];
                max = Math.max(max, Math.abs(step[j]));
            }
            if (max < 1e-9) {
                break;
            }
        }
        return theta;
    }

    static double[] solve(double[][] a, double[] b) {
        return Linear.solve(a, b);
    }
}
