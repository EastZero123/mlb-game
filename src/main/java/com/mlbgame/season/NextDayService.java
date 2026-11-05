package com.mlbgame.season;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.boxscore.BoxScoreCodec;
import com.mlbgame.career.CareerCreator;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.SeasonCalendar;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.SimTeam;

/**
 * "다음 날" 1회 = 하루치 MLB + AAA + AA 경기.
 * <pre>
 * 0. 경기 전: 부상 복귀·월요일 정기 교체 ({@link DayRules})
 * 1. 메모리 상태(캐시)로 전 경기 시뮬 (휴식 교체·불펜 피로 반영) → 하루치 기록 합산   (DB 읽기 0)
 *    경기 후: 피로 기록·부상 판정 → 하루 끝에 콜업·팀 재구성
 * 2. 한 트랜잭션:
 *    career UPDATE (version 확인 = 다른 요청이 먼저 진행했으면 실패)
 *    c_game 배치 INSERT (MLB 경기만, 박스스코어 바이너리)
 *    c_bat_season / c_pit_season 가산 upsert (레벨별, 오늘 뛴 선수만)
 *    c_team / c_team_minor upsert (절대값)
 *    c_player UPDATE (오늘 상태가 바뀐 선수만: 부상·복귀·콜업·강등)
 * </pre>
 * 경기 seed = 커리어 seed + 시즌 + 레벨 + 경기 키 → 같은 커리어·같은 상태면 항상 같은 결과.
 */
@Service
public class NextDayService {

    public static final int PHASE_REGULAR = 1;
    public static final int PHASE_POSTSEASON = 2;
    public static final int PHASE_OFFSEASON = 3;
    /** 포스트시즌 기록 레벨 (c_bat_season / c_pit_season) */
    public static final int LEVEL_POSTSEASON = 3;
    /** 포스트시즌 경기 번호 시작 (c_game) */
    static final int POSTSEASON_GAME_BASE = 10_000;

    private static final String BAT_COLS = "g, pa, ab, r, h, b2, b3, hr, rbi, bb, so, hbp, sb, cs, sf, gidp";
    private static final String PIT_COLS = "g, gs, w, l, sv, outs, bf, pitches, h, r, er, bb, so, hr, hbp";
    /** 휴식·부상 판정 난수를 경기 난수와 분리 (같은 seed 에서 다른 흐름) */
    private static final long RULES_SALT = 0x5DEECE66DL;
    private static final String SERIES_UPSERT = "INSERT INTO c_series (career_id, season, series_id, round, league, "
            + "best_of, high_team, low_team, high_seed, low_seed, high_wins, low_wins, next_date, winner) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n ON DUPLICATE KEY UPDATE high_wins = n.high_wins, "
            + "low_wins = n.low_wins, next_date = n.next_date, winner = n.winner";
    private static final String PERSON_INSERT = "INSERT INTO c_person (career_id, player_id, full_name, birth_date, "
            + "bats, throws, primary_pos, origin, created_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String BAT_UPSERT = upsert("c_bat_season", BAT_COLS);
    static final String PIT_UPSERT = upsert("c_pit_season", PIT_COLS);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterData master;
    private final CareerRuntimeCache cache;
    private final com.mlbgame.ops.Ops ops;

    NextDayService(JdbcTemplate jdbc, TransactionTemplate tx, MasterData master, CareerRuntimeCache cache,
                   com.mlbgame.ops.Ops ops) {
        this.ops = ops;
        this.jdbc = jdbc;
        this.tx = tx;
        this.master = master;
        this.cache = cache;
    }

    /**
     * @param loadMicros 캐시에 없어 DB 에서 상태를 만든 시간 (캐시 적중이면 0)
     */
    public record DayReport(LocalDate date, int mlbGames, int minorGames, int phase, int batRows, int pitRows,
                            int injuries, int mlbInjuries, int returns, int moves, int signed,
                            int shortRosterKeeps,
                            long loadMicros, long simMicros, long dbMicros) {
    }

    public static class ConcurrentAdvanceException extends RuntimeException {
        ConcurrentAdvanceException(long careerId) {
            super("다른 요청이 먼저 진행함: 커리어 " + careerId);
        }
    }

    public DayReport advance(long careerId) {
        long t0 = System.nanoTime();
        boolean cached = cache.isLoaded(careerId);
        CareerRuntime rt = cache.get(careerId);
        long loadMicros = cached ? 0 : (System.nanoTime() - t0) / 1000;

        rt.lock.lock();
        try {
            if (rt.phase != PHASE_REGULAR && rt.phase != PHASE_POSTSEASON) {
                throw new IllegalStateException("시즌이 끝남: 오프시즌 처리 필요 (단계 " + rt.phase + ")");
            }
            MasterData.Snapshot snap = master.get();
            LocalDate date = rt.curDate.plusDays(1);
            long t1 = System.nanoTime();

            long today = date.toEpochDay();
            DayRules.Events ev = new DayRules.Events();
            DayRules.startOfDay(rt, date, ev);

            DayBatch batch = new DayBatch();
            int nextGameNo = rt.nextGameNo;
            int phase;
            if (rt.phase == PHASE_POSTSEASON) {
                postseasonGames(rt, snap, date, today, batch, ev);
                phase = Postseason.champion(rt.series) != null ? PHASE_OFFSEASON : PHASE_POSTSEASON;
            } else {
                nextGameNo = regularGames(rt, snap, date, today, batch, ev);
                phase = date.isBefore(SeasonCalendar.lastDay(snap, rt.season)) ? PHASE_REGULAR : PHASE_POSTSEASON;
            }
            DayRules.endOfDay(rt, today, ev);
            List<CareerRuntime.Player> changed = new ArrayList<>();
            for (CareerRuntime.Player p : rt.players.values()) {
                if (p.dirty) {
                    changed.add(p);
                }
            }
            List<Series> seriesChanged = rt.series.stream().filter(s -> s.dirty).toList();
            long t2 = System.nanoTime();

            // 마이너 기록은 모아 두었다가 7일마다·단계가 바뀌는 날 함께 저장 (PendingStats)
            Map<Long, int[]> minorBat = PendingStats.takeMinor(batch.bat);
            Map<Long, int[]> minorPit = PendingStats.takeMinor(batch.pit);
            boolean flushMinor = rt.pending.due(phase != rt.phase);
            Map<Long, int[]> extraBat = flushMinor ? PendingStats.combined(rt.pending.bat, minorBat) : Map.of();
            Map<Long, int[]> extraPit = flushMinor ? PendingStats.combined(rt.pending.pit, minorPit) : Map.of();
            long[] steps = new long[4];
            persist(rt, batch, extraBat, extraPit, changed, ev.signed, seriesChanged, date, nextGameNo, phase, steps);
            if (flushMinor) {
                rt.pending.clear();
            } else {
                rt.pending.add(minorBat, minorPit); // 저장이 성공한 뒤에만 합산
            }
            for (CareerRuntime.Player p : changed) {
                p.dirty = false;
            }
            seriesChanged.forEach(s -> s.dirty = false);
            rt.curDate = date;
            rt.nextGameNo = nextGameNo;
            rt.phase = phase;
            rt.version++;
            long t3 = System.nanoTime();
            ops.record("day", careerId, t3 - t0, String.format(
                    "sim=%d db=%d load=%d games=%d minor=%d rows=%d minorRows=%d flush=%s dbGames=%d dbBat=%d dbPit=%d dbPlayers=%d",
                    (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, loadMicros / 1000, batch.games.size(),
                    batch.minorGames, batch.bat.size() + batch.pit.size(), extraBat.size() + extraPit.size(),
                    flushMinor, steps[0] / 1_000_000, steps[1] / 1_000_000, steps[2] / 1_000_000,
                    steps[3] / 1_000_000));
            return new DayReport(date, batch.games.size(), batch.minorGames, phase, batch.bat.size(),
                    batch.pit.size(), ev.injuries, ev.mlbInjuries, ev.returns, ev.moves, ev.signed.size(),
                    rt.shortRosterKeeps,
                    loadMicros, (t2 - t1) / 1000, (t3 - t2) / 1000);
        } catch (RuntimeException e) {
            cache.evict(careerId); // 메모리가 DB 보다 앞섰을 수 있음 → 다음 요청 때 DB 기준으로 다시 만든다
            if (e instanceof ConcurrentAdvanceException) {
                ops.conflict("day", careerId);
            }
            throw e;
        } finally {
            rt.lock.unlock();
        }
    }

    /**
     * 포스트시즌 하루: 첫날 대진 생성 → 오늘 경기가 잡힌 시리즈마다 1경기 → 라운드가 끝났으면 다음 라운드 (이틀 뒤 시작).
     * 상위 4선발 로테이션, 휴식 교체 없음. 경기 번호 = 10000 + 시리즈 × 10 + 경기 순번, 기록은 레벨 3.
     */
    private void postseasonGames(CareerRuntime rt, MasterData.Snapshot snap, LocalDate date, long today,
                                 DayBatch batch, DayRules.Events ev) {
        Map<Integer, int[]> standings = rt.mlbStandings();
        if (rt.series.isEmpty()) {
            for (Series s : Postseason.wildCard(standings, snap.teams(),
                    SeasonCalendar.lastDay(snap, rt.season).plusDays(2))) {
                s.dirty = true;
                rt.series.add(s);
            }
        }
        for (Series s : List.copyOf(rt.series)) {
            if (s.done() || !date.equals(s.nextDate)) {
                continue;
            }
            int gameNo = POSTSEASON_GAME_BASE + s.id * 10 + s.played() + 1;
            long gs = gameSeed(rt.seed, rt.season, 0, gameNo);
            SplittableRandom rules = new SplittableRandom(gs ^ RULES_SALT);
            int homeId = s.homeTeam();
            int awayId = s.awayTeam();
            SimTeam awayBase = DayRules.postseasonTeam(rt, awayId);
            SimTeam homeBase = DayRules.postseasonTeam(rt, homeId);
            SimTeam away = DayRules.gameTeam(rt, 0, awayId, awayBase, today, rules, homeBase.starter().hand(),
                    false);
            SimTeam home = DayRules.gameTeam(rt, 0, homeId, homeBase, today, rules, awayBase.starter().hand(),
                    false);
            GameResult r = rt.sims[0].simulate(away, home, gs);
            batch.addGame(gameNo, r, BoxScoreCodec.encode(r, away, home));
            batch.record(LEVEL_POSTSEASON, r, away, home, rt);
            DayRules.afterGame(rt, 0, r, away, home, today, rules, ev);
            s.record(r.homeWin() ? homeId : awayId, date);
        }
        rt.series.addAll(Postseason.nextRound(rt.series, standings, snap.teams(), date.plusDays(2)));
    }

    /** 정규시즌 하루: MLB 일정 + AAA·AA 계산 일정. @return 다음 MLB 경기 번호 */
    private int regularGames(CareerRuntime rt, MasterData.Snapshot snap, LocalDate date, long today,
                             DayBatch batch, DayRules.Events ev) {
        int nextGameNo = rt.nextGameNo;
        LocalDate template = SeasonCalendar.toTemplate(date, rt.season);
        for (MasterData.ScheduledGame g : snap.schedule().getOrDefault(template, List.of())) {
            long gs = gameSeed(rt.seed, rt.season, 0, g.gameNo());
            SplittableRandom rules = new SplittableRandom(gs ^ RULES_SALT);
            SimTeam awayBase = DayRules.baseTeam(rt, 0, g.awayTeamId());
            SimTeam homeBase = DayRules.baseTeam(rt, 0, g.homeTeamId());
            SimTeam away = DayRules.gameTeam(rt, 0, g.awayTeamId(), awayBase, today, rules,
                    homeBase.starter().hand(), true);
            SimTeam home = DayRules.gameTeam(rt, 0, g.homeTeamId(), homeBase, today, rules,
                    awayBase.starter().hand(), true);
            GameResult r = rt.sims[0].simulate(away, home, gs);
            batch.addGame(g.gameNo(), r, BoxScoreCodec.encode(r, away, home));
            batch.record(0, r, away, home, rt);
            rt.applyResult(0, g.awayTeamId(), g.homeTeamId(), r);
            DayRules.afterGame(rt, 0, r, away, home, today, rules, ev);
            nextGameNo = Math.max(nextGameNo, g.gameNo() + 1);
        }
        for (int level = 1; level <= 2; level++) {
            int day = MinorSchedule.playDay(date,
                    MinorSchedule.start(level, SeasonCalendar.openingDay(snap, rt.season)),
                    MinorSchedule.end(level, SeasonCalendar.lastDay(snap, rt.season)));
            List<MinorSchedule.Game> games = MinorSchedule.games(day, rt.teamIds);
            for (int i = 0; i < games.size(); i++) {
                MinorSchedule.Game g = games.get(i);
                long gs = gameSeed(rt.seed, rt.season, level, day * 32 + i);
                SplittableRandom rules = new SplittableRandom(gs ^ RULES_SALT);
                SimTeam awayBase = DayRules.baseTeam(rt, level, g.awayTeamId());
                SimTeam homeBase = DayRules.baseTeam(rt, level, g.homeTeamId());
                SimTeam away = DayRules.gameTeam(rt, level, g.awayTeamId(), awayBase, today, rules,
                        homeBase.starter().hand(), true);
                SimTeam home = DayRules.gameTeam(rt, level, g.homeTeamId(), homeBase, today, rules,
                        awayBase.starter().hand(), true);
                GameResult r = rt.sims[level].simulate(away, home, gs);
                batch.record(level, r, away, home, rt);
                rt.applyResult(level, g.awayTeamId(), g.homeTeamId(), r);
                DayRules.afterGame(rt, level, r, away, home, today, rules, ev);
                batch.minorGames++;
            }
        }
        return nextGameNo;
    }

    /**
     * @param extraBat·extraPit 오늘 함께 저장할 마이너 기록 (모아 둔 것 + 오늘 몫, 저장 날이 아니면 빈 맵)
     * @param steps             단계별 시간 (나노초) {경기·순위, 타격, 투구, 선수·시리즈·대체 선수}
     */
    private void persist(CareerRuntime rt, DayBatch batch, Map<Long, int[]> extraBat, Map<Long, int[]> extraPit,
                         List<CareerRuntime.Player> changed, List<Filler.Signed> signed, List<Series> series,
                         LocalDate date, int nextGameNo, int phase, long[] steps) {
        long id = rt.careerId;
        int season = rt.season;
        List<Object[]> games = new ArrayList<>(batch.games.size());
        for (DayBatch.Game g : batch.games) {
            games.add(new Object[] {id, season, g.gameNo(), g.awayRuns(), g.homeRuns(), g.innings(), g.box()});
        }
        List<Object[]> bat = rows(id, season, batch.bat);
        bat.addAll(rows(id, season, extraBat));
        List<Object[]> pit = rows(id, season, batch.pit);
        pit.addAll(rows(id, season, extraPit));
        List<Object[]> teams = new ArrayList<>(30);
        List<Object[]> minorTeams = new ArrayList<>(60);
        for (int i = 0; i < rt.teamIds.size(); i++) {
            int[] s = rt.standings[0][i];
            teams.add(new Object[] {id, rt.teamIds.get(i), s[0], s[1], s[2], s[3], s[4]});
            for (int level = 1; level <= 2; level++) {
                int[] m = rt.standings[level][i];
                minorTeams.add(new Object[] {id, level, rt.teamIds.get(i), m[0], m[1], m[2], m[3]});
            }
        }

        tx.executeWithoutResult(st -> {
            long s0 = System.nanoTime();
            int updated = jdbc.update("""
                    UPDATE career SET cur_date = ?, next_game_no = ?, phase = ?, version = version + 1
                    WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                    """, date, nextGameNo, phase, id, rt.version);
            if (updated != 1) {
                throw new ConcurrentAdvanceException(id);
            }
            if (!games.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO c_game (career_id, season, game_no, away_runs, home_runs, innings, box)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, games);
                jdbc.batchUpdate("""
                        INSERT INTO c_team (career_id, team_id, wins, losses, runs_for, runs_against, streak)
                        VALUES (?, ?, ?, ?, ?, ?, ?) AS n
                        ON DUPLICATE KEY UPDATE wins = n.wins, losses = n.losses, runs_for = n.runs_for,
                            runs_against = n.runs_against, streak = n.streak
                        """, teams);
            }
            if (batch.minorGames > 0) {
                jdbc.batchUpdate("""
                        INSERT INTO c_team_minor (career_id, level, team_id, wins, losses, runs_for, runs_against)
                        VALUES (?, ?, ?, ?, ?, ?, ?) AS n
                        ON DUPLICATE KEY UPDATE wins = n.wins, losses = n.losses, runs_for = n.runs_for,
                            runs_against = n.runs_against
                        """, minorTeams);
            }
            long s1 = System.nanoTime();
            if (!bat.isEmpty()) {
                jdbc.batchUpdate(BAT_UPSERT, bat);
            }
            long s2 = System.nanoTime();
            if (!pit.isEmpty()) {
                jdbc.batchUpdate(PIT_UPSERT, pit);
            }
            long s3 = System.nanoTime();
            steps[1] = s2 - s1;
            steps[2] = s3 - s2;
            steps[0] = System.nanoTime() - s0 - steps[1] - steps[2];
            long s4 = System.nanoTime();
            if (!series.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(series.size());
                for (Series x : series) {
                    rows.add(new Object[] {id, season, x.id, x.round, x.league, x.bestOf, x.high, x.low, x.highSeed,
                            x.lowSeed, x.highWins, x.lowWins, x.nextDate, x.winner == 0 ? null : x.winner});
                }
                jdbc.batchUpdate(SERIES_UPSERT, rows);
            }
            if (!signed.isEmpty()) {
                List<Object[]> persons = new ArrayList<>(signed.size());
                List<Object[]> newPlayers = new ArrayList<>(signed.size());
                for (Filler.Signed f : signed) {
                    persons.add(f.personRow());
                    newPlayers.add(f.playerRow());
                }
                jdbc.batchUpdate(PERSON_INSERT, persons);
                jdbc.batchUpdate(CareerCreator.INSERT_PLAYER, newPlayers);
            }
            if (!changed.isEmpty()) {
                List<Object[]> rows = new ArrayList<>(changed.size());
                for (CareerRuntime.Player p : changed) {
                    rows.add(new Object[] {p.team, p.level, p.status,
                            p.injuredUntil > 0 ? LocalDate.ofEpochDay(p.injuredUntil) : null, id, p.id});
                }
                jdbc.batchUpdate("""
                        UPDATE c_player SET team_id = ?, level = ?, roster_status = ?, injury_until = ?
                        WHERE career_id = ? AND player_id = ?
                        """, rows);
            }
            steps[3] = System.nanoTime() - s4;
        });
    }

    /** 누적 배열 [구단, 값...] → (career, season, level, player, 구단, 값...) */
    static List<Object[]> rows(long careerId, int season, Map<Long, int[]> acc) {
        List<Object[]> out = new ArrayList<>(acc.size());
        acc.forEach((key, a) -> {
            Object[] r = new Object[4 + a.length];
            r[0] = careerId;
            r[1] = season;
            r[2] = DayBatch.level(key);
            r[3] = DayBatch.playerId(key);
            for (int i = 0; i < a.length; i++) {
                r[4 + i] = a[i];
            }
            out.add(r);
        });
        return out;
    }

    /** 가산 upsert: 이미 있는 행은 각 기록에 더하고 구단만 덮어쓴다. 기존 값은 테이블명으로 한정 (행 별칭 n 과 모호함 방지) */
    static String upsert(String table, String cols) {
        String[] c = cols.split(", ");
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (career_id, season, level, player_id, team_id, ").append(cols).append(") VALUES (?, ?, ?, ?, ?");
        sql.append(", ?".repeat(c.length)).append(") AS n ON DUPLICATE KEY UPDATE team_id = n.team_id");
        for (String col : c) {
            sql.append(", ").append(col).append(" = ").append(table).append('.').append(col).append(" + n.").append(col);
        }
        return sql.toString();
    }

    static long gameSeed(long careerSeed, int season, int level, int key) {
        long x = careerSeed ^ ((long) season << 40) ^ ((long) level << 36) ^ key;
        return new SplittableRandom(x).nextLong();
    }
}
