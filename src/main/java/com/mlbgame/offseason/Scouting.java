package com.mlbgame.offseason;

/**
 * 스카우트 단계 (1~5): 높을수록 능력치 추정 범위가 좁다 (±12 / ±9 / ±6 / ±4 / ±2, 화면 ScoutView).
 * <pre>
 * 비용 (연, 1단계 대비, 다음 시즌 예산에서 차감)  1 $0 · 2 $2M · 3 $5M · 4 $9M · 5 $15M
 * 변경 기간: 오프시즌 1~4단계 (FA 시장 개장 전). 개장 뒤에는 다음 오프시즌까지 고정
 *           (드래프트용으로 올렸다가 FA 때 내려 예산을 늘리는 식의 반복 방지)
 * </pre>
 * 비용은 예산 자체를 줄이지 않고 내 팀 재계약·FA 제안의 예산 확인에서만 뺀다
 * (리그 시장 규모 = 구단 예산 합이라, 예산을 깎아 저장하면 해마다 비율이 조금씩 줄어드는 문제가 생김).
 * AI 구단은 스카우트 단계가 없다 (자체 추정 오차로 처리).
 */
public final class Scouting {

    public static final int MIN = 1, MAX = 5;
    private static final int[] COST = {0, 2_000, 5_000, 9_000, 15_000};

    private Scouting() {
    }

    /** 연 비용 (천 달러) */
    public static int cost(int level) {
        return COST[Math.clamp(level, MIN, MAX) - 1];
    }

    /** 오프시즌 끝낸 단계 수(done)로 변경 가능 여부: FA 시장 개장(5단계) 전까지 */
    public static boolean changeable(int phase, int done) {
        return phase == com.mlbgame.season.NextDayService.PHASE_OFFSEASON
                && done < OffseasonService.Stage.MARKET.no();
    }
}
