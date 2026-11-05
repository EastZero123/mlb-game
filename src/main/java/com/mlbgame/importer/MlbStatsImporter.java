package com.mlbgame.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLB Stats API → 마스터 테이블 (팀, 일정, 개막 로스터, 선수 프로필, 연도별 기록).
 * 모든 쓰기는 upsert 라서 여러 번 실행해도 결과가 같다.
 */
@Component
@Profile("import")
class MlbStatsImporter {

    private static final Logger log = LoggerFactory.getLogger(MlbStatsImporter.class);
    private static final String API = "https://statsapi.mlb.com/api/v1";
    private static final String JSON = "application/json";
    private static final int PEOPLE_CHUNK = 100;
    private static final int MLB_SPORT_ID = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper = JsonMapper.builder().build();

    MlbStatsImporter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Summary(int teams, int games, LocalDate openingDay, int rosterPlayers, int players,
                   int battingRows, int pitchingRows, int teamSeasons) {
    }

    private record ScheduledGame(long gamePk, LocalDate date, int awayTeamId, int homeTeamId, boolean postponed) {
    }

    /** 개막 로스터 정보: 소속 팀 + 상태 (1=40인, 2=개막 26인) */
    private record RosterSpot(int teamId, int status) {
    }

    Summary importAll(CachedHttpClient http, int season) {
        List<Integer> teamIds = importTeams(http, season);
        List<ScheduledGame> games = importSchedule(http, season);
        LocalDate openingDay = games.getFirst().date();
        Map<Integer, RosterSpot> roster = loadOpeningRoster(http, season, teamIds, openingDay);
        int[] counts = importPeople(http, season, roster);
        int teamSeasons = importTeamSeasons(http, season);
        return new Summary(teamIds.size(), games.size(), openingDay, roster.size(), counts[0], counts[1], counts[2],
                teamSeasons);
    }

    // ------------------------------------------------------------------ 팀 시즌 결과 (검증·보정 기준)

    /** 순위표(승패·득실점) + 팀 타격 합계 → mst_team_season */
    private int importTeamSeasons(CachedHttpClient http, int season) {
        JsonNode standings = read(http.get("statsapi/standings-" + season + ".json",
                API + "/standings?leagueId=103,104&standingsTypes=regularSeason&season=" + season, JSON));
        JsonNode hitting = read(http.get("statsapi/team-hitting-" + season + ".json",
                API + "/teams/stats?group=hitting&stats=season&sportIds=1&gameType=R&season=" + season, JSON));

        Map<Integer, int[]> records = new HashMap<>(); // 팀 → {승, 패, 득점, 실점}
        for (JsonNode division : standings.path("records")) {
            for (JsonNode t : division.path("teamRecords")) {
                records.put(t.path("team").path("id").asInt(), new int[] {
                        t.path("wins").asInt(), t.path("losses").asInt(),
                        t.path("runsScored").asInt(), t.path("runsAllowed").asInt()});
            }
        }

        List<Object[]> rows = new ArrayList<>();
        for (JsonNode split : hitting.path("stats").path(0).path("splits")) {
            int teamId = split.path("team").path("id").asInt();
            int[] rec = records.get(teamId);
            if (rec == null) {
                log.warn("순위표에 없는 팀: {}", teamId);
                continue;
            }
            JsonNode s = split.path("stat");
            rows.add(new Object[] {teamId, season, rec[0], rec[1], rec[2], rec[3],
                    s.path("plateAppearances").asInt(), s.path("strikeOuts").asInt(), s.path("baseOnBalls").asInt(),
                    s.path("hitByPitch").asInt(), s.path("hits").asInt(), s.path("doubles").asInt(),
                    s.path("triples").asInt(), s.path("homeRuns").asInt(),
                    s.path("groundOuts").asInt(), s.path("airOuts").asInt()});
        }

        tx.executeWithoutResult(st -> jdbc.batchUpdate("""
                INSERT INTO mst_team_season (team_id, season, wins, losses, runs_scored, runs_allowed,
                    pa, so, bb, hbp, h, b2, b3, hr, ground_outs, air_outs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE wins = n.wins, losses = n.losses, runs_scored = n.runs_scored,
                    runs_allowed = n.runs_allowed, pa = n.pa, so = n.so, bb = n.bb, hbp = n.hbp, h = n.h,
                    b2 = n.b2, b3 = n.b3, hr = n.hr, ground_outs = n.ground_outs, air_outs = n.air_outs
                """, rows));
        log.info("팀 시즌 결과 {}팀", rows.size());
        return rows.size();
    }

    // ------------------------------------------------------------------ 팀

    private List<Integer> importTeams(CachedHttpClient http, int season) {
        JsonNode root = read(http.get("statsapi/teams-" + season + ".json",
                API + "/teams?sportId=1&season=" + season, JSON));

        List<Object[]> rows = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (JsonNode t : root.path("teams")) {
            int id = t.path("id").asInt();
            String league = switch (t.path("league").path("id").asInt()) {
                case 103 -> "AL";
                case 104 -> "NL";
                default -> throw new IllegalStateException("알 수 없는 리그: " + t);
            };
            String division = switch (t.path("division").path("id").asInt()) {
                case 201, 204 -> "E";
                case 202, 205 -> "C";
                case 200, 203 -> "W";
                default -> throw new IllegalStateException("알 수 없는 지구: " + t);
            };
            ids.add(id);
            rows.add(new Object[] {id, t.path("abbreviation").asString(), cut(t.path("name").asString(), 40),
                    league, division, cut(t.path("venue").path("name").asString(), 60)});
        }
        if (ids.size() != 30) {
            throw new IllegalStateException("팀 수가 30이 아님: " + ids.size());
        }

        tx.executeWithoutResult(s -> jdbc.batchUpdate("""
                INSERT INTO mst_team (team_id, abbrev, name, league, division, venue_name)
                VALUES (?, ?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE abbrev = n.abbrev, name = n.name, league = n.league,
                                        division = n.division, venue_name = n.venue_name
                """, rows));
        log.info("팀 {}개", ids.size());
        return ids;
    }

    // ------------------------------------------------------------------ 일정

    /**
     * 정규시즌 일정. 우천 연기된 경기는 원래 날짜(Postponed)와 실제 치른 날짜에 중복으로 나오므로
     * gamePk 기준으로 실제 치른 날짜 1건만 남긴다. 실제로 취소된 경기도 원래 날짜에 포함 (팀당 162경기 유지).
     */
    private List<ScheduledGame> importSchedule(CachedHttpClient http, int season) {
        JsonNode root = read(http.get("statsapi/schedule-" + season + ".json",
                API + "/schedule?sportId=1&gameType=R&season=" + season, JSON));

        Map<Long, ScheduledGame> byPk = new LinkedHashMap<>();
        for (JsonNode d : root.path("dates")) {
            for (JsonNode g : d.path("games")) {
                if (!"R".equals(g.path("gameType").asString())) {
                    continue;
                }
                ScheduledGame sg = new ScheduledGame(
                        g.path("gamePk").asLong(),
                        LocalDate.parse(g.path("officialDate").asString()),
                        g.path("teams").path("away").path("team").path("id").asInt(),
                        g.path("teams").path("home").path("team").path("id").asInt(),
                        "Postponed".equals(g.path("status").path("detailedState").asString()));
                ScheduledGame prev = byPk.get(sg.gamePk());
                boolean replace = prev == null
                        || (prev.postponed() && !sg.postponed())
                        || (prev.postponed() == sg.postponed() && sg.date().isAfter(prev.date()));
                if (replace) {
                    byPk.put(sg.gamePk(), sg);
                }
            }
        }

        List<ScheduledGame> games = new ArrayList<>(byPk.values());
        games.sort(Comparator.comparing(ScheduledGame::date).thenComparingLong(ScheduledGame::gamePk));

        Map<Integer, Integer> perTeam = new TreeMap<>();
        List<Object[]> rows = new ArrayList<>(games.size());
        int no = 1;
        for (ScheduledGame g : games) {
            rows.add(new Object[] {no++, g.date(), g.awayTeamId(), g.homeTeamId()});
            perTeam.merge(g.awayTeamId(), 1, Integer::sum);
            perTeam.merge(g.homeTeamId(), 1, Integer::sum);
        }
        if (games.size() != 2430) {
            log.warn("정규시즌 경기 수가 2,430이 아님: {}", games.size());
        }
        perTeam.forEach((team, n) -> {
            if (n != 162) {
                log.warn("팀 {} 경기 수 {}", team, n);
            }
        });

        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM mst_schedule");
            jdbc.batchUpdate("INSERT INTO mst_schedule (game_no, game_date, away_team_id, home_team_id) VALUES (?, ?, ?, ?)",
                    rows);
        });
        log.info("일정 {}경기 ({} ~ {})", games.size(), games.getFirst().date(), games.getLast().date());
        return games;
    }

    // ------------------------------------------------------------------ 개막 로스터

    private Map<Integer, RosterSpot> loadOpeningRoster(CachedHttpClient http, int season, List<Integer> teamIds,
                                                       LocalDate openingDay) {
        Map<Integer, RosterSpot> roster = new HashMap<>();
        for (int teamId : teamIds) {
            for (JsonNode e : rosterOf(http, season, teamId, "40Man", openingDay)) {
                roster.put(e.path("person").path("id").asInt(), new RosterSpot(teamId, 1));
            }
            for (JsonNode e : rosterOf(http, season, teamId, "active", openingDay)) {
                roster.put(e.path("person").path("id").asInt(), new RosterSpot(teamId, 2));
            }
        }
        log.info("개막({}) 로스터 {}명", openingDay, roster.size());
        return roster;
    }

    private JsonNode rosterOf(CachedHttpClient http, int season, int teamId, String type, LocalDate date) {
        String key = "statsapi/roster-" + season + "/" + teamId + "-" + type + ".json";
        String url = API + "/teams/" + teamId + "/roster?rosterType=" + type + "&date=" + date;
        return read(http.get(key, url, JSON)).path("roster");
    }

    // ------------------------------------------------------------------ 선수 + 연도별 기록

    /** @return {선수 수, 타격 기록 행, 투구 기록 행} */
    private int[] importPeople(CachedHttpClient http, int season, Map<Integer, RosterSpot> roster) {
        List<Integer> ids = new ArrayList<>(roster.keySet());
        ids.sort(null);

        List<Object[]> players = new ArrayList<>(ids.size());
        List<Object[]> batting = new ArrayList<>();
        List<Object[]> pitching = new ArrayList<>();

        for (int from = 0; from < ids.size(); from += PEOPLE_CHUNK) {
            List<Integer> chunk = ids.subList(from, Math.min(from + PEOPLE_CHUNK, ids.size()));
            String idList = String.join(",", chunk.stream().map(String::valueOf).toList());
            String key = "statsapi/people-" + season + "/" + Integer.toHexString(idList.hashCode()) + ".json";
            String url = API + "/people?personIds=" + idList
                    + "&hydrate=stats(group=%5Bhitting,pitching%5D,type=%5ByearByYear%5D)";

            for (JsonNode p : read(http.get(key, url, JSON)).path("people")) {
                int id = p.path("id").asInt();
                String birth = p.path("birthDate").asString();
                if (birth.isEmpty()) {
                    log.warn("생년월일 없음, 제외: {} {}", id, p.path("fullName").asString());
                    continue;
                }
                RosterSpot spot = roster.get(id);
                players.add(new Object[] {id, cut(p.path("fullName").asString(), 60), LocalDate.parse(birth),
                        hand(p.path("batSide").path("code").asString(), "R"),
                        hand(p.path("pitchHand").path("code").asString(), "R"),
                        position(p.path("primaryPosition").path("code").asString()),
                        spot.teamId(), spot.status()});
                collectStats(id, p.path("stats"), batting, pitching);
            }
        }

        tx.executeWithoutResult(s -> {
            jdbc.batchUpdate("""
                    INSERT INTO mst_player (player_id, full_name, birth_date, bats, throws, primary_pos, team_id, roster_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE full_name = n.full_name, birth_date = n.birth_date, bats = n.bats,
                        throws = n.throws, primary_pos = n.primary_pos, team_id = n.team_id, roster_status = n.roster_status
                    """, players);
            jdbc.batchUpdate("""
                    INSERT INTO mst_bat_season (player_id, season, team_id, g, pa, ab, r, h, b2, b3, hr, rbi, bb, so, hbp, sb, cs, sf)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, pa = n.pa, ab = n.ab, r = n.r, h = n.h, b2 = n.b2, b3 = n.b3,
                        hr = n.hr, rbi = n.rbi, bb = n.bb, so = n.so, hbp = n.hbp, sb = n.sb, cs = n.cs, sf = n.sf
                    """, batting);
            jdbc.batchUpdate("""
                    INSERT INTO mst_pit_season (player_id, season, team_id, g, gs, w, l, sv, outs, h, r, er, bb, so, hr, hbp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, gs = n.gs, w = n.w, l = n.l, sv = n.sv, outs = n.outs, h = n.h,
                        r = n.r, er = n.er, bb = n.bb, so = n.so, hr = n.hr, hbp = n.hbp
                    """, pitching);
        });
        log.info("선수 {}명, 타격 기록 {}행, 투구 기록 {}행", players.size(), batting.size(), pitching.size());
        return new int[] {players.size(), batting.size(), pitching.size()};
    }

    /**
     * 연도별 기록. 시즌 중 이적한 선수는 팀별 행 + 합계 행(team 없음)이 오므로 팀별 행만 쓴다.
     * 2026 기록도 저장한다 (능력치 산정용). 화면의 '과거 기록'은 커리어 시작 시즌 이전만 보여준다.
     */
    static void collectStats(int playerId, JsonNode stats, List<Object[]> batting, List<Object[]> pitching) {
        for (JsonNode group : stats) {
            if (!"yearByYear".equals(group.path("type").path("displayName").asString())) {
                continue;
            }
            boolean hitting = "hitting".equals(group.path("group").path("displayName").asString());
            for (JsonNode split : group.path("splits")) {
                if (split.path("team").isMissingNode() || split.path("sport").path("id").asInt() != MLB_SPORT_ID) {
                    continue;
                }
                int season = MlbStatsImporter.seasonOf(split.path("season").asString());
                int teamId = split.path("team").path("id").asInt();
                JsonNode s = split.path("stat");
                if (hitting) {
                    if (s.path("plateAppearances").asInt() == 0) {
                        continue;
                    }
                    batting.add(new Object[] {playerId, season, teamId,
                            s.path("gamesPlayed").asInt(), s.path("plateAppearances").asInt(), s.path("atBats").asInt(),
                            s.path("runs").asInt(), s.path("hits").asInt(), s.path("doubles").asInt(),
                            s.path("triples").asInt(), s.path("homeRuns").asInt(), s.path("rbi").asInt(),
                            s.path("baseOnBalls").asInt(), s.path("strikeOuts").asInt(), s.path("hitByPitch").asInt(),
                            s.path("stolenBases").asInt(), s.path("caughtStealing").asInt(), s.path("sacFlies").asInt()});
                } else {
                    if (s.path("battersFaced").asInt() == 0) {
                        continue;
                    }
                    pitching.add(new Object[] {playerId, season, teamId,
                            s.path("gamesPitched").asInt(), s.path("gamesStarted").asInt(),
                            s.path("wins").asInt(), s.path("losses").asInt(), s.path("saves").asInt(),
                            s.path("outs").asInt(), s.path("hits").asInt(), s.path("runs").asInt(),
                            s.path("earnedRuns").asInt(), s.path("baseOnBalls").asInt(), s.path("strikeOuts").asInt(),
                            s.path("homeRuns").asInt(), s.path("hitBatsmen").asInt()});
                }
            }
        }
    }

    // ------------------------------------------------------------------ 변환 헬퍼

    /** 시즌 표기 → 연도. 한 시즌에 여러 구간이 있으면 "2018.2" 처럼 오므로 점 앞만 쓴다 */
    static int seasonOf(String season) {
        int dot = season.indexOf('.');
        return Integer.parseInt(dot < 0 ? season : season.substring(0, dot));
    }

    private JsonNode read(String body) {
        return mapper.readTree(body);
    }

    /** MLB 포지션 코드 → 1=P 2=C 3=1B ... 9=RF 10=DH */
    static int position(String code) {
        return switch (code) {
            case "1", "2", "3", "4", "5", "6", "7", "8", "9", "10" -> Integer.parseInt(code);
            case "Y" -> 10; // 투타겸업: 타자 기준 DH, 투수 능력치는 별도로 보유
            case "O" -> 8;  // 외야수(포지션 미지정)
            case "I" -> 4;  // 내야수(포지션 미지정)
            default -> 10;
        };
    }

    static String hand(String code, String fallback) {
        return code.equals("L") || code.equals("R") || code.equals("S") ? code : fallback;
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
