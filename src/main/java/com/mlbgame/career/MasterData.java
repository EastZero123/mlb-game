package com.mlbgame.career;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mlbgame.sim.Hand;

/**
 * 커리어 생성에 필요한 마스터 데이터 (선수·능력치·팀·개막일). 읽기 전용이라 처음 한 번만 DB 에서 읽고 메모리에 둔다.
 * 선수 약 2,650명 × 40칸 short ≈ 200KB.
 * 임포트로 마스터가 바뀌면 서버를 재시작하거나 {@link #reload()} 호출.
 */
@Component
public class MasterData {

    /** mst_player_rating 과 c_player 공통 능력치 컬럼 (순서 고정) */
    public static final String[] RATING_COLUMNS = {
            "contact", "power", "eye", "quality", "bb_tendency", "speed", "baserunning",
            "control", "suppression", "groundball", "stamina",
            "ff_use", "ff_stuff", "si_use", "si_stuff", "fc_use", "fc_stuff", "sl_use", "sl_stuff",
            "st_use", "st_stuff", "cu_use", "cu_stuff", "ch_use", "ch_stuff", "fs_use", "fs_stuff",
            "def_c", "def_1b", "def_2b", "def_3b", "def_ss", "def_lf", "def_cf", "def_rf",
            "arm", "framing", "blocking", "durability", "potential"};

    public static final int CONTACT = 0, POWER = 1, EYE = 2, QUALITY = 3, SPEED = 5,
            CONTROL = 7, SUPPRESSION = 8, STAMINA = 10, FIRST_PITCH = 11, DEF_C = 27, DURABILITY = 38,
            POTENTIAL = 39;
    public static final int PITCH_TYPES = 8;

    /** 시즌 = 마스터 기준 시즌 (2026 개막에서 시작) */
    public static final int START_SEASON = 2026;

    /** MLB 정규시즌 1경기 (mst_schedule) */
    public record ScheduledGame(int gameNo, int awayTeamId, int homeTeamId) {
    }

    /**
     * @param byId       선수 ID → 선수
     * @param schedule   날짜 → 그날 MLB 경기 (경기 번호 순)
     * @param lastDay    MLB 정규시즌 마지막 날
     * @param totalGames MLB 정규시즌 경기 수
     * @param teams      팀 ID → 리그·지구
     */
    public record Snapshot(List<MasterPlayer> players, Map<Integer, MasterPlayer> byId, List<Integer> teamIds,
                           LocalDate openingDay, LocalDate lastDay, Map<LocalDate, List<ScheduledGame>> schedule,
                           int totalGames, Map<Integer, TeamInfo> teams) {
    }

    /** @param league AL/NL, division E/C/W */
    public record TeamInfo(int teamId, String abbrev, String name, String league, String division) {
    }

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public MasterData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot get() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (this) {
                s = snapshot;
                if (s == null) {
                    s = load();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    public synchronized void reload() {
        snapshot = load();
    }

    private static Hand hand(String code) {
        return switch (code) {
            case "L" -> Hand.LEFT;
            case "S" -> Hand.SWITCH;
            default -> Hand.RIGHT;
        };
    }

    private Snapshot load() {
        Map<Integer, Integer> service = new HashMap<>();
        jdbc.query("""
                SELECT player_id, COUNT(DISTINCT season) FROM (
                    SELECT player_id, season FROM mst_bat_season WHERE season < ?
                    UNION SELECT player_id, season FROM mst_pit_season WHERE season < ?) t
                GROUP BY player_id
                """, rs -> {
            service.put(rs.getInt(1), rs.getInt(2));
        }, START_SEASON, START_SEASON);

        String cols = "r." + String.join(", r.", RATING_COLUMNS);
        List<MasterPlayer> players = new ArrayList<>(3000);
        jdbc.query("""
                SELECT p.player_id, p.birth_date, p.primary_pos, p.team_id, p.roster_status, p.level,
                       r.source, r.sample_pa + r.sample_bf, p.bats, p.throws, %s
                FROM mst_player p JOIN mst_player_rating r USING (player_id)
                WHERE p.team_id IS NOT NULL
                ORDER BY p.player_id
                """.formatted(cols), rs -> {
            short[] ratings = new short[RATING_COLUMNS.length];
            for (int i = 0; i < ratings.length; i++) {
                int v = rs.getInt(11 + i);
                ratings[i] = rs.wasNull() ? -1 : (short) v;
            }
            Integer level = rs.getObject(6, Integer.class);
            int id = rs.getInt(1);
            players.add(new MasterPlayer(id, rs.getObject(2, LocalDate.class), rs.getInt(3), rs.getInt(4),
                    rs.getInt(5), level == null ? -1 : level, rs.getInt(7), rs.getInt(8),
                    service.getOrDefault(id, 0), ratings, hand(rs.getString(9)),
                    "L".equals(rs.getString(10)) ? Hand.LEFT : Hand.RIGHT));
        });

        List<Integer> teams = jdbc.queryForList("SELECT team_id FROM mst_team ORDER BY team_id", Integer.class);
        Map<Integer, TeamInfo> teamInfo = new HashMap<>();
        jdbc.query("SELECT team_id, abbrev, name, league, division FROM mst_team", rs -> {
            teamInfo.put(rs.getInt(1), new TeamInfo(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5)));
        });
        Map<LocalDate, List<ScheduledGame>> schedule = new TreeMap<>();
        jdbc.query("SELECT game_no, game_date, away_team_id, home_team_id FROM mst_schedule ORDER BY game_no", rs -> {
            schedule.computeIfAbsent(rs.getObject(2, LocalDate.class), d -> new ArrayList<>())
                    .add(new ScheduledGame(rs.getInt(1), rs.getInt(3), rs.getInt(4)));
        });
        if (players.isEmpty() || teams.size() != 30 || schedule.isEmpty()) {
            throw new IllegalStateException("마스터 데이터가 비어 있음. 임포트를 먼저 실행하세요.");
        }
        Map<Integer, MasterPlayer> byId = new HashMap<>(players.size() * 2);
        for (MasterPlayer p : players) {
            byId.put(p.id(), p);
        }
        TreeMap<LocalDate, List<ScheduledGame>> sorted = (TreeMap<LocalDate, List<ScheduledGame>>) schedule;
        int total = schedule.values().stream().mapToInt(List::size).sum();
        return new Snapshot(Collections.unmodifiableList(players), Collections.unmodifiableMap(byId),
                List.copyOf(teams), sorted.firstKey(), sorted.lastKey(), Collections.unmodifiableMap(schedule), total,
                Collections.unmodifiableMap(teamInfo));
    }
}
