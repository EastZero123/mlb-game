package com.mlbgame.season;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모아 둔 마이너 기록({@link PendingStats})을 저장한다: 커리어가 캐시에서 내려갈 때(오래 안 씀·상한·명시적 내림)와 서버 종료 때.
 * 진행("다음 날")과 같은 커리어 잠금을 쓴다. 실패하면 경고만 남긴다 (마이너 기록 일부가 빠질 뿐 진행 상태는 영향 없음).
 */
@Component
class StatsFlusher {

    private static final Logger log = LoggerFactory.getLogger(StatsFlusher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    StatsFlusher(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    void flush(CareerRuntime rt) {
        rt.lock.lock();
        try {
            if (rt.pending.isEmpty()) {
                return;
            }
            List<Object[]> bat = NextDayService.rows(rt.careerId, rt.season, rt.pending.bat);
            List<Object[]> pit = NextDayService.rows(rt.careerId, rt.season, rt.pending.pit);
            tx.executeWithoutResult(st -> {
                if (!bat.isEmpty()) {
                    jdbc.batchUpdate(NextDayService.BAT_UPSERT, bat);
                }
                if (!pit.isEmpty()) {
                    jdbc.batchUpdate(NextDayService.PIT_UPSERT, pit);
                }
            });
            log.info("op=flush career={} days={} rows={}", rt.careerId, rt.pending.days, bat.size() + pit.size());
            rt.pending.clear();
        } catch (RuntimeException e) {
            log.warn("op=flush career={} 마이너 기록 저장 실패: {}", rt.careerId, e.toString());
        } finally {
            rt.lock.unlock();
        }
    }
}
