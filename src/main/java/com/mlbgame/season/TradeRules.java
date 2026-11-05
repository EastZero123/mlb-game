package com.mlbgame.season;

import java.util.List;

import com.mlbgame.career.ContractModel;

/**
 * 트레이드 판정 (DB 없음, {@link TradeService} 가 자료를 모아 호출). 금액 단위 천 달러.
 * <pre>
 * 선수 가치 = 남은 보유 기간 동안 Σ 0.9^t × (기대 시장가치 - 받을 연봉)          ("잉여 가치")
 *   보유 기간   FA 계약 = 남은 연수, 연봉조정 전·연봉조정 = 6 - 서비스, 마이너 계약 = 6 (최대 8년, 45세 시즌까지)
 *   기대 가치   해마다 나이로 예상: 26세 이하 잠재력 쪽으로 남은 차이의 25%, 27~30 유지, 31~33 -1, 34+ -2
 *   시장가치    가치 45 이상 ContractModel.marketValue, 미만은 최저연봉 × (가치-35)/10 (벤치급)
 *   연봉        FA 계약 = 계약 연봉, 연봉조정 전 = 최저, 연봉조정(서비스 3~5) = 시장가치 × 35/55/75%,
 *               마이너 계약 유망주는 MLB 수준(45)이 되기 전에는 가치·연봉 모두 0
 *   올해        시즌 중이면 남은 일정 비율만큼만 (트레이드 마감 직전 = 단기 임대)
 *   MLB 경력 없는 가치 45 미만 유망주는 실패 위험으로 ×0.6
 * 팀 상황 (승률)  윈나우 .530+  올해 ×1.5 / 리빌딩 .470 미만  올해 ×0.6, 연봉 부담 ×1.2, 25세 이하 ×1.15
 * 포지션 필요     받는 쪽 유형별 MLB 가능(45+) 40인이 13명 미만이면 MLB 가능 선수 ×1.1
 * AI 수락         받는 가치 ≥ 주는 가치 + max(30만, |주는 가치| × 7%)   (AI 도 이득을 봐야 함)
 * </pre>
 * AI 는 실제 능력치로 평가한다 (유저는 스카우트 범위만 보므로 스카우트가 좋을수록 저평가 선수를 찾기 쉽다).
 */
final class TradeRules {

    static final int MID = 0, WIN = 1, REBUILD = -1;
    static final int MAX_PER_SIDE = 5;
    static final double DISCOUNT = 0.9;
    static final int MARGIN_MIN = 300;
    static final double MARGIN = 0.07;
    static final int RETIRE_AGE = 45;

    private TradeRules() {
    }

    /**
     * 선수 1명.
     *
     * @param age      평가 시즌 나이 (시즌 중 = 올해, 오프시즌 = 다음 시즌)
     * @param value    현재 가치 (ContractModel.value)
     * @param fortyMan 40인 명단 (연봉이 구단 연봉 합계에 들어감)
     */
    record Asset(int id, int team, boolean pitcher, int age, double value, int potential, int contractType,
                 int years, int salary, int service, boolean fortyMan) {
    }

    /** @param capable 유형별(0 타자, 1 투수) 40인 중 MLB 가능(45+) 인원 */
    record TeamCtx(int team, int mode, int[] capable) {
    }

    record Verdict(boolean accept, double get, double give, String reason) {
    }

    static int mode(int wins, int losses) {
        int g = wins + losses;
        if (g < 10) {
            return MID;
        }
        double pct = wins / (double) g;
        return pct >= 0.530 ? WIN : pct < 0.470 ? REBUILD : MID;
    }

    /** 다음 해 예상 가치 */
    static double project(double v, int potential, int age) {
        if (age <= 26) {
            return v + Math.max(0, potential - v) * 0.25;
        }
        return age <= 30 ? v : age <= 33 ? v - 1 : v - 2;
    }

    /**
     * @param rate       연봉 단가 (가치 1점당 천 달러)
     * @param seasonLeft 올해 남은 비율 (오프시즌 1.0)
     */
    static double worth(Asset a, TeamCtx ctx, double rate, double seasonLeft) {
        int horizon = switch (a.contractType()) {
            case ContractModel.GUARANTEED -> a.years();
            case ContractModel.PRE_ARB, ContractModel.ARBITRATION -> 6 - a.service();
            default -> 6;
        };
        horizon = Math.clamp(horizon, 1, 8);
        horizon = Math.min(horizon, Math.max(1, RETIRE_AGE - a.age() + 1));
        boolean rebuild = ctx.mode() == REBUILD;
        double sum = 0;
        double v = a.value();
        for (int t = 0; t < horizon; t++) {
            int age = a.age() + t;
            if (t > 0) {
                v = project(v, a.potential(), age - 1);
            }
            double market = v >= 45 ? ContractModel.marketValue(v, age, rate)
                    : ContractModel.MIN_SALARY * Math.max(0, (v - 35) / 10);
            double salary;
            switch (a.contractType()) {
                case ContractModel.GUARANTEED -> salary = a.salary();
                case ContractModel.PRE_ARB, ContractModel.ARBITRATION -> {
                    int service = a.service() + t;
                    salary = service < 3 ? ContractModel.MIN_SALARY
                            : market * ContractModel.arbitrationShare(service);
                }
                default -> {
                    if (v < 45) {
                        market = 0; // 아직 마이너 (구단 연봉에도 안 들어감)
                        salary = 0;
                    } else {
                        salary = ContractModel.MIN_SALARY;
                    }
                }
            }
            double weight = Math.pow(DISCOUNT, t);
            if (t == 0) {
                weight *= seasonLeft * (ctx.mode() == WIN ? 1.5 : rebuild ? 0.6 : 1.0);
            }
            sum += weight * (market - (rebuild ? 1.2 : 1.0) * salary);
        }
        if (a.service() == 0 && a.value() < 45) {
            sum *= 0.6;
        }
        if (sum > 0) {
            if (rebuild && a.age() <= 25) {
                sum *= 1.15;
            }
            if (a.value() >= 45 && ctx.capable()[a.pitcher() ? 1 : 0] < 13) {
                sum *= 1.1;
            }
        }
        return sum;
    }

    /**
     * AI 구단 판정.
     *
     * @param aiGets  AI 가 받는 선수 (유저가 주는 선수)
     * @param aiGives AI 가 주는 선수
     */
    static Verdict judge(List<Asset> aiGets, List<Asset> aiGives, TeamCtx ai, double rate, double seasonLeft) {
        double get = 0;
        for (Asset a : aiGets) {
            get += worth(a, ai, rate, seasonLeft);
        }
        double give = 0;
        for (Asset a : aiGives) {
            give += worth(a, ai, rate, seasonLeft);
        }
        double need = give + Math.max(MARGIN_MIN, Math.abs(give) * MARGIN);
        if (get >= need) {
            return new Verdict(true, get, give, "수락");
        }
        double close = Math.max(1_500, Math.abs(give) * 0.15);
        return new Verdict(false, get, give, get >= need - close ? "가치가 조금 부족합니다" : "가치가 많이 부족합니다");
    }
}
