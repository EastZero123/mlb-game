package com.mlbgame.season;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.mlbgame.career.MasterData;

/**
 * 포스트시즌 대진 (2022~ MLB 12팀 방식). 순수 계산이라 DB·시뮬과 무관.
 * <pre>
 * 리그별 시드: 지구 우승 3팀 = 1~3 (성적순), 나머지 중 상위 3팀 = 와일드카드 4~6
 * 와일드카드 3전   : 3 vs 6 (시리즈 1/3), 4 vs 5 (시리즈 2/4)   상위 시드 홈 3경기
 * 디비전 5전       : 1 vs (4·5 승자) (5/7), 2 vs (3·6 승자) (6/8)  2-2-1
 * 챔피언십 7전     : 디비전 승자끼리 (9/10), 상위 시드 홈            2-3-2
 * 월드시리즈 7전   : 리그 챔피언끼리 (11), 정규시즌 성적 좋은 팀 홈   2-3-2
 * </pre>
 * 순위 동률은 득실차, 그다음 팀 ID 로 가른다. 라운드 사이 하루 휴식.
 */
final class Postseason {

    static final int WILD_CARD = 1, DIVISION = 2, CHAMPIONSHIP = 3, WORLD_SERIES = 4;
    static final String[] LEAGUES = {"AL", "NL"};

    record Seed(int team, int seed) {
    }

    private Postseason() {
    }

    /** 성적순 (승 → 득실차 → 팀 ID) */
    static Comparator<Integer> byRecord(Map<Integer, int[]> standings) {
        return Comparator.<Integer>comparingInt(t -> -standings.get(t)[CareerRuntime.W])
                .thenComparingInt(t -> -(standings.get(t)[CareerRuntime.RF] - standings.get(t)[CareerRuntime.RA]))
                .thenComparingInt(t -> t);
    }

    /** 리그 시드 1~6 */
    static List<Seed> seeds(String league, Map<Integer, int[]> standings, Map<Integer, MasterData.TeamInfo> teams) {
        Comparator<Integer> order = byRecord(standings);
        List<Integer> divisionWinners = new ArrayList<>();
        for (String div : List.of("E", "C", "W")) {
            teams.values().stream()
                    .filter(t -> t.league().equals(league) && t.division().equals(div))
                    .map(MasterData.TeamInfo::teamId)
                    .min(order)
                    .ifPresent(divisionWinners::add);
        }
        divisionWinners.sort(order);
        List<Integer> others = teams.values().stream()
                .filter(t -> t.league().equals(league) && !divisionWinners.contains(t.teamId()))
                .map(MasterData.TeamInfo::teamId)
                .sorted(order)
                .toList();
        List<Seed> seeds = new ArrayList<>(6);
        for (int i = 0; i < divisionWinners.size(); i++) {
            seeds.add(new Seed(divisionWinners.get(i), i + 1));
        }
        for (int i = 0; i < 3 && i < others.size(); i++) {
            seeds.add(new Seed(others.get(i), divisionWinners.size() + i + 1));
        }
        return seeds;
    }

    static List<Series> wildCard(Map<Integer, int[]> standings, Map<Integer, MasterData.TeamInfo> teams,
                                 LocalDate start) {
        List<Series> out = new ArrayList<>(4);
        for (int l = 0; l < 2; l++) {
            List<Seed> s = seeds(LEAGUES[l], standings, teams);
            out.add(new Series(1 + 2 * l, WILD_CARD, LEAGUES[l], 3, s.get(2).team(), s.get(5).team(), 3, 6, start));
            out.add(new Series(2 + 2 * l, WILD_CARD, LEAGUES[l], 3, s.get(3).team(), s.get(4).team(), 4, 5, start));
        }
        return out;
    }

    /** 직전 라운드가 모두 끝났으면 다음 라운드 시리즈, 아니면 빈 목록 */
    static List<Series> nextRound(List<Series> all, Map<Integer, int[]> standings,
                                  Map<Integer, MasterData.TeamInfo> teams, LocalDate start) {
        int last = all.stream().mapToInt(s -> s.round).max().orElse(0);
        if (last == 0 || last == WORLD_SERIES || all.stream().anyMatch(s -> s.round == last && !s.done())) {
            return List.of();
        }
        List<Series> out = new ArrayList<>();
        switch (last) {
            case WILD_CARD -> {
                for (int l = 0; l < 2; l++) {
                    List<Seed> s = seeds(LEAGUES[l], standings, teams);
                    Series wc36 = find(all, 1 + 2 * l);
                    Series wc45 = find(all, 2 + 2 * l);
                    out.add(new Series(5 + 2 * l, DIVISION, LEAGUES[l], 5, s.get(0).team(), wc45.winner, 1,
                            wc45.seedOf(wc45.winner), start));
                    out.add(new Series(6 + 2 * l, DIVISION, LEAGUES[l], 5, s.get(1).team(), wc36.winner, 2,
                            wc36.seedOf(wc36.winner), start));
                }
            }
            case DIVISION -> {
                for (int l = 0; l < 2; l++) {
                    Series a = find(all, 5 + 2 * l);
                    Series b = find(all, 6 + 2 * l);
                    int ta = a.winner;
                    int tb = b.winner;
                    int sa = a.seedOf(ta);
                    int sb = b.seedOf(tb);
                    out.add(sa < sb
                            ? new Series(9 + l, CHAMPIONSHIP, LEAGUES[l], 7, ta, tb, sa, sb, start)
                            : new Series(9 + l, CHAMPIONSHIP, LEAGUES[l], 7, tb, ta, sb, sa, start));
                }
            }
            case CHAMPIONSHIP -> {
                Series al = find(all, 9);
                Series nl = find(all, 10);
                int ta = al.winner;
                int tb = nl.winner;
                boolean alHome = byRecord(standings).compare(ta, tb) < 0;
                out.add(alHome
                        ? new Series(11, WORLD_SERIES, null, 7, ta, tb, al.seedOf(ta), nl.seedOf(tb), start)
                        : new Series(11, WORLD_SERIES, null, 7, tb, ta, nl.seedOf(tb), al.seedOf(ta), start));
            }
            default -> {
            }
        }
        out.forEach(s -> s.dirty = true);
        return out;
    }

    static Series find(List<Series> all, int id) {
        for (Series s : all) {
            if (s.id == id) {
                return s;
            }
        }
        throw new IllegalStateException("시리즈 없음: " + id);
    }

    static Integer champion(List<Series> all) {
        for (Series s : all) {
            if (s.round == WORLD_SERIES && s.done()) {
                return s.winner;
            }
        }
        return null;
    }
}
