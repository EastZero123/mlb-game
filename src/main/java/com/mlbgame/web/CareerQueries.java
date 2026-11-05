package com.mlbgame.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mlbgame.boxscore.BoxScore;
import com.mlbgame.boxscore.BoxScoreCodec;
import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.SeasonCalendar;
import com.mlbgame.offseason.OffseasonService;
import com.mlbgame.season.NextDayService;
import com.mlbgame.sim.BatStat;

/**
 * 화면 조회 (읽기 전용). 화면 하나 = 쿼리 1~3개, 인덱스(커리어 ID 선두) 범위만 읽는다.
 * 선수 신상은 마스터(mst_player) 또는 커리어 생성 선수(c_person) 중 있는 쪽.
 */
@Service
public class CareerQueries {

    static final String[] POS = {"", "P", "C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH"};
    static final String[] LEVEL = {"MLB", "AAA", "AA", "포스트시즌"};
    static final String[] PHASE = {"", "정규시즌", "포스트시즌", "오프시즌"};
    static final String[] ROUND = {"", "와일드카드", "디비전 시리즈", "챔피언십 시리즈", "월드시리즈"};
    static final String[] DIVISION = {"동부", "중부", "서부"};

    /** 신상 조인 (마스터 우선) */
    private static final String PERSON_JOIN = """
            LEFT JOIN mst_player m ON m.player_id = c.player_id
            LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id""";
    private static final String PERSON_COLS = """
            COALESCE(m.full_name, p.full_name) AS name, COALESCE(m.birth_date, p.birth_date) AS birth,
            COALESCE(m.primary_pos, p.primary_pos) AS pos, COALESCE(m.bats, p.bats) AS bats,
            COALESCE(m.throws, p.throws) AS throws_hand""";

    private final JdbcTemplate jdbc;
    private final MasterData master;

    public CareerQueries(JdbcTemplate jdbc, MasterData master) {
        this.jdbc = jdbc;
        this.master = master;
    }

    // ------------------------------------------------------------------ 커리어

    public List<Views.Slot> slots(int userId) {
        Map<Integer, Views.Slot> bySlot = new HashMap<>();
        jdbc.query("""
                SELECT slot, career_id, team_id, season, cur_date, phase FROM career
                WHERE user_id = ? AND deleted_at IS NULL
                """, rs -> {
            bySlot.put(rs.getInt(1), new Views.Slot(rs.getInt(1), rs.getLong(2), teamName(rs.getInt(3)),
                    rs.getInt(4), rs.getObject(5, LocalDate.class), PHASE[rs.getInt(6)]));
        }, userId);
        List<Views.Slot> out = new ArrayList<>();
        for (int s = 1; s <= com.mlbgame.career.CareerCreator.MAX_SLOTS; s++) {
            out.add(bySlot.getOrDefault(s, new Views.Slot(s, null, null, 0, null, null)));
        }
        return out;
    }

    /** 소유자가 아니거나 없으면 null */
    public Views.CareerHead head(long careerId, int userId) {
        List<Views.CareerHead> list = jdbc.query("""
                SELECT user_id, team_id, season, cur_date, phase, scout_level, seed FROM career
                WHERE career_id = ? AND deleted_at IS NULL
                """, (rs, n) -> new Views.CareerHead(careerId, rs.getInt(1), rs.getInt(2), teamName(rs.getInt(2)),
                rs.getInt(3), rs.getObject(4, LocalDate.class), rs.getInt(5), PHASE[rs.getInt(5)], rs.getInt(6),
                rs.getLong(7)), careerId);
        return list.isEmpty() || list.getFirst().userId() != userId ? null : list.getFirst();
    }

    public List<MasterData.TeamInfo> teams() {
        return master.get().teams().values().stream()
                .sorted(Comparator.comparing(MasterData.TeamInfo::name)).toList();
    }

    String teamName(int teamId) {
        MasterData.TeamInfo t = master.get().teams().get(teamId);
        return t == null ? "-" : t.name();
    }

    String abbrev(int teamId) {
        MasterData.TeamInfo t = master.get().teams().get(teamId);
        return t == null ? "-" : t.abbrev();
    }

    // ------------------------------------------------------------------ 순위·경기

    /** 6개 지구 (AL 동·중·서, NL 동·중·서) */
    public List<Views.Division> standings(Views.CareerHead h) {
        Map<Integer, int[]> rows = new HashMap<>();
        jdbc.query("SELECT team_id, wins, losses, runs_for, runs_against, streak FROM c_team WHERE career_id = ?",
                rs -> {
                    rows.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getInt(6)});
                }, h.careerId());
        List<Views.Division> out = new ArrayList<>();
        String[] divs = {"E", "C", "W"};
        for (String league : List.of("AL", "NL")) {
            for (int d = 0; d < 3; d++) {
                String div = divs[d];
                List<MasterData.TeamInfo> teams = master.get().teams().values().stream()
                        .filter(t -> t.league().equals(league) && t.division().equals(div))
                        .sorted(Comparator.comparingInt((MasterData.TeamInfo t) -> -rows.getOrDefault(t.teamId(),
                                new int[5])[0]).thenComparingInt(t -> rows.getOrDefault(t.teamId(), new int[5])[1]))
                        .toList();
                if (teams.isEmpty()) {
                    continue;
                }
                int[] lead = rows.getOrDefault(teams.getFirst().teamId(), new int[5]);
                List<Views.StandingRow> list = new ArrayList<>();
                for (MasterData.TeamInfo t : teams) {
                    int[] r = rows.getOrDefault(t.teamId(), new int[5]);
                    int g = r[0] + r[1];
                    double gb = ((lead[0] - r[0]) + (r[1] - lead[1])) / 2.0;
                    list.add(new Views.StandingRow(t.teamId(), t.abbrev(), t.name(), r[0], r[1],
                            g == 0 ? ".000" : rate((double) r[0] / g),
                            gb == 0 ? "-" : String.format("%.1f", gb), r[2], r[3],
                            r[4] == 0 ? "-" : (r[4] > 0 ? "W" + r[4] : "L" + (-r[4])), t.teamId() == h.teamId()));
                }
                out.add(new Views.Division(league + " " + DIVISION[d], list));
            }
        }
        return out;
    }

    /** 커리어 현재 날짜에 치른 MLB 경기 (일정 템플릿 → 경기 번호) */
    public List<Views.GameRow> gamesOn(Views.CareerHead h, LocalDate date) {
        List<MasterData.ScheduledGame> scheduled = master.get().schedule()
                .getOrDefault(SeasonCalendar.toTemplate(date, h.season()), List.of());
        if (scheduled.isEmpty()) {
            return List.of();
        }
        int from = scheduled.getFirst().gameNo();
        int to = scheduled.getLast().gameNo();
        Map<Integer, MasterData.ScheduledGame> byNo = scheduled.stream()
                .collect(Collectors.toMap(MasterData.ScheduledGame::gameNo, g -> g));
        List<Views.GameRow> out = new ArrayList<>();
        jdbc.query("""
                SELECT game_no, away_runs, home_runs, innings FROM c_game
                WHERE career_id = ? AND season = ? AND game_no BETWEEN ? AND ? ORDER BY game_no
                """, rs -> {
            MasterData.ScheduledGame g = byNo.get(rs.getInt(1));
            if (g != null) {
                out.add(new Views.GameRow(h.season(), g.gameNo(), abbrev(g.awayTeamId()), rs.getInt(2),
                        abbrev(g.homeTeamId()), rs.getInt(3), rs.getInt(4),
                        g.awayTeamId() == h.teamId() || g.homeTeamId() == h.teamId()));
            }
        }, h.careerId(), h.season(), from, to);
        return out;
    }

    /** 내 팀의 이번 시즌 최근 경기 (최대 n) */
    public List<Views.GameRow> recentGames(Views.CareerHead h, int n) {
        List<Views.GameRow> out = new ArrayList<>();
        LocalDate d = h.date();
        for (int back = 0; back < 14 && out.size() < n; back++) {
            for (Views.GameRow g : gamesOn(h, d.minusDays(back))) {
                if (g.mine() && out.size() < n) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    public List<Views.SeriesRow> series(Views.CareerHead h) {
        return jdbc.query("""
                SELECT round, league, high_team, high_seed, high_wins, low_team, low_seed, low_wins, winner
                FROM c_series WHERE career_id = ? AND season = ? ORDER BY series_id
                """, (rs, n) -> new Views.SeriesRow(ROUND[rs.getInt(1)], rs.getString(2) == null ? "" : rs.getString(2),
                abbrev(rs.getInt(3)), rs.getInt(4), rs.getInt(5), abbrev(rs.getInt(6)), rs.getInt(7), rs.getInt(8),
                rs.getObject(9) == null ? null : abbrev(rs.getInt(9))), h.careerId(), h.season());
    }

    // ------------------------------------------------------------------ 오프시즌

    /** 오프시즌 진행 현황 (오프시즌 단계가 아니면 null) */
    public Views.OffseasonView offseason(Views.CareerHead h) {
        if (h.phase() != NextDayService.PHASE_OFFSEASON) {
            return null;
        }
        Object[] row = jdbc.query("SELECT stage, report, fa_round FROM c_offseason WHERE career_id = ? AND season = ?",
                rs -> rs.next() ? new Object[] {rs.getInt(1), rs.getString(2), rs.getInt(3)}
                        : new Object[] {0, null, 0},
                h.careerId(), h.season());
        int done = (int) row[0];
        List<String> report = row[1] == null ? List.of()
                : ((String) row[1]).lines().filter(l -> !l.isBlank()).toList();
        OffseasonService.Stage next = OffseasonService.Stage.after(done);
        List<Views.DraftRow> first = new ArrayList<>();
        List<Views.DraftRow> mine = new ArrayList<>();
        jdbc.query("""
                SELECT d.pick_no, d.round, d.team_id, d.pre_rank, COALESCE(m.full_name, p.full_name)
                FROM c_draft_pick d
                LEFT JOIN mst_player m ON m.player_id = d.player_id
                LEFT JOIN c_person p ON p.career_id = d.career_id AND p.player_id = d.player_id
                WHERE d.career_id = ? AND d.season = ? AND (d.round = 1 OR d.team_id = ?)
                ORDER BY d.pick_no
                """, rs -> {
            int team = rs.getInt(3);
            Views.DraftRow r = new Views.DraftRow(rs.getInt(1), rs.getInt(2), abbrev(team), rs.getInt(4),
                    rs.getString(5), team == h.teamId());
            if (r.round() == 1) {
                first.add(r);
            }
            if (r.mine()) {
                mine.add(r);
            }
        }, h.careerId(), h.season(), h.teamId());
        int[] contracts = jdbc.query("""
                SELECT COUNT(*), COALESCE(SUM(d.decision IS NULL), 0) FROM c_contract_decision d
                JOIN c_player c ON c.career_id = d.career_id AND c.player_id = d.player_id
                WHERE d.career_id = ? AND d.season = ? AND c.team_id = ? AND c.roster_status <> 4
                """, rs -> rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : new int[2],
                h.careerId(), h.season(), h.teamId());
        return new Views.OffseasonView(done, OffseasonService.STAGES, next == null ? null : next.label(), report,
                first, mine, contracts[0], contracts[1], (int) row[2]);
    }

    // ------------------------------------------------------------------ 로스터

    /** 내 팀 MLB 현역·40인 인원 (DB 기준, 오프시즌 화면용) */
    public com.mlbgame.season.MoveService.Counts counts(Views.CareerHead h) {
        return jdbc.query("""
                SELECT COALESCE(SUM(roster_status = 2), 0), COALESCE(SUM(roster_status IN (1, 2, 3)), 0)
                FROM c_player WHERE career_id = ? AND team_id = ?
                """, rs -> {
            rs.next();
            return new com.mlbgame.season.MoveService.Counts(rs.getInt(1), rs.getInt(2));
        }, h.careerId(), h.teamId());
    }

    /** 팀 로스터 (레벨별) + 이번 시즌 그 레벨 기록 */
    public List<Views.RosterRow> roster(Views.CareerHead h, int teamId, int level) {
        String cols = String.join(", ", prefixed("c.", MasterData.RATING_COLUMNS));
        boolean own = teamId == h.teamId();
        int width = ScoutView.width(h.scoutLevel(), own);
        List<Views.RosterRow> out = new ArrayList<>();
        jdbc.query("""
                SELECT c.player_id, c.roster_status, c.salary, c.contract_years, c.injury_until, %s, %s,
                       b.g, b.pa, b.ab, b.h, b.b2, b.b3, b.hr, b.bb, b.hbp, b.sf, b.sb,
                       t.g, t.gs, t.outs, t.er, t.so, t.bb
                FROM c_player c
                %s
                LEFT JOIN c_bat_season b ON b.career_id = c.career_id AND b.season = ? AND b.level = c.level
                    AND b.player_id = c.player_id
                LEFT JOIN c_pit_season t ON t.career_id = c.career_id AND t.season = ? AND t.level = c.level
                    AND t.player_id = c.player_id
                WHERE c.career_id = ? AND c.team_id = ? AND c.level = ?
                """.formatted(PERSON_COLS, cols, PERSON_JOIN), rs -> {
            int id = rs.getInt(1);
            int[] r = ratings(rs, 11);
            int pos = rs.getInt("pos");
            boolean pitcher = r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || pos == 1);
            List<String> shown = new ArrayList<>();
            if (pitcher) {
                shown.add(ScoutView.range(stuff(r), width, h.seed(), id, 100));
                shown.add(ScoutView.range(r[MasterData.CONTROL], width, h.seed(), id, MasterData.CONTROL));
                shown.add(ScoutView.range(r[MasterData.SUPPRESSION], width, h.seed(), id, MasterData.SUPPRESSION));
                shown.add(ScoutView.range(r[MasterData.STAMINA], width, h.seed(), id, MasterData.STAMINA));
            } else {
                shown.add(ScoutView.range(r[MasterData.CONTACT], width, h.seed(), id, MasterData.CONTACT));
                shown.add(ScoutView.range(r[MasterData.POWER], width, h.seed(), id, MasterData.POWER));
                shown.add(ScoutView.range(r[MasterData.EYE], width, h.seed(), id, MasterData.EYE));
                shown.add(ScoutView.range(r[MasterData.SPEED], width, h.seed(), id, MasterData.SPEED));
                int def = pos >= 2 && pos <= 9 ? r[MasterData.DEF_C + pos - 2] : -1;
                shown.add(ScoutView.range(def, width, h.seed(), id, MasterData.DEF_C + Math.max(0, pos - 2)));
            }
            int base = 11 + MasterData.RATING_COLUMNS.length;
            String stats = pitcher ? pitchingLine(rs, base + 11) : battingLine(rs, base);
            LocalDate injury = rs.getObject(5, LocalDate.class);
            boolean injured = injury != null && injury.isAfter(h.date());
            String status = injured ? "부상 (~" + injury + ")"
                    : switch (rs.getInt(2)) {
                        case 2 -> "26인";
                        case 1 -> "40인";
                        case 3 -> "부상자 명단";
                        default -> "마이너 계약";
                    };
            short[] sr = new short[r.length];
            for (int i = 0; i < r.length; i++) {
                sr[i] = (short) r[i];
            }
            int overall = (int) Math.round(ScoutView.estimate(sr, pos, width, h.seed(), id, 0));
            out.add(new Views.RosterRow(id, rs.getString("name"), POS[Math.clamp(pos, 0, 10)],
                    age(rs.getObject("birth", LocalDate.class), h.date()),
                    rs.getString("bats") + "/" + rs.getString("throws_hand"), status, money(rs.getInt(3)), shown,
                    stats, pitcher, rs.getInt(2), injured, overall));
        }, h.season(), h.season(), h.careerId(), teamId, level);
        out.sort(Comparator.comparing(Views.RosterRow::pitcher).thenComparing(Views.RosterRow::pos)
                .thenComparing(Views.RosterRow::name));
        return out;
    }

    /** 내 팀 MLB 타자별 수비 가능 위치 ("C 52–60 · 1B 41–49"), 운영 지정 화면용 */
    public Map<Integer, String> defense(Views.CareerHead h) {
        String cols = String.join(", ", prefixed("c.", MasterData.RATING_COLUMNS));
        int width = ScoutView.width(h.scoutLevel(), true);
        Map<Integer, String> out = new HashMap<>();
        jdbc.query("SELECT c.player_id, %s FROM c_player c WHERE c.career_id = ? AND c.team_id = ? AND c.level = 0"
                .formatted(cols), rs -> {
            int id = rs.getInt(1);
            int[] r = ratings(rs, 2);
            if (r[MasterData.CONTACT] < 0) {
                return;
            }
            StringJoiner j = new StringJoiner(" · ");
            for (int pos = 2; pos <= 9; pos++) {
                int k = MasterData.DEF_C + pos - 2;
                if (r[k] > 0) {
                    j.add(POS[pos] + " " + ScoutView.range(r[k], width, h.seed(), id, k));
                }
            }
            out.put(id, j.length() == 0 ? "수비 불가 (지명타자만)" : j.toString());
        }, h.careerId(), h.teamId());
        return out;
    }

    // ------------------------------------------------------------------ 선수

    public Views.PlayerPage player(Views.CareerHead h, int playerId) {
        String cols = String.join(", ", prefixed("c.", MasterData.RATING_COLUMNS));
        List<Views.PlayerPage> found = jdbc.query("""
                SELECT c.player_id, c.team_id, c.level, c.roster_status, c.salary, c.contract_years, c.contract_type,
                       c.injury_until, c.service_years, %s, %s
                FROM c_player c %s
                WHERE c.career_id = ? AND c.player_id = ?
                """.formatted(PERSON_COLS, cols, PERSON_JOIN), (rs, n) -> {
            int[] r = ratings(rs, 15);
            int pos = rs.getInt("pos");
            boolean pitcher = r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || pos == 1);
            Object teamObj = rs.getObject(2);
            int team = teamObj == null ? 0 : rs.getInt(2);
            int width = ScoutView.width(h.scoutLevel(), team == h.teamId());
            List<Views.Rating> shown = new ArrayList<>();
            String[][] bat = {{"컨택", "0"}, {"파워", "1"}, {"선구", "2"}, {"타구질", "3"}, {"타구 성향(땅볼)", "4"},
                    {"스피드", "5"}, {"주루", "6"}};
            String[][] pit = {{"제구", "7"}, {"장타 억제", "8"}, {"땅볼 유도", "9"}, {"스태미나", "10"}};
            if (!pitcher || r[MasterData.CONTACT] >= 0) {
                for (String[] b : bat) {
                    int c = Integer.parseInt(b[1]);
                    shown.add(new Views.Rating(b[0], ScoutView.range(r[c], width, h.seed(), playerId, c)));
                }
                for (int i = 0; i < 8; i++) {
                    int c = MasterData.DEF_C + i;
                    if (r[c] >= 0) {
                        shown.add(new Views.Rating("수비 " + POS[i + 2], ScoutView.range(r[c], width, h.seed(),
                                playerId, c)));
                    }
                }
                shown.add(new Views.Rating("송구", ScoutView.range(r[MasterData.DEF_C + 8], width, h.seed(), playerId,
                        MasterData.DEF_C + 8)));
            }
            if (pitcher) {
                shown.add(new Views.Rating("구위(평균)", ScoutView.range(stuff(r), width, h.seed(), playerId, 100)));
                for (String[] b : pit) {
                    int c = Integer.parseInt(b[1]);
                    shown.add(new Views.Rating(b[0], ScoutView.range(r[c], width, h.seed(), playerId, c)));
                }
            }
            List<Views.Rating> hidden = List.of(
                    new Views.Rating("잠재력", ScoutView.range(r[MasterData.POTENTIAL], width * 3 / 2, h.seed(), playerId,
                            MasterData.POTENTIAL)),
                    new Views.Rating("내구성", ScoutView.range(r[MasterData.DURABILITY], width * 3 / 2, h.seed(),
                            playerId, MasterData.DURABILITY)));
            LocalDate injury = rs.getObject(8, LocalDate.class);
            String contract = (switch (rs.getInt(7)) {
                case ContractModel.PRE_ARB -> "연봉조정 전, FA까지 " + rs.getInt(6) + "년";
                case ContractModel.ARBITRATION -> "연봉조정, FA까지 " + rs.getInt(6) + "년";
                case ContractModel.GUARANTEED -> rs.getInt(6) + "년 남음";
                default -> "마이너 계약 " + rs.getInt(6) + "년";
            }) + " · 연봉 " + money(rs.getInt(5)) + " · 서비스 " + rs.getInt(9) + "년";
            return new Views.PlayerPage(playerId, rs.getString("name"), POS[Math.clamp(pos, 0, 10)],
                    age(rs.getObject("birth", LocalDate.class), h.date()),
                    rs.getString("bats") + "/" + rs.getString("throws_hand"),
                    team == 0 ? "FA" : teamName(team), LEVEL[Math.clamp(rs.getInt(3), 0, 2)],
                    rs.getInt(4) == 4 ? "은퇴" : team == 0 ? "소속 없음" : "",
                    contract, injury != null && injury.isAfter(h.date()) ? injury.toString() : null, pitcher, shown,
                    hidden, careerStats(h, playerId, pitcher), realStats(playerId, pitcher));
        }, h.careerId(), playerId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private List<Views.StatLine> careerStats(Views.CareerHead h, int playerId, boolean pitcher) {
        String sql = pitcher
                ? "SELECT season, level, team_id, g, gs, outs, er, so, bb, h, hr FROM c_pit_season "
                + "WHERE career_id = ? AND player_id = ? ORDER BY season, level"
                : "SELECT season, level, team_id, g, pa, ab, h, b2, b3, hr, bb, hbp, sf, sb FROM c_bat_season "
                + "WHERE career_id = ? AND player_id = ? ORDER BY season, level";
        return jdbc.query(sql, (rs, n) -> new Views.StatLine(rs.getInt(1), LEVEL[Math.clamp(rs.getInt(2), 0, 3)],
                abbrev(rs.getInt(3)), pitcher ? pitchingLine(rs, 4) : battingLine(rs, 4)), h.careerId(), playerId);
    }

    /** 실제 MLB 기록 (2025 까지, 마스터 선수만) */
    private List<Views.StatLine> realStats(int playerId, boolean pitcher) {
        String sql = pitcher
                ? "SELECT season, 0, team_id, g, gs, outs, er, so, bb, h, hr FROM mst_pit_season "
                + "WHERE player_id = ? AND season < ? ORDER BY season"
                : "SELECT season, 0, team_id, g, pa, ab, h, b2, b3, hr, bb, hbp, sf, sb FROM mst_bat_season "
                + "WHERE player_id = ? AND season < ? ORDER BY season";
        return jdbc.query(sql, (rs, n) -> new Views.StatLine(rs.getInt(1), "MLB", abbrev(rs.getInt(3)),
                pitcher ? pitchingLine(rs, 4) : battingLine(rs, 4)), playerId, MasterData.START_SEASON);
    }

    // ------------------------------------------------------------------ 박스스코어

    public Views.BoxPage box(Views.CareerHead h, int season, int gameNo) {
        List<byte[]> boxes = jdbc.query("SELECT box FROM c_game WHERE career_id = ? AND season = ? AND game_no = ?",
                (rs, n) -> rs.getBytes(1), h.careerId(), season, gameNo);
        if (boxes.isEmpty()) {
            return null;
        }
        BoxScore b = BoxScoreCodec.decode(boxes.getFirst());
        List<Integer> ids = new ArrayList<>();
        for (int[] side : b.lineupIds()) {
            for (int id : side) {
                ids.add(id);
            }
        }
        for (List<BoxScore.PitcherBox> side : b.pitching()) {
            for (BoxScore.PitcherBox p : side) {
                ids.add(p.playerId());
            }
        }
        Map<Integer, String> names = names(h.careerId(), ids);
        Map<Integer, Integer> team = new HashMap<>();
        jdbc.query("SELECT player_id, team_id FROM c_player WHERE career_id = ? AND player_id IN (" + in(ids) + ")",
                rs -> {
                    team.put(rs.getInt(1), rs.getInt(2));
                }, h.careerId());
        List<List<Views.BoxBatter>> batting = new ArrayList<>();
        List<List<Views.BoxPitcher>> pitching = new ArrayList<>();
        int[] runs = new int[2];
        for (int side = 0; side < 2; side++) {
            List<Views.BoxBatter> bats = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                int[] s = b.batting()[side][slot];
                runs[side] += s[BatStat.R];
                bats.add(new Views.BoxBatter(names.getOrDefault(b.lineupIds()[side][slot], "?"), s[BatStat.AB],
                        s[BatStat.R], s[BatStat.H], s[BatStat.RBI], s[BatStat.BB], s[BatStat.SO], s[BatStat.HR]));
            }
            batting.add(bats);
            List<Views.BoxPitcher> pits = new ArrayList<>();
            for (BoxScore.PitcherBox p : b.pitching().get(side)) {
                pits.add(new Views.BoxPitcher(names.getOrDefault(p.playerId(), "?"), ip(p.outs()), p.hits(), p.runs(),
                        p.earnedRuns(), p.walks(), p.strikeouts(), p.homeRuns(), p.pitches(),
                        p.decision() == ' ' ? "" : String.valueOf(p.decision())));
            }
            pitching.add(pits);
        }
        String away = abbrev(team.getOrDefault(b.lineupIds()[0][0], 0));
        String home = abbrev(team.getOrDefault(b.lineupIds()[1][0], 0));
        return new Views.BoxPage(season, gameNo, away, home, line(b.lineScore()[0]), line(b.lineScore()[1]), runs[0],
                runs[1], batting.get(0), batting.get(1), pitching.get(0), pitching.get(1));
    }

    Map<Integer, String> names(long careerId, List<Integer> ids) {
        Map<Integer, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String list = in(ids);
        jdbc.query("SELECT player_id, full_name FROM mst_player WHERE player_id IN (" + list + ")", rs -> {
            out.put(rs.getInt(1), rs.getString(2));
        });
        jdbc.query("SELECT player_id, full_name FROM c_person WHERE career_id = ? AND player_id IN (" + list + ")",
                rs -> {
                    out.put(rs.getInt(1), rs.getString(2));
                }, careerId);
        return out;
    }

    // ------------------------------------------------------------------ 표시 헬퍼

    /** 정수 ID 목록 → IN 절 (숫자만이라 주입 위험 없음) */
    private static String in(List<Integer> ids) {
        return ids.stream().distinct().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Integer> line(int[] innings) {
        List<Integer> out = new ArrayList<>(innings.length);
        for (int v : innings) {
            out.add(v);
        }
        return out;
    }

    private static String[] prefixed(String prefix, String[] cols) {
        String[] out = new String[cols.length];
        for (int i = 0; i < cols.length; i++) {
            out[i] = prefix + cols[i];
        }
        return out;
    }

    private static int[] ratings(ResultSet rs, int first) throws SQLException {
        int[] r = new int[MasterData.RATING_COLUMNS.length];
        for (int i = 0; i < r.length; i++) {
            int v = rs.getInt(first + i);
            r[i] = rs.wasNull() ? -1 : v;
        }
        return r;
    }

    static int stuff(int[] r) {
        double use = 0, sum = 0;
        for (int k = 0; k < MasterData.PITCH_TYPES; k++) {
            int u = r[MasterData.FIRST_PITCH + 2 * k];
            int s = r[MasterData.FIRST_PITCH + 2 * k + 1];
            if (u > 0 && s >= 0) {
                use += u;
                sum += u * s;
            }
        }
        return use > 0 ? (int) Math.round(sum / use) : -1;
    }

    /** 컬럼 순서: g, pa, ab, h, b2, b3, hr, bb, hbp, sf, sb (first 부터) */
    private static String battingLine(ResultSet rs, int first) throws SQLException {
        int g = rs.getInt(first);
        if (rs.wasNull() || g == 0) {
            return "-";
        }
        double pa = rs.getInt(first + 1), ab = rs.getInt(first + 2), h = rs.getInt(first + 3);
        double b2 = rs.getInt(first + 4), b3 = rs.getInt(first + 5), hr = rs.getInt(first + 6);
        double bb = rs.getInt(first + 7), hbp = rs.getInt(first + 8), sf = rs.getInt(first + 9);
        int sb = rs.getInt(first + 10);
        double avg = ab > 0 ? h / ab : 0;
        double obp = ab + bb + hbp + sf > 0 ? (h + bb + hbp) / (ab + bb + hbp + sf) : 0;
        double slg = ab > 0 ? (h + b2 + 2 * b3 + 3 * hr) / ab : 0;
        return String.format("%d경기 %d타석  %s/%s/%s  %d홈런 %d도루", g, (int) pa, rate(avg), rate(obp), rate(slg),
                (int) hr, sb);
    }

    /** 컬럼 순서: g, gs, outs, er, so, bb (first 부터) */
    private static String pitchingLine(ResultSet rs, int first) throws SQLException {
        int g = rs.getInt(first);
        if (rs.wasNull() || g == 0) {
            return "-";
        }
        int gs = rs.getInt(first + 1), outs = rs.getInt(first + 2), er = rs.getInt(first + 3);
        int so = rs.getInt(first + 4), bb = rs.getInt(first + 5);
        String era = outs > 0 ? String.format("%.2f", 27.0 * er / outs) : "-";
        return String.format("%d경기(%d선발) %s이닝  ERA %s  삼진 %d 볼넷 %d", g, gs, ip(outs), era, so, bb);
    }

    private static String rate(double v) {
        String s = String.format("%.3f", v);
        return s.startsWith("0") ? s.substring(1) : s;
    }

    static String ip(int outs) {
        return outs / 3 + "." + outs % 3;
    }

    static String money(int thousands) {
        return thousands <= 0 ? "-" : thousands < 1000 ? "$" + thousands + "K"
                : String.format("$%.2fM", thousands / 1000.0);
    }

    static int age(LocalDate birth, LocalDate on) {
        return birth == null ? 0 : Period.between(birth, on).getYears();
    }

    /** 스카우트 단계 표시 (1~5) */
    public static String scoutLabel(int level) {
        return level + "단계 (표시 범위 ±" + ScoutView.WIDTH[Math.clamp(level, 1, 5) - 1] + ")";
    }

    static <K, V> Map<K, V> ordered() {
        return new LinkedHashMap<>();
    }
}
