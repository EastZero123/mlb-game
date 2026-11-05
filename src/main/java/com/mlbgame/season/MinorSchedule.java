package com.mlbgame.season;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 마이너(AAA·AA) 일정. 저장하지 않고 날짜에서 바로 계산한다 (DB 공간 0, 같은 날짜면 항상 같은 일정).
 * <ul>
 *   <li>레벨마다 30팀(구단별 산하 팀 1개)이 매일 15경기, 월요일 휴식 (실제 마이너 관행)</li>
 *   <li>상대는 원형 라운드로빈(circle method), 같은 상대와 3연전 → 29라운드 순환</li>
 *   <li>홈/원정은 라운드·짝 위치로 번갈아 배정</li>
 * </ul>
 * 개막·종료일은 MLB 일정 기준 상대값: AAA 개막 +2일 ~ MLB 마지막 날, AA 개막 +8일 ~ MLB 마지막 날 -7일
 * (2026 실제: AAA 3/27, AA 4/2 개막). 팀당 약 150경기(AAA) / 140경기(AA).
 */
public final class MinorSchedule {

    public record Game(int awayTeamId, int homeTeamId) {
    }

    private static final int SERIES = 3;

    private MinorSchedule() {
    }

    public static LocalDate start(int level, LocalDate mlbOpening) {
        return mlbOpening.plusDays(level == 1 ? 2 : 8);
    }

    public static LocalDate end(int level, LocalDate mlbLastDay) {
        return level == 1 ? mlbLastDay : mlbLastDay.minusDays(7);
    }

    /** 경기가 있는 날이면 그날의 순번(0부터), 아니면 -1 */
    public static int playDay(LocalDate date, LocalDate start, LocalDate end) {
        if (date.isBefore(start) || date.isAfter(end) || date.getDayOfWeek() == DayOfWeek.MONDAY) {
            return -1;
        }
        long days = ChronoUnit.DAYS.between(start, date);
        return (int) (days - mondaysBefore(start, date));
    }

    /** start 이상 date 미만 사이의 월요일 수 */
    static long mondaysBefore(LocalDate start, LocalDate date) {
        long days = ChronoUnit.DAYS.between(start, date);
        int offset = (DayOfWeek.MONDAY.getValue() - start.getDayOfWeek().getValue() + 7) % 7; // 첫 월요일까지
        return days <= offset ? 0 : (days - offset - 1) / 7 + 1;
    }

    /**
     * @param teams 팀 ID 목록 (짝수, 정렬된 고정 순서)
     */
    public static List<Game> games(int playDay, List<Integer> teams) {
        int n = teams.size();
        if (playDay < 0 || n < 2 || n % 2 != 0) {
            return List.of();
        }
        int series = playDay / SERIES;
        int round = series % (n - 1);
        int cycle = series / (n - 1);
        // circle method: 0번 고정, 나머지 n-1 자리를 round 만큼 회전
        int[] slot = new int[n];
        slot[0] = 0;
        for (int i = 1; i < n; i++) {
            slot[i] = 1 + (i - 1 + round) % (n - 1);
        }
        List<Game> out = new ArrayList<>(n / 2);
        for (int i = 0; i < n / 2; i++) {
            int a = teams.get(slot[i]);
            int b = teams.get(slot[n - 1 - i]);
            boolean flip = ((round + i + cycle) & 1) == 1;
            out.add(flip ? new Game(b, a) : new Game(a, b));
        }
        return out;
    }
}
