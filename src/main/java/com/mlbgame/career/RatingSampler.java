package com.mlbgame.career;

import java.util.SplittableRandom;

/**
 * 커리어별 능력치 샘플링: 마스터 값을 중앙값으로 하는 절단 정규분포 (docs/ARCHITECTURE.md 5번).
 * <pre>
 * 값 = 마스터 + σ·z,  z ~ N(0,1) 을 |z| ≤ 2 가 될 때까지 다시 뽑음 (경계에 몰리지 않도록 clamp 대신 재추첨)
 * σ  = 3 × min(표본 배율 × 나이 배율 × 항목 배율, 상한)
 * </pre>
 * 같은 커리어 seed + 선수 ID 면 항상 같은 값 (선수 순서와 무관).
 */
public final class RatingSampler {

    public static final double BASE_SIGMA = 3.0;
    public static final double TRUNCATE = 2.0;
    private static final double MAX_MULTIPLIER = 1.6;

    /**
     * 항목 배율. 측정이 정확한 항목(스프린트 속도)은 작게, 추정 성격 항목(수비·내구성·잠재력)은 크게.
     * 구사율(*_use)은 0 = 샘플링하지 않음.
     */
    static final double[] TYPE_WEIGHT = {
            1.0, 1.0, 1.0, 1.0, 0.8, 0.6, 1.0,          // 컨택 파워 선구 타구질 성향 스피드 주루
            1.0, 1.1, 0.8, 0.8,                          // 제구 억제 땅볼 스태미나
            0, 0.9, 0, 0.9, 0, 0.9, 0, 0.9, 0, 0.9, 0, 0.9, 0, 0.9, 0, 0.9, // 구종 구사율/구위
            1.3, 1.3, 1.3, 1.3, 1.3, 1.3, 1.3, 1.3,      // 수비 8
            1.0, 1.3, 1.3,                               // 송구 프레이밍 블로킹
            1.3, 1.5};                                   // 내구성 잠재력

    private RatingSampler() {
    }

    public static SplittableRandom rng(long careerSeed, int playerId) {
        return new SplittableRandom(careerSeed ^ (playerId * 0x9E3779B97F4A7C15L));
    }

    /** @return 커리어 능력치 (마스터와 같은 컬럼 순서, NULL = -1) */
    public static short[] sample(MasterPlayer p, int season, SplittableRandom rng) {
        double base = BASE_SIGMA * sampleMultiplier(p) * ageMultiplier(p.age(season));
        short[] out = new short[p.ratings().length];
        for (int i = 0; i < out.length; i++) {
            int master = p.rating(i);
            if (master < 0 || TYPE_WEIGHT[i] == 0) {
                out[i] = (short) master;
                continue;
            }
            double sigma = Math.min(base * TYPE_WEIGHT[i], BASE_SIGMA * MAX_MULTIPLIER);
            out[i] = (short) Math.clamp(Math.round(master + sigma * truncatedGaussian(rng)), 20, 80);
        }
        return out;
    }

    /** 숨김 성격 (적응력·멘탈 안정성·프로의식): 마스터 값이 없으므로 50 중심 σ=10 */
    public static int personality(SplittableRandom rng) {
        return (int) Math.clamp(Math.round(50 + 10 * truncatedGaussian(rng)), 20, 80);
    }

    static double truncatedGaussian(SplittableRandom rng) {
        double z;
        do {
            z = rng.nextGaussian();
        } while (Math.abs(z) > TRUNCATE);
        return z;
    }

    /** 2026 표본이 적을수록 불확실 → 큰 σ. 마이너 환산·무작위 능력치는 더 크게 */
    static double sampleMultiplier(MasterPlayer p) {
        return switch (p.source()) {
            case 1 -> 1.4;
            case 2 -> 1.6;
            default -> 1 + 0.6 * (1 - Math.min(1.0, p.sample() / 500.0));
        };
    }

    /** 젊을수록 큰 σ, 베테랑은 작게 */
    static double ageMultiplier(int age) {
        if (age <= 22) {
            return 1.3;
        }
        if (age <= 25) {
            return 1.15;
        }
        if (age <= 30) {
            return 1.0;
        }
        return age <= 34 ? 0.9 : 0.85;
    }
}
