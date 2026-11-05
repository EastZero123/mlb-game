package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.career.CareerCreator;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.MasterPlayer;
import com.mlbgame.career.Names;
import com.mlbgame.career.SeasonCalendar;
import com.mlbgame.season.CareerRuntimeCache;
import com.mlbgame.season.NextDayService;

/**
 * 오프시즌 (단계 3 → 다음 시즌 단계 1). 8개 단계로 나눠 한 번에 한 단계씩 진행한다.
 * <pre>
 * 1 시즌 마감 (서비스 연차·계약 만료·논텐더) + 드래프트 순서 확정 (1라운드 추첨, {@link DraftLottery}) + 다음 시즌 예산
 * 2 은퇴   3 성장·노화   4 연봉 재산정
 * 5 FA 시장 개장 (내 팀 계약 결정 반영·포스팅·시장 등록) → FA 라운드 1~6 ({@link FaService}, 단계 사이)
 * 6 FA 시장 마감 (남은 라운드 AI 진행·마이너 계약)   7 드래프트
 * 8 마이너 인원 정리 → 개막 로스터 → 리그 기준 정규화 → 순위 초기화·오래된 박스스코어 삭제·career 다음 시즌으로
 * </pre>
 * 단계마다: 커리어 전 선수를 읽어 메모리에서 그 단계만 처리 → 한 트랜잭션으로 c_player 전체 upsert + 새 선수 INSERT +
 * c_offseason(끝낸 단계·연봉 단가·결과 요약) 갱신. 중간에 멈추거나 서버가 내려가도 다음 단계부터 이어서 진행.
 * 동시에 두 번 누르면 career.version 확인으로 한쪽만 반영.
 * <p>
 * 유저 결정:
 * 내 팀 재계약·텐더는 1단계에서 대상 등록 → 4단계 뒤 {@link ContractService} 화면 → 5단계 시작 때 반영.
 * 6단계 드래프트는 {@link DraftService} 화면에서 지명 (기록된 지명은 그대로 쓰고 빈 지명권만 AI).
 * FA 영입 화면은 예정, 그 전까지는 AI 가 처리.
 * 박스스코어는 직전 시즌까지만 보관 (저사양 서버 저장 공간), 시즌 기록·시리즈·드래프트·오프시즌 요약은 영구 보관.
 */
@Service
public class OffseasonService {

    /** 이번 시즌 + 직전 시즌 박스스코어만 보관 */
    static final int KEEP_BOX_SEASONS = 2;

    public enum Stage {
        CLOSE("시즌 마감·드래프트 추첨"),
        RETIRE("은퇴"),
        DEVELOP("성장·노화"),
        SALARY("연봉 재산정"),
        MARKET("FA 시장 개장"),
        FREE_AGENCY("FA 시장 마감"),
        DRAFT("드래프트"),
        OPENING("개막 로스터·새 시즌");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 1~8 */
        public int no() {
            return ordinal() + 1;
        }

        /** done 단계까지 끝났을 때 다음 단계 (다 끝났으면 null) */
        public static Stage after(int done) {
            return done >= 0 && done < values().length ? values()[done] : null;
        }
    }

    public static final int STAGES = Stage.values().length;

    public static class ConcurrentStageException extends RuntimeException {
        ConcurrentStageException(long careerId) {
            super("다른 요청이 먼저 진행함: 커리어 " + careerId);
        }
    }

    private static final String OFFSEASON_UPSERT = """
            INSERT INTO c_offseason (career_id, season, stage, salary_rate, report) VALUES (?, ?, ?, ?, ?) AS n
            ON DUPLICATE KEY UPDATE stage = n.stage,
                salary_rate = COALESCE(n.salary_rate, c_offseason.salary_rate),
                report = CONCAT(COALESCE(c_offseason.report, ''), n.report)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterData master;
    private final AgingCurves aging;
    private final CareerRuntimeCache cache;
    private final FaService market;
    private final com.mlbgame.ops.Ops ops;

    public OffseasonService(JdbcTemplate jdbc, TransactionTemplate tx, MasterData master, AgingCurves aging,
                            CareerRuntimeCache cache, FaService market, com.mlbgame.ops.Ops ops) {
        this.ops = ops;
        this.jdbc = jdbc;
        this.tx = tx;
        this.master = master;
        this.aging = aging;
        this.cache = cache;
        this.market = market;
    }

    /** 남은 단계 전체 결과 */
    public record Result(int season, int nextSeason, LocalDate nextOpening, int players, String report, long millis) {
    }

    /**
     * 한 단계 결과.
     *
     * @param finished 마지막 단계까지 끝나 다음 시즌 정규시즌이 됨
     */
    public record StageResult(Stage stage, int season, boolean finished, int nextSeason, LocalDate nextOpening,
                              int players, String report, long millis) {
    }

    /** 남은 단계를 한 번에 (벤치마크·"끝까지 진행") */
    public Result run(long careerId) {
        long t0 = System.nanoTime();
        StringBuilder all = new StringBuilder();
        StageResult r;
        do {
            r = runStage(careerId);
            all.append(r.report());
        } while (!r.finished());
        return new Result(r.season(), r.nextSeason(), r.nextOpening(), r.players(), all.toString(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** 다음 단계 1개 */
    public StageResult runStage(long careerId) {
        long t0 = System.nanoTime();
        cache.evict(careerId); // 로스터가 바뀌므로 메모리 상태는 다시 만든다 (오프시즌 중 이동은 막혀 있음)
        // 6단계 전: FA 라운드가 남았으면 AI 로 끝까지 (일괄 진행용, 내 제안은 그대로 유효). career.version 이 바뀌므로 먼저
        Integer done = jdbc.query("""
                SELECT o.stage FROM career k JOIN c_offseason o ON o.career_id = k.career_id AND o.season = k.season
                WHERE k.career_id = ? AND k.phase = ?
                """, rs -> rs.next() ? rs.getInt(1) : null, careerId, NextDayService.PHASE_OFFSEASON);
        if (done != null && Stage.after(done) == Stage.FREE_AGENCY) {
            market.finishRounds(careerId);
        }
        MasterData.Snapshot snap = master.get();
        Object[] c = jdbc.query("""
                SELECT seed, season, phase, version, cur_date, team_id FROM career
                WHERE career_id = ? AND deleted_at IS NULL
                """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getObject(5, LocalDate.class), rs.getInt(6)} : null, careerId);
        if (c == null) {
            throw new IllegalArgumentException("커리어 없음: " + careerId);
        }
        long seed = (long) c[0];
        int season = (int) c[1];
        int version = (int) c[3];
        LocalDate curDate = (LocalDate) c[4];
        int userTeam = (int) c[5];
        if ((int) c[2] != NextDayService.PHASE_OFFSEASON) {
            throw new IllegalStateException("오프시즌 단계가 아님: " + c[2]);
        }
        Object[] state = jdbc.query("SELECT stage, salary_rate FROM c_offseason WHERE career_id = ? AND season = ?",
                rs -> rs.next() ? new Object[] {rs.getInt(1), (Double) rs.getObject(2)} : new Object[] {0, null},
                careerId, season);
        Stage stage = Stage.after((int) state[0]);
        if (stage == null) {
            throw new IllegalStateException("오프시즌이 이미 끝남: 커리어 " + careerId);
        }
        Double rate = (Double) state[1];

        int next = season + 1;
        LocalDate opening = SeasonCalendar.openingDay(snap, next);
        LocalDate today = processDay(snap, season);

        List<OffPlayer> all = load(careerId, season, snap);
        loadDecisions(careerId, season, all);
        Map<Integer, int[]> standings = new HashMap<>();
        jdbc.query("SELECT team_id, wins, losses, runs_for, runs_against FROM c_team WHERE career_id = ?",
                rs -> {
                    standings.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)});
                }, careerId);
        // 다음 시즌 예산 (리그 시장 규모 × 점유율). 마지막 단계에서 한 번만 저장 → 중간 단계에서는 매번 같은 값
        LeagueBudget.Result league = LeagueBudget.load(jdbc, careerId, season, seed);
        Map<Integer, Integer> budget = new HashMap<>(league.next());
        int[] nextId = {firstNewId(jdbc, careerId)};

        OffseasonRules.Report rep = new OffseasonRules.Report();
        List<OffPlayer> created = new ArrayList<>();
        Write w = new Write();
        String text;
        switch (stage) {
            case CLOSE -> {
                OffseasonRules.closeSeason(all, userTeam, rep);
                w.decisions = new ArrayList<>();
                for (OffPlayer p : all) {
                    if (p.pending != OffPlayer.PENDING_NONE) {
                        w.decisions.add(new Object[] {careerId, season, p.id, p.pending});
                    }
                }
                w.picks = DraftLottery.order(standings, playoffTeams(careerId, season), seed, season,
                        OffseasonRules.DRAFT_ROUNDS);
                text = String.format("서비스 연차 +1 %d명, 계약 만료 FA %d명, 논텐더 %d명%n", rep.serviceUp, rep.expired,
                        rep.nonTendered)
                        + String.format("마이너 계약 만료: 6년 이상·MLB 경력 없음 방출 %d명, 구단 보류 재계약 %d명%n",
                        rep.minorOut, rep.minorRenewed)
                        + (rep.pendingFa + rep.pendingArb == 0 ? ""
                        : String.format("내 팀 계약 결정 대상: FA 자격 %d명, 연봉조정 %d명 (4단계 연봉 재산정 뒤 결정)%n",
                        rep.pendingFa, rep.pendingArb))
                        + lotteryText(w.picks, snap) + budgetText(league, userTeam, snap);
            }
            case RETIRE -> {
                OffseasonRules.retire(all, seed, season, rep);
                text = String.format("은퇴 %d명 (45세 시즌 종료 %d명 포함)%n", rep.retired, rep.ageRetired);
            }
            case DEVELOP -> {
                OffseasonRules.develop(all, aging.get(), seed, season, rep);
                text = developText(rep, aging.get());
            }
            case SALARY -> {
                rate = OffseasonRules.reprice(all, next, league.payrollTarget(), rep);
                text = String.format("연봉 단가 가치 1점당 %.2fM%n", rep.salaryRate / 1000);
            }
            case MARKET -> {
                if (rate == null) {
                    throw new IllegalStateException("연봉 단가 없음 (4단계 누락): 커리어 " + careerId);
                }
                OffseasonRules.applyDecisions(all, rate, next, rep);
                List<OffPlayer> posted = OffseasonRules.posting(nextId, careerId, seed, next, today, rep);
                all.addAll(posted);
                created.addAll(posted);
                w.faRows = FaService.openRows(all, rate, careerId, seed, season);
                text = (rep.resigned + rep.letGo + rep.tendered + rep.myNonTendered == 0 ? ""
                        : String.format("내 팀: 재계약 %d명, FA 포기 %d명, 연봉조정 텐더 %d명, 논텐더 %d명%n",
                        rep.resigned, rep.letGo, rep.tendered, rep.myNonTendered))
                        + String.format("FA 시장 개장: MLB 계약 대상 %d명 (포스팅 %d명 포함), %d라운드 입찰%n",
                        w.faRows.size(), rep.posted, FaService.ROUNDS);
            }
            case FREE_AGENCY -> {
                OffseasonRules.minorContracts(all, budget.keySet(), rep);
                text = market.summary(careerId, season, userTeam)
                        + String.format("남은 FA 마이너 계약 %d명, 미계약 %d명%n", rep.signedMinor, rep.unsigned);
            }
            case DRAFT -> {
                // 드래프트 화면에서 이미 정한 지명(유저·AI)은 그대로, 빈 지명권만 AI
                List<Integer> picks = new ArrayList<>();
                List<Integer> chosen = new ArrayList<>();
                jdbc.query("""
                        SELECT team_id, player_id FROM c_draft_pick WHERE career_id = ? AND season = ? ORDER BY pick_no
                        """, rs -> {
                    picks.add(rs.getInt(1));
                    chosen.add((Integer) rs.getObject(2, Integer.class));
                }, careerId, season);
                List<OffPlayer> drafted = OffseasonRules.draft(picks, chosen, nextId, careerId, seed, next, today,
                        rep);
                all.addAll(drafted);
                created.addAll(drafted);
                w.pickPlayers = new ArrayList<>(drafted.size());
                for (int i = 0; i < drafted.size(); i++) {
                    w.pickPlayers.add(new Object[] {drafted.get(i).id, careerId, season, i + 1});
                }
                text = String.format("드래프트 %d명 지명%n", rep.drafted) + myDraftText(picks, drafted, userTeam, next);
            }
            case OPENING -> {
                Set<Integer> draftees = new HashSet<>(jdbc.queryForList("""
                        SELECT player_id FROM c_draft_pick WHERE career_id = ? AND season = ? AND player_id IS NOT NULL
                        """, Integer.class, careerId, season));
                for (OffPlayer p : all) {
                    p.draftee = draftees.contains(p.id);
                }
                OffseasonRules.trimMinors(all, next, rep);
                int[] fillers = {0};
                OffseasonRules.assignRosters(all, opening, (team, level, pitcher) -> {
                    int id = nextId[0]++;
                    java.util.SplittableRandom rng = OffseasonRules.rng(seed, next, 7, id);
                    int age = 24 + rng.nextInt(5);
                    OffPlayer f = OffseasonRules.create(id, careerId, next, today, pitcher, age,
                            level == 0 ? 44 : level == 1 ? 41 : 38, 45, 1, rng, Names.american(rng));
                    f.proSeasons = com.mlbgame.career.ContractModel.proSeasonsEstimate(age);
                    created.add(f);
                    fillers[0]++;
                    return f;
                });
                rep.fillers = fillers[0];
                // 기준 = 2026 개막 로스터 평균 + 리그 환경 결과 보정 (지난 시즌 볼넷·홈런·삼진 비율 → 투수 기준값)
                EnvFeedback.Rates simRates = simRates(careerId, season);
                EnvFeedback.Rates realRates = realRates();
                double[] bias = EnvFeedback.next(previousBias(careerId, season), simRates, realRates);
                double[] target = referenceMeans(snap);
                int pit = OffseasonRules.BAT_NORM.length;
                target[pit] += bias[EnvFeedback.CONTROL];
                target[pit + 1] += bias[EnvFeedback.SUPPRESSION];
                target[OffseasonRules.STUFF_IDX] += bias[EnvFeedback.STUFF];
                target[OffseasonRules.DEF_IDX] += bias[EnvFeedback.DEFENSE];
                w.normBias = EnvFeedback.encode(bias);
                double[] shift = OffseasonRules.normalize(all, target);
                w.payroll = new HashMap<>();
                for (OffPlayer p : all) {
                    if (p.team != 0 && p.fortyMan()) {
                        w.payroll.merge(p.team, p.salary, Integer::sum);
                    }
                }
                w.budget = budget;
                w.baseShare = league.base();
                text = openingText(rep, w.payroll, shift) + envText(simRates, realRates, bias);
            }
            default -> throw new IllegalStateException("알 수 없는 단계 " + stage);
        }
        String block = "[" + stage.no() + ". " + stage.label() + "]\n" + text;
        boolean last = stage == Stage.OPENING;
        try {
            persist(careerId, version, season, stage, rate, block, all, created, w, next,
                    last ? opening.minusDays(1) : curDate);
        } catch (ConcurrentStageException e) {
            ops.conflict("stage", careerId);
            throw e;
        }
        ops.record("stage", careerId, System.nanoTime() - t0, "stage=" + stage.no() + " players=" + all.size()
                + " created=" + created.size());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new StageResult(stage, season, last, next, opening, all.size(), block, ms);
    }

    /** 오프시즌 처리일 (신규 선수 생성일 기록용) */
    static LocalDate processDay(MasterData.Snapshot snap, int season) {
        return SeasonCalendar.lastDay(snap, season).plusDays(40);
    }

    /**
     * 이번 단계에서 새로 만들 선수의 첫 ID. 드래프트 후보는 이 ID 부터 차례로 만들어지므로,
     * 드래프트 단계가 저장되기 전까지는 드래프트 화면과 단계 처리가 같은 후보를 보게 된다.
     */
    static int firstNewId(JdbcTemplate jdbc, long careerId) {
        Integer maxPerson = jdbc.queryForObject("SELECT MAX(player_id) FROM c_person WHERE career_id = ?",
                Integer.class, careerId);
        return Math.max(1_000_000_000, maxPerson == null ? 0 : maxPerson + 1);
    }

    /** 단계별로 추가로 쓸 것 */
    private static final class Write {
        /** 1단계: 내 팀 계약 결정 대상 {career_id, season, player_id, kind} */
        List<Object[]> decisions;
        /** 5단계: FA 시장 등록 {career_id, season, player_id, market, ask, pref_years, reserve, holdout} */
        List<Object[]> faRows;
        /** 1단계: 드래프트 순서 */
        List<DraftLottery.Pick> picks;
        /** 6단계: {player_id, career_id, season, pick_no} */
        List<Object[]> pickPlayers;
        /** 7단계: 다음 시즌 예산·연봉 합계·기준 비율 */
        Map<Integer, Integer> budget;
        Map<Integer, Double> baseShare;
        /** 8단계: 리그 환경 결과 보정치 */
        String normBias;
        Map<Integer, Integer> payroll;
    }

    private Set<Integer> playoffTeams(long careerId, int season) {
        Set<Integer> out = new HashSet<>();
        jdbc.query("SELECT high_team, low_team FROM c_series WHERE career_id = ? AND season = ?", rs -> {
            out.add(rs.getInt(1));
            out.add(rs.getInt(2));
        }, careerId, season);
        return out;
    }

    /** 정규화 기준 = 2026 실제 개막 로스터(마스터 26인)의 항목별 평균. 매년 같은 기준이라 편차가 쌓이지 않음 */
    private static double[] referenceMeans(MasterData.Snapshot snap) {
        List<short[]> hitters = new ArrayList<>();
        List<Integer> hitterPos = new ArrayList<>();
        List<short[]> pitchers = new ArrayList<>();
        for (MasterPlayer m : snap.players()) {
            if (m.rosterStatus() == 2) {
                boolean pitcher = m.rating(MasterData.CONTROL) >= 0
                        && (m.rating(MasterData.CONTACT) < 0 || m.primaryPos() == 1);
                if (pitcher) {
                    pitchers.add(m.ratings());
                } else {
                    hitters.add(m.ratings());
                    hitterPos.add(m.primaryPos());
                }
            }
        }
        return OffseasonRules.mlbMeans(hitters, hitterPos, pitchers);
    }

    // ------------------------------------------------------------------ 읽기

    private List<OffPlayer> load(long careerId, int season, MasterData.Snapshot snap) {
        Map<Integer, Object[]> persons = new HashMap<>();
        jdbc.query("SELECT player_id, birth_date, primary_pos FROM c_person WHERE career_id = ?", rs -> {
            persons.put(rs.getInt(1), new Object[] {rs.getObject(2, LocalDate.class), rs.getInt(3)});
        }, careerId);
        Map<Integer, Integer> games = new HashMap<>();
        jdbc.query("""
                SELECT player_id, SUM(g) FROM (
                    SELECT player_id, g FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0
                    UNION ALL
                    SELECT player_id, g FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0) t
                GROUP BY player_id
                """, rs -> {
            games.put(rs.getInt(1), rs.getInt(2));
        }, careerId, season, careerId, season);

        String[] cols = MasterData.RATING_COLUMNS;
        List<OffPlayer> out = new ArrayList<>(3500);
        jdbc.query("""
                SELECT player_id, team_id, level, roster_status, contract_type, contract_years, salary, service_years,
                       adaptability, stability, professionalism, injury_until, %s, pro_seasons
                FROM c_player WHERE career_id = ? AND roster_status <> 4
                """.formatted(String.join(", ", cols)), rs -> {
            int id = rs.getInt(1);
            MasterPlayer m = snap.byId().get(id);
            Object[] person = persons.get(id);
            LocalDate birth = m != null ? m.birth() : person == null ? null : (LocalDate) person[0];
            if (birth == null) {
                return;
            }
            int pos = m != null ? m.primaryPos() : (int) person[1];
            short[] r = new short[cols.length];
            for (int i = 0; i < cols.length; i++) {
                int v = rs.getInt(13 + i);
                r[i] = rs.wasNull() ? -1 : (short) v;
            }
            OffPlayer p = new OffPlayer(id, birth, pos, r);
            p.team = rs.getInt(2);
            p.level = rs.getInt(3);
            p.status = rs.getInt(4);
            p.contractType = rs.getInt(5);
            p.contractYears = rs.getInt(6);
            p.salary = rs.getInt(7);
            p.service = rs.getInt(8);
            p.adaptability = rs.getInt(9);
            p.stability = rs.getInt(10);
            p.professionalism = rs.getInt(11);
            p.injuryUntil = rs.getObject(12, LocalDate.class);
            p.proSeasons = rs.getInt(13 + cols.length);
            p.mlbGames = games.getOrDefault(id, 0);
            out.add(p);
        }, careerId);
        return out;
    }

    /** 내 팀 계약 결정 대상·결정 (1단계 이후) */
    private void loadDecisions(long careerId, int season, List<OffPlayer> all) {
        Map<Integer, int[]> rows = new HashMap<>();
        jdbc.query("""
                SELECT player_id, kind, decision, years FROM c_contract_decision WHERE career_id = ? AND season = ?
                """, rs -> {
            rows.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
        }, careerId, season);
        if (rows.isEmpty()) {
            return;
        }
        for (OffPlayer p : all) {
            int[] r = rows.get(p.id);
            if (r != null) {
                p.pending = r[0];
                p.decision = r[1];
                p.decisionYears = r[2];
            }
        }
    }

    // ------------------------------------------------------------------ 쓰기

    private void persist(long careerId, int version, int season, Stage stage, Double rate, String block,
                         List<OffPlayer> all, List<OffPlayer> created, Write w, int next, LocalDate stateDate) {
        List<String> cols = CareerCreator.PLAYER_COLUMNS;
        StringBuilder update = new StringBuilder();
        for (String col : cols) {
            if (!col.equals("career_id") && !col.equals("player_id")) {
                update.append(update.isEmpty() ? "" : ", ").append(col).append(" = n.").append(col);
            }
        }
        String upsert = CareerCreator.INSERT_PLAYER + " AS n ON DUPLICATE KEY UPDATE " + update;

        List<Object[]> rows = new ArrayList<>(all.size());
        List<Object[]> persons = new ArrayList<>(created.size());
        for (OffPlayer p : all) {
            Object[] row = CareerCreator.playerValues(careerId, p.id, p.team, p.status, p.level, p.contractType,
                    p.contractYears, p.salary, Math.min(p.service, 255), p.r, p.adaptability, p.stability,
                    p.professionalism, stateDate, p.injuryUntil, p.proSeasons);
            if (p.team == 0) {
                row[2] = null;
            }
            rows.add(row);
            if (p.personRow != null) {
                persons.add(p.personRow);
            }
        }
        boolean last = stage == Stage.OPENING;

        tx.executeWithoutResult(st -> {
            int updated = last
                    ? jdbc.update("""
                            UPDATE career SET season = ?, cur_date = ?, phase = ?, next_game_no = 1, version = version + 1
                            WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                            """, next, stateDate, NextDayService.PHASE_REGULAR, careerId, version)
                    : jdbc.update("""
                            UPDATE career SET version = version + 1
                            WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                            """, careerId, version);
            if (updated != 1) {
                throw new ConcurrentStageException(careerId);
            }
            jdbc.update(OFFSEASON_UPSERT, careerId, season, stage.no(), rate, block);
            if (!persons.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO c_person (career_id, player_id, full_name, birth_date, bats, throws, primary_pos,
                                              origin, created_on)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, persons);
            }
            jdbc.batchUpdate(upsert, rows);

            if (w.faRows != null) {
                jdbc.update("DELETE FROM c_fa_offer WHERE career_id = ? AND season = ?", careerId, season);
                jdbc.update("DELETE FROM c_fa_player WHERE career_id = ? AND season = ?", careerId, season);
                if (!w.faRows.isEmpty()) {
                    jdbc.batchUpdate("""
                            INSERT INTO c_fa_player (career_id, season, player_id, market, ask, pref_years, reserve,
                                                     holdout)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, w.faRows);
                }
                jdbc.update("UPDATE c_offseason SET fa_round = 1 WHERE career_id = ? AND season = ?", careerId,
                        season);
            }
            if (w.decisions != null) {
                jdbc.update("DELETE FROM c_contract_decision WHERE career_id = ? AND season = ?", careerId, season);
                if (!w.decisions.isEmpty()) {
                    jdbc.batchUpdate("""
                            INSERT INTO c_contract_decision (career_id, season, player_id, kind) VALUES (?, ?, ?, ?)
                            """, w.decisions);
                }
            }
            if (w.picks != null) {
                jdbc.update("DELETE FROM c_draft_pick WHERE career_id = ? AND season = ?", careerId, season);
                List<Object[]> pickRows = new ArrayList<>(w.picks.size());
                for (DraftLottery.Pick p : w.picks) {
                    pickRows.add(new Object[] {careerId, season, p.pickNo(), p.round(), p.teamId(), p.preRank()});
                }
                jdbc.batchUpdate("""
                        INSERT INTO c_draft_pick (career_id, season, pick_no, round, team_id, pre_rank)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, pickRows);
            }
            if (w.pickPlayers != null) {
                jdbc.batchUpdate("""
                        UPDATE c_draft_pick SET player_id = ? WHERE career_id = ? AND season = ? AND pick_no = ?
                        """, w.pickPlayers);
            }
            if (w.normBias != null) {
                jdbc.update("UPDATE c_offseason SET norm_bias = ? WHERE career_id = ? AND season = ?", w.normBias,
                        careerId, season);
            }
            if (last) {
                List<Object[]> teams = new ArrayList<>();
                List<Object[]> minors = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : w.budget.entrySet()) {
                    teams.add(new Object[] {careerId, e.getKey(), e.getValue(), w.payroll.getOrDefault(e.getKey(), 0),
                            w.baseShare.get(e.getKey())});
                    for (int level = 1; level <= 2; level++) {
                        minors.add(new Object[] {careerId, level, e.getKey()});
                    }
                }
                jdbc.batchUpdate("""
                        INSERT INTO c_team (career_id, team_id, budget, payroll, base_share) VALUES (?, ?, ?, ?, ?) AS n
                        ON DUPLICATE KEY UPDATE wins = 0, losses = 0, runs_for = 0, runs_against = 0, streak = 0,
                            budget = n.budget, payroll = n.payroll,
                            base_share = COALESCE(c_team.base_share, n.base_share)
                        """, teams);
                jdbc.batchUpdate("""
                        INSERT INTO c_team_minor (career_id, level, team_id) VALUES (?, ?, ?) AS n
                        ON DUPLICATE KEY UPDATE wins = 0, losses = 0, runs_for = 0, runs_against = 0
                        """, minors);
                jdbc.update("DELETE FROM c_game WHERE career_id = ? AND season <= ?", careerId,
                        next - KEEP_BOX_SEASONS);
            }
        });
    }

    // ------------------------------------------------------------------ 결과 요약

    private static String abbrev(MasterData.Snapshot snap, int team) {
        MasterData.TeamInfo t = snap.teams().get(team);
        return t == null ? String.valueOf(team) : t.abbrev();
    }

    /** 다음 시즌 예산: 리그 시장 규모·성장률, 최고·최저 구단, 내 팀 */
    static String budgetText(LeagueBudget.Result lb, int userTeam, MasterData.Snapshot snap) {
        int top = 0;
        int low = 0;
        for (Map.Entry<Integer, Double> e : lb.ratio().entrySet()) {
            if (top == 0 || e.getValue() > lb.ratio().get(top)) {
                top = e.getKey();
            }
            if (low == 0 || e.getValue() < lb.ratio().get(low)) {
                low = e.getKey();
            }
        }
        StringBuilder b = new StringBuilder();
        b.append(String.format("다음 시즌 리그 시장 규모 %s (%+.1f%%) · 최고 %s %.0f%% · 최저 %s %.0f%%",
                LeagueBudget.billions(lb.nextMarket()), lb.growth() * 100, abbrev(snap, top),
                lb.ratio().get(top) * 100, abbrev(snap, low), lb.ratio().get(low) * 100));
        Double mine = lb.ratio().get(userTeam);
        if (mine != null) {
            b.append(String.format("%n내 팀 다음 시즌 예산 $%.1fM (리그 평균의 %.0f%%, %s)",
                    lb.next().get(userTeam) / 1000.0, mine * 100, LeagueBudget.tierName(mine)));
        }
        return b.append(System.lineSeparator()).toString();
    }

    /** 1라운드 추첨 결과: 1~6순위와 추첨 전 순위 */
    static String lotteryText(List<DraftLottery.Pick> picks, MasterData.Snapshot snap) {
        StringBuilder b = new StringBuilder("드래프트 추첨 (1라운드 1~6순위):");
        for (int i = 0; i < DraftLottery.LOTTERY_PICKS && i < picks.size(); i++) {
            DraftLottery.Pick p = picks.get(i);
            int move = p.preRank() - p.pickNo();
            b.append(String.format(" %d순위 %s(성적 %d위%s)", p.pickNo(), abbrev(snap, p.teamId()), p.preRank(),
                    move > 0 ? " ↑" + move : move < 0 ? " ↓" + (-move) : ""));
        }
        return b.append('\n').toString();
    }

    /** 내 팀 지명 선수: "내 지명: 1R 7순 이름 (SS, 고졸) / ..." */
    private static String myDraftText(List<Integer> picks, List<OffPlayer> drafted, int userTeam, int nextSeason) {
        List<String> mine = new ArrayList<>();
        int teams = Math.max(1, picks.size() / OffseasonRules.DRAFT_ROUNDS);
        for (int i = 0; i < drafted.size(); i++) {
            OffPlayer p = drafted.get(i);
            if (p.team == userTeam) {
                mine.add(String.format("%dR %d순 %s (%s, %s)", i / teams + 1, i + 1, p.personRow[2],
                        p.isPitcher() ? "P" : POS[Math.clamp(p.pos, 0, 9)],
                        p.age(nextSeason) <= 18 ? "고졸" : "대졸"));
            }
        }
        return mine.isEmpty() ? "" : "내 지명: " + String.join(" / ", mine) + System.lineSeparator();
    }

    private static final String[] POS = {"", "P", "C", "1B", "2B", "3B", "SS", "LF", "CF", "RF"};

    private static String developText(OffseasonRules.Report r, AgingCurves.Curves curves) {
        StringBuilder b = new StringBuilder("평균 능력치 변화 (곡선 적용 항목)  ");
        String[] bands = {"24세 이하", "25~29세", "30~33세", "34세 이상"};
        for (int i = 0; i < 4; i++) {
            b.append(String.format("%s %+.2f (%d명)  ", bands[i], r.change[i][0] / Math.max(1, r.change[i][1]),
                    (int) r.change[i][1]));
        }
        b.append('\n');
        b.append("나이 곡선 (1년 변화, 데이터 추정)   23세   26세   29세   32세   35세\n");
        for (int g = 0; g < AgingCurves.GROUPS; g++) {
            b.append(String.format("  %-6s                         %+.2f  %+.2f  %+.2f  %+.2f  %+.2f%n",
                    AgingCurves.NAMES[g], curves.at(g, 23), curves.at(g, 26), curves.at(g, 29), curves.at(g, 32),
                    curves.at(g, 35)));
        }
        return b.toString();
    }

    /** 지난 시즌 MLB 정규시즌 볼넷·삼진·홈런·BABIP (기록이 없으면 null) */
    private EnvFeedback.Rates simRates(long careerId, int season) {
        return jdbc.query("""
                SELECT SUM(pa), SUM(bb), SUM(so), SUM(hr), SUM(h), SUM(hbp)
                FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0
                """, rs -> rs.next() ? EnvFeedback.Rates.of(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)) : null, careerId, season);
    }

    /** 실제 2026 MLB (팀 시즌 합계) */
    private EnvFeedback.Rates realRates() {
        return jdbc.query("SELECT SUM(pa), SUM(bb), SUM(so), SUM(hr), SUM(h), SUM(hbp) FROM mst_team_season WHERE season = ?",
                rs -> rs.next() ? EnvFeedback.Rates.of(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)) : null, MasterData.START_SEASON);
    }

    /** 지난 오프시즌에 저장한 보정치 (없으면 null) */
    private double[] previousBias(long careerId, int season) {
        return EnvFeedback.decode(jdbc.query("SELECT norm_bias FROM c_offseason WHERE career_id = ? AND season = ?",
                rs -> rs.next() ? rs.getString(1) : null, careerId, season - 1));
    }

    private static String envText(EnvFeedback.Rates sim, EnvFeedback.Rates real, double[] bias) {
        String rates = sim == null || real == null ? ""
                : String.format("지난 시즌 볼넷%% %.3f (실제 %.3f) · 삼진%% %.3f (%.3f) · 홈런%% %.3f (%.3f) · BABIP %.3f (%.3f) → ",
                sim.bb(), real.bb(), sim.k(), real.k(), sim.hr(), real.hr(), sim.babip(), real.babip());
        return String.format("리그 환경 보정: %s기준 제구 %+.1f · 장타억제 %+.1f · 구위 %+.1f · 수비 %+.1f%n", rates,
                bias[EnvFeedback.CONTROL], bias[EnvFeedback.SUPPRESSION], bias[EnvFeedback.STUFF],
                bias[EnvFeedback.DEFENSE]);
    }

    private static String openingText(OffseasonRules.Report r, Map<Integer, Integer> payroll, double[] shift) {
        List<Integer> pays = new ArrayList<>(payroll.values());
        pays.sort(null);
        StringBuilder b = new StringBuilder();
        b.append(String.format("마이너 인원 정리 방출 %d명, 개막 인원 부족 대체 선수 %d명%n", r.released, r.fillers));
        b.append(String.format("팀 연봉 합계 (백만 달러) 최소 %.0f / 중앙 %.0f / 최대 %.0f%n",
                pays.isEmpty() ? 0 : pays.getFirst() / 1000.0, pays.isEmpty() ? 0 : pays.get(pays.size() / 2) / 1000.0,
                pays.isEmpty() ? 0 : pays.getLast() / 1000.0));
        b.append("리그 기준 정규화 (2026 개막 로스터 평균 유지, 전 선수 이동 폭)  ");
        for (int i = 0; i < shift.length; i++) {
            b.append(String.format("%s %+.2f  ", OffseasonRules.NORM_NAMES[i], shift[i]));
        }
        return b.append('\n').toString();
    }
}
