package com.mlbgame.ops;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.mlbgame.season.CareerRuntimeCache;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 서버 현황 주기 로그 (부하 점검용, 웹 서버 실행 때만). 기본 5분마다 한 줄:
 * <pre>
 * stats  careers=7/32 hit=0.97 loads=12 evict=3 loadAvg=820ms heap=312/768MB db=1/6 wait=0
 * </pre>
 * careers = 메모리에 올라온 커리어 / 상한 (game.cache.max-careers), hit = 캐시 적중률, loads·evict = 누적 적재·내보냄,
 * heap = 사용/최대 힙, db = 사용 중/전체 커넥션, wait = 커넥션 대기 스레드 (0 보다 크면 풀이 모자람).
 * 같은 값을 지표 game.cache.careers 로도 내보낸다. 주기: app.ops.stats-interval (기본 PT5M).
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
public class OpsStats {

    private static final Logger log = LoggerFactory.getLogger("stats");

    private final CareerRuntimeCache cache;
    private final DataSource dataSource;

    public OpsStats(CareerRuntimeCache cache, DataSource dataSource, MeterRegistry registry) {
        this.cache = cache;
        this.dataSource = dataSource;
        Gauge.builder("game.cache.careers", cache, c -> c.stats().loaded())
                .description("메모리에 올라온 커리어 수").register(registry);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.ops.stats-interval:PT5M}")
    public void log() {
        CareerRuntimeCache.Stats s = cache.stats();
        Runtime jvm = Runtime.getRuntime();
        long used = (jvm.totalMemory() - jvm.freeMemory()) / 1_048_576;
        long max = jvm.maxMemory() / 1_048_576;
        String db = "db=?";
        if (dataSource instanceof HikariDataSource h) {
            HikariPoolMXBean pool = h.getHikariPoolMXBean();
            if (pool != null) {
                db = String.format("db=%d/%d wait=%d", pool.getActiveConnections(), pool.getTotalConnections(),
                        pool.getThreadsAwaitingConnection());
            }
        }
        log.info(String.format("careers=%d/%d hit=%.2f loads=%d evict=%d loadAvg=%.0fms heap=%d/%dMB %s",
                s.loaded(), s.max(), s.hitRate(), s.misses(), s.evictions(), s.avgLoadMs(), used, max, db));
    }
}
