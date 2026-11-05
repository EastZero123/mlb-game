package com.mlbgame.web;

import java.util.SplittableRandom;

/**
 * 스카우트 추정 범위 (docs/ARCHITECTURE.md 6번). 유저에게는 실제 능력치 대신 범위만 보인다.
 * <pre>
 * 폭 w     = 스카우트 단계별 ±12 / ±9 / ±6 / ±4 / ±2 (자기 팀 선수는 한 단계 위)
 * 오프셋 u = [-1, 1], 커리어 seed + 선수 + 항목으로 결정 (저장 안 함, 새로고침해도 같음)
 * 중심     = 실제값 + u × w × 0.5,  범위 = [중심 - w, 중심 + w]  → 항상 실제값 포함
 * </pre>
 */
public final class ScoutView {

    static final int[] WIDTH = {12, 9, 6, 4, 2};

    private ScoutView() {
    }

    public static int width(int scoutLevel, boolean ownTeam) {
        int level = Math.clamp(scoutLevel + (ownTeam ? 1 : 0), 1, WIDTH.length);
        return WIDTH[level - 1];
    }

    /** 범위 중심 = 스카우트 추정값 (드래프트 후보 순위 등 유저에게 보이는 판단 기준) */
    public static double center(int trueValue, int width, long seed, int playerId, int column) {
        double u = new SplittableRandom(seed ^ (playerId * 0x9E3779B97F4A7C15L) ^ (column * 0xBF58476D1CE4E5B9L))
                .nextDouble() * 2 - 1;
        return trueValue + u * width * 0.5;
    }

    /**
     * 스카우트 추정 가치 (현재 가치와 잠재력 반반, 실제값 대신 범위 중심). 드래프트·FA 후보 순위용.
     *
     * @param potentialWeight 잠재력 비중 (드래프트 0.5, FA 0 = 현재 가치만)
     */
    public static double estimate(short[] r, int pos, int width, long seed, int playerId, double potentialWeight) {
        short[] est = new short[r.length];
        for (int c = 0; c < r.length; c++) {
            est[c] = r[c] < 0 ? -1 : (short) Math.clamp(Math.round(center(r[c], width, seed, playerId, c)), 20, 80);
        }
        double value = com.mlbgame.career.ContractModel.value(est, pos);
        if (potentialWeight <= 0) {
            return value;
        }
        int p = com.mlbgame.career.MasterData.POTENTIAL;
        double potential = center(r[p], width * 3 / 2, seed, playerId, p);
        return (1 - potentialWeight) * value + potentialWeight * potential;
    }

    /** @return "45–57", 값이 없으면 "-" */
    public static String range(int trueValue, int width, long seed, int playerId, int column) {
        if (trueValue < 0) {
            return "-";
        }
        double center = center(trueValue, width, seed, playerId, column);
        int lo = (int) Math.clamp(Math.round(center - width), 20, 80);
        int hi = (int) Math.clamp(Math.round(center + width), 20, 80);
        lo = Math.min(lo, trueValue);
        hi = Math.max(hi, trueValue);
        return lo == hi ? String.valueOf(lo) : lo + "–" + hi;
    }
}
