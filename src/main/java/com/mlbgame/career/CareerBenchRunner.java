package com.mlbgame.career;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

/**
 * 커리어 생성 벤치마크 + 샘플링 점검. 생성한 커리어는 끝나면 모두 삭제한다.
 * <pre>
 * ./gradlew bootRun --args="--spring.profiles.active=local,career-bench --spring.main.web-application-type=none"
 * </pre>
 */
@Component
@Profile("career-bench")
class CareerBenchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CareerBenchRunner.class);
    /** 벤치마크용 가상 사용자 (app_user 와 FK 없음) */
    private static final int BENCH_USER = 0;

    private final CareerCreator creator;
    private final MasterData master;
    private final JdbcTemplate jdbc;
    private final ConfigurableApplicationContext context;
    private final int count;

    CareerBenchRunner(CareerCreator creator, MasterData master, JdbcTemplate jdbc,
                      ConfigurableApplicationContext context, @Value("${bench.count:10}") int count) {
        this.creator = creator;
        this.master = master;
        this.jdbc = jdbc;
        this.context = context;
        this.count = count;
    }

    @Override
    public void run(ApplicationArguments args) {
        long loadStart = System.nanoTime();
        MasterData.Snapshot snap = master.get();
        long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
        int team = snap.teamIds().getFirst();
        // 이전 실행이 중간에 죽어 남은 벤치마크 커리어 정리
        for (Long id : jdbc.queryForList("SELECT career_id FROM career WHERE user_id = ?", Long.class, BENCH_USER)) {
            creator.purge(id);
        }

        // 1회 예열 (JIT, 커넥션, 준비된 문장 캐시)
        CareerCreator.Result warm = creator.create(BENCH_USER, 1, team, 1L);
        StringBuilder out = new StringBuilder();
        out.append(String.format("%n===== 커리어 생성 벤치마크 (%d회, 선수 %d명) =====%n", count, warm.players()));
        out.append(String.format("마스터 캐시 적재 %dms (최초 1회)%n", loadMs));
        out.append(String.format("c_player INSERT 전송량 약 %dKB, 연봉 단가 가치 1점당 %.2fM 달러%n",
                warm.payloadBytes() / 1024, warm.salaryRate() / 1000));
        String check = samplingCheck(warm.careerId());
        String rosters = rosterCheck(warm.careerId(), warm.payroll());
        creator.purge(warm.careerId());

        List<long[]> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            CareerCreator.Result r = creator.create(BENCH_USER, 1, team, null);
            long total = (System.nanoTime() - start) / 1000;
            times.add(new long[] {r.buildMicros(), r.insertMicros(), total});
            long purgeStart = System.nanoTime();
            creator.purge(r.careerId());
            if (i == 0) {
                out.append(String.format("커리어 삭제(purge) %.1fms%n", (System.nanoTime() - purgeStart) / 1e6));
            }
        }
        out.append(String.format("계산(샘플링·계약) 평균 %.1fms | INSERT 평균 %.1fms | 전체 평균 %.1fms, 최대 %.1fms%n",
                avg(times, 0) / 1000, avg(times, 1) / 1000, avg(times, 2) / 1000, max(times, 2) / 1000));
        out.append(check).append(rosters);
        log.info(out.toString());
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** 커리어 능력치 - 마스터 능력치 차이: 평균(≈0 이어야 함), 표준편차, 최대 */
    private String samplingCheck(long careerId) {
        return jdbc.query("""
                SELECT AVG(CAST(c.contact AS SIGNED) - CAST(m.contact AS SIGNED)), STDDEV_POP(CAST(c.contact AS SIGNED) - CAST(m.contact AS SIGNED)), MAX(ABS(CAST(c.contact AS SIGNED) - CAST(m.contact AS SIGNED))),
                       AVG(CAST(c.control AS SIGNED) - CAST(m.control AS SIGNED)), STDDEV_POP(CAST(c.control AS SIGNED) - CAST(m.control AS SIGNED)),
                       AVG(CAST(c.speed AS SIGNED) - CAST(m.speed AS SIGNED)), STDDEV_POP(CAST(c.speed AS SIGNED) - CAST(m.speed AS SIGNED)),
                       AVG(CAST(c.potential AS SIGNED) - CAST(m.potential AS SIGNED)), STDDEV_POP(CAST(c.potential AS SIGNED) - CAST(m.potential AS SIGNED)),
                       AVG(c.adaptability), STDDEV_POP(c.adaptability)
                FROM c_player c JOIN mst_player_rating m USING (player_id)
                WHERE c.career_id = ?
                """, rs -> {
            rs.next();
            return String.format("""
                    샘플링 (커리어 - 마스터)  컨택 평균 %+.2f σ %.2f 최대 %.0f | 제구 %+.2f σ %.2f | 스피드 %+.2f σ %.2f | 잠재력 %+.2f σ %.2f
                    숨김 성격 (적응력)      평균 %.1f σ %.1f
                    """, rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                    rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10),
                    rs.getDouble(11));
        }, careerId);
    }

    /** 레벨·상태별 인원, 팀별 인원 범위, 연봉 합계 범위 */
    private String rosterCheck(long careerId, Map<Integer, Integer> payroll) {
        StringBuilder b = new StringBuilder("배치 (레벨/상태별 인원): ");
        jdbc.query("""
                SELECT level, roster_status, COUNT(*) FROM c_player WHERE career_id = ?
                GROUP BY level, roster_status ORDER BY level, roster_status
                """, rs -> {
            String lv = switch (rs.getInt(1)) {
                case 0 -> "MLB";
                case 1 -> "AAA";
                default -> "AA";
            };
            String st = switch (rs.getInt(2)) {
                case 0 -> "마이너계약";
                case 1 -> "40인";
                case 2 -> "26인";
                default -> "부상";
            };
            b.append(lv).append('-').append(st).append(' ').append(rs.getInt(3)).append("  ");
        }, careerId);
        b.append('\n');
        jdbc.query("""
                SELECT MIN(n), MAX(n), MIN(a), MAX(a) FROM (
                    SELECT team_id, SUM(level = 0 AND roster_status = 2) n, SUM(level > 0) a
                    FROM c_player WHERE career_id = ? GROUP BY team_id) t
                """, rs -> {
            b.append(String.format("팀별 MLB 26인 %d~%d명, 마이너(AAA+AA) %d~%d명%n",
                    rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
        }, careerId);
        List<Integer> pays = new ArrayList<>(payroll.values());
        pays.sort(null);
        b.append(String.format("팀 연봉 합계 (백만 달러) 최소 %.0f / 중앙 %.0f / 최대 %.0f%n",
                pays.getFirst() / 1000.0, pays.get(pays.size() / 2) / 1000.0, pays.getLast() / 1000.0));
        jdbc.query("""
                SELECT contract_type, COUNT(*), AVG(salary), MAX(salary) FROM c_player
                WHERE career_id = ? GROUP BY contract_type ORDER BY contract_type
                """, rs -> {
            String type = switch (rs.getInt(1)) {
                case 0 -> "마이너";
                case 1 -> "최저연봉";
                case 2 -> "연봉조정";
                default -> "FA계약";
            };
            b.append(String.format("  %s %d명 평균 %.2fM 최대 %.1fM%n", type, rs.getInt(2), rs.getDouble(3) / 1000,
                    rs.getDouble(4) / 1000));
        }, careerId);
        return b.toString();
    }

    private static double avg(List<long[]> t, int i) {
        return t.stream().mapToLong(a -> a[i]).average().orElse(0);
    }

    private static double max(List<long[]> t, int i) {
        return t.stream().mapToLong(a -> a[i]).max().orElse(0);
    }
}
