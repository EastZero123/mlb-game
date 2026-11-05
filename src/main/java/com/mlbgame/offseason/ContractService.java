package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.career.MasterData;
import com.mlbgame.season.NextDayService;

/**
 * 내 팀 계약 결정 화면 (오프시즌 4단계 연봉 재산정 뒤, 5단계 포스팅·FA 전).
 * <pre>
 * FA 자격 (MLB 계약 만료·서비스 6년)  재계약 = 시장가치 연봉 × 정한 연수 (1~15년), 포기 = FA 시장으로
 * 연봉조정 (서비스 3~5년)            텐더 = 4단계에서 정한 조정 연봉으로 보유, 논텐더 = 방출
 * </pre>
 * 결정은 c_contract_decision 에 저장만 하고, 5단계 시작 때 {@link OffseasonRules#applyDecisions} 가 반영.
 * 결정하지 않은 선수는 AI 기본값 (FA 자격 포기, 연봉조정은 가치 45 이상 텐더).
 * 재계약은 다음 시즌 예산({@link LeagueBudget}) 안에서만 (확정 연봉 + 텐더 연봉 + 재계약 연봉 ≤ 예산).
 */
@Service
public class ContractService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ContractService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public static final int FA = OffPlayer.PENDING_FA, ARB = OffPlayer.PENDING_ARB;
    public static final int KEEP = OffPlayer.DECIDE_KEEP, RELEASE = OffPlayer.DECIDE_RELEASE;

    /**
     * 결정 대상 1명.
     *
     * @param kind       {@link #FA} / {@link #ARB}
     * @param lastSalary 지난 시즌 연봉 (천 달러)
     * @param ask        FA 자격 = 재계약 요구 연봉, 연봉조정 = 조정 연봉 (천 달러)
     * @param decision   저장된 결정 (0 = 아직 안 함)
     * @param fallback   결정하지 않으면 적용될 값 (AI 기본값)
     */
    public record Item(int playerId, String name, int kind, int pos, boolean pitcher, int age, int service,
                       int lastSalary, int ask, int maxYears, int decision, int years, int fallback) {

        /** 화면 기본 선택 (저장된 결정 → 없으면 AI 기본값) */
        public int shown() {
            return decision != 0 ? decision : fallback;
        }

        /** 화면 기본 연수 (저장된 연수 → 없으면 최대 연수와 2년 중 작은 값) */
        public int shownYears() {
            return years > 0 ? Math.min(years, maxYears) : Math.min(2, maxYears);
        }
    }

    /**
     * @param budget    다음 시즌 예산 (천 달러)
     * @param committed 결정 대상을 뺀 다음 시즌 40인 연봉 합계
     * @param saved     모든 대상의 결정이 저장됨
     */
    public record Board(int season, int nextSeason, int budget, int committed, List<Item> items, boolean saved) {
    }

    /** 결정 기간(4단계 끝, 5단계 전)이 아니면 null. 대상이 없으면 items 가 빈 목록 */
    public Board board(long careerId) {
        Object[] c = state(careerId);
        if (c == null) {
            return null;
        }
        int season = (int) c[0];
        int userTeam = (int) c[1];
        double rate = (double) c[2];
        int next = season + 1;
        // 다음 시즌 예산: 오프시즌 단계 처리와 같은 계산 (리그 시장 규모 × 점유율)
        int budget = LeagueBudget.load(jdbc, careerId, season, (long) c[3]).next().getOrDefault(userTeam, 0)
                - Scouting.cost((int) c[4]); // 스카우트 비용은 내 팀 예산 확인에서만 뺀다
        Integer committed = jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.salary), 0) FROM c_player c
                LEFT JOIN c_contract_decision d ON d.career_id = c.career_id AND d.season = ? AND d.player_id = c.player_id
                WHERE c.career_id = ? AND c.team_id = ? AND c.roster_status IN (1, 2, 3) AND d.player_id IS NULL
                """, Integer.class, season, careerId, userTeam);

        String cols = String.join(", ", java.util.Arrays.stream(MasterData.RATING_COLUMNS).map(s -> "c." + s).toList());
        List<Item> items = new ArrayList<>();
        boolean[] saved = {true};
        jdbc.query("""
                SELECT d.player_id, d.kind, d.decision, d.years, c.salary, c.service_years,
                       COALESCE(m.birth_date, p.birth_date), COALESCE(m.primary_pos, p.primary_pos),
                       COALESCE(m.full_name, p.full_name), %s
                FROM c_contract_decision d
                JOIN c_player c ON c.career_id = d.career_id AND c.player_id = d.player_id
                LEFT JOIN mst_player m ON m.player_id = d.player_id
                LEFT JOIN c_person p ON p.career_id = d.career_id AND p.player_id = d.player_id
                WHERE d.career_id = ? AND d.season = ? AND c.team_id = ? AND c.roster_status <> 4
                ORDER BY d.kind, c.salary DESC, d.player_id
                """.formatted(cols), rs -> {
            short[] r = new short[MasterData.RATING_COLUMNS.length];
            for (int i = 0; i < r.length; i++) {
                int v = rs.getInt(10 + i);
                r[i] = rs.wasNull() ? -1 : (short) v;
            }
            LocalDate birth = rs.getObject(7, LocalDate.class);
            OffPlayer p = new OffPlayer(rs.getInt(1), birth, rs.getInt(8), r);
            p.pending = rs.getInt(2);
            p.salary = rs.getInt(5);
            p.service = rs.getInt(6);
            int decision = rs.getInt(3);
            if (decision == 0) {
                saved[0] = false;
            }
            int ask = p.pending == FA ? OffseasonRules.resignAsk(p, rate, next) : p.salary;
            items.add(new Item(p.id, rs.getString(9), p.pending, p.pos, p.isPitcher(), p.age(next), p.service,
                    p.salary, ask, OffseasonRules.maxYears(p, next), decision, rs.getInt(4),
                    OffseasonRules.defaultDecision(p)));
        }, careerId, season, userTeam);
        return new Board(season, next, budget, committed == null ? 0 : committed, items, saved[0]);
    }

    /** 결정하지 않은 대상이 남았나 (결정 기간이 아니면 false) */
    public boolean undecided(long careerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM career k
                JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                JOIN c_contract_decision d ON d.career_id = k.career_id AND d.season = k.season
                JOIN c_player c ON c.career_id = d.career_id AND c.player_id = d.player_id
                WHERE k.career_id = ? AND k.phase = ? AND o.stage = ? AND d.decision IS NULL
                  AND c.team_id = k.team_id AND c.roster_status <> 4
                """, Integer.class, careerId, NextDayService.PHASE_OFFSEASON, OffseasonService.Stage.MARKET.ordinal());
        return n != null && n > 0;
    }

    /**
     * 결정 저장 (대상 전원). decisions / years 는 playerId → 값.
     *
     * @throws IllegalArgumentException 빠진 결정, 연수 범위 밖, 예산 초과
     */
    public void save(long careerId, Map<Integer, Integer> decisions, Map<Integer, Integer> years) {
        int version = version(careerId); // 화면 계산보다 먼저 읽어, 그 사이 다른 진행이 있으면 저장 실패
        Board b = board(careerId);
        if (b == null) {
            throw new IllegalArgumentException("계약 결정 기간이 아닙니다 (4단계 연봉 재산정 뒤, 5단계 전).");
        }
        int total = b.committed();
        List<Object[]> rows = new ArrayList<>();
        for (Item it : b.items()) {
            Integer d = decisions.get(it.playerId());
            if (d == null || (d != KEEP && d != RELEASE)) {
                throw new IllegalArgumentException(it.name() + ": 결정을 선택해 주세요.");
            }
            Integer y = null;
            if (it.kind() == FA && d == KEEP) {
                y = years.get(it.playerId());
                if (y == null || y < 1 || y > it.maxYears()) {
                    throw new IllegalArgumentException(it.name() + ": 재계약 연수는 1~" + it.maxYears() + "년입니다.");
                }
            }
            if (d == KEEP) {
                total += it.ask();
            }
            rows.add(new Object[] {d, y, careerId, b.season(), it.playerId()});
        }
        boolean resigning = b.items().stream()
                .anyMatch(it -> it.kind() == FA && Integer.valueOf(KEEP).equals(decisions.get(it.playerId())));
        if (resigning && total > b.budget()) {
            throw new IllegalArgumentException(String.format("예산 초과: 결정 후 연봉 합계 %.1fM > 다음 시즌 예산 %.1fM",
                    total / 1000.0, b.budget() / 1000.0));
        }
        tx.executeWithoutResult(st -> {
            int updated = jdbc.update("""
                    UPDATE career SET version = version + 1
                    WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                    """, careerId, version);
            if (updated != 1) {
                throw new OffseasonService.ConcurrentStageException(careerId);
            }
            jdbc.batchUpdate("""
                    UPDATE c_contract_decision SET decision = ?, years = ?
                    WHERE career_id = ? AND season = ? AND player_id = ?
                    """, rows);
        });
    }

    /** {season, team_id, salary_rate, seed, scout_level}, 결정 기간이 아니면 null */
    private Object[] state(long careerId) {
        return jdbc.query("""
                SELECT k.season, k.team_id, o.salary_rate, o.stage, k.seed, k.scout_level FROM career k
                JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                WHERE k.career_id = ? AND k.phase = ? AND k.deleted_at IS NULL
                """, rs -> rs.next() && rs.getInt(4) == OffseasonService.Stage.MARKET.ordinal()
                && rs.getObject(3) != null
                ? new Object[] {rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getLong(5), rs.getInt(6)} : null,
                careerId, NextDayService.PHASE_OFFSEASON);
    }

    private int version(long careerId) {
        Integer v = jdbc.queryForObject("SELECT version FROM career WHERE career_id = ?", Integer.class, careerId);
        return v == null ? 0 : v;
    }
}
