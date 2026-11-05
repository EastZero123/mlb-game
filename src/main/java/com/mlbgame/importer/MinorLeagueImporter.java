package com.mlbgame.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MLB Stats API → 마이너(AAA·AA) 마스터 데이터. {@link MlbStatsImporter} 다음에 실행한다.
 * <ol>
 *   <li>AAA·AA 팀 + 모기업 연결 → mst_affiliate</li>
 *   <li>레벨별 개막일 기준 로스터 → 선수의 개막 레벨 (mst_player.level). 마이너 계약 선수는 신규 등록</li>
 *   <li>신규 선수 프로필 + MLB 기록 (베테랑 마이너 선수의 과거 MLB 기록)</li>
 *   <li>전체 선수의 최근 3시즌 AAA·AA 기록 → mst_bat_season_minor / mst_pit_season_minor</li>
 * </ol>
 * 모든 쓰기는 upsert 라서 여러 번 실행해도 결과가 같다.
 */
@Component
@Profile("import")
class MinorLeagueImporter {

    private static final Logger log = LoggerFactory.getLogger(MinorLeagueImporter.class);
    private static final String API = "https://statsapi.mlb.com/api/v1";
    private static final String JSON = "application/json";
    private static final int PEOPLE_CHUNK = 100;
    /** sportId → 레벨 */
    private static final int[][] LEVELS = {{11, 1}, {12, 2}};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper = JsonMapper.builder().build();

    MinorLeagueImporter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Summary(int affiliates, LocalDate aaaOpening, LocalDate aaOpening, int rosterPlayers, int newPlayers,
                   int battingRows, int pitchingRows) {
    }

    /** 개막 로스터 위치: 소속 구단(모기업) + 레벨 */
    private record Spot(int parentTeamId, int level) {
    }

    Summary importAll(CachedHttpClient http, int season) {
        Map<Integer, Spot> affiliates = importAffiliates(http, season);
        LocalDate[] opening = {openingDay(http, season, 11), openingDay(http, season, 12)};

        Map<Integer, Spot> spots = new HashMap<>();
        affiliates.forEach((teamId, aff) -> {
            LocalDate date = opening[aff.level() - 1];
            String key = "statsapi/milb-roster-" + season + "/" + teamId + ".json";
            String url = API + "/teams/" + teamId + "/roster?rosterType=active&date=" + date;
            for (JsonNode e : read(http.get(key, url, JSON)).path("roster")) {
                spots.put(e.path("person").path("id").asInt(), aff);
            }
        });

        Map<Integer, Integer> existingStatus = new HashMap<>();
        jdbc.query("SELECT player_id, roster_status FROM mst_player", rs -> {
            existingStatus.put(rs.getInt(1), rs.getInt(2));
        });
        Set<Integer> newIds = new HashSet<>(spots.keySet());
        newIds.removeAll(existingStatus.keySet());

        // 신규 선수: 프로필 + MLB 기록 (sportId 미지정 = MLB)
        List<Object[]> players = new ArrayList<>();
        List<Object[]> mlbBat = new ArrayList<>();
        List<Object[]> mlbPit = new ArrayList<>();
        for (List<Integer> chunk : chunks(newIds)) {
            for (JsonNode p : people(http, season, chunk, 0)) {
                int id = p.path("id").asInt();
                String birth = p.path("birthDate").asString();
                if (birth.isEmpty()) {
                    log.warn("생년월일 없음, 제외: {} {}", id, p.path("fullName").asString());
                    continue;
                }
                Spot spot = spots.get(id);
                players.add(new Object[] {id, MlbStatsImporter.cut(p.path("fullName").asString(), 60),
                        LocalDate.parse(birth),
                        MlbStatsImporter.hand(p.path("batSide").path("code").asString(), "R"),
                        MlbStatsImporter.hand(p.path("pitchHand").path("code").asString(), "R"),
                        MlbStatsImporter.position(p.path("primaryPosition").path("code").asString()),
                        spot.parentTeamId(), spot.level()});
                MlbStatsImporter.collectStats(id, p.path("stats"), mlbBat, mlbPit);
            }
        }

        // 전체 선수의 AAA·AA 기록
        Set<Integer> all = new HashSet<>(existingStatus.keySet());
        all.addAll(spots.keySet());
        List<Object[]> minorBat = new ArrayList<>();
        List<Object[]> minorPit = new ArrayList<>();
        for (int[] lv : LEVELS) {
            for (List<Integer> chunk : chunks(all)) {
                for (JsonNode p : people(http, season, chunk, lv[0])) {
                    collectMinor(p.path("id").asInt(), p.path("stats"), lv[0], lv[1], season, minorBat, minorPit);
                }
            }
        }

        // 개막 레벨: 개막 26인 = MLB, AAA·AA 로스터 = 해당 레벨, 나머지 40인 = 미배정(부상·하위 리그)
        List<Object[]> levels = new ArrayList<>();
        existingStatus.forEach((id, status) -> {
            Spot s = spots.get(id);
            Integer level = status == 2 ? Integer.valueOf(0) : s != null ? Integer.valueOf(s.level()) : null;
            levels.add(new Object[] {level, id});
        });

        tx.executeWithoutResult(st -> {
            jdbc.batchUpdate("""
                    INSERT INTO mst_player (player_id, full_name, birth_date, bats, throws, primary_pos, team_id,
                                            roster_status, level)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?) AS n
                    ON DUPLICATE KEY UPDATE full_name = n.full_name, birth_date = n.birth_date, bats = n.bats,
                        throws = n.throws, primary_pos = n.primary_pos, team_id = n.team_id,
                        roster_status = n.roster_status, level = n.level
                    """, players);
            jdbc.batchUpdate("UPDATE mst_player SET level = ? WHERE player_id = ?", levels);
            jdbc.batchUpdate("""
                    INSERT INTO mst_bat_season (player_id, season, team_id, g, pa, ab, r, h, b2, b3, hr, rbi, bb, so, hbp, sb, cs, sf)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, pa = n.pa, ab = n.ab, r = n.r, h = n.h, b2 = n.b2, b3 = n.b3,
                        hr = n.hr, rbi = n.rbi, bb = n.bb, so = n.so, hbp = n.hbp, sb = n.sb, cs = n.cs, sf = n.sf
                    """, mlbBat);
            jdbc.batchUpdate("""
                    INSERT INTO mst_pit_season (player_id, season, team_id, g, gs, w, l, sv, outs, h, r, er, bb, so, hr, hbp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, gs = n.gs, w = n.w, l = n.l, sv = n.sv, outs = n.outs, h = n.h,
                        r = n.r, er = n.er, bb = n.bb, so = n.so, hr = n.hr, hbp = n.hbp
                    """, mlbPit);
            jdbc.batchUpdate("""
                    INSERT INTO mst_bat_season_minor (player_id, season, level, team_id, g, pa, ab, h, b2, b3, hr,
                                                      bb, so, hbp, sb, cs, sf, go, ao)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, pa = n.pa, ab = n.ab, h = n.h, b2 = n.b2, b3 = n.b3, hr = n.hr,
                        bb = n.bb, so = n.so, hbp = n.hbp, sb = n.sb, cs = n.cs, sf = n.sf, go = n.go, ao = n.ao
                    """, minorBat);
            jdbc.batchUpdate("""
                    INSERT INTO mst_pit_season_minor (player_id, season, level, team_id, g, gs, outs, bf, h, er,
                                                      bb, so, hr, hbp, go, ao)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
                    ON DUPLICATE KEY UPDATE g = n.g, gs = n.gs, outs = n.outs, bf = n.bf, h = n.h, er = n.er,
                        bb = n.bb, so = n.so, hr = n.hr, hbp = n.hbp, go = n.go, ao = n.ao
                    """, minorPit);
        });

        log.info("마이너 팀 {}개, 개막 로스터 {}명 (신규 {}명), 마이너 타격 {}행 / 투구 {}행",
                affiliates.size(), spots.size(), players.size(), minorBat.size(), minorPit.size());
        return new Summary(affiliates.size(), opening[0], opening[1], spots.size(), players.size(),
                minorBat.size(), minorPit.size());
    }

    // ------------------------------------------------------------------ 팀 / 개막일

    private Map<Integer, Spot> importAffiliates(CachedHttpClient http, int season) {
        Map<Integer, Spot> out = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int[] lv : LEVELS) {
            JsonNode root = read(http.get("statsapi/milb-teams-" + season + "-" + lv[0] + ".json",
                    API + "/teams?sportId=" + lv[0] + "&season=" + season, JSON));
            for (JsonNode t : root.path("teams")) {
                int id = t.path("id").asInt();
                int parent = t.path("parentOrgId").asInt();
                if (parent == 0) {
                    log.warn("모기업 없는 마이너 팀 제외: {}", t.path("name").asString());
                    continue;
                }
                out.put(id, new Spot(parent, lv[1]));
                rows.add(new Object[] {id, parent, lv[1],
                        MlbStatsImporter.cut(t.path("abbreviation").asString(), 8),
                        MlbStatsImporter.cut(t.path("name").asString(), 50)});
            }
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate("""
                INSERT INTO mst_affiliate (team_id, parent_team_id, level, abbrev, name)
                VALUES (?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE parent_team_id = n.parent_team_id, level = n.level,
                                        abbrev = n.abbrev, name = n.name
                """, rows));
        return out;
    }

    /** 해당 레벨 정규시즌 첫 경기 날짜 */
    private LocalDate openingDay(CachedHttpClient http, int season, int sportId) {
        JsonNode root = read(http.get("statsapi/milb-opening-" + season + "-" + sportId + ".json",
                API + "/schedule?sportId=" + sportId + "&gameType=R&startDate=" + season + "-03-01&endDate="
                        + season + "-05-31", JSON));
        JsonNode dates = root.path("dates");
        if (dates.isEmpty()) {
            throw new IllegalStateException("sportId " + sportId + " 개막일을 찾지 못함");
        }
        return LocalDate.parse(dates.path(0).path("date").asString());
    }

    // ------------------------------------------------------------------ 선수 / 기록

    /** @param sportId 0 = MLB(기본), 11 = AAA, 12 = AA */
    private JsonNode people(CachedHttpClient http, int season, List<Integer> ids, int sportId) {
        String idList = String.join(",", ids.stream().map(String::valueOf).toList());
        String sport = sportId == 0 ? "" : ",sportId=" + sportId;
        String key = "statsapi/milb-people-" + season + "/" + sportId + "-" + Integer.toHexString(idList.hashCode())
                + ".json";
        String url = API + "/people?personIds=" + idList
                + "&hydrate=stats(group=%5Bhitting,pitching%5D,type=%5ByearByYear%5D" + sport + ")";
        return read(http.get(key, url, JSON)).path("people");
    }

    /** 최근 3시즌, 팀별 행만 (시즌 중 이적 시 오는 합계 행 제외) */
    static void collectMinor(int playerId, JsonNode stats, int sportId, int level, int season,
                             List<Object[]> batting, List<Object[]> pitching) {
        for (JsonNode group : stats) {
            if (!"yearByYear".equals(group.path("type").path("displayName").asString())) {
                continue;
            }
            boolean hitting = "hitting".equals(group.path("group").path("displayName").asString());
            for (JsonNode split : group.path("splits")) {
                if (split.path("team").isMissingNode() || split.path("sport").path("id").asInt() != sportId) {
                    continue;
                }
                int year = MlbStatsImporter.seasonOf(split.path("season").asString());
                if (year < season - 2 || year > season) {
                    continue;
                }
                int teamId = split.path("team").path("id").asInt();
                JsonNode s = split.path("stat");
                if (hitting) {
                    if (s.path("plateAppearances").asInt() == 0) {
                        continue;
                    }
                    batting.add(new Object[] {playerId, year, level, teamId,
                            s.path("gamesPlayed").asInt(), s.path("plateAppearances").asInt(), s.path("atBats").asInt(),
                            s.path("hits").asInt(), s.path("doubles").asInt(), s.path("triples").asInt(),
                            s.path("homeRuns").asInt(), s.path("baseOnBalls").asInt(), s.path("strikeOuts").asInt(),
                            s.path("hitByPitch").asInt(), s.path("stolenBases").asInt(),
                            s.path("caughtStealing").asInt(), s.path("sacFlies").asInt(),
                            s.path("groundOuts").asInt(), s.path("airOuts").asInt()});
                } else {
                    int bf = s.path("battersFaced").asInt();
                    if (bf == 0) {
                        continue;
                    }
                    pitching.add(new Object[] {playerId, year, level, teamId,
                            s.path("gamesPitched").asInt(), s.path("gamesStarted").asInt(), outs(s), bf,
                            s.path("hits").asInt(), s.path("earnedRuns").asInt(), s.path("baseOnBalls").asInt(),
                            s.path("strikeOuts").asInt(), s.path("homeRuns").asInt(), s.path("hitBatsmen").asInt(),
                            s.path("groundOuts").asInt(), s.path("airOuts").asInt()});
                }
            }
        }
    }

    /** outs 필드가 없으면 이닝 표기("45.2" = 45이닝 2아웃)에서 계산 */
    static int outs(JsonNode stat) {
        if (stat.has("outs")) {
            return stat.path("outs").asInt();
        }
        String ip = stat.path("inningsPitched").asString();
        if (ip.isEmpty()) {
            return 0;
        }
        int dot = ip.indexOf('.');
        return dot < 0 ? Integer.parseInt(ip) * 3
                : Integer.parseInt(ip.substring(0, dot)) * 3 + Integer.parseInt(ip.substring(dot + 1));
    }

    private static List<List<Integer>> chunks(Set<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        List<List<Integer>> out = new ArrayList<>();
        for (int from = 0; from < sorted.size(); from += PEOPLE_CHUNK) {
            out.add(sorted.subList(from, Math.min(from + PEOPLE_CHUNK, sorted.size())));
        }
        return out;
    }

    private JsonNode read(String body) {
        return mapper.readTree(body);
    }
}
