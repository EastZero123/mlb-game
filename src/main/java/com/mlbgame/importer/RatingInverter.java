package com.mlbgame.importer;

import com.mlbgame.util.Linear;

/**
 * 기록 → 능력치 z 역산 (MAP 추정). 시뮬레이터와 같은 모델을 거꾸로 푼다.
 * <pre>
 * p_i ∝ exp(base_i + Σ_k 계수[i][k] × z_k)   (i = 기준 결과 외 결과들),  p_기준 ∝ 1
 * 최대화: Σ_i n_i log p_i + n_기준 log p_기준 − Σ_k (z_k − 사전평균_k)² / (2 σ²)
 * </pre>
 * 표본이 많으면 기록이, 적으면 사전평균(레벨 평균)이 결과를 정한다. 뉴턴법, 차원 ≤ 6.
 */
final class RatingInverter {

    private static final int MAX_ITER = 40;

    private RatingInverter() {
    }

    /**
     * @param counts   결과별 횟수 (기준 결과 제외), 길이 K
     * @param refCount 기준 결과(인플레이 아웃) 횟수
     * @param base     결과별 기본 로그 배율 log(리그_i / 리그_기준) + 고정 차원 기여, 길이 K
     * @param beta     K × d 계수 (추정하는 차원만)
     * @param prior    사전 평균, 길이 d
     * @param priorSd  사전 표준편차 (z 단위)
     * @return 추정 z, 길이 d
     */
    static double[] map(double[] counts, double refCount, double[] base, double[][] beta, double[] prior,
                        double priorSd) {
        int k = counts.length;
        int d = prior.length;
        double n = refCount;
        for (double c : counts) {
            n += c;
        }
        double invVar = 1 / (priorSd * priorSd);
        double[] z = prior.clone();
        double[] p = new double[k];
        for (int iter = 0; iter < MAX_ITER; iter++) {
            double denom = 1;
            for (int i = 0; i < k; i++) {
                double eta = base[i];
                for (int j = 0; j < d; j++) {
                    eta += beta[i][j] * z[j];
                }
                p[i] = Math.exp(eta);
                denom += p[i];
            }
            double[] mean = new double[d];
            for (int i = 0; i < k; i++) {
                p[i] /= denom;
                for (int j = 0; j < d; j++) {
                    mean[j] += p[i] * beta[i][j];
                }
            }
            double[] grad = new double[d];
            double[][] negHess = new double[d][d];
            for (int j = 0; j < d; j++) {
                double obs = 0;
                for (int i = 0; i < k; i++) {
                    obs += counts[i] * beta[i][j];
                }
                grad[j] = obs - n * mean[j] - (z[j] - prior[j]) * invVar;
                for (int l = 0; l < d; l++) {
                    double second = 0;
                    for (int i = 0; i < k; i++) {
                        second += p[i] * beta[i][j] * beta[i][l];
                    }
                    negHess[j][l] = n * (second - mean[j] * mean[l]);
                }
                negHess[j][j] += invVar;
            }
            double[] step = Linear.solve(negHess, grad);
            double max = 0;
            for (int j = 0; j < d; j++) {
                z[j] += step[j];
                max = Math.max(max, Math.abs(step[j]));
            }
            if (max < 1e-8) {
                break;
            }
        }
        return z;
    }
}
