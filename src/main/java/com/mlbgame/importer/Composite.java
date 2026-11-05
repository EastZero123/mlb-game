package com.mlbgame.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 여러 지표 → 20~80 능력치 1개.
 *
 * <ol>
 *   <li>지표별 z 점수: 표본이 충분한 선수들의 평균·표준편차 기준</li>
 *   <li>표본 보정: {@code z' = (n·z + k·prior) / (n + k)} — 표본(n)이 안정화 상수(k)보다 작을수록 prior 로 끌려감</li>
 *   <li>가중합 → 정보량이 충분한 선수들 기준으로 다시 표준화 (가중합은 분산이 줄어들기 때문)</li>
 *   <li>{@code 50 + 10·z}, 20~80 으로 제한</li>
 * </ol>
 * prior 를 음수로 두면 MLB 기록이 거의 없는 선수는 평균(50)이 아니라 평균 아래로 시작한다.
 */
final class Composite {

    /** @param n 표본 크기 (타석, 투구 수 등) */
    record Obs(double value, double n) {
    }

    private record Part(double weight, double k, boolean higherIsBetter, Map<Integer, Obs> data) {
    }

    private static final int MIN_POPULATION = 20;

    private final double priorZ;
    private final List<Part> parts = new ArrayList<>();

    Composite(double priorZ) {
        this.priorZ = priorZ;
    }

    Composite add(double weight, double stabilizer, boolean higherIsBetter, Map<Integer, Obs> data) {
        parts.add(new Part(weight, stabilizer, higherIsBetter, data));
        return this;
    }

    Map<Integer, Integer> rate(Collection<Integer> players) {
        int m = parts.size();
        double[] mean = new double[m];
        double[] sd = new double[m];
        double totalWeight = 0;
        for (int j = 0; j < m; j++) {
            Part p = parts.get(j);
            double[] ms = meanSd(p.data().values(), p.k());
            mean[j] = ms[0];
            sd[j] = ms[1];
            totalWeight += p.weight();
        }

        Map<Integer, Double> raw = new HashMap<>(players.size() * 2);
        List<Double> qualified = new ArrayList<>();
        for (int id : players) {
            double sum = 0;
            double info = 0;
            for (int j = 0; j < m; j++) {
                Part p = parts.get(j);
                Obs o = p.data().get(id);
                double z = priorZ;
                if (o != null && o.n() > 0 && sd[j] > 0) {
                    double observed = (o.value() - mean[j]) / sd[j];
                    if (!p.higherIsBetter()) {
                        observed = -observed;
                    }
                    z = (o.n() * observed + p.k() * priorZ) / (o.n() + p.k());
                    info += p.weight() * o.n() / (o.n() + p.k());
                }
                sum += p.weight() * z;
            }
            raw.put(id, sum);
            if (info >= totalWeight * 0.5) {
                qualified.add(sum);
            }
        }

        double[] ms = qualified.size() >= MIN_POPULATION ? meanSdOf(qualified) : meanSdOf(raw.values());
        Map<Integer, Integer> out = new HashMap<>(players.size() * 2);
        raw.forEach((id, v) -> out.put(id, toRating(ms[1] > 0 ? (v - ms[0]) / ms[1] : 0)));
        return out;
    }

    static int toRating(double z) {
        return clampRating((int) Math.round(50 + 10 * z));
    }

    static int clampRating(int rating) {
        return Math.clamp(rating, 20, 80);
    }

    /** 표본이 k 이상인 관측치로 평균·표준편차. 그런 선수가 적으면 전체 사용. */
    private static double[] meanSd(Collection<Obs> obs, double k) {
        List<Double> enough = new ArrayList<>();
        List<Double> all = new ArrayList<>();
        for (Obs o : obs) {
            if (o.n() > 0) {
                all.add(o.value());
                if (o.n() >= k) {
                    enough.add(o.value());
                }
            }
        }
        return meanSdOf(enough.size() >= MIN_POPULATION ? enough : all);
    }

    private static double[] meanSdOf(Collection<Double> values) {
        if (values.isEmpty()) {
            return new double[] {0, 0};
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / values.size();
        double sq = 0;
        for (double v : values) {
            sq += (v - mean) * (v - mean);
        }
        return new double[] {mean, Math.sqrt(sq / values.size())};
    }
}
