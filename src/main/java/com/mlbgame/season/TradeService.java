package com.mlbgame.season;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.SeasonCalendar;
import com.mlbgame.offseason.LeagueBudget;
import com.mlbgame.offseason.OffseasonService;
import com.mlbgame.offseason.Scouting;

/**
 * 유저 제안 트레이드 (선수끼리, 한쪽 최대 5명). 판정은 {@link TradeRules}.
 * <pre>
 * 기간   정규시즌 개막 ~ 7월 31일 (트레이드 마감) / 오프시즌 FA 시장 마감 뒤 ~ 개막 전 (7·8단계 전)
 * 규칙   양 팀 모두 트레이드 뒤 40인 ≤ 40, 연봉 합계(40인)가 늘어나면 예산 이하
 *        (시즌 중 = 올해 예산, 오프시즌 = 다음 시즌 예산, 내 팀은 스카우트 비용 차감)
 * 적용   시즌 중: 메모리 상태에서 소속 이동 → 26인 넘으면 강등·모자라면 콜업 → 바뀐 선수 행 저장 + 팀 구성 재생성
 *        오프시즌: 소속만 바꿔 저장 (개막 로스터 단계에서 레벨 재배치)
 * </pre>
 */
@Service
public class TradeService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CareerRuntimeCache cache;
    private final MasterData master;
    private final com.mlbgame.ops.Ops ops;

    TradeService(JdbcTemplate jdbc, TransactionTemplate tx, CareerRuntimeCache cache, MasterData master,
                 com.mlbgame.ops.Ops ops) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cache = cache;
        this.master = master;
        this.ops = ops;
    }

    public static final int MAX_PER_SIDE = TradeRules.MAX_PER_SIDE;

    /** 트레이드 가능 여부 (message = 닫혀 있으면 이유) */
    public record Window(boolean open, boolean offseason, String message) {
    }

    /** 화면 미리보기용 구단 현황 (천 달러) */
    public record TeamInfo(int teamId, int budget, int payroll, int fortyMan) {
    }

    public record Result(boolean accepted, String message) {
    }

    public record History(LocalDate date, int season, int partner, String summary) {
    }

    // ------------------------------------------------------------------ 조회

    public Window window(long careerId) {
        return state(careerId).window;
    }

    /** 두 구단의 예산·연봉 합계·40인 (화면은 내 팀 것만 보여 준다) */
    public Map<Integer, TeamInfo> teams(long careerId, int userTeam, int partner) {
        State s = state(careerId);
        Map<Integer, TeamInfo> out = new LinkedHashMap<>();
        for (int team : new int[] {userTeam, partner}) {
            out.put(team, new TeamInfo(team, s.budget(team), s.payroll.getOrDefault(team, 0),
                    s.forty.getOrDefault(team, 0)));
        }
        return out;
    }

    /** 선수 ID → "FA 3년", "연봉조정 2년", "최저 5년", "마이너" (두 구단) */
    public Map<Integer, String> contracts(long careerId, int... teams) {
        Map<Integer, String> out = new HashMap<>();
        for (int team : teams) {
            jdbc.query("""
                    SELECT player_id, contract_type, contract_years FROM c_player
                    WHERE career_id = ? AND team_id = ? AND roster_status <> 4
                    """, rs -> {
                int years = rs.getInt(3);
                out.put(rs.getInt(1), switch (rs.getInt(2)) {
                    case ContractModel.GUARANTEED -> "FA " + years + "년";
                    case ContractModel.ARBITRATION -> "연봉조정 " + years + "년";
                    case ContractModel.PRE_ARB -> "최저 " + years + "년";
                    default -> "마이너";
                });
            }, careerId, team);
        }
        return out;
    }

    public List<History> history(long careerId) {
        return jdbc.query("""
                SELECT trade_date, season, partner_team, summary FROM c_trade WHERE career_id = ?
                ORDER BY trade_id DESC LIMIT 20
                """, (rs, i) -> new History(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3),
                rs.getString(4)), careerId);
    }

    // ------------------------------------------------------------------ 제안

    /**
     * @param give 내 팀에서 보낼 선수, get 상대에게서 받을 선수
     * @throws IllegalArgumentException 기간·인원·소속 규칙 위반 (판정 전 단계)
     */
    public Result propose(long careerId, int partner, List<Integer> give, List<Integer> get) {
        long t0 = System.nanoTime();
        Result r = evaluateAndApply(careerId, partner, give, get);
        ops.record("trade", careerId, System.nanoTime() - t0, "accepted=" + r.accepted());
        return r;
    }

    private Result evaluateAndApply(long careerId, int partner, List<Integer> give, List<Integer> get) {
        State s = state(careerId);
        if (!s.window.open()) {
            throw new IllegalArgumentException(s.window.message());
        }
        give = give == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(give));
        get = get == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(get));
        if (partner == s.userTeam || !master.get().teamIds().contains(partner)) {
            throw new IllegalArgumentException("상대 구단을 골라 주세요.");
        }
        if (give.isEmpty() && get.isEmpty()) {
            throw new IllegalArgumentException("주고받을 선수를 골라 주세요.");
        }
        if (give.size() > MAX_PER_SIDE || get.size() > MAX_PER_SIDE) {
            throw new IllegalArgumentException("한쪽에 최대 " + MAX_PER_SIDE + "명까지 묶을 수 있습니다.");
        }
        Map<Integer, Row> rows = rows(careerId, s.evalSeason, give, get);
        List<TradeRules.Asset> out = new ArrayList<>();
        List<TradeRules.Asset> in = new ArrayList<>();
        for (int id : give) {
            Row r = rows.get(id);
            if (r == null || r.asset.team() != s.userTeam) {
                throw new IllegalArgumentException("내 팀 선수가 아닙니다: " + id);
            }
            out.add(r.asset);
        }
        for (int id : get) {
            Row r = rows.get(id);
            if (r == null || r.asset.team() != partner) {
                throw new IllegalArgumentException("상대 구단 선수가 아닙니다: " + id);
            }
            in.add(r.asset);
        }

        // 40인·예산 (양 팀)
        String rule = check(s, s.userTeam, out, in, "내 팀");
        if (rule != null) {
            return new Result(false, rule);
        }
        if (check(s, partner, in, out, "상대 구단") != null) {
            // 상대 구단 연봉·예산·40인은 비공개 → 사정만 알림
            return new Result(false, "거절: 상대 구단이 로스터·연봉 사정으로 이 조건을 받을 수 없다고 합니다.");
        }

        TradeRules.TeamCtx ai = context(careerId, partner);
        TradeRules.Verdict v = TradeRules.judge(out, in, ai, rate(careerId, s.season), s.seasonLeft);
        if (!v.accept()) {
            return new Result(false, "거절: " + v.reason());
        }
        String summary = summary(rows, give, get, partner);
        apply(careerId, s, partner, give, get, summary);
        return new Result(true, "트레이드 성사: " + summary);
    }

    // ------------------------------------------------------------------ 내부

    private static final class State {
        Window window;
        long seed;
        int season;
        int evalSeason;
        int phase;
        int version;
        int userTeam;
        int scoutLevel;
        LocalDate curDate;
        double seasonLeft;
        Map<Integer, Integer> budget = new HashMap<>();
        Map<Integer, Integer> payroll = new HashMap<>();
        Map<Integer, Integer> forty = new HashMap<>();

        /** 내 팀은 스카우트 비용 차감 (오프시즌) */
        int budget(int team) {
            int b = budget.getOrDefault(team, 0);
            return team == userTeam && phase == NextDayService.PHASE_OFFSEASON ? b - Scouting.cost(scoutLevel) : b;
        }
    }

    private record Row(TradeRules.Asset asset, String name, String pos) {
    }

    private State state(long careerId) {
        Object[] c = jdbc.query("""
                SELECT k.seed, k.season, k.phase, k.version, k.team_id, k.scout_level, k.cur_date, o.stage
                FROM career k
                LEFT JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                WHERE k.career_id = ? AND k.deleted_at IS NULL
                """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getObject(7, LocalDate.class), rs.getInt(8)} : null, careerId);
        if (c == null) {
            throw new IllegalArgumentException("커리어 없음: " + careerId);
        }
        State s = new State();
        s.seed = (long) c[0];
        s.season = (int) c[1];
        s.phase = (int) c[2];
        s.version = (int) c[3];
        s.userTeam = (int) c[4];
        s.scoutLevel = (int) c[5];
        s.curDate = (LocalDate) c[6];
        int done = (int) c[7];
        boolean offseason = s.phase == NextDayService.PHASE_OFFSEASON;
        LocalDate deadline = LocalDate.of(s.season, 7, 31);
        if (s.phase == NextDayService.PHASE_REGULAR && s.curDate.isBefore(deadline)) {
            s.window = new Window(true, false, "트레이드 마감 " + deadline + " 까지");
        } else if (offseason && done >= OffseasonService.Stage.FREE_AGENCY.no() && done < OffseasonService.STAGES) {
            s.window = new Window(true, true, "오프시즌 트레이드 (개막 전까지)");
        } else if (s.phase == NextDayService.PHASE_REGULAR) {
            s.window = new Window(false, false, "트레이드 마감(7월 31일)이 지났습니다. 오프시즌 FA 시장이 끝난 뒤 다시 열립니다.");
        } else if (offseason) {
            s.window = new Window(false, true, "오프시즌 트레이드는 FA 시장 마감 뒤부터 할 수 있습니다.");
        } else {
            s.window = new Window(false, false, "포스트시즌에는 트레이드할 수 없습니다.");
        }
        s.evalSeason = offseason ? s.season + 1 : s.season;
        MasterData.Snapshot snap = master.get();
        if (offseason) {
            s.seasonLeft = 1.0;
            s.budget.putAll(LeagueBudget.nextBudgets(jdbc, careerId, s.season, s.seed));
        } else {
            LocalDate open = SeasonCalendar.openingDay(snap, s.season);
            LocalDate end = SeasonCalendar.lastDay(snap, s.season);
            double total = Math.max(1, end.toEpochDay() - open.toEpochDay());
            s.seasonLeft = Math.clamp((end.toEpochDay() - s.curDate.toEpochDay()) / total, 0.05, 1.0);
            jdbc.query("SELECT team_id, budget FROM c_team WHERE career_id = ?",
                    rs -> {
                        s.budget.put(rs.getInt(1), rs.getInt(2));
                    }, careerId);
        }
        jdbc.query("""
                SELECT team_id, COALESCE(SUM(salary), 0), COUNT(*) FROM c_player
                WHERE career_id = ? AND team_id IS NOT NULL AND roster_status IN (1, 2, 3) GROUP BY team_id
                """, rs -> {
            s.payroll.put(rs.getInt(1), rs.getInt(2));
            s.forty.put(rs.getInt(1), rs.getInt(3));
        }, careerId);
        return s;
    }

    /** 40인·예산 규칙 (위반이면 안내 문구) */
    private static String check(State s, int team, List<TradeRules.Asset> out, List<TradeRules.Asset> in,
                                String who) {
        int forty = s.forty.getOrDefault(team, 0);
        int payroll = s.payroll.getOrDefault(team, 0);
        int after = forty;
        int pay = payroll;
        for (TradeRules.Asset a : out) {
            if (a.fortyMan()) {
                after--;
                pay -= a.salary();
            }
        }
        for (TradeRules.Asset a : in) {
            if (a.fortyMan()) {
                after++;
                pay += a.salary();
            }
        }
        if (after > RosterMoves.FORTY_MAN && after > forty) {
            return String.format("%s 40인 명단 초과 (%d명 → %d명). 먼저 40인에서 제외해 주세요.", who, forty, after);
        }
        int budget = s.budget(team);
        if (pay > payroll && pay > budget) {
            return String.format("%s 예산 초과 (연봉 합계 $%.1fM → $%.1fM, 예산 $%.1fM)", who, payroll / 1000.0,
                    pay / 1000.0, budget / 1000.0);
        }
        return null;
    }

    /** 고른 선수들 (ID → 능력치·계약) */
    private Map<Integer, Row> rows(long careerId, int evalSeason, List<Integer> give, List<Integer> get) {
        Set<Integer> ids = new HashSet<>(give);
        ids.addAll(get);
        Map<Integer, Row> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String cols = String.join(", ", Arrays.stream(MasterData.RATING_COLUMNS).map(x -> "c." + x).toList());
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        LocalDate mid = LocalDate.of(evalSeason, 7, 1);
        jdbc.query("""
                SELECT c.player_id, c.team_id, c.roster_status, c.contract_type, c.contract_years, c.salary,
                       c.service_years, COALESCE(m.birth_date, p.birth_date), COALESCE(m.primary_pos, p.primary_pos),
                       COALESCE(m.full_name, p.full_name), %s
                FROM c_player c
                LEFT JOIN mst_player m ON m.player_id = c.player_id
                LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id
                WHERE c.career_id = ? AND c.roster_status <> 4 AND c.player_id IN (%s)
                """.formatted(cols, in), rs -> {
            short[] r = new short[MasterData.RATING_COLUMNS.length];
            for (int i = 0; i < r.length; i++) {
                int v = rs.getInt(11 + i);
                r[i] = rs.wasNull() ? -1 : (short) v;
            }
            int pos = rs.getInt(9);
            boolean pitcher = r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || pos == 1);
            LocalDate birth = rs.getObject(8, LocalDate.class);
            int age = birth == null ? 30 : Period.between(birth, mid).getYears();
            int status = rs.getInt(3);
            TradeRules.Asset a = new TradeRules.Asset(rs.getInt(1), rs.getInt(2), pitcher, age,
                    ContractModel.value(r, pos), r[MasterData.POTENTIAL], rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    rs.getInt(7), status >= 1 && status <= 3);
            String posName = pitcher ? "P" : POS[Math.clamp(pos, 0, 10)];
            out.put(a.id(), new Row(a, rs.getString(10), posName));
        }, careerId);
        return out;
    }

    private static final String[] POS = {"", "P", "C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH"};

    /** AI 구단 상황: 승률(오프시즌 중에는 지난 시즌 성적) + 유형별 MLB 가능 40인 */
    private TradeRules.TeamCtx context(long careerId, int team) {
        int[] wl = jdbc.query("SELECT wins, losses FROM c_team WHERE career_id = ? AND team_id = ?",
                rs -> rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : new int[2], careerId, team);
        int[] capable = new int[2];
        String cols = String.join(", ", Arrays.stream(MasterData.RATING_COLUMNS).map(x -> "c." + x).toList());
        jdbc.query("""
                SELECT COALESCE(m.primary_pos, p.primary_pos), %s FROM c_player c
                LEFT JOIN mst_player m ON m.player_id = c.player_id
                LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id
                WHERE c.career_id = ? AND c.team_id = ? AND c.roster_status IN (1, 2, 3)
                """.formatted(cols), rs -> {
            short[] r = new short[MasterData.RATING_COLUMNS.length];
            for (int i = 0; i < r.length; i++) {
                int v = rs.getInt(2 + i);
                r[i] = rs.wasNull() ? -1 : (short) v;
            }
            int pos = rs.getInt(1);
            boolean pitcher = r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || pos == 1);
            if (ContractModel.value(r, pos) >= 45) {
                capable[pitcher ? 1 : 0]++;
            }
        }, careerId, team);
        return new TradeRules.TeamCtx(team, TradeRules.mode(wl[0], wl[1]), capable);
    }

    /** 연봉 단가: 가장 최근 오프시즌 값, 없으면 (첫 시즌) 마스터로 계산 */
    private double rate(long careerId, int season) {
        Double r = jdbc.query("""
                SELECT salary_rate FROM c_offseason WHERE career_id = ? AND season <= ? AND salary_rate IS NOT NULL
                ORDER BY season DESC LIMIT 1
                """, rs -> rs.next() ? rs.getDouble(1) : null, careerId, season);
        return r != null ? r : ContractModel.calibrateRate(master.get().players(), MasterData.START_SEASON);
    }

    private String summary(Map<Integer, Row> rows, List<Integer> give, List<Integer> get, int partner) {
        MasterData.TeamInfo t = master.get().teams().get(partner);
        String team = t == null ? String.valueOf(partner) : t.abbrev();
        return String.format("%s 와 · 보냄 %s · 받음 %s", team, names(rows, give), names(rows, get));
    }

    private static String names(Map<Integer, Row> rows, List<Integer> ids) {
        if (ids.isEmpty()) {
            return "없음";
        }
        List<String> out = new ArrayList<>();
        for (int id : ids) {
            Row r = rows.get(id);
            out.add(r.name() + "(" + r.pos() + ")");
        }
        return String.join(", ", out);
    }

    private void apply(long careerId, State s, int partner, List<Integer> give, List<Integer> get, String summary) {
        LocalDate date = s.phase == NextDayService.PHASE_OFFSEASON ? s.curDate : s.curDate.plusDays(1);
        Object[] record = {careerId, s.season, date, partner, join(give), join(get), summary};
        if (s.phase == NextDayService.PHASE_OFFSEASON) {
            List<Object[]> moves = new ArrayList<>();
            give.forEach(id -> moves.add(new Object[] {partner, careerId, id}));
            get.forEach(id -> moves.add(new Object[] {s.userTeam, careerId, id}));
            tx.executeWithoutResult(st -> {
                int updated = jdbc.update("""
                        UPDATE career SET version = version + 1
                        WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                        """, careerId, s.version);
                if (updated != 1) {
                    throw new IllegalArgumentException("다른 창에서 먼저 진행했습니다. 화면을 새로 고쳐 주세요.");
                }
                jdbc.batchUpdate("UPDATE c_player SET team_id = ? WHERE career_id = ? AND player_id = ?", moves);
                insertTrade(record);
            });
            cache.evict(careerId);
            return;
        }

        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            if (rt.phase != s.phase || !rt.curDate.equals(s.curDate)) {
                throw new IllegalArgumentException("다른 창에서 먼저 진행했습니다. 화면을 새로 고쳐 주세요.");
            }
            long day = rt.curDate.toEpochDay() + 1;
            for (int id : give) {
                rt.transfer(rt.players.get(id), partner);
            }
            for (int id : get) {
                rt.transfer(rt.players.get(id), s.userTeam);
            }
            DayRules.afterTrade(rt, s.userTeam, day);
            DayRules.afterTrade(rt, partner, day);
            List<CareerRuntime.Player> changed = new ArrayList<>();
            for (int team : new int[] {s.userTeam, partner}) {
                for (int level = 0; level < CareerRuntime.LEVELS; level++) {
                    for (CareerRuntime.Player p : rt.members(level, team)) {
                        if (p.dirty) {
                            changed.add(p);
                        }
                    }
                }
            }
            List<Object[]> rows = new ArrayList<>();
            for (CareerRuntime.Player p : changed) {
                rows.add(new Object[] {p.team, p.level, p.status,
                        p.injuredUntil > 0 ? LocalDate.ofEpochDay(p.injuredUntil) : null, careerId, p.id});
            }
            try {
                tx.executeWithoutResult(st -> {
                    jdbc.batchUpdate("""
                            UPDATE c_player SET team_id = ?, level = ?, roster_status = ?, injury_until = ?
                            WHERE career_id = ? AND player_id = ?
                            """, rows);
                    insertTrade(record);
                });
            } catch (RuntimeException e) {
                cache.evict(careerId); // 메모리만 바뀐 상태 → 다음 요청 때 DB 기준으로 다시 만든다
                throw e;
            }
            changed.forEach(p -> p.dirty = false);
        } finally {
            rt.lock.unlock();
        }
    }

    private void insertTrade(Object[] record) {
        jdbc.update("""
                INSERT INTO c_trade (career_id, season, trade_date, partner_team, give_ids, get_ids, summary)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, record);
    }

    private static String join(List<Integer> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }
}
