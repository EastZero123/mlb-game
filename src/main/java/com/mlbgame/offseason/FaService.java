package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;
import com.mlbgame.season.NextDayService;

/**
 * FA 라운드 입찰 시장 (오프시즌 5단계 "개장"과 6단계 "마감" 사이). 규칙은 {@link FaMarket}.
 * <pre>
 * 라운드 진행 1회 = 시장 선수(c_fa_player, 수백 명) + 40인(약 1,200명) 능력치 + 구단 연봉 합계를 읽어
 *                   메모리에서 처리 → 바뀐 시장 선수·계약 선수 행·내 제안 반응만 저장 (전체 선수 쓰기 없음)
 * 내 제안은 라운드가 지나도 남는다 (바꾸거나 철회). 제안 합계 + 확정 연봉 ≤ 다음 시즌 예산 - 스카우트 비용.
 * </pre>
 * 동시에 두 번 누르면 career.version 확인으로 한쪽만 반영.
 */
@Service
public class FaService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterData master;
    private final com.mlbgame.ops.Ops ops;

    public FaService(JdbcTemplate jdbc, TransactionTemplate tx, MasterData master, com.mlbgame.ops.Ops ops) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.master = master;
        this.ops = ops;
    }

    public static final int ROUNDS = FaMarket.ROUNDS;
    public static final int OPEN = FaMarket.OPEN, SIGNED = FaMarket.SIGNED;

    /**
     * 시장 선수 1명 (화면은 ratings 를 스카우트 범위로만 보여 준다).
     *
     * @param ask       현재 요구 연봉, askYears 요구 연수
     * @param offers    지난 라운드 제안 구단 수
     * @param mySalary  내 제안 (없으면 0)
     */
    public record Item(int playerId, String name, int pos, boolean pitcher, int age, short[] ratings, int ask,
                       int askYears, int offers, int status, int team, int salary, int years, int signedRound,
                       int mySalary, int myYears, String reaction, int maxYears) {
    }

    /**
     * @param round   진행할 라운드 (1~6, 7 = 라운드 끝)
     * @param offered 내 제안 합계 (아직 계약 안 된 선수)
     */
    public record Board(int season, int round, int userTeam, long seed, int budget, int payroll, int offered,
                        List<Item> items) {

        public int room() {
            return budget - payroll - offered;
        }
    }

    // ------------------------------------------------------------------ 조회

    /** 시장 기간(5단계 끝, 6단계 전)이 아니면 null */
    public Board board(long careerId) {
        State s = load(careerId, false);
        if (s == null) {
            return null;
        }
        Map<Integer, int[]> mine = myOffers(careerId, s.season);
        List<Item> items = new ArrayList<>(s.fas.size());
        int offered = 0;
        for (FaMarket.Fa f : s.fas) {
            int[] o = mine.get(f.id);
            if (o != null && f.status == OPEN) {
                offered += o[0];
            }
            Object[] person = s.person.get(f.id);
            items.add(new Item(f.id, (String) person[0], (int) person[1], f.pitcher, f.age, (short[]) person[2],
                    f.ask, f.prefYears, f.offers, f.status, f.team, f.salary, f.years, f.signedRound,
                    o == null ? 0 : o[0], o == null ? 0 : o[1], o == null ? "" : FaMarket.reactionText(o[2]),
                    FaMarket.maxYears(f.age)));
        }
        int budget = s.budget.getOrDefault(s.userTeam, 0) - Scouting.cost(s.scoutLevel);
        int payroll = s.payroll.getOrDefault(s.userTeam, 0);
        return new Board(s.season, s.round, s.userTeam, s.seed, budget, payroll, offered, items);
    }

    /** 진행할 라운드가 남았나 (시장 기간이 아니면 false) */
    public boolean roundsLeft(long careerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM career k
                JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                WHERE k.career_id = ? AND k.phase = ? AND o.stage = ? AND o.fa_round BETWEEN 1 AND ?
                """, Integer.class, careerId, NextDayService.PHASE_OFFSEASON,
                OffseasonService.Stage.FREE_AGENCY.ordinal(), ROUNDS);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ 내 제안

    /**
     * @param salary 연봉 (천 달러)
     * @throws IllegalArgumentException 시장 밖 / 계약된 선수 / 연수·금액 범위 / 예산 초과
     */
    public void offer(long careerId, int playerId, int salary, int years) {
        Board b = board(careerId);
        if (b == null || b.round() > ROUNDS) {
            throw new IllegalArgumentException("FA 라운드가 진행 중일 때만 제안할 수 있습니다.");
        }
        Item it = b.items().stream().filter(i -> i.playerId() == playerId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("시장에 없는 선수입니다."));
        if (it.status() != OPEN) {
            throw new IllegalArgumentException(it.name() + ": 이미 계약이 끝난 선수입니다.");
        }
        if (years < 1 || years > it.maxYears()) {
            throw new IllegalArgumentException(it.name() + ": 계약 연수는 1~" + it.maxYears() + "년입니다 (45세 시즌까지).");
        }
        if (salary < ContractModel.MIN_SALARY || salary > ContractModel.MAX_SALARY) {
            throw new IllegalArgumentException(String.format("연봉은 $%.2fM ~ $%.0fM 사이입니다.",
                    ContractModel.MIN_SALARY / 1000.0, ContractModel.MAX_SALARY / 1000.0));
        }
        int total = b.payroll() + b.offered() - it.mySalary() + salary;
        if (total > b.budget()) {
            throw new IllegalArgumentException(String.format(
                    "예산 초과: 확정 연봉 + 제안 합계 $%.1fM > 다음 시즌 예산 $%.1fM", total / 1000.0, b.budget() / 1000.0));
        }
        jdbc.update("""
                INSERT INTO c_fa_offer (career_id, season, player_id, salary, years) VALUES (?, ?, ?, ?, ?) AS n
                ON DUPLICATE KEY UPDATE salary = n.salary, years = n.years, reaction = 0
                """, careerId, b.season(), playerId, salary, years);
    }

    public void withdraw(long careerId, int playerId) {
        Board b = board(careerId);
        if (b == null) {
            throw new IllegalArgumentException("FA 시장 기간이 아닙니다.");
        }
        jdbc.update("DELETE FROM c_fa_offer WHERE career_id = ? AND season = ? AND player_id = ?", careerId,
                b.season(), playerId);
    }

    // ------------------------------------------------------------------ 라운드

    /**
     * 라운드 1회 진행.
     *
     * @return 결과 한 줄 ("FA 라운드 2: 계약 14명 · ...")
     */
    public String advance(long careerId) {
        long t0 = System.nanoTime();
        State s = load(careerId, true);
        if (s == null || s.round > ROUNDS) {
            throw new IllegalArgumentException("진행할 FA 라운드가 없습니다.");
        }
        int round = s.round;
        Map<Integer, int[]> offers = myOffers(careerId, s.season);
        Map<Integer, FaMarket.Offer> mine = new HashMap<>();
        offers.forEach((id, o) -> mine.put(id, new FaMarket.Offer(s.userTeam, o[0], o[1])));
        Map<Integer, FaMarket.Team> teams = teams(careerId, s);
        List<FaMarket.Fa> open = new ArrayList<>(s.fas.stream().filter(f -> f.status == OPEN).toList());
        List<FaMarket.Reaction> reactions = FaMarket.round(round, open, teams, mine, s.userTeam, s.seed,
                s.season + 1);

        List<Object[]> faRows = new ArrayList<>(open.size());
        List<Object[]> signed = new ArrayList<>();
        List<FaMarket.Fa> signedNow = new ArrayList<>();
        for (FaMarket.Fa f : open) {
            faRows.add(new Object[] {f.ask, f.status, f.status == SIGNED ? f.team : null,
                    f.status == SIGNED ? f.salary : null, f.status == SIGNED ? f.years : null,
                    f.status == SIGNED ? round : null, f.bestTeam == 0 ? null : f.bestTeam,
                    f.bestTeam == 0 ? null : f.bestSalary, Math.min(255, f.offers), careerId, s.season, f.id});
            if (f.status == SIGNED) {
                signed.add(new Object[] {f.team, ContractModel.GUARANTEED, f.years, f.salary, careerId, f.id});
                signedNow.add(f);
            }
        }
        List<Object[]> reactionRows = new ArrayList<>();
        for (FaMarket.Reaction r : reactions) {
            reactionRows.add(new Object[] {r.reaction(), careerId, s.season, r.playerId()});
        }
        String text = roundText(round, signedNow, s);
        tx.executeWithoutResult(st -> {
            int updated = jdbc.update("""
                    UPDATE career SET version = version + 1
                    WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                    """, careerId, s.version);
            if (updated != 1) {
                throw new OffseasonService.ConcurrentStageException(careerId);
            }
            jdbc.batchUpdate("""
                    UPDATE c_fa_player SET ask = ?, status = ?, team_id = ?, salary = ?, years = ?, signed_round = ?,
                        best_team = ?, best_salary = ?, offers = ?
                    WHERE career_id = ? AND season = ? AND player_id = ?
                    """, faRows);
            if (!signed.isEmpty()) {
                jdbc.batchUpdate("""
                        UPDATE c_player SET team_id = ?, level = 0, roster_status = 1, contract_type = ?,
                            contract_years = ?, salary = ?
                        WHERE career_id = ? AND player_id = ?
                        """, signed);
            }
            if (!reactionRows.isEmpty()) {
                jdbc.batchUpdate("""
                        UPDATE c_fa_offer SET reaction = ? WHERE career_id = ? AND season = ? AND player_id = ?
                        """, reactionRows);
            }
            jdbc.update("""
                    UPDATE c_offseason SET fa_round = ?, report = CONCAT(COALESCE(report, ''), ?)
                    WHERE career_id = ? AND season = ?
                    """, round + 1, text, careerId, s.season);
        });
        ops.record("fa-round", careerId, System.nanoTime() - t0, "round=" + round + " market=" + open.size()
                + " signed=" + signedNow.size());
        return text.strip();
    }

    /** 남은 라운드를 AI 로 끝까지 (6단계 처리·일괄 진행 때. 내 제안은 그대로 유효) */
    void finishRounds(long careerId) {
        while (roundsLeft(careerId)) {
            advance(careerId);
        }
    }

    /** 시장 결과 요약 (6단계 결과 줄) */
    String summary(long careerId, int season, int userTeam) {
        MasterData.Snapshot snap = master.get();
        int[] counts = new int[3];
        List<String> top = new ArrayList<>();
        List<String> mine = new ArrayList<>();
        jdbc.query("""
                SELECT f.status, f.team_id, f.salary, f.years, f.signed_round, COALESCE(m.full_name, p.full_name)
                FROM c_fa_player f
                LEFT JOIN mst_player m ON m.player_id = f.player_id
                LEFT JOIN c_person p ON p.career_id = f.career_id AND p.player_id = f.player_id
                WHERE f.career_id = ? AND f.season = ?
                ORDER BY f.salary DESC
                """, rs -> {
            int status = rs.getInt(1);
            counts[Math.clamp(status, 0, 2)]++;
            if (status == SIGNED) {
                int team = rs.getInt(2);
                String line = String.format("%s → %s %d년 $%.1fM (%d라운드)", rs.getString(6), abbrev(snap, team),
                        rs.getInt(4), rs.getInt(3) / 1000.0, rs.getInt(5));
                if (top.size() < 5) {
                    top.add(line);
                }
                if (team == userTeam) {
                    mine.add(line);
                }
            }
        }, careerId, season);
        StringBuilder b = new StringBuilder(String.format("FA 시장 결과: MLB 계약 %d명, 미계약 %d명%n", counts[SIGNED],
                counts[FaMarket.UNSIGNED] + counts[OPEN]));
        if (!top.isEmpty()) {
            b.append("주요 계약: ").append(String.join(" / ", top)).append(System.lineSeparator());
        }
        if (!mine.isEmpty()) {
            b.append("내 팀 영입: ").append(String.join(" / ", mine)).append(System.lineSeparator());
        }
        return b.toString();
    }

    /** 5단계 개장: 가치 46+ FA 를 시장에 등록할 행 {career_id, season, player_id, market, ask, pref_years, reserve, holdout} */
    static List<Object[]> openRows(List<OffPlayer> all, double rate, long careerId, long seed, int season) {
        List<Object[]> rows = new ArrayList<>();
        for (OffPlayer p : all) {
            if (p.freeAgent() && p.value() >= FaMarket.MARKET_MIN_VALUE) {
                FaMarket.Fa f = FaMarket.open(p, rate, seed, season + 1);
                rows.add(new Object[] {careerId, season, f.id, f.market, f.ask, f.prefYears, f.reserve, f.holdout});
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ 내부

    private static final class State {
        long seed;
        int season;
        int version;
        int userTeam;
        int scoutLevel;
        int round;
        List<FaMarket.Fa> fas = new ArrayList<>();
        /** 선수 ID → {이름, 포지션, 능력치} */
        Map<Integer, Object[]> person = new HashMap<>();
        Map<Integer, Integer> budget;
        Map<Integer, Integer> payroll = new HashMap<>();
    }

    /** @param forRound true 면 라운드 진행 가능할 때만 (1~6) */
    private State load(long careerId, boolean forRound) {
        Object[] c = jdbc.query("""
                SELECT k.seed, k.season, k.version, k.team_id, o.stage, o.fa_round, k.scout_level FROM career k
                JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                WHERE k.career_id = ? AND k.phase = ? AND k.deleted_at IS NULL
                """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7)} : null, careerId, NextDayService.PHASE_OFFSEASON);
        if (c == null || (int) c[4] != OffseasonService.Stage.FREE_AGENCY.ordinal() || (int) c[5] < 1
                || forRound && (int) c[5] > ROUNDS) {
            return null;
        }
        State s = new State();
        s.seed = (long) c[0];
        s.season = (int) c[1];
        s.version = (int) c[2];
        s.userTeam = (int) c[3];
        s.round = (int) c[5];
        s.scoutLevel = (int) c[6];
        int next = s.season + 1;
        String cols = String.join(", ", Arrays.stream(MasterData.RATING_COLUMNS).map(x -> "c." + x).toList());
        jdbc.query("""
                SELECT f.player_id, f.market, f.ask, f.pref_years, f.reserve, f.holdout, f.status, f.team_id, f.salary,
                       f.years, f.signed_round, f.best_team, f.best_salary, f.offers,
                       COALESCE(m.birth_date, p.birth_date), COALESCE(m.primary_pos, p.primary_pos),
                       COALESCE(m.full_name, p.full_name), %s
                FROM c_fa_player f
                JOIN c_player c ON c.career_id = f.career_id AND c.player_id = f.player_id
                LEFT JOIN mst_player m ON m.player_id = f.player_id
                LEFT JOIN c_person p ON p.career_id = f.career_id AND p.player_id = f.player_id
                WHERE f.career_id = ? AND f.season = ?
                """.formatted(cols), rs -> {
            short[] r = ratings(rs, 18);
            OffPlayer op = new OffPlayer(rs.getInt(1), rs.getObject(15, LocalDate.class), rs.getInt(16), r);
            FaMarket.Fa f = new FaMarket.Fa();
            f.id = op.id;
            f.value = op.value();
            f.age = op.age(next);
            f.pitcher = op.isPitcher();
            f.market = rs.getInt(2);
            f.ask = rs.getInt(3);
            f.prefYears = rs.getInt(4);
            f.reserve = rs.getInt(5);
            f.holdout = rs.getInt(6);
            f.status = rs.getInt(7);
            f.team = rs.getInt(8);
            f.salary = rs.getInt(9);
            f.years = rs.getInt(10);
            f.signedRound = rs.getInt(11);
            f.bestTeam = rs.getInt(12);
            f.bestSalary = rs.getInt(13);
            f.offers = rs.getInt(14);
            s.fas.add(f);
            s.person.put(f.id, new Object[] {rs.getString(17), op.pos, r});
        }, careerId, s.season);
        s.budget = LeagueBudget.load(jdbc, careerId, s.season, s.seed).next();
        jdbc.query("""
                SELECT team_id, SUM(salary) FROM c_player
                WHERE career_id = ? AND team_id IS NOT NULL AND roster_status IN (1, 2, 3) GROUP BY team_id
                """, rs -> {
            s.payroll.put(rs.getInt(1), rs.getInt(2));
        }, careerId);
        return s;
    }

    /** 구단 요약: 예산 여유 + 40인 유형별 MLB 가능 인원 (라운드 진행 때만 읽음) */
    private Map<Integer, FaMarket.Team> teams(long careerId, State s) {
        Map<Integer, FaMarket.Team> teams = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : s.budget.entrySet()) {
            FaMarket.Team t = new FaMarket.Team();
            t.id = e.getKey();
            t.room = e.getValue() - s.payroll.getOrDefault(e.getKey(), 0)
                    - (t.id == s.userTeam ? Scouting.cost(s.scoutLevel) : 0);
            teams.put(t.id, t);
        }
        String cols = String.join(", ", Arrays.stream(MasterData.RATING_COLUMNS).map(x -> "c." + x).toList());
        jdbc.query("""
                SELECT c.player_id, c.team_id, COALESCE(m.primary_pos, p.primary_pos), %s
                FROM c_player c
                LEFT JOIN mst_player m ON m.player_id = c.player_id
                LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id
                WHERE c.career_id = ? AND c.team_id IS NOT NULL AND c.roster_status IN (1, 2, 3)
                """.formatted(cols), rs -> {
            FaMarket.Team t = teams.get(rs.getInt(2));
            if (t != null) {
                OffPlayer op = new OffPlayer(rs.getInt(1), null, rs.getInt(3), ratings(rs, 4));
                t.add(op.isPitcher(), op.value());
            }
        }, careerId);
        return teams;
    }

    /** 선수 ID → {연봉, 연수, 반응} */
    private Map<Integer, int[]> myOffers(long careerId, int season) {
        Map<Integer, int[]> out = new HashMap<>();
        jdbc.query("SELECT player_id, salary, years, reaction FROM c_fa_offer WHERE career_id = ? AND season = ?",
                rs -> {
                    out.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
                }, careerId, season);
        return out;
    }

    private String roundText(int round, List<FaMarket.Fa> signed, State s) {
        MasterData.Snapshot snap = master.get();
        List<FaMarket.Fa> top = new ArrayList<>(signed);
        top.sort((a, b) -> Integer.compare(b.salary, a.salary));
        StringBuilder b = new StringBuilder(String.format("FA %d라운드: 계약 %d명", round, signed.size()));
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            FaMarket.Fa f = top.get(i);
            b.append(i == 0 ? " · " : " / ").append(String.format("%s → %s %d년 $%.1fM",
                    s.person.get(f.id)[0], abbrev(snap, f.team), f.years, f.salary / 1000.0));
        }
        for (FaMarket.Fa f : signed) {
            if (f.team == s.userTeam) {
                b.append(String.format(" · 내 팀 영입 %s %d년 $%.1fM", s.person.get(f.id)[0], f.years,
                        f.salary / 1000.0));
            }
        }
        return b.append(System.lineSeparator()).toString();
    }

    private static String abbrev(MasterData.Snapshot snap, int team) {
        MasterData.TeamInfo t = snap.teams().get(team);
        return t == null ? String.valueOf(team) : t.abbrev();
    }

    private static short[] ratings(java.sql.ResultSet rs, int first) throws java.sql.SQLException {
        short[] r = new short[MasterData.RATING_COLUMNS.length];
        for (int i = 0; i < r.length; i++) {
            int v = rs.getInt(first + i);
            r[i] = rs.wasNull() ? -1 : (short) v;
        }
        return r;
    }
}
