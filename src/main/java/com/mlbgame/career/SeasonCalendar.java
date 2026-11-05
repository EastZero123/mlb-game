package com.mlbgame.career;

import java.time.LocalDate;

/**
 * 시즌별 날짜. 2026 실제 일정(mst_schedule)을 템플릿으로 쓰고, 다른 시즌은 날짜만 옮긴다.
 * <pre>
 * 이동 일수 = 364 × (시즌 - 2026) + 7 × round(1.25 × (시즌 - 2026) / 7)
 * </pre>
 * 364일 = 52주라 요일이 그대로이고, 해마다 1.25일씩 앞당겨지는 만큼 가끔 한 주를 더해 개막이 3월 하순에 머문다.
 * 경기 번호·대진은 템플릿 그대로 (매년 같은 일정 구조).
 */
public final class SeasonCalendar {

    private SeasonCalendar() {
    }

    public static long shiftDays(int season) {
        int k = season - MasterData.START_SEASON;
        return 364L * k + 7L * Math.round(1.25 * k / 7.0);
    }

    /** 실제 날짜 → 템플릿(2026) 날짜 */
    public static LocalDate toTemplate(LocalDate date, int season) {
        return date.minusDays(shiftDays(season));
    }

    /** 템플릿(2026) 날짜 → 그 시즌 실제 날짜 */
    public static LocalDate fromTemplate(LocalDate templateDate, int season) {
        return templateDate.plusDays(shiftDays(season));
    }

    public static LocalDate openingDay(MasterData.Snapshot snap, int season) {
        return fromTemplate(snap.openingDay(), season);
    }

    public static LocalDate lastDay(MasterData.Snapshot snap, int season) {
        return fromTemplate(snap.lastDay(), season);
    }
}
