package com.mlbgame.ops;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 무거운 작업의 처리 시간 기록 (부하 점검용). 같은 형식의 한 줄 로그 + Micrometer 타이머 {@code game.op{op=...}}.
 * <pre>
 * 로그   ops  op=day career=12 ms=41 sim=18 db=19 games=15        (로거 이름 "ops", grep "op=day" 로 모으기)
 *        느린 작업(app.ops.slow-ms, 기본 1000ms)은 WARN
 * 지표   /actuator/metrics/game.op?tag=op:day   (관리 포트, localhost 전용) → 횟수·합계·최대
 *        /actuator/metrics/game.conflict        동시에 눌러 한쪽이 실패한 횟수
 * </pre>
 * 작업 이름: day(다음 날), stage(오프시즌 단계), fa-round(FA 라운드), trade(트레이드 제안), load(커리어 메모리 적재)
 */
@Component
public class Ops {

    private static final Logger log = LoggerFactory.getLogger("ops");

    private final MeterRegistry registry;
    private final long slowMs;

    public Ops(MeterRegistry registry, @Value("${app.ops.slow-ms:1000}") long slowMs) {
        this.registry = registry;
        this.slowMs = slowMs;
    }

    /**
     * @param nanos  걸린 시간 (System.nanoTime 차이)
     * @param detail "sim=18 db=19 games=15" 처럼 key=value 나열 (없으면 빈 문자열)
     */
    public void record(String op, long careerId, long nanos, String detail) {
        registry.timer("game.op", "op", op).record(nanos, TimeUnit.NANOSECONDS);
        long ms = nanos / 1_000_000;
        if (ms >= slowMs) {
            log.warn("op={} career={} ms={} {} slow", op, careerId, ms, detail);
        } else if (log.isInfoEnabled()) {
            log.info("op={} career={} ms={} {}", op, careerId, ms, detail);
        }
    }

    /** 동시에 눌러 한쪽이 실패 (version 불일치) */
    public void conflict(String op, long careerId) {
        registry.counter("game.conflict", "op", op).increment();
        log.info("op={} career={} conflict", op, careerId);
    }
}
