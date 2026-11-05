package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PendingStatsTest {

    /** [구단, 경기, 타석] */
    private static Map<Long, int[]> day(int team, int... levelAndPlayer) {
        Map<Long, int[]> m = new HashMap<>();
        for (int i = 0; i < levelAndPlayer.length; i += 2) {
            m.put(DayBatch.key(levelAndPlayer[i], levelAndPlayer[i + 1]), new int[] {team, 1, 4});
        }
        return m;
    }

    @Test
    void 마이너_행만_떼어_내고_MLB_행은_오늘_저장에_남긴다() {
        Map<Long, int[]> today = day(147, 0, 1, 1, 2, 2, 3);
        Map<Long, int[]> minor = PendingStats.takeMinor(today);
        assertThat(today).containsOnlyKeys(DayBatch.key(0, 1));
        assertThat(minor).containsOnlyKeys(DayBatch.key(1, 2), DayBatch.key(2, 3));
    }

    @Test
    void 성공한_날만_합산하고_같은_선수는_한_행으로_더한다() {
        PendingStats p = new PendingStats();
        p.add(day(147, 1, 2), Map.of());
        p.add(day(111, 1, 2), Map.of()); // 같은 선수, 다음 날 다른 구단(트레이드)
        assertThat(p.days).isEqualTo(2);
        assertThat(p.bat).hasSize(1);
        assertThat(p.bat.get(DayBatch.key(1, 2))).containsExactly(111, 2, 8); // 구단은 마지막 값, 나머지는 합
    }

    @Test
    void 함께_저장할_행을_만들어도_모아_둔_값은_그대로() {
        PendingStats p = new PendingStats();
        p.add(day(147, 1, 2), Map.of());
        Map<Long, int[]> write = PendingStats.combined(p.bat, day(147, 1, 2, 2, 9));
        assertThat(write.get(DayBatch.key(1, 2))).containsExactly(147, 2, 8);
        assertThat(write).containsKey(DayBatch.key(2, 9));
        assertThat(p.bat.get(DayBatch.key(1, 2))).containsExactly(147, 1, 4); // 저장 실패하면 이 값으로 남아야 함
    }

    @Test
    void 일곱째_날과_단계가_바뀌는_날에_함께_저장() {
        PendingStats p = new PendingStats();
        for (int d = 1; d < PendingStats.FLUSH_DAYS; d++) {
            assertThat(p.due(false)).isFalse();
            p.add(Map.of(), Map.of());
        }
        assertThat(p.due(false)).isTrue(); // 7일째
        p.clear();
        assertThat(p.due(true)).isTrue();  // 정규시즌 마지막 날 등
        assertThat(p.isEmpty()).isTrue();
    }
}
