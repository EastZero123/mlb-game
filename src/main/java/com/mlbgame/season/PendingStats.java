package com.mlbgame.season;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 아직 저장하지 않은 마이너 리그(AAA·AA) 시즌 기록 (커리어 메모리 상태 안). 저장량 줄이기:
 * <pre>
 * MLB 기록은 매일 저장 (화면에서 바로 보는 기록), 마이너 기록은 메모리에 합산해 두었다가
 *   7일마다 / 단계가 바뀌는 날(정규시즌 끝·포스트시즌 끝) / 커리어가 캐시에서 내려갈 때 / 서버 종료 때 한 번에 저장.
 * 하루 저장 행이 약 1/3 로 줄고 (마이너 경기가 MLB 의 2배), 모아 저장할 때도 같은 선수는 1행.
 * </pre>
 * 하루 처리가 실패하면 그날 몫은 더하지 않는다 (DB 에 저장되지 않은 날의 기록이 섞이지 않도록 성공한 뒤에만 합산).
 * 서버가 갑자기 죽으면 최대 6일치 마이너 기록이 사라질 수 있다 (경기 결과·순위·MLB 기록은 매일 저장되어 영향 없음).
 * 배열 형식은 {@link DayBatch} 와 같다: [구단, 값...] (구단은 마지막 값으로 덮어쓰고 나머지는 더함).
 */
final class PendingStats {

    static final int FLUSH_DAYS = 7;

    final Map<Long, int[]> bat = new HashMap<>(2048);
    final Map<Long, int[]> pit = new HashMap<>(1024);
    int days;

    boolean isEmpty() {
        return bat.isEmpty() && pit.isEmpty();
    }

    /** 오늘 배치에서 마이너 행을 떼어 낸다 (배치에는 MLB 행만 남음) */
    static Map<Long, int[]> takeMinor(Map<Long, int[]> today) {
        Map<Long, int[]> out = new HashMap<>();
        for (Iterator<Map.Entry<Long, int[]>> it = today.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, int[]> e = it.next();
            if (DayBatch.level(e.getKey()) > 0) {
                out.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return out;
    }

    /** acc 에 add 를 합산 (구단은 덮어쓰기, 나머지 더하기). acc 가 바뀐다 */
    static void merge(Map<Long, int[]> acc, Map<Long, int[]> add) {
        add.forEach((key, a) -> acc.merge(key, a.clone(), (old, n) -> {
            old[0] = n[0];
            for (int i = 1; i < old.length; i++) {
                old[i] += n[i];
            }
            return old;
        }));
    }

    /** 지금까지 모은 것 + 오늘 몫 (저장할 행, 모은 상태는 바꾸지 않음) */
    static Map<Long, int[]> combined(Map<Long, int[]> pending, Map<Long, int[]> today) {
        Map<Long, int[]> out = new HashMap<>(pending.size() + today.size());
        merge(out, pending);
        merge(out, today);
        return out;
    }

    /** 오늘 저장이 성공한 뒤: 모아 두기 */
    void add(Map<Long, int[]> todayBat, Map<Long, int[]> todayPit) {
        merge(bat, todayBat);
        merge(pit, todayPit);
        days++;
    }

    void clear() {
        bat.clear();
        pit.clear();
        days = 0;
    }

    /** 오늘 함께 저장해야 하나: 7일째이거나 단계가 바뀌는 날 */
    boolean due(boolean phaseChanged) {
        return phaseChanged || days + 1 >= FLUSH_DAYS;
    }
}
