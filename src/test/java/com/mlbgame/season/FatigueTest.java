package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class FatigueTest {

    private final Map<Integer, int[]> load = new HashMap<>();

    @Test
    void 등판_기록이_없으면_가능() {
        assertThat(Fatigue.available(load, 1, 100)).isTrue();
    }

    @Test
    void 어제_20구_이상이면_오늘_휴식() {
        Fatigue.record(load, 1, 99, 22);
        assertThat(Fatigue.available(load, 1, 100)).isFalse();
        assertThat(Fatigue.available(load, 1, 101)).isTrue();
        Fatigue.record(load, 2, 99, 15);
        assertThat(Fatigue.available(load, 2, 100)).isTrue();
    }

    @Test
    void 연투_다음날은_휴식() {
        Fatigue.record(load, 1, 98, 12);
        Fatigue.record(load, 1, 99, 12);
        assertThat(Fatigue.available(load, 1, 100)).isFalse();
    }

    @Test
    void 그제_40구_이상이면_오늘도_휴식() {
        Fatigue.record(load, 1, 98, 45);
        assertThat(Fatigue.available(load, 1, 100)).isFalse();
        assertThat(Fatigue.available(load, 1, 101)).isTrue();
    }

    @Test
    void 최근_6일_중_3번_등판하면_휴식() {
        Fatigue.record(load, 1, 94, 10);
        Fatigue.record(load, 1, 96, 10);
        Fatigue.record(load, 1, 98, 10);
        assertThat(Fatigue.appearancesInWindow(load.get(1), 100)).isEqualTo(3);
        assertThat(Fatigue.available(load, 1, 100)).isFalse();
        // 94일 등판이 창(94~99) 밖으로 나가는 101일엔 가능
        assertThat(Fatigue.appearancesInWindow(load.get(1), 101)).isEqualTo(2);
        assertThat(Fatigue.available(load, 1, 101)).isTrue();
    }

    @Test
    void 더블헤더_같은_날_재등판_불가_투구수는_합산() {
        Fatigue.record(load, 1, 100, 15);
        assertThat(Fatigue.available(load, 1, 100)).isFalse();
        Fatigue.record(load, 1, 100, 20);
        assertThat(load.get(1)[Fatigue.PITCHES]).isEqualTo(35);
        assertThat(Integer.bitCount(load.get(1)[Fatigue.MASK])).isEqualTo(1);
    }

    @Test
    void 부상_기간은_최소_10일_중앙_약_3주_최대_180일() {
        SplittableRandom rng = new SplittableRandom(3);
        int[] days = new int[10_000];
        for (int i = 0; i < days.length; i++) {
            days[i] = DayRules.injuryDays(rng, false);
        }
        Arrays.sort(days);
        assertThat(days[0]).isGreaterThanOrEqualTo(10);
        assertThat(days[days.length / 2]).isBetween(18, 26);
        assertThat(days[days.length - 1]).isLessThanOrEqualTo(180);
    }
}
