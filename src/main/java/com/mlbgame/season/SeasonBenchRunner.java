package com.mlbgame.season;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mlbgame.career.CareerCreator;
import com.mlbgame.career.MasterData;
import com.mlbgame.offseason.OffseasonService;

/**
 * 다년 벤치마크: 커리어를 만들고 (정규시즌 → 포스트시즌 → 오프시즌) 을 N 시즌 반복한 뒤 시간·저장량·리그 기록을 점검한다.
 * 끝나면 커리어를 삭제한다 ({@code --bench.keep=true} 면 남김, {@code --bench.seasons=N}, 기본 2).
 * 마지막에 시즌별 리그 평균 추이 표 (득점 환경 재보정용): 경기당 득점·타율·출루율·장타율·홈런%·삼진%·볼넷%·BABIP,
 * MLB 26인 평균 능력치(컨택·파워·선구 / 제구·장타억제·구위), 실제 2026 기준 행. {@code --bench.quiet=true} 면 시즌·오프시즌 상세 생략.
 * <pre>
 * ./gradlew bootRun --args="--spring.profiles.active=local,season-bench --spring.main.web-application-type=none --bench.seasons=6 --bench.quiet=true"
 * </pre>
 */
@Component
@Profile("season-bench")
class SeasonBenchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeasonBenchRunner.class);
    private static final int BENCH_USER = 0;

    private final CareerCreator creator;
    private final NextDayService nextDay;
    private final OffseasonService offseason;
    private final CareerRuntimeCache cache;
    private final MasterData master;
    private final JdbcTemplate jdbc;
    private final ConfigurableApplicationContext context;
    private final boolean keep;
    private final int seasons;
    private final boolean quiet;

    SeasonBenchRunner(CareerCreator creator, NextDayService nextDay, OffseasonService offseason,
                      CareerRuntimeCache cache, MasterData master, JdbcTemplate jdbc,
                      ConfigurableApplicationContext context, @Value("${bench.keep:false}") boolean keep,
                      @Value("${bench.seasons:2}") int seasons, @Value("${bench.quiet:false}") boolean quiet) {
        this.creator = creator;
        this.nextDay = nextDay;
        this.offseason = offseason;
        this.cache = cache;
        this.master = master;
        this.jdbc = jdbc;
        this.context = context;
        this.keep = keep;
        this.seasons = seasons;
        this.quiet = quiet;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Long id : jdbc.queryForList("SELECT career_id FROM career WHERE user_id = ?", Long.class, BENCH_USER)) {
            creator.purge(id);
        }
        MasterData.Snapshot snap = master.get();
        long careerId = creator.create(BENCH_USER, 1, snap.teamIds().getFirst(), 20260325L).careerId();

        Runtime jvm = Runtime.getRuntime();
        System.gc();
        long heapBefore = jvm.totalMemory() - jvm.freeMemory();
        long loadStart = System.nanoTime();
        cache.get(careerId);
        double loadMs = (System.nanoTime() - loadStart) / 1e6;
        System.gc();
        long heapAfter = jvm.totalMemory() - jvm.freeMemory();
        log.info(String.format("%n커리어 상태 적재 %.0fms, 메모리 약 %.1fMB (캐시 1개)", loadMs,
                (heapAfter - heapBefore) / 1048576.0));

        List<String> trend = new ArrayList<>();
        List<String> drift = new ArrayList<>();
        for (int n = 0; n < seasons; n++) {
            int season = MasterData.START_SEASON + n;
            double[] opening = ratingMeans(careerId); // 개막 26인 (시즌 중 부상·콜업으로 얼마나 바뀌는지 비교)
            long t0 = System.nanoTime();
            String detail = runSeason(careerId, season);
            log.info(quiet ? String.format("%d 시즌 진행 %.0f초", season, (System.nanoTime() - t0) / 1e9) : detail);
            trend.add(trendRow(careerId, season)); // 오프시즌이 순위·로스터를 바꾸기 전에
            double[] end = ratingMeans(careerId);
            drift.add(String.format(
                    "%d   제구 %4.1f → %4.1f   억제 %4.1f → %4.1f   구위 %4.1f → %4.1f   컨택 %4.1f → %4.1f   수비 %4.1f → %4.1f",
                    season, opening[3], end[3], opening[4], end[4], opening[5], end[5], opening[0], end[0],
                    opening[6], end[6]));
            if (n + 1 < seasons) {
                OffseasonService.Result off = offseason.run(careerId);
                log.info(quiet
                        ? String.format("오프시즌 %d → %d (%dms)", off.season(), off.nextSeason(), off.millis())
                        : String.format("%n===== 오프시즌 %d → %d (%dms, 선수 %d명, 다음 개막 %s) =====%n%s",
                        off.season(), off.nextSeason(), off.millis(), off.players(), off.nextOpening(), off.report()));
            }
        }
        log.info(trendTable(trend) + "\n[MLB 26인 평균 능력치: 개막 → 시즌 끝]\n" + String.join("\n", drift) + "\n");
        log.info(storage(careerId));

        if (!keep) {
            creator.purge(careerId);
        }
        cache.evict(careerId);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private String runSeason(long careerId, int season) {
        List<NextDayService.DayReport> days = new ArrayList<>(200);
        long start = System.nanoTime();
        NextDayService.DayReport r;
        do {
            r = nextDay.advance(careerId);
            days.add(r);
        } while (r.phase() == NextDayService.PHASE_REGULAR);
        double totalSec = (System.nanoTime() - start) / 1e9;

        List<NextDayService.DayReport> post = new ArrayList<>(40);
        long postStart = System.nanoTime();
        do {
            r = nextDay.advance(careerId);
            post.add(r);
        } while (r.phase() == NextDayService.PHASE_POSTSEASON && post.size() < 60);
        double postSec = (System.nanoTime() - postStart) / 1e9;

        StringBuilder out = new StringBuilder();
        out.append(String.format("%n===== %d 시즌: 정규시즌 %d일 (%s ~ %s), 총 %.1f초 =====%n", season, days.size(),
                days.getFirst().date(), days.getLast().date(), totalSec));
        List<NextDayService.DayReport> playing = days.stream().filter(d -> d.mlbGames() + d.minorGames() > 0).toList();
        out.append(String.format("하루 처리 (경기 있는 날 %d일)  평균 %.1fms | 중앙 %.1fms | 95%% %.1fms | 최대 %.1fms%n",
                playing.size(), avg(playing), pct(playing, 0.5), pct(playing, 0.95), pct(playing, 1.0)));
        out.append(String.format("  시뮬 평균 %.2fms (경기 평균 MLB %.1f + 마이너 %.1f) | DB 저장 평균 %.1fms%n",
                playing.stream().mapToLong(NextDayService.DayReport::simMicros).average().orElse(0) / 1000,
                playing.stream().mapToInt(NextDayService.DayReport::mlbGames).average().orElse(0),
                playing.stream().mapToInt(NextDayService.DayReport::minorGames).average().orElse(0),
                playing.stream().mapToLong(NextDayService.DayReport::dbMicros).average().orElse(0) / 1000));
        out.append(String.format("  하루 저장 행 평균  타격 %.0f / 투구 %.0f%n",
                playing.stream().mapToInt(NextDayService.DayReport::batRows).average().orElse(0),
                playing.stream().mapToInt(NextDayService.DayReport::pitRows).average().orElse(0)));
        out.append(String.format("시즌 중 운영  부상 %d건 (MLB %d, 팀당 %.1f) / 복귀 %d / 콜업·강등 %d회 / 대체 선수 영입 %d명 / 인원 부족 시 이전 구성 유지 %d회%n",
                days.stream().mapToInt(NextDayService.DayReport::injuries).sum(),
                days.stream().mapToInt(NextDayService.DayReport::mlbInjuries).sum(),
                days.stream().mapToInt(NextDayService.DayReport::mlbInjuries).sum() / 30.0,
                days.stream().mapToInt(NextDayService.DayReport::returns).sum(),
                days.stream().mapToInt(NextDayService.DayReport::moves).sum(),
                days.stream().mapToInt(NextDayService.DayReport::signed).sum(),
                days.getLast().shortRosterKeeps()));
        out.append(league(careerId, season)).append(standings(careerId)).append(distribution(careerId, season));
        out.append(String.format("%n[포스트시즌] %d일 (%s ~ %s), 경기 %d, 총 %.2f초, 끝난 단계 %d (3 = 오프시즌)%n",
                post.size(), post.getFirst().date(), post.getLast().date(),
                post.stream().mapToInt(NextDayService.DayReport::mlbGames).sum(), postSec, r.phase()));
        out.append(bracket(careerId, season));
        return out.toString();
    }

    private static final String TREND_HEADER =
            "시즌   득점/G  타율   출루   장타   홈런%  삼진%  볼넷%  BABIP | 26인 컨택 파워 선구 | 제구 억제 구위";

    /** 시즌별 리그 평균 한 줄 (MLB 정규시즌 기록 + 시즌 끝 MLB 26인 평균 능력치) */
    private String trendRow(long careerId, int season) {
        double[] b = jdbc.query("""
                SELECT SUM(pa), SUM(ab), SUM(h), SUM(b2), SUM(b3), SUM(hr), SUM(bb), SUM(hbp), SUM(sf), SUM(so)
                FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0
                """, rs -> {
            rs.next();
            double[] v = new double[10];
            for (int i = 0; i < 10; i++) {
                v[i] = rs.getDouble(i + 1);
            }
            return v;
        }, careerId, season);
        double rpg = jdbc.query("SELECT SUM(runs_for) / SUM(wins + losses) FROM c_team WHERE career_id = ?",
                rs -> rs.next() ? rs.getDouble(1) : 0, careerId);
        double[] r = ratingMeans(careerId);
        return season + "  " + line(rpg, b) + String.format(" |      %4.1f %4.1f %4.1f | %4.1f %4.1f %4.1f",
                r[0], r[1], r[2], r[3], r[4], r[5]);
    }

    /** MLB 26인 평균 {컨택, 파워, 선구, 제구, 장타억제, 구위, 타자 주 포지션 수비} */
    private double[] ratingMeans(long careerId) {
        return jdbc.query("""
                SELECT AVG(CASE WHEN contact IS NOT NULL AND primary_pos <> 1 THEN contact END),
                       AVG(CASE WHEN contact IS NOT NULL AND primary_pos <> 1 THEN power END),
                       AVG(CASE WHEN contact IS NOT NULL AND primary_pos <> 1 THEN eye END),
                       AVG(CASE WHEN control IS NOT NULL AND (contact IS NULL OR primary_pos = 1) THEN control END),
                       AVG(CASE WHEN control IS NOT NULL AND (contact IS NULL OR primary_pos = 1) THEN suppression END),
                       AVG(CASE WHEN control IS NOT NULL AND (contact IS NULL OR primary_pos = 1)
                                THEN (COALESCE(ff_use * ff_stuff, 0) + COALESCE(si_use * si_stuff, 0)
                                      + COALESCE(fc_use * fc_stuff, 0) + COALESCE(sl_use * sl_stuff, 0)
                                      + COALESCE(st_use * st_stuff, 0) + COALESCE(cu_use * cu_stuff, 0)
                                      + COALESCE(ch_use * ch_stuff, 0) + COALESCE(fs_use * fs_stuff, 0))
                                     / NULLIF(COALESCE(ff_use, 0) + COALESCE(si_use, 0) + COALESCE(fc_use, 0)
                                      + COALESCE(sl_use, 0) + COALESCE(st_use, 0) + COALESCE(cu_use, 0)
                                      + COALESCE(ch_use, 0) + COALESCE(fs_use, 0), 0) END),
                       AVG(CASE primary_pos WHEN 2 THEN def_c WHEN 3 THEN def_1b WHEN 4 THEN def_2b WHEN 5 THEN def_3b
                                WHEN 6 THEN def_ss WHEN 7 THEN def_lf WHEN 8 THEN def_cf WHEN 9 THEN def_rf END)
                FROM (SELECT c.*, COALESCE(m.primary_pos, p.primary_pos) AS primary_pos FROM c_player c
                      LEFT JOIN mst_player m ON m.player_id = c.player_id
                      LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id
                      WHERE c.career_id = ? AND c.level = 0 AND c.roster_status = 2) t
                """, rs -> {
            rs.next();
            double[] v = new double[7];
            for (int i = 0; i < 7; i++) {
                v[i] = rs.getDouble(i + 1);
            }
            return v;
        }, careerId);
    }

    /** 득점/G 타율 출루 장타 홈런% 삼진% 볼넷% BABIP. b = {pa, ab, h, 2b, 3b, hr, bb, hbp, sf, so} */
    private static String line(double rpg, double[] b) {
        double pa = Math.max(1, b[0]);
        double ab = Math.max(1, b[1]);
        double avg = b[2] / ab;
        double obp = (b[2] + b[6] + b[7]) / Math.max(1, b[1] + b[6] + b[7] + b[8]);
        double slg = (b[2] + b[3] + 2 * b[4] + 3 * b[5]) / ab;
        double babip = (b[2] - b[5]) / Math.max(1, b[1] - b[9] - b[5] + b[8]);
        return String.format("%5.2f  %.3f  %.3f  %.3f  %.3f  %.3f  %.3f  %.3f", rpg, avg, obp, slg, b[5] / pa,
                b[9] / pa, b[6] / pa, babip);
    }

    /** 시즌별 추이 + 실제 2026 (팀 시즌 합계, 타수는 타석 - 볼넷 - 사구 근사라 타율·장타·BABIP 는 약간 낮게 나옴) */
    private String trendTable(List<String> rows) {
        double[] real = jdbc.query("""
                SELECT SUM(pa), SUM(pa - bb - hbp), SUM(h), SUM(b2), SUM(b3), SUM(hr), SUM(bb), SUM(hbp), 0, SUM(so),
                       SUM(runs_scored) / SUM(wins + losses)
                FROM mst_team_season WHERE season = ?
                """, rs -> {
            rs.next();
            double[] v = new double[11];
            for (int i = 0; i < 11; i++) {
                v[i] = rs.getDouble(i + 1);
            }
            return v;
        }, MasterData.START_SEASON);
        StringBuilder out = new StringBuilder("\n===== 시즌별 리그 평균 추이 (MLB 정규시즌) =====\n");
        out.append(TREND_HEADER).append('\n');
        rows.forEach(r -> out.append(r).append('\n'));
        out.append("실제  ").append(line(real[10], real)).append(" | (실제 " + MasterData.START_SEASON
                + ", 타수 근사)\n");
        return out.toString();
    }

    /** 시리즈별 결과 (시드·팀·승패) */
    private String bracket(long careerId, int season) {
        StringBuilder b = new StringBuilder();
        String[] rounds = {"", "와일드카드", "디비전", "챔피언십", "월드시리즈"};
        jdbc.query("""
                SELECT s.round, COALESCE(s.league, ''), s.high_seed, h.abbrev, s.high_wins, s.low_wins, l.abbrev,
                       s.low_seed, w.abbrev
                FROM c_series s
                JOIN mst_team h ON h.team_id = s.high_team
                JOIN mst_team l ON l.team_id = s.low_team
                LEFT JOIN mst_team w ON w.team_id = s.winner
                WHERE s.career_id = ? AND s.season = ? ORDER BY s.series_id
                """, rs -> {
            b.append(String.format("  %-6s %-2s (%d) %-4s %d - %d %-4s (%d)  → %s%n", rounds[rs.getInt(1)],
                    rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                    rs.getInt(8), rs.getString(9) == null ? "진행 중" : rs.getString(9)));
        }, careerId, season);
        return b.toString();
    }

    private String storage(long careerId) {
        return jdbc.query("""
                SELECT (SELECT COUNT(*) FROM c_game WHERE career_id = ?), (SELECT SUM(LENGTH(box)) FROM c_game WHERE career_id = ?),
                       (SELECT COUNT(*) FROM c_bat_season WHERE career_id = ?), (SELECT COUNT(*) FROM c_pit_season WHERE career_id = ?),
                       (SELECT COUNT(*) FROM c_player WHERE career_id = ?), (SELECT COUNT(*) FROM c_person WHERE career_id = ?)
                """, rs -> {
            rs.next();
            return String.format("%n저장량 (커리어 전체)  보관 중 경기 %d개 (박스스코어 합계 %.0fKB), 시즌 기록 타격 %d행 / 투구 %d행, "
                            + "선수 %d명 (커리어 생성 선수 %d명)%n",
                    rs.getInt(1), rs.getDouble(2) / 1024, rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6));
        }, careerId, careerId, careerId, careerId, careerId, careerId);
    }

    /** 레벨별 리그 기록 (시뮬) vs 실제 2026 (MLB 는 mst_team_season, 마이너는 마이너 기록 합계) */
    private String league(long careerId, int season) {
        StringBuilder b = new StringBuilder("\n[레벨별 리그 기록]   삼진%    볼넷%    홈런%    OPS     | 실제 2026 삼진% 볼넷% 홈런%\n");
        String[] names = {"MLB", "AAA", "AA "};
        for (int level = 0; level <= 2; level++) {
            double[] sim = jdbc.query("""
                    SELECT SUM(pa), SUM(so), SUM(bb), SUM(hr), SUM(h), SUM(b2), SUM(b3), SUM(ab), SUM(hbp), SUM(sf)
                    FROM c_bat_season WHERE career_id = ? AND season = ? AND level = ?
                    """, rs -> {
                rs.next();
                double[] v = new double[10];
                for (int i = 0; i < 10; i++) {
                    v[i] = rs.getDouble(i + 1);
                }
                return v;
            }, careerId, season, level);
            String realSql = level == 0
                    ? "SELECT SUM(pa), SUM(so), SUM(bb), SUM(hr) FROM mst_team_season WHERE season = ?"
                    : "SELECT SUM(pa), SUM(so), SUM(bb), SUM(hr) FROM mst_bat_season_minor WHERE season = ? AND level = "
                    + level;
            double[] real = jdbc.query(realSql, rs -> {
                rs.next();
                return new double[] {rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)};
            }, MasterData.START_SEASON);
            double pa = Math.max(1, sim[0]);
            double obp = (sim[4] + sim[2] + sim[8]) / Math.max(1, sim[7] + sim[2] + sim[8] + sim[9]);
            double slg = (sim[4] + sim[5] + 2 * sim[6] + 3 * sim[3]) / Math.max(1, sim[7]);
            b.append(String.format("%s                 %.3f    %.3f    %.3f    %.3f   |      %.3f  %.3f  %.3f%n",
                    names[level], sim[1] / pa, sim[2] / pa, sim[3] / pa, obp + slg,
                    real[1] / Math.max(1, real[0]), real[2] / Math.max(1, real[0]), real[3] / Math.max(1, real[0])));
        }
        return b.toString();
    }

    /** 현재 순위 (오프시즌에 초기화되므로 시즌이 끝난 직후에 호출) */
    private String standings(long careerId) {
        return jdbc.query("""
                SELECT MIN(wins), MAX(wins), SUM(runs_for) / SUM(wins + losses), SUM(wins + losses) / COUNT(*)
                FROM c_team WHERE career_id = ?
                """, rs -> {
            rs.next();
            return String.format("MLB 순위  최소 %d승 ~ 최대 %d승, 팀당 경기 득점 %.2f, 팀당 %.0f경기%n",
                    rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4));
        }, careerId);
    }

    /** MLB 출전 분포 (실제 최근 시즌 참고값과 비교) */
    private String distribution(long careerId, int season) {
        return jdbc.query("""
                SELECT (SELECT COUNT(*) FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0 AND pa >= 502),
                       (SELECT MAX(pa) FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0),
                       (SELECT COUNT(*) FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0 AND outs >= 486),
                       (SELECT MAX(outs) FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0),
                       (SELECT MAX(g) FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0 AND gs = 0),
                       (SELECT COUNT(*) FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0 AND gs = 0 AND g >= 60),
                       (SELECT COUNT(DISTINCT player_id) FROM c_bat_season WHERE career_id = ? AND season = ? AND level = 0),
                       (SELECT COUNT(DISTINCT player_id) FROM c_pit_season WHERE career_id = ? AND season = ? AND level = 0)
                """, rs -> {
            rs.next();
            return String.format("""
                    [MLB 출전 분포]                       시뮬    실제(최근 시즌 대략)
                    규정타석(502) 타자                     %3d     약 130
                    최다 타석                              %3d     약 720
                    규정이닝(162) 투수                     %3d     약 50
                    최다 이닝                              %5.1f   약 210
                    구원 최다 등판 / 60경기 이상 구원 투수  %3d / %d  약 80 / 약 40
                    한 시즌 출전 타자 / 투수 (리그 전체)     %d / %d  약 650 / 약 850
                    """, rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) / 3.0, rs.getInt(5), rs.getInt(6),
                    rs.getInt(7), rs.getInt(8));
        }, careerId, season, careerId, season, careerId, season, careerId, season, careerId, season, careerId, season,
                careerId, season, careerId, season);
    }

    private static double avg(List<NextDayService.DayReport> d) {
        return d.stream().mapToLong(x -> x.simMicros() + x.dbMicros()).average().orElse(0) / 1000;
    }

    private static double pct(List<NextDayService.DayReport> d, double p) {
        long[] t = d.stream().mapToLong(x -> x.simMicros() + x.dbMicros()).sorted().toArray();
        if (t.length == 0) {
            return 0;
        }
        return t[Math.min(t.length - 1, (int) Math.floor(p * (t.length - 1)))] / 1000.0;
    }
}
