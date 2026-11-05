package com.mlbgame.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

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
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * 동시 부하 벤치마크: 커리어 N개를 만들어 스레드 T개가 동시에 "다음 날"을 D일씩 반복한다 (여러 유저가 동시에 진행하는 상황).
 * HTTP·로그인 없이 서버 자체의 감당 능력(CPU·메모리·DB)을 잰다. 끝나면 만든 커리어를 모두 삭제한다.
 * <pre>
 * ./gradlew bootRun --args="--spring.profiles.active=local,load-bench --spring.main.web-application-type=none
 *     --bench.careers=8 --bench.threads=8 --bench.days=30"
 * </pre>
 * 출력: 커리어 적재 시간·커리어당 메모리, 전체 처리량(초당 처리 일수), 하루 처리 시간 분포(중앙·95%·최대),
 * 최대 힙, DB 커넥션 최대 사용·최대 대기 스레드. careers 는 캐시 상한(game.cache.max-careers, 기본 32) 이하로.
 */
@Component
@Profile("load-bench")
class LoadBenchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadBenchRunner.class);
    /** 벤치마크 커리어용 가상 사용자 ID 시작값 (app_user 와 FK 없음, 슬롯 1 하나씩) */
    private static final int BENCH_USER_BASE = 900_000_000;

    private final CareerCreator creator;
    private final NextDayService nextDay;
    private final CareerRuntimeCache cache;
    private final MasterData master;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ConfigurableApplicationContext context;
    private final int careers;
    private final int threads;
    private final int days;

    LoadBenchRunner(CareerCreator creator, NextDayService nextDay, CareerRuntimeCache cache, MasterData master,
                    JdbcTemplate jdbc, DataSource dataSource, ConfigurableApplicationContext context,
                    @Value("${bench.careers:8}") int careers, @Value("${bench.threads:0}") int threads,
                    @Value("${bench.days:30}") int days) {
        this.creator = creator;
        this.nextDay = nextDay;
        this.cache = cache;
        this.master = master;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.context = context;
        this.careers = careers;
        this.threads = threads <= 0 ? careers : threads;
        this.days = days;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        purge();
        MasterData.Snapshot snap = master.get();
        List<Integer> teams = snap.teamIds();
        List<Long> ids = new ArrayList<>();
        long c0 = System.nanoTime();
        for (int i = 0; i < careers; i++) {
            ids.add(creator.create(BENCH_USER_BASE + i, 1, teams.get(i % teams.size()), 1000L + i).careerId());
        }
        double createSec = (System.nanoTime() - c0) / 1e9;

        Runtime jvm = Runtime.getRuntime();
        System.gc();
        long heap0 = jvm.totalMemory() - jvm.freeMemory();
        long l0 = System.nanoTime();
        for (long id : ids) {
            cache.get(id);
        }
        double loadMs = (System.nanoTime() - l0) / 1e6 / careers;
        System.gc();
        long heap1 = jvm.totalMemory() - jvm.freeMemory();

        // 진행 중 최대 힙·커넥션 사용·대기 표본 (100ms 마다)
        AtomicLong peakHeap = new AtomicLong(heap1);
        AtomicInteger peakActive = new AtomicInteger();
        AtomicInteger peakWait = new AtomicInteger();
        HikariPoolMXBean pool = dataSource instanceof HikariDataSource h ? h.getHikariPoolMXBean() : null;
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peakHeap.accumulateAndGet(jvm.totalMemory() - jvm.freeMemory(), Math::max);
                if (pool != null) {
                    peakActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                    peakWait.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        List<Long> dayMs = Collections.synchronizedList(new ArrayList<>());
        AtomicLong simMicros = new AtomicLong();
        AtomicLong dbMicros = new AtomicLong();
        AtomicInteger errors = new AtomicInteger();
        long t0 = System.nanoTime();
        try (ExecutorService pool2 = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (long id : ids) {
                futures.add(pool2.submit(() -> {
                    for (int d = 0; d < days; d++) {
                        long s = System.nanoTime();
                        try {
                            NextDayService.DayReport r = nextDay.advance(id);
                            simMicros.addAndGet(r.simMicros());
                            dbMicros.addAndGet(r.dbMicros());
                        } catch (RuntimeException e) {
                            errors.incrementAndGet();
                            log.warn("커리어 {} {}일째 오류: {}", id, d + 1, e.toString());
                        }
                        dayMs.add((System.nanoTime() - s) / 1_000_000);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        double wallSec = (System.nanoTime() - t0) / 1e9;
        sampler.interrupt();

        List<Long> sorted = new ArrayList<>(dayMs);
        sorted.sort(null);
        int n = sorted.size();
        StringBuilder out = new StringBuilder();
        out.append(String.format("%n===== 동시 부하 벤치마크: 커리어 %d개, 스레드 %d개, 커리어당 %d일 =====%n", careers, threads, days));
        out.append(String.format("커리어 생성 %.1f초 (1개 평균 %.2f초) | 메모리 적재 1개 평균 %.0fms, 커리어당 메모리 약 %.1fMB%n",
                createSec, createSec / careers, loadMs, (heap1 - heap0) / 1048576.0 / careers));
        out.append(String.format("전체 %d일 처리 %.1f초 → 초당 %.1f일 (커리어 1개 기준 정규시즌 186일 ≈ %.0f초, 동시 %d개일 때)%n",
                n, wallSec, n / wallSec, 186 * wallSec / Math.max(1, days), careers));
        out.append(String.format("하루 처리 (대기 포함)  중앙 %dms | 95%% %dms | 최대 %dms | 평균 시뮬 %.1fms · DB %.1fms%n",
                pct(sorted, 0.5), pct(sorted, 0.95), pct(sorted, 1.0), simMicros.get() / 1000.0 / Math.max(1, n),
                dbMicros.get() / 1000.0 / Math.max(1, n)));
        out.append(String.format("최대 힙 %.0fMB (JVM 최대 %.0fMB) | DB 커넥션 최대 사용 %d / 최대 대기 %d | 오류 %d건%n",
                peakHeap.get() / 1048576.0, jvm.maxMemory() / 1048576.0, peakActive.get(), peakWait.get(),
                errors.get()));
        log.info(out.toString());

        for (long id : ids) {
            cache.evict(id);
        }
        purge();
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** 이전 실행이 중간에 죽어 남은 벤치마크 커리어까지 정리 */
    private void purge() {
        for (Long id : jdbc.queryForList("SELECT career_id FROM career WHERE user_id >= ?", Long.class,
                BENCH_USER_BASE)) {
            creator.purge(id);
        }
    }

    private static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * (sorted.size() - 1))));
    }
}
