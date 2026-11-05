package com.mlbgame.career;

import java.util.SplittableRandom;

/**
 * 계약 자동 산정 (공식 API 에 계약 데이터가 없음). MLB 서비스 타임 구조를 단순화:
 * <pre>
 * 마이너 계약 (40인 외)        연봉 7.5만 달러 고정 (구단 예산에 넣지 않음), 1~3년
 * 서비스 0~2년 (연봉조정 전)    최저연봉 78만 달러 고정, 보유 연수 = 6 - 서비스
 * 서비스 3~5년 (연봉조정)       시장가치 × 35/55/75%, 보유 연수 = 6 - 서비스
 * 서비스 6년 이상 (FA 계약)     시장가치, 잔여 1~6년 (젊고 뛰어날수록 길게)
 * 시장가치 = 최저연봉 + 단가 × 초과가치 × 노장 할인, 상한 6,500만 달러
 * 초과가치 = max(0, 가치 - 대체선수 수준 45) + 슈퍼스타 가산 0.1 × max(0, 가치 - 65)²  (가치 75 +10, 80 +22.5)
 * </pre>
 * 실제 연봉은 대체선수 대비 기여(WAR)에 거의 비례하므로 가치에 선형. 단가는 상수로 정하지 않고
 * {@link #calibrateRate} 가 "40인 연봉 합계 = 실제 리그 총 연봉" 이 되도록 계산한다.
 * 연봉 단위는 천 달러.
 */
public final class ContractModel {

    public static final int MIN_SALARY = 780;     // 2026 MLB 최저연봉 78만 달러
    /** 마이너 계약 연봉 (천 달러). 구단 예산·연봉 합계에는 넣지 않는다 (40인 연봉만 합산) */
    public static final int MINOR_SALARY = 75;
    public static final int MAX_SALARY = 65_000;
    /** 리그 40인 연봉 총액 목표 (천 달러). 2025 개막 기준 약 48억 달러 */
    public static final long LEAGUE_PAYROLL = 4_800_000;
    static final double REPLACEMENT = 45;
    /** 슈퍼스타 가산 시작 가치와 계수 (최상위 몇 명만 몸값이 가파르게 오르도록 제곱) */
    public static final double STAR_LINE = 65;
    static final double STAR_K = 0.1;
    private static final double[] ARB_SHARE = {0.35, 0.55, 0.75};

    public static final int MINOR = 0, PRE_ARB = 1, ARBITRATION = 2, GUARANTEED = 3;

    public record Contract(int type, int years, int salary) {
    }

    private ContractModel() {
    }

    /**
     * 계약은 마스터 능력치(실제 세계의 평가) 기준이라 커리어마다 같은 선수면 연봉이 같다 (FA 잔여 연수만 무작위).
     *
     * @param rate {@link #calibrateRate} 결과 (가치 1점당 천 달러)
     */
    public static Contract contract(MasterPlayer p, int season, double rate, SplittableRandom rng) {
        if (p.rosterStatus() == 0) {
            return new Contract(MINOR, 1 + rng.nextInt(3), MINOR_SALARY);
        }
        int service = p.serviceYears();
        if (service < 3) {
            return new Contract(PRE_ARB, 6 - service, preArbSalary(service));
        }
        double market = marketValue(value(p), p.age(season), rate);
        if (service < 6) {
            return new Contract(ARBITRATION, 6 - service, arbitrationSalary(market, service));
        }
        return new Contract(GUARANTEED, freeAgentYears(p.age(season), value(p), rng), (int) Math.round(market));
    }

    /**
     * 40인 연봉 합계가 {@link #LEAGUE_PAYROLL} 이 되는 단가.
     * <pre>
     * 목표 = Σ최저연봉류 + Σ연봉조정 비율 × (최저 + 단가 × 초과가치) + ΣFA (최저 + 단가 × 초과가치)
     * </pre>
     * 상한·하한 clamp 는 무시한 근사 (상한에 걸리는 선수가 많으면 합계가 목표보다 약간 작아진다).
     */
    public static double calibrateRate(Iterable<MasterPlayer> players, int season) {
        double fixed = 0;
        double flexible = 0;
        for (MasterPlayer p : players) {
            if (p.rosterStatus() == 0) {
                continue;
            }
            int service = p.serviceYears();
            if (service < 3) {
                fixed += preArbSalary(service);
                continue;
            }
            double share = service < 6 ? ARB_SHARE[service - 3] : 1.0;
            fixed += share * MIN_SALARY;
            flexible += share * excess(value(p), p.age(season));
        }
        return flexible > 0 ? Math.max(0, (LEAGUE_PAYROLL - fixed) / flexible) : 0;
    }

    /** 천 달러 */
    public static double marketValue(double value, int age, double rate) {
        return Math.clamp(MIN_SALARY + rate * excess(value, age), MIN_SALARY, MAX_SALARY);
    }

    /** (대체선수 대비 초과 가치 + 슈퍼스타 가산) × 노장 할인 (34세 이상 ×0.75, 37세 이상 ×0.5) */
    public static double excess(double value, int age) {
        double star = Math.max(0, value - STAR_LINE);
        double e = Math.max(0, value - REPLACEMENT) + STAR_K * star * star;
        if (age >= 37) {
            return e * 0.5;
        }
        return age >= 34 ? e * 0.75 : e;
    }

    /** 연봉조정 전: 서비스 연차와 관계없이 최저연봉 고정 */
    public static int preArbSalary(int service) {
        return MIN_SALARY;
    }

    /**
     * 프로 경력 연차 추정 (기록이 없는 기존 선수용): 19세 입단 가정, 0~15.
     * 마이너 6년 동안 MLB 경력이 없으면 계약 만료 때 방출 (오프시즌 1단계) 에 쓴다.
     */
    public static int proSeasonsEstimate(int age) {
        return Math.clamp(age - 19, 0, 15);
    }

    /** 연봉조정 3~5년차: 시장가치의 35/55/75% */
    public static double arbitrationShare(int service) {
        return ARB_SHARE[Math.clamp(service, 3, 5) - 3];
    }

    public static int arbitrationSalary(double market, int service) {
        return (int) Math.max(MIN_SALARY, Math.round(market * arbitrationShare(service)));
    }

    /** FA 계약 기간: 30세 이하·가치 60 이상 2~6년, 33세 이하 1~3년, 그 외 1~2년 */
    public static int freeAgentYears(int age, double value, SplittableRandom rng) {
        if (age <= 30 && value >= 60) {
            return 2 + rng.nextInt(5);
        }
        return age <= 33 ? 1 + rng.nextInt(3) : 1 + rng.nextInt(2);
    }

    static double value(MasterPlayer p) {
        return value(p.ratings(), p.primaryPos());
    }

    /**
     * 선수 가치 (20~80 스케일). 타자: 공격 + 포지션 가중 + 수비. 투수: 구위·제구·억제 + 역할(선발 우대).
     *
     * @param r {@link MasterData#RATING_COLUMNS} 순서, NULL = -1
     */
    public static double value(short[] r, int primaryPos) {
        boolean pitcher = r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || primaryPos == 1);
        if (pitcher) {
            double q = 0.4 * stuff(r) + 0.3 * rating(r, MasterData.CONTROL) + 0.3 * rating(r, MasterData.SUPPRESSION);
            boolean starter = rating(r, MasterData.STAMINA) >= 50;
            return q + (starter ? 2 : -3);
        }
        double offense = 0.30 * rating(r, MasterData.CONTACT) + 0.30 * rating(r, MasterData.POWER)
                + 0.20 * rating(r, MasterData.EYE) + 0.20 * rating(r, MasterData.QUALITY);
        double premium = switch (primaryPos) {
            case 2, 6 -> 4;
            case 8 -> 3;
            case 4 -> 2;
            case 5 -> 1.5;
            case 3 -> -2;
            case 10 -> -4;
            default -> 0;
        };
        double def = primaryPos >= 2 && primaryPos <= 9 && r[MasterData.DEF_C + primaryPos - 2] >= 0
                ? r[MasterData.DEF_C + primaryPos - 2] : 45;
        return offense + premium + 0.3 * (def - 50);
    }

    /** 구사율 가중 평균 구위 */
    public static double stuff(short[] r) {
        double use = 0;
        double sum = 0;
        for (int k = 0; k < MasterData.PITCH_TYPES; k++) {
            int u = r[MasterData.FIRST_PITCH + 2 * k];
            int s = r[MasterData.FIRST_PITCH + 2 * k + 1];
            if (u > 0 && s >= 0) {
                use += u;
                sum += u * s;
            }
        }
        return use > 0 ? sum / use : 40;
    }

    private static double rating(short[] r, int column) {
        return r[column] >= 0 ? r[column] : 40;
    }
}
