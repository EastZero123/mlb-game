package com.mlbgame.season;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mlbgame.boxscore.BoxScore;
import com.mlbgame.boxscore.BoxScoreCodec;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.SeasonCalendar;
import com.mlbgame.career.MasterPlayer;
import com.mlbgame.league.LeagueBaselineRepository;
import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.GameSimulator;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.PitcherRatings;
import com.mlbgame.sim.RateModel;

/**
 * DB → {@link CareerRuntime}. 캐시에 없을 때만 (서버 시작 후 첫 진행, 오래 쉰 커리어, 저장 실패 후) 실행된다.
 * 비용: career 1행 + c_player 약 2,650행 + 순위 90행 읽기, 팀 90개 자동 구성.
 */
@Component
class CareerRuntimeLoader {

    private static final String[] R = MasterData.RATING_COLUMNS;

    private final JdbcTemplate jdbc;
    private final MasterData master;
    private final LeagueBaselineRepository baselines;

    private final com.mlbgame.ops.Ops ops;

    CareerRuntimeLoader(JdbcTemplate jdbc, MasterData master, LeagueBaselineRepository baselines,
                        com.mlbgame.ops.Ops ops) {
        this.jdbc = jdbc;
        this.master = master;
        this.baselines = baselines;
        this.ops = ops;
    }

    /** 커리어 메모리 상태 적재 (캐시에 없을 때 1번). 시간·선수 수를 기록 */
    CareerRuntime load(long careerId) {
        long t0 = System.nanoTime();
        CareerRuntime rt = build(careerId);
        ops.record("load", careerId, System.nanoTime() - t0, "players=" + rt.players.size());
        return rt;
    }

    private CareerRuntime build(long careerId) {
        MasterData.Snapshot snap = master.get();
        Object[] c = jdbc.query("""
                SELECT seed, team_id, season, cur_date, phase, next_game_no, version
                FROM career WHERE career_id = ? AND deleted_at IS NULL
                """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3),
                rs.getObject(4, LocalDate.class), rs.getInt(5), rs.getInt(6), rs.getInt(7)} : null, careerId);
        if (c == null) {
            throw new IllegalArgumentException("커리어 없음: " + careerId);
        }
        int season = (int) c[2];

        // 리그 환경: 시즌 보정값이 없으면 시작 시즌 값
        LeagueBaseline mlb = baselines.find(season)
                .or(() -> baselines.find(MasterData.START_SEASON))
                .orElse(LeagueBaseline.mlbDefault());
        LeagueBaseline[] env = {mlb, baselines.forLevel(mlb, MasterData.START_SEASON, 1),
                baselines.forLevel(mlb, MasterData.START_SEASON, 2)};

        List<Integer> teams = snap.teamIds();
        Map<Integer, Integer> teamIndex = new HashMap<>();
        for (int i = 0; i < teams.size(); i++) {
            teamIndex.put(teams.get(i), i);
        }
        // 커리어에서 생긴 선수 신상 (마스터에 없음)
        Map<Integer, Profile> persons = new HashMap<>();
        int[] maxPerson = {Filler.FIRST_ID - 1};
        jdbc.query("SELECT player_id, primary_pos, bats, throws FROM c_person WHERE career_id = ?", rs -> {
            int id = rs.getInt(1);
            persons.put(id, new Profile(rs.getInt(2), hand(rs.getString(3)), hand(rs.getString(4))));
            maxPerson[0] = Math.max(maxPerson[0], id);
        }, careerId);

        Map<Integer, CareerRuntime.Player> players = new HashMap<>(4096);
        jdbc.query("""
                SELECT player_id, team_id, level, roster_status, injury_until, %s FROM c_player
                WHERE career_id = ? AND team_id IS NOT NULL
                """.formatted(String.join(", ", R)), rs -> {
            int id = rs.getInt(1);
            MasterPlayer mp = snap.byId().get(id);
            Profile m = mp != null ? new Profile(mp.primaryPos(), mp.bats(), mp.throwsHand()) : persons.get(id);
            if (m == null) {
                return;
            }
            int team = rs.getInt(2);
            LocalDate until = rs.getObject(5, LocalDate.class);
            int[] r = new int[R.length];
            for (int i = 0; i < r.length; i++) {
                int v = rs.getInt(6 + i);
                r[i] = rs.wasNull() ? -1 : v;
            }
            int durability = r[MasterData.DURABILITY] < 0 ? 50 : r[MasterData.DURABILITY];
            players.put(id, new CareerRuntime.Player(id, card(id, team, m, r), durability, team, rs.getInt(3),
                    rs.getInt(4), until == null ? 0 : until.toEpochDay()));
        }, careerId);

        AutoManager[] managers = new AutoManager[CareerRuntime.LEVELS];
        GameSimulator[] sims = new GameSimulator[CareerRuntime.LEVELS];
        for (int lv = 0; lv < CareerRuntime.LEVELS; lv++) {
            managers[lv] = new AutoManager(new RateModel(env[lv]));
            sims[lv] = new GameSimulator(env[lv]);
        }

        int[][][] standings = new int[CareerRuntime.LEVELS][teams.size()][5];
        jdbc.query("SELECT team_id, wins, losses, runs_for, runs_against, streak FROM c_team WHERE career_id = ?",
                rs -> {
                    fill(standings[0], teamIndex.get(rs.getInt(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6));
                }, careerId);
        jdbc.query("""
                SELECT level, team_id, wins, losses, runs_for, runs_against FROM c_team_minor WHERE career_id = ?
                """, rs -> {
            fill(standings[rs.getInt(1)], teamIndex.get(rs.getInt(2)), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    rs.getInt(6), 0);
        }, careerId);

        CareerRuntime rt = new CareerRuntime(careerId, (long) c[0], (int) c[1], teams, teamIndex, players, managers,
                standings, sims);
        rt.season = season;
        rt.curDate = (LocalDate) c[3];
        rt.phase = (int) c[4];
        rt.nextGameNo = (int) c[5];
        rt.version = (int) c[6];
        rt.nextPersonId = maxPerson[0] + 1;
        jdbc.query("""
                SELECT series_id, round, league, best_of, high_team, low_team, high_seed, low_seed,
                       high_wins, low_wins, next_date, winner
                FROM c_series WHERE career_id = ? AND season = ? ORDER BY series_id
                """, rs -> {
            Series x = new Series(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                    rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getObject(11, LocalDate.class));
            x.highWins = rs.getInt(9);
            x.lowWins = rs.getInt(10);
            x.winner = rs.getInt(12);
            rt.series.add(x);
        }, careerId, season);
        if (rt.phase != NextDayService.PHASE_OFFSEASON) {
            fixFortyMan(rt); // 오프시즌 중 로스터는 단계 처리가 정리 (개막 로스터 단계에서 40인 맞춤)
        }
        rt.userPlan = TacticService.loadPlan(jdbc, careerId);
        long today = rt.curDate.toEpochDay() + 1;
        rt.rebuildAll(today);
        restoreFatigue(rt, snap);
        return rt;
    }

    /**
     * 불펜 피로는 메모리에만 둔다. 캐시에서 내렸다 다시 만들 때는 최근 3일 MLB 박스스코어(투수별 투구 수)로 복원
     * (마이너는 박스스코어가 없어 피로 0 에서 시작).
     */
    private void restoreFatigue(CareerRuntime rt, MasterData.Snapshot snap) {
        List<MasterData.ScheduledGame> recent = new ArrayList<>();
        Map<Integer, Long> dayOfGame = new HashMap<>();
        for (int back = 2; back >= 0; back--) {
            LocalDate d = rt.curDate.minusDays(back);
            for (MasterData.ScheduledGame g : snap.schedule().getOrDefault(SeasonCalendar.toTemplate(d, rt.season),
                    List.of())) {
                recent.add(g);
                dayOfGame.put(g.gameNo(), d.toEpochDay());
            }
        }
        if (recent.isEmpty()) {
            return;
        }
        int from = recent.stream().mapToInt(MasterData.ScheduledGame::gameNo).min().orElse(0);
        int to = recent.stream().mapToInt(MasterData.ScheduledGame::gameNo).max().orElse(0);
        jdbc.query("""
                SELECT game_no, box FROM c_game WHERE career_id = ? AND season = ? AND game_no BETWEEN ? AND ?
                ORDER BY game_no
                """, rs -> {
            Long day = dayOfGame.get(rs.getInt(1));
            if (day == null) {
                return;
            }
            BoxScore box = BoxScoreCodec.decode(rs.getBytes(2));
            for (List<BoxScore.PitcherBox> side : box.pitching()) {
                for (BoxScore.PitcherBox pb : side) {
                    if (!pb.starter()) {
                        Fatigue.record(rt.pitcherLoad, pb.playerId(), day, pb.pitches());
                    }
                }
            }
        }, rt.careerId, rt.season, from, to);
    }

    /**
     * 40인 초과 정리 (커리어 생성 직후 실제 로스터 자료, 오프시즌 재배치, 규칙 도입 전 커리어). 바로 저장해서 화면과 맞춘다.
     * 정상 상태면 바뀌는 선수가 없어 쿼리도 없다.
     */
    private void fixFortyMan(CareerRuntime rt) {
        int removed = 0;
        for (int team : rt.teamIds) {
            removed += DayRules.trimFortyMan(rt, team);
        }
        if (removed == 0) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(removed);
        for (CareerRuntime.Player p : rt.players.values()) {
            if (p.dirty) {
                rows.add(new Object[] {p.status, rt.careerId, p.id});
                p.dirty = false;
            }
        }
        jdbc.batchUpdate("UPDATE c_player SET roster_status = ? WHERE career_id = ? AND player_id = ?", rows);
    }

    /** 시뮬에 필요한 신상 (마스터 또는 c_person) */
    record Profile(int primaryPos, Hand bats, Hand throwsHand) {
    }

    static Hand hand(String code) {
        return switch (code) {
            case "L" -> Hand.LEFT;
            case "S" -> Hand.SWITCH;
            default -> Hand.RIGHT;
        };
    }

    private static void fill(int[][] table, Integer idx, int w, int l, int rf, int ra, int streak) {
        if (idx != null) {
            table[idx] = new int[] {w, l, rf, ra, streak};
        }
    }

    /** 커리어 능력치 → 시뮬용 카드. 투수 구위 = 구사율 가중 평균 */
    static PlayerCard card(int id, int team, Profile m, int[] r) {
        BatterRatings batting = r[MasterData.CONTACT] < 0 ? null
                : new BatterRatings(r[0], r[1], r[2], r[3], r[4], r[5], r[6]);
        PitcherRatings pitching = null;
        if (r[MasterData.CONTROL] >= 0) {
            double use = 0;
            double sum = 0;
            for (int k = 0; k < MasterData.PITCH_TYPES; k++) {
                int u = r[MasterData.FIRST_PITCH + 2 * k];
                int s = r[MasterData.FIRST_PITCH + 2 * k + 1];
                if (u > 0 && s >= 0) {
                    use += u;
                    sum += u * s;
                }
            }
            int stuff = use > 0 ? (int) Math.round(sum / use) : 40;
            pitching = new PitcherRatings(stuff, r[7], r[8], r[9], r[10]);
        }
        int[] def = new int[8];
        for (int i = 0; i < 8; i++) {
            def[i] = Math.max(0, r[MasterData.DEF_C + i]);
        }
        int arm = r[MasterData.DEF_C + 8] < 0 ? 50 : r[MasterData.DEF_C + 8];
        return new PlayerCard(id, team, m.primaryPos(), m.bats(), m.throwsHand(), batting, pitching, def, arm);
    }
}
