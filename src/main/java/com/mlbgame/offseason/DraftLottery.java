package com.mlbgame.offseason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 드래프트 순서 (2023 노사협약 방식 근사).
 * <ul>
 *   <li>1라운드 1~6순위: 포스트시즌 미진출 구단 추첨. 성적 역순 확률 16.5% ×3, 13.25, 10, 7.5, 5.5, 3.9, 2.7, 1.8, 1.4,
 *       1.1, 0.9, 0.8, 0.6, 0.4, 0.2, 0.2 (남은 구단끼리 비율 유지하며 한 장씩 뽑음)</li>
 *   <li>1라운드 7순위부터: 추첨에서 안 뽑힌 미진출 구단 성적 역순 → 포스트시즌 진출 구단 성적 역순</li>
 *   <li>2~10라운드: 전 구단 성적 역순 (추첨 없음)</li>
 * </ul>
 * 성적 역순 = 승 적은 팀 먼저, 같으면 득실차 낮은 팀, 같으면 구단 ID. 추첨 난수는 커리어 seed·시즌에서 파생 (재현 가능).
 * 생략: 같은 구단 연속 로터리 제한, 보상 지명권, 지명권 박탈.
 */
public final class DraftLottery {

    static final int LOTTERY_PICKS = 6;
    /** 미진출 구단 성적 역순 확률 (%) */
    static final double[] ODDS = {16.5, 16.5, 16.5, 13.25, 10.0, 7.5, 5.5, 3.9, 2.7, 1.8, 1.4, 1.1, 0.9, 0.8, 0.6,
            0.4, 0.2, 0.2};

    /**
     * @param preRank 1라운드 추첨 대상이면 추첨 전 순위 (1 = 최저 성적), 아니면 0
     */
    public record Pick(int pickNo, int round, int teamId, int preRank) {
    }

    private DraftLottery() {
    }

    /**
     * @param standings 구단 → {승, 패, 득점, 실점}
     * @param playoff   포스트시즌 진출 구단
     */
    static List<Pick> order(Map<Integer, int[]> standings, Set<Integer> playoff, long seed, int season, int rounds) {
        List<Integer> reverse = OffseasonRules.draftOrder(standings);
        List<Integer> out = new ArrayList<>(reverse.size());
        List<Integer> eligible = new ArrayList<>();
        for (int t : reverse) {
            if (!playoff.contains(t)) {
                eligible.add(t);
            }
        }
        // 추첨: 남은 구단 중 확률 비례로 한 장씩
        SplittableRandom rng = OffseasonRules.rng(seed, season, 11, 0);
        List<Integer> remaining = new ArrayList<>(eligible);
        for (int k = 0; k < LOTTERY_PICKS && !remaining.isEmpty(); k++) {
            double total = 0;
            for (int t : remaining) {
                total += weight(eligible.indexOf(t));
            }
            double x = rng.nextDouble() * total;
            int chosen = remaining.getLast();
            for (int t : remaining) {
                x -= weight(eligible.indexOf(t));
                if (x < 0) {
                    chosen = t;
                    break;
                }
            }
            out.add(chosen);
            remaining.remove((Integer) chosen);
        }
        out.addAll(remaining);
        for (int t : reverse) {
            if (playoff.contains(t)) {
                out.add(t);
            }
        }

        List<Pick> picks = new ArrayList<>(reverse.size() * rounds);
        int no = 1;
        for (int t : out) {
            int pre = eligible.indexOf(t);
            picks.add(new Pick(no++, 1, t, pre >= 0 ? pre + 1 : 0));
        }
        for (int round = 2; round <= rounds; round++) {
            for (int t : reverse) {
                picks.add(new Pick(no++, round, t, 0));
            }
        }
        return picks;
    }

    private static double weight(int rank) {
        return rank < ODDS.length ? ODDS[rank] : ODDS[ODDS.length - 1];
    }

    /** 지명 순서대로 구단 ID */
    static List<Integer> teams(List<Pick> picks) {
        return picks.stream().map(Pick::teamId).toList();
    }
}
