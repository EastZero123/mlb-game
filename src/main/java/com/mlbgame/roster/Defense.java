package com.mlbgame.roster;

/** 수비 능력치 파생 (임포터·생성 선수 공통). */
public final class Defense {

    private Defense() {
    }

    /**
     * 주 포지션 수비 능력치 → 8개 포지션 [C, 1B, 2B, 3B, SS, LF, CF, RF]. 이동 난이도만큼 깎아 다른 포지션 값을 파생.
     * 포지션이 2~9 가 아니면(지명타자 등) 1루만 주어진 값으로 가능. 소화 불가 = null.
     */
    public static Integer[] derive(int pos, int rating) {
        Integer[] d = new Integer[8];
        if (pos < 2 || pos > 9) {
            set(d, 3, rating);
            return d;
        }
        set(d, pos, rating);
        int[][] derive = switch (pos) {
            case 6 -> new int[][] {{4, -2}, {5, -4}, {3, -10}};
            case 4 -> new int[][] {{6, -8}, {5, -6}, {3, -6}};
            case 5 -> new int[][] {{3, -4}, {4, -8}, {6, -12}};
            case 8 -> new int[][] {{7, 3}, {9, 2}};
            case 7 -> new int[][] {{9, -3}, {8, -10}, {3, -10}};
            case 9 -> new int[][] {{7, 0}, {8, -8}, {3, -10}};
            case 2 -> new int[][] {{3, -15}};
            default -> new int[0][];
        };
        for (int[] x : derive) {
            set(d, x[0], rating + x[1]);
        }
        return d;
    }

    private static void set(Integer[] d, int pos, int rating) {
        int i = pos - 2;
        int v = Math.clamp(rating, 20, 80);
        if (d[i] == null || d[i] < v) {
            d[i] = v;
        }
    }
}
