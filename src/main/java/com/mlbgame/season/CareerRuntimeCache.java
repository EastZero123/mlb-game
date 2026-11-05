package com.mlbgame.season;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.annotation.PreDestroy;

/**
 * 진행 중인 커리어 메모리 캐시. 커리어 1개 ≈ 1~2MB (선수 카드·확률표·팀 구성).
 * 오래 쓰지 않은 커리어는 내리고, 다음 요청 때 DB 에서 다시 만든다 (저사양 서버 메모리 보호).
 * 내릴 때(이유와 관계없이)와 서버 종료 때 모아 둔 마이너 기록을 저장한다 ({@link StatsFlusher}).
 * 내림 처리는 호출한 스레드에서 바로 실행해, evict() 가 끝나면 저장도 끝나 있다.
 */
@Component
public class CareerRuntimeCache {

    private final Cache<Long, CareerRuntime> cache;
    private final CareerRuntimeLoader loader;

    CareerRuntimeCache(CareerRuntimeLoader loader, StatsFlusher flusher,
                       @Value("${game.cache.max-careers:32}") int maxCareers,
                       @Value("${game.cache.idle-minutes:30}") int idleMinutes) {
        this.loader = loader;
        this.maxCareers = maxCareers;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxCareers)
                .expireAfterAccess(Duration.ofMinutes(idleMinutes))
                .recordStats() // 적중·적재·내보냄 횟수 (부하 점검, 비용 거의 없음)
                .executor(Runnable::run) // 내림 처리를 호출 스레드에서 (저장이 끝난 뒤 evict 반환)
                .removalListener((Long id, CareerRuntime rt, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (rt != null) {
                        flusher.flush(rt);
                    }
                })
                .build();
    }

    private final int maxCareers;

    /**
     * 캐시 현황 (서버 시작 후 누적).
     *
     * @param loaded    지금 메모리에 있는 커리어 수, max = 상한
     * @param avgLoadMs 커리어 1개 적재 평균 시간
     */
    public record Stats(long loaded, int max, long hits, long misses, long evictions, double avgLoadMs) {

        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 1.0 : hits / (double) total;
        }
    }

    public Stats stats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = cache.stats();
        return new Stats(cache.estimatedSize(), maxCareers, s.hitCount(), s.missCount(), s.evictionCount(),
                s.averageLoadPenalty() / 1e6);
    }

    CareerRuntime get(long careerId) {
        return cache.get(careerId, loader::load);
    }

    boolean isLoaded(long careerId) {
        return cache.getIfPresent(careerId) != null;
    }

    public void evict(long careerId) {
        cache.invalidate(careerId);
    }

    /** 서버 종료: 모든 커리어를 내려 모아 둔 마이너 기록을 저장 */
    @PreDestroy
    void close() {
        cache.invalidateAll();
        cache.cleanUp();
    }
}
