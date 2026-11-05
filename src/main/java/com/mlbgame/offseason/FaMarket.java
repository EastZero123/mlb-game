package com.mlbgame.offseason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.mlbgame.career.ContractModel;

/**
 * FA 라운드 입찰 규칙 (DB 없음, {@link FaService} 가 상태를 읽고 저장). 6라운드.
 * <pre>
 * 시장 개장: 가치 46+ FA 마다
 *   시장가치 m   = ContractModel.marketValue (슈퍼스타 가산 포함)
 *   요구액       = m × (1.0 + 0.3 × clamp((가치-55)/20, 0, 1) ± 5%)   대어일수록 높게 부름 (화면에 보임)
 *   최저선       = m × 0.85~1.00                                       숨김, 마지막 라운드 판단
 *   원하는 연수  = 27세 이하·가치 65+ 10~15년, 30세 이하·가치 60+ 6~9년, 33세 이하 2~4년, 그 외 1~2년 (45세 시즌까지)
 *   버티기       = 가치 65+ 2~4라운드, 58+ 1~2라운드, 그 외 1
 * 제안 점수 = 연봉 × 연수 보정 (원하는 연수보다 1년 짧을 때마다 -4%, 길면 1년당 +1%, 최소 0.6)
 * 라운드마다 (가치 높은 순):
 *   AI 구단: 유형별 MLB 가능(45+) 선수 부족 또는 최하위보다 3 이상 좋으면 관심.
 *            상한 = m × (0.95 + 0.06×부족(최대 5) + 0.015×개선폭(최대 20) + 0.2×구단별 무작위), 예산 여유 안에서.
 *            첫 제안 = max(최저선, 요구액 × 0.92), 지난 라운드에 밀렸으면 max(최고 제안 × 1.05, 요구액 × 0.95)
 *            (버티기 라운드가 지났으면 요구액까지), 모두 상한 안에서
 *   선수: 최고 점수 ≥ 요구액 × 1.15  → 즉시 계약
 *         마지막 라운드              → 최고 점수 ≥ 최저선이면 계약, 아니면 미계약 (마감 단계에서 마이너 계약 대상)
 *         경쟁 (요구액 90% 이상 제안 2곳+) → 버티기 라운드가 지났고 최고 ≥ 요구액이면 계약, 아니면 요구액 +5~10%
 *         경쟁 없음                  → 최고 ≥ 요구액이면 계약, 아니면 요구액 -5% (최저선까지)
 * 다른 구단 금액은 감추고, 유저에게는 관심 구단 수·요구액·반응 문구만 보인다. 계약이 성사되면 금액이 공개된다.
 * </pre>
 */
final class FaMarket {

    static final int ROUNDS = 6;
    static final double INSTANT = 1.15;
    static final double NEAR = 0.90;
    static final int MARKET_MIN_VALUE = OffseasonRules.FA_MAJOR_MIN_VALUE;

    static final int OPEN = 0, SIGNED = 1, UNSIGNED = 2;
    /** 유저 제안 반응 */
    static final int R_NONE = 0, R_SIGNED = 1, R_LOST = 2, R_BELOW = 3, R_OUTBID = 4, R_HOT = 5, R_WAIT = 6,
            R_BUDGET = 7;

    private FaMarket() {
    }

    /** 시장 선수 1명 */
    static final class Fa {
        int id;
        double value;
        int age;
        boolean pitcher;
        int market;
        int ask;
        int prefYears;
        int reserve;
        int holdout;
        int status;
        int team;
        int salary;
        int years;
        int signedRound;
        /** 지난 라운드 최고 제안 (AI 가 밀렸을 때 올리는 기준) */
        int bestTeam;
        int bestSalary;
        int offers;
    }

    /** 구단 요약: 예산 여유, 유형별(0 타자, 1 투수) MLB 가능 인원·최하위 가치 */
    static final class Team {
        int id;
        long room;
        final int[] capable = new int[2];
        final double[] worst = {Double.MAX_VALUE, Double.MAX_VALUE};

        void add(boolean pitcher, double value) {
            if (value >= OffseasonRules.MLB_CAPABLE) {
                int t = pitcher ? 1 : 0;
                capable[t]++;
                worst[t] = Math.min(worst[t], value);
            }
        }
    }

    record Offer(int team, int salary, int years) {
    }

    /** 유저 제안 처리 결과 */
    record Reaction(int playerId, int reaction) {
    }

    // ------------------------------------------------------------------ 개장

    static Fa open(OffPlayer p, double rate, long seed, int nextSeason) {
        SplittableRandom rng = OffseasonRules.rng(seed, nextSeason, 20, p.id);
        Fa f = new Fa();
        f.id = p.id;
        f.value = p.value();
        f.age = p.age(nextSeason);
        f.pitcher = p.isPitcher();
        f.market = (int) Math.round(ContractModel.marketValue(f.value, f.age, rate));
        double premium = 1.0 + 0.3 * Math.clamp((f.value - 55) / 20, 0, 1) + 0.1 * (rng.nextDouble() - 0.5);
        f.ask = Math.clamp(Math.round(f.market * premium), ContractModel.MIN_SALARY, ContractModel.MAX_SALARY);
        f.reserve = Math.max(ContractModel.MIN_SALARY, (int) Math.round(f.market * (0.85 + 0.15 * rng.nextDouble())));
        f.prefYears = prefYears(f.age, f.value, rng);
        f.holdout = f.value >= ContractModel.STAR_LINE ? 2 + rng.nextInt(3) : f.value >= 58 ? 1 + rng.nextInt(2) : 1;
        return f;
    }

    static int prefYears(int age, double value, SplittableRandom rng) {
        int y;
        if (age <= 27 && value >= ContractModel.STAR_LINE) {
            y = 10 + rng.nextInt(6);
        } else if (age <= 30 && value >= 60) {
            y = 6 + rng.nextInt(4);
        } else if (age <= 33) {
            y = 2 + rng.nextInt(3);
        } else {
            y = 1 + rng.nextInt(2);
        }
        return Math.min(y, OffseasonRules.yearsToRetire(age));
    }

    /** 유저·AI 공통 연수 상한 (15년, 45세 시즌까지) */
    static int maxYears(int age) {
        return Math.min(OffseasonRules.RESIGN_MAX_YEARS, OffseasonRules.yearsToRetire(age));
    }

    static double yearsFactor(int years, int pref) {
        return years <= pref ? Math.max(0.6, 1 - 0.04 * (pref - years)) : 1 + 0.01 * (years - pref);
    }

    static double score(Fa f, int salary, int years) {
        return salary * yearsFactor(years, f.prefYears);
    }

    // ------------------------------------------------------------------ 라운드

    /**
     * 한 라운드 처리. fas 의 상태(요구액·계약)와 teams 의 예산 여유·인원이 바뀐다.
     *
     * @param round   1~6
     * @param mine    유저 제안 (선수 ID → 제안)
     * @return 유저 제안별 반응
     */
    static List<Reaction> round(int round, List<Fa> fas, Map<Integer, Team> teams, Map<Integer, Offer> mine,
                                int userTeam, long seed, int season) {
        List<Reaction> out = new ArrayList<>();
        List<Fa> order = new ArrayList<>(fas.stream().filter(f -> f.status == OPEN).toList());
        order.sort(Comparator.comparingDouble((Fa f) -> -f.value).thenComparingInt(f -> f.id));
        java.util.Map<Integer, Long> pending = new java.util.HashMap<>(); // 이번 라운드 AI 제안 합 (예산 여유 안에서만)
        for (Fa f : order) {
            List<Offer> offers = new ArrayList<>();
            Offer u = mine.get(f.id);
            boolean userBroke = false;
            if (u != null) {
                Team ut = teams.get(userTeam);
                if (ut != null && ut.room >= u.salary()) {
                    offers.add(u);
                } else {
                    userBroke = true; // 앞 선수와 계약해 예산이 모자람
                }
            }
            for (Team t : teams.values()) {
                if (t.id == userTeam) {
                    continue;
                }
                Offer o = aiOffer(f, t, round, pending.getOrDefault(t.id, 0L), seed, season);
                if (o != null) {
                    offers.add(o);
                    pending.merge(t.id, (long) o.salary(), Long::sum);
                }
            }
            int askBefore = f.ask;
            f.offers = offers.size();
            Offer best = null;
            double bestScore = -1;
            int near = 0;
            for (Offer o : offers) {
                double s = score(f, o.salary(), o.years());
                if (s >= NEAR * f.ask) {
                    near++;
                }
                if (s > bestScore || s == bestScore && best != null && o.salary() > best.salary()) {
                    bestScore = s;
                    best = o;
                }
            }
            boolean sign = false;
            if (best != null) {
                if (bestScore >= INSTANT * f.ask) {
                    sign = true;
                } else if (round >= ROUNDS) {
                    sign = bestScore >= f.reserve;
                } else if (near >= 2) {
                    sign = round >= f.holdout && bestScore >= f.ask;
                } else {
                    sign = bestScore >= f.ask;
                }
            }
            if (sign) {
                f.status = SIGNED;
                f.team = best.team();
                f.salary = best.salary();
                f.years = best.years();
                f.signedRound = round;
                Team t = teams.get(best.team());
                if (t != null) {
                    t.room -= best.salary();
                    t.add(f.pitcher, f.value);
                }
            } else if (round >= ROUNDS) {
                f.status = UNSIGNED;
            } else if (near >= 2) {
                double up = 1.05 + 0.05 * OffseasonRules.rng(seed, season, 22 + round, f.id).nextDouble();
                f.ask = Math.min(ContractModel.MAX_SALARY, (int) Math.round(f.ask * up));
            } else {
                f.ask = Math.max(f.reserve, (int) Math.round(f.ask * 0.95));
            }
            f.bestTeam = best == null ? 0 : best.team();
            f.bestSalary = best == null ? 0 : best.salary();

            if (u != null) {
                int r;
                if (userBroke) {
                    r = R_BUDGET;
                } else if (sign && f.team == userTeam) {
                    r = R_SIGNED;
                } else if (sign) {
                    r = R_LOST;
                } else if (score(f, u.salary(), u.years()) < NEAR * askBefore) {
                    r = R_BELOW;
                } else if (best != u) {
                    r = R_OUTBID;
                } else {
                    r = near >= 2 ? R_HOT : R_WAIT;
                }
                out.add(new Reaction(f.id, r));
            }
        }
        return out;
    }

    /** AI 구단 제안 (관심 없거나 여유가 없으면 null) */
    static Offer aiOffer(Fa f, Team t, int round, long pending, long seed, int season) {
        int type = f.pitcher ? 1 : 0;
        int need = Math.max(0, OffseasonRules.WANT_PER_TYPE - t.capable[type]);
        double upgrade = t.capable[type] == 0 ? 10 : f.value - t.worst[type];
        if (need == 0 && upgrade < 3) {
            return null;
        }
        double u = OffseasonRules.rng(seed, season, 21, f.id ^ (t.id * 7919)).nextDouble();
        double cap = f.market * (0.95 + 0.06 * Math.min(need, 5) + 0.015 * Math.clamp(upgrade, 0, 20) + 0.2 * u);
        double salary;
        if (round == 1 || f.bestTeam == 0) {
            salary = Math.max(f.reserve, f.ask * 0.92);
        } else if (f.bestTeam == t.id) {
            salary = f.bestSalary;
        } else {
            // 밀렸으면 최고 제안보다 5% 위, 버티기 라운드가 지났으면 요구액까지 (그래야 경쟁 끝에 계약이 성사됨)
            salary = Math.max(f.ask * (round >= f.holdout ? 1.0 : 0.95), f.bestSalary * 1.05);
        }
        salary = Math.min(salary, cap);
        if (salary < f.reserve) {
            return null;
        }
        int s = (int) Math.min(ContractModel.MAX_SALARY, Math.round(salary));
        if (t.room - pending < s) {
            return null;
        }
        boolean youngStar = f.age <= 29 && f.value >= ContractModel.STAR_LINE;
        int years = Math.min(f.prefYears, Math.min(maxYears(f.age), youngStar ? 15 : 6));
        return new Offer(t.id, s, Math.max(1, years));
    }

    static String reactionText(int r) {
        return switch (r) {
            case R_SIGNED -> "계약 성사";
            case R_LOST -> "다른 구단과 계약";
            case R_BELOW -> "요구액에 못 미침";
            case R_OUTBID -> "더 좋은 제안이 있음";
            case R_HOT -> "검토 중 (경쟁 과열, 요구액 상승)";
            case R_WAIT -> "검토 중";
            case R_BUDGET -> "예산 부족으로 제안 무효";
            default -> "";
        };
    }
}
