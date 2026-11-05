package com.mlbgame.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mlbgame.importer.Composite.Obs;

class CompositeTest {

    @Test
    void 표본이_충분한_선수들은_평균_50_표준편차_10() {
        Map<Integer, Obs> data = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            data.put(i, new Obs(i, 10_000)); // 표본 매우 큼 → 보정 거의 없음
            ids.add(i);
        }
        Map<Integer, Integer> r = new Composite(-1).add(1.0, 100, true, data).rate(ids);

        double mean = r.values().stream().mapToInt(Integer::intValue).average().orElseThrow();
        assertThat(mean).isBetween(49.0, 51.0);
        assertThat(r.get(199)).isGreaterThan(r.get(100)).isGreaterThan(r.get(0));
    }

    @Test
    void 낮을수록_좋은_지표는_방향이_반대() {
        Map<Integer, Obs> data = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.put(i, new Obs(i, 10_000));
            ids.add(i);
        }
        Map<Integer, Integer> r = new Composite(0).add(1.0, 100, false, data).rate(ids);
        assertThat(r.get(0)).isGreaterThan(r.get(99));
    }

    @Test
    void 표본이_작으면_prior_쪽으로_끌린다() {
        Map<Integer, Obs> data = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.put(i, new Obs(i % 10, 1_000));
            ids.add(i);
        }
        data.put(1000, new Obs(9, 1_000)); // 최상위 값, 표본 충분
        data.put(1001, new Obs(9, 5));     // 같은 값, 표본 아주 작음
        ids.add(1000);
        ids.add(1001);
        ids.add(1002);                     // 기록 없음

        Map<Integer, Integer> r = new Composite(-1).add(1.0, 100, true, data).rate(ids);

        assertThat(r.get(1001)).isLessThan(r.get(1000));
        assertThat(r.get(1002)).isLessThan(50); // 기록 없는 선수는 평균 아래 (prior -1)
        assertThat(r.get(1001)).isGreaterThan(r.get(1002));
    }

    @Test
    void 결과는_20에서_80_사이() {
        Map<Integer, Obs> data = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            data.put(i, new Obs(i == 0 ? 1_000_000 : i % 5, 10_000)); // 극단값 1개
            ids.add(i);
        }
        Map<Integer, Integer> r = new Composite(0).add(1.0, 100, true, data).rate(ids);
        assertThat(r.values()).allMatch(v -> v >= 20 && v <= 80);
        assertThat(r.get(0)).isEqualTo(80);
    }
}
