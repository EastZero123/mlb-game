package com.mlbgame.career;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커리어 생성. 마스터(메모리 캐시) → 능력치 샘플링·계약 산정(메모리) → 한 트랜잭션에서 배치 INSERT.
 * <p>
 * 비용 대부분은 c_player 약 2,650행 INSERT. 계산은 트랜잭션 밖에서 끝내 트랜잭션을 짧게 유지하고,
 * INSERT 는 rewriteBatchedStatements 로 다중 행 INSERT 몇 번으로 묶인다.
 */
@Service
public class CareerCreator {

    public static final int MAX_SLOTS = 3;
    /** 예산 = 개막 연봉 합계 × 여유분, 최소 7,000만 달러 */
    static final double BUDGET_HEADROOM = 1.10;
    static final int MIN_BUDGET = 70_000;

    static final int MLB = 0, AAA = 1, AA = 2;
    static final int ST_MINOR = 0, ST_40MAN = 1, ST_ACTIVE = 2, ST_INJURED = 3;

    private static final String[] PRE_COLUMNS = {"career_id", "player_id", "team_id", "roster_status", "level",
            "contract_type", "contract_years", "salary", "service_years"};
    private static final String[] POST_COLUMNS = {"adaptability", "stability", "professionalism", "state_date",
            "injury_until", "pro_seasons"};
    /** c_player 1행 INSERT. 값 순서: {@link #playerValues} */
    public static final String INSERT_PLAYER;
    /** c_player 컬럼 (INSERT 순서) */
    public static final List<String> PLAYER_COLUMNS;

    /**
     * c_player INSERT 값 배열 (시즌 중 생성 선수 등 커리어 생성 외 경로에서도 같은 형식).
     *
     * @param ratings    {@link MasterData#RATING_COLUMNS} 순서, NULL = -1
     * @param proSeasons 프로 경력 연차 (마이너 6년 규칙용)
     */
    public static Object[] playerValues(long careerId, int playerId, int teamId, int status, int level,
                                        int contractType, int contractYears, int salary, int serviceYears,
                                        short[] ratings, int adaptability, int stability, int professionalism,
                                        java.time.LocalDate stateDate, java.time.LocalDate injuryUntil,
                                        int proSeasons) {
        Object[] r = new Object[PRE_COLUMNS.length + ratings.length + POST_COLUMNS.length];
        int i = 0;
        r[i++] = careerId;
        r[i++] = playerId;
        r[i++] = teamId;
        r[i++] = status;
        r[i++] = level;
        r[i++] = contractType;
        r[i++] = contractYears;
        r[i++] = salary;
        r[i++] = serviceYears;
        for (short v : ratings) {
            r[i++] = v < 0 ? null : (Object) (int) v;
        }
        r[i++] = adaptability;
        r[i++] = stability;
        r[i++] = professionalism;
        r[i++] = stateDate;
        r[i++] = injuryUntil;
        r[i] = Math.clamp(proSeasons, 0, 255);
        return r;
    }

    static {
        List<String> cols = new ArrayList<>(List.of(PRE_COLUMNS));
        cols.addAll(List.of(MasterData.RATING_COLUMNS));
        cols.addAll(List.of(POST_COLUMNS));
        INSERT_PLAYER = "INSERT INTO c_player (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        PLAYER_COLUMNS = List.copyOf(cols);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterData master;

    public CareerCreator(JdbcTemplate jdbc, TransactionTemplate tx, MasterData master) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.master = master;
    }

    /** @param salaryRate 연봉 단가 (가치 1점당 천 달러) */
    public record Result(long careerId, long seed, int players, Map<Integer, Integer> payroll, double salaryRate,
                         long payloadBytes, long buildMicros, long insertMicros) {
    }

    /** @param seed null 이면 무작위 */
    public Result create(int userId, int slot, int teamId, Long seed) {
        if (slot < 1 || slot > MAX_SLOTS) {
            throw new IllegalArgumentException("슬롯은 1~" + MAX_SLOTS);
        }
        MasterData.Snapshot snap = master.get();
        if (!snap.teamIds().contains(teamId)) {
            throw new IllegalArgumentException("알 수 없는 팀: " + teamId);
        }
        long s = seed != null ? seed : ThreadLocalRandom.current().nextLong();
        int season = MasterData.START_SEASON;
        LocalDate curDate = snap.openingDay().minusDays(1);

        long t0 = System.nanoTime();
        double rate = ContractModel.calibrateRate(snap.players(), season);
        List<Object[]> rows = new ArrayList<>(snap.players().size());
        Map<Integer, Integer> payroll = new HashMap<>();
        for (MasterPlayer p : snap.players()) {
            rows.add(playerRow(p, s, season, rate, curDate, snap.openingDay(), payroll));
        }
        List<Object[]> teams = new ArrayList<>(snap.teamIds().size());
        for (int team : snap.teamIds()) {
            int pay = payroll.getOrDefault(team, 0);
            teams.add(new Object[] {null, team, (int) Math.max(MIN_BUDGET, Math.round(pay * BUDGET_HEADROOM)), pay});
        }
        long t1 = System.nanoTime();

        long careerId = tx.execute(st -> {
            Integer taken = jdbc.query("""
                    SELECT career_id FROM career WHERE user_id = ? AND slot = ? AND deleted_at IS NULL FOR UPDATE
                    """, rs -> rs.next() ? rs.getInt(1) : null, userId, slot);
            if (taken != null) {
                throw new IllegalStateException("이미 사용 중인 슬롯: " + slot);
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO career (user_id, slot, team_id, seed, season, cur_date, phase, next_game_no)
                        VALUES (?, ?, ?, ?, ?, ?, 1, 1)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, userId);
                ps.setInt(2, slot);
                ps.setInt(3, teamId);
                ps.setLong(4, s);
                ps.setInt(5, season);
                ps.setObject(6, curDate);
                return ps;
            }, key);
            long id = key.getKey().longValue();
            for (Object[] r : rows) {
                r[0] = id;
            }
            for (Object[] t : teams) {
                t[0] = id;
            }
            jdbc.batchUpdate(INSERT_PLAYER, rows);
            jdbc.batchUpdate("INSERT INTO c_team (career_id, team_id, budget, payroll) VALUES (?, ?, ?, ?)", teams);
            List<Object[]> minors = new ArrayList<>(teams.size() * 2);
            for (int level = AAA; level <= AA; level++) {
                for (int team : snap.teamIds()) {
                    minors.add(new Object[] {id, level, team});
                }
            }
            jdbc.batchUpdate("INSERT INTO c_team_minor (career_id, level, team_id) VALUES (?, ?, ?)", minors);
            return id;
        });
        long t2 = System.nanoTime();
        return new Result(careerId, s, rows.size(), payroll, rate, payloadBytes(rows), (t1 - t0) / 1000,
                (t2 - t1) / 1000);
    }

    /**
     * 개막 배치:
     * <ul>
     *   <li>개막 26인 → MLB 현역</li>
     *   <li>40인 + AAA/AA 로스터 → 해당 레벨 (옵션)</li>
     *   <li>40인 + 미배정 → MLB 기록 기반 선수면 부상자 명단(10~60일), 아니면 AA (하위 리그 유망주)</li>
     *   <li>마이너 계약 → 해당 레벨</li>
     * </ul>
     */
    static Object[] playerRow(MasterPlayer p, long seed, int season, double salaryRate, LocalDate curDate,
                              LocalDate openingDay, Map<Integer, Integer> payroll) {
        SplittableRandom rng = RatingSampler.rng(seed, p.id());
        short[] ratings = RatingSampler.sample(p, season, rng);
        int adaptability = RatingSampler.personality(rng);
        int stability = RatingSampler.personality(rng);
        int professionalism = RatingSampler.personality(rng);
        ContractModel.Contract c = ContractModel.contract(p, season, salaryRate, rng);

        int level;
        int status;
        LocalDate injuryUntil = null;
        if (p.rosterStatus() == 2) {
            level = MLB;
            status = ST_ACTIVE;
        } else if (p.rosterStatus() == 1) {
            if (p.level() == AAA || p.level() == AA) {
                level = p.level();
                status = ST_40MAN;
            } else if (p.source() == 0) {
                level = MLB;
                status = ST_INJURED;
                injuryUntil = openingDay.plusDays(10 + rng.nextInt(51));
            } else {
                level = AA;
                status = ST_40MAN;
            }
        } else {
            level = p.level() == AAA ? AAA : AA;
            status = ST_MINOR;
        }
        if (status != ST_MINOR) {
            payroll.merge(p.teamId(), c.salary(), Integer::sum);
        }

        Object[] r = new Object[PRE_COLUMNS.length + ratings.length + POST_COLUMNS.length];
        int i = 1; // 0 = career_id (INSERT 직전에 채움)
        r[i++] = p.id();
        r[i++] = p.teamId();
        r[i++] = status;
        r[i++] = level;
        r[i++] = c.type();
        r[i++] = c.years();
        r[i++] = c.salary();
        r[i++] = Math.min(p.serviceYears(), 255);
        for (short v : ratings) {
            r[i++] = v < 0 ? null : (Object) (int) v;
        }
        r[i++] = adaptability;
        r[i++] = stability;
        r[i++] = professionalism;
        r[i++] = curDate;
        r[i++] = injuryUntil;
        r[i] = ContractModel.proSeasonsEstimate(p.age(season));
        return r;
    }

    /** c_player INSERT 로 보내는 SQL 텍스트 크기 근사 (값 + 구분자). 원격 DB 에선 이 전송량이 INSERT 시간을 좌우 */
    static long payloadBytes(List<Object[]> rows) {
        long bytes = 0;
        for (Object[] r : rows) {
            bytes += 3; // "(", ")", ","
            for (Object v : r) {
                bytes += (v == null ? 4 : String.valueOf(v).length() + (v instanceof LocalDate ? 2 : 0)) + 1;
            }
        }
        return bytes;
    }

    /** 커리어와 모든 커리어 데이터 영구 삭제 (벤치마크 정리·삭제 후 보관 기간 만료 처리용) */
    public void purge(long careerId) {
        tx.executeWithoutResult(st -> {
            for (String table : List.of("c_bat_season", "c_pit_season", "c_game", "c_series", "c_player", "c_person", "c_team", "c_team_minor",
                    "c_tactic", "c_offseason", "c_draft_pick", "c_contract_decision", "c_fa_player", "c_fa_offer", "c_trade")) {
                jdbc.update("DELETE FROM " + table + " WHERE career_id = ?", careerId);
            }
            jdbc.update("DELETE FROM career WHERE career_id = ?", careerId);
        });
    }
}
