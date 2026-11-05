package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class MinorScheduleTest {

    private static final List<Integer> TEAMS = IntStream.range(100, 130).boxed().toList();
    private static final LocalDate START = LocalDate.of(2026, 3, 27);
    private static final LocalDate END = LocalDate.of(2026, 9, 27);

    @Test
    void 매일_모든_팀이_한_번씩_경기한다() {
        for (int day = 0; day < 200; day++) {
            List<MinorSchedule.Game> games = MinorSchedule.games(day, TEAMS);
            assertThat(games).hasSize(15);
            Set<Integer> seen = new HashSet<>();
            for (MinorSchedule.Game g : games) {
                assertThat(seen.add(g.awayTeamId())).isTrue();
                assertThat(seen.add(g.homeTeamId())).isTrue();
            }
        }
    }

    @Test
    void 같은_상대와_3연전() {
        assertThat(MinorSchedule.games(0, TEAMS)).isEqualTo(MinorSchedule.games(2, TEAMS));
        assertThat(MinorSchedule.games(2, TEAMS)).isNotEqualTo(MinorSchedule.games(3, TEAMS));
    }

    @Test
    void 월요일은_쉬고_시즌_밖은_경기_없음() {
        LocalDate d = START;
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        assertThat(MinorSchedule.playDay(d, START, END)).isEqualTo(-1);
        assertThat(MinorSchedule.playDay(START.minusDays(1), START, END)).isEqualTo(-1);
        assertThat(MinorSchedule.playDay(END.plusDays(1), START, END)).isEqualTo(-1);
        assertThat(MinorSchedule.playDay(START, START, END)).isZero();
    }

    @Test
    void 경기일_순번은_연속이고_시즌_경기수와_홈원정_균형이_현실적() {
        int expected = 0;
        Map<Integer, int[]> homeAway = new HashMap<>();
        for (LocalDate d = START; !d.isAfter(END); d = d.plusDays(1)) {
            int day = MinorSchedule.playDay(d, START, END);
            if (day < 0) {
                continue;
            }
            assertThat(day).isEqualTo(expected++);
            for (MinorSchedule.Game g : MinorSchedule.games(day, TEAMS)) {
                homeAway.computeIfAbsent(g.homeTeamId(), k -> new int[2])[0]++;
                homeAway.computeIfAbsent(g.awayTeamId(), k -> new int[2])[1]++;
            }
        }
        assertThat(expected).isBetween(140, 165);
        for (int[] ha : homeAway.values()) {
            assertThat(Math.abs(ha[0] - ha[1])).isLessThanOrEqualTo(30);
        }
    }
}
