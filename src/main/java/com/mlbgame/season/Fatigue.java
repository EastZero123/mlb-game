package com.mlbgame.season;

import java.util.Map;

/**
 * 불펜 피로. 투수별 {마지막 등판일, 그날 투구 수, 최근 등판 비트마스크} 만 메모리에 둔다 (저장 0).
 * 비트마스크: bit k = 마지막 등판일로부터 k일 전에 등판 (bit 0 = 마지막 등판일).
 * <pre>
 * 오늘 이미 등판 (더블헤더)           → 휴식
 * 어제 20구 이상, 또는 어제·그제 연투  → 휴식
 * 그제 40구 이상                       → 휴식
 * 최근 6일 중 3번 등판                 → 휴식   (실제 상위 불펜 시즌 70~80경기 수준)
 * </pre>
 * 선발은 로테이션 순번으로 쉬므로 여기서 다루지 않는다.
 */
final class Fatigue {

    static final int DAY = 0, PITCHES = 1, MASK = 2;
    static final int HEAVY_YESTERDAY = 20;
    static final int HEAVY_TWO_DAYS = 40;
    static final int WINDOW = 6;
    static final int MAX_IN_WINDOW = 3;

    private Fatigue() {
    }

    static void record(Map<Integer, int[]> load, int pitcherId, long day, int pitches) {
        int[] a = load.get(pitcherId);
        if (a == null) {
            load.put(pitcherId, new int[] {(int) day, pitches, 1});
        } else if (a[DAY] == day) {
            a[PITCHES] += pitches;
        } else {
            int shift = (int) Math.min(31, day - a[DAY]);
            a[MASK] = (a[MASK] << shift | 1) & 0xFF;
            a[DAY] = (int) day;
            a[PITCHES] = pitches;
        }
    }

    static boolean available(Map<Integer, int[]> load, int pitcherId, long today) {
        int[] a = load.get(pitcherId);
        if (a == null) {
            return true;
        }
        long rest = today - a[DAY];
        if (rest <= 0) {
            return false;
        }
        if (rest == 1 && (a[PITCHES] >= HEAVY_YESTERDAY || (a[MASK] & 0b10) != 0)) {
            return false;
        }
        if (rest == 2 && a[PITCHES] >= HEAVY_TWO_DAYS) {
            return false;
        }
        return appearancesInWindow(a, today) < MAX_IN_WINDOW;
    }

    /** 오늘 기준 최근 WINDOW 일 (오늘 제외) 등판 횟수 */
    static int appearancesInWindow(int[] a, long today) {
        long rest = today - a[DAY];
        if (rest > WINDOW) {
            return 0;
        }
        int keep = (int) (WINDOW - rest + 1); // 마지막 등판일부터 거슬러 셀 일수
        return Integer.bitCount(a[MASK] & ((1 << keep) - 1));
    }
}
