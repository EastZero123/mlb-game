package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.Names;
import com.mlbgame.career.RatingSampler;
import com.mlbgame.roster.Defense;

/**
 * 오프시즌 규칙 (AI 구단 공통, 유저 구단도 화면이 생기기 전까지 AI 가 처리). 모든 무작위는 커리어 seed + 시즌 + 선수에서 파생.
 * 순서: 시즌 마감 → 은퇴 → 성장·노화 → 연봉 재산정 → 포스팅 → FA → 드래프트 → 인원 정리 → 새 시즌 로스터.
 */
final class OffseasonRules {

    /** 오프시즌 결과 집계 */
    static final class Report {
        int serviceUp;
        int expired;
        int nonTendered;
        int retired;
        int posted;
        int signedMinor;
        int unsigned;
        int drafted;
        int released;
        int fillers;
        /** 마이너 계약 만료: 6년 이상·MLB 경력 없음 방출(은퇴) / 6년 미만 보류 재계약 */
        int minorOut;
        int minorRenewed;
        /** 45세 시즌을 마쳐 은퇴 */
        int ageRetired;
        /** 내 팀 계약 결정 대상 (1단계) */
        int pendingFa;
        int pendingArb;
        /** 내 팀 계약 결정 반영 (5단계) */
        int resigned;
        int letGo;
        int tendered;
        int myNonTendered;
        double salaryRate;
        /** 나이대별 평균 능력치 변화 {≤24, 25~29, 30~33, 34+} × {합, 인원} */
        final double[][] change = new double[4][2];
    }

    static final int ORG_MINOR_CAP = 56;
    static final int MLB_HITTERS = 13, MLB_PITCHERS = 13, MLB_STARTERS = 5;
    static final int AAA_SIZE = 28;
    static final int POSTING_COUNT = 8;
    static final int DRAFT_ROUNDS = 10;
    /** 구단이 원하는 MLB 가능(가치 45+) 40인 선수 수 (유형별) */
    static final int WANT_PER_TYPE = 14;
    static final int MLB_CAPABLE = 45;
    static final int FA_MAJOR_MIN_VALUE = 46;
    static final int FA_MINOR_MIN_VALUE = 40;
    /** 이 나이 시즌을 마치면 무조건 은퇴. 계약도 이 나이 시즌까지만 */
    static final int RETIRE_AGE = 45;
    /** 마이너 계약 만료 때 MLB 경력 없이 이 연차 이상이면 방출 (다시 계약 못 함 = 은퇴 처리) */
    static final int MINOR_LIMIT_SEASONS = 6;

    private OffseasonRules() {
    }

    static SplittableRandom rng(long seed, int season, int salt, int id) {
        return new SplittableRandom(seed ^ ((long) season << 40) ^ ((long) salt << 32) ^ (id * 0x9E3779B97F4A7C15L));
    }

    // ------------------------------------------------------------------ 1. 시즌 마감

    /**
     * 서비스 연차: 지난 시즌 MLB 타자 40경기 또는 투수 15등판 이상이면 +1 (실제 172일 기준의 근사).
     * 계약: 연봉조정 전·연봉조정 선수는 서비스 6년이 되면 FA, 아니면 계속 보유 (연봉조정 대상 중 가치 45 미만은 방출 = 논텐더).
     * FA 계약은 남은 연수 -1, 0 이면 FA.
     * 마이너 계약은 남은 연수 -1, 0 이면: MLB 경력 있음 = 마이너 FA, 없고 프로 6년 이상 = 방출(은퇴 처리, 다시 계약 못 함),
     * 없고 6년 미만 = 구단 보류 (같은 구단 마이너 1년 재계약). 프로 경력 연차는 소속 선수 +1.
     * 시즌 중 40인에 올라온 마이너 계약 선수는 MLB 계약(연봉조정 전/연봉조정)으로 바꾼 뒤 처리.
     */
    static void closeSeason(List<OffPlayer> all, Report rep) {
        closeSeason(all, 0, rep);
    }

    /**
     * 유저 구단(userTeam)은 바로 방출하지 않고 결정 대상으로 남긴다 ({@link OffPlayer#pending}):
     * MLB 계약 만료·서비스 6년 도달 = FA 자격 (재계약 여부), 서비스 3~5년 = 연봉조정 (텐더 여부).
     * 결정은 4단계 연봉 재산정 뒤 화면에서 하고 5단계 시작 때 {@link #applyDecisions} 로 반영. 마이너 계약 만료는 자동.
     */
    static void closeSeason(List<OffPlayer> all, int userTeam, Report rep) {
        for (OffPlayer p : all) {
            if (p.retired() || p.team == 0) {
                continue;
            }
            p.proSeasons = Math.min(255, p.proSeasons + 1);
            if (p.mlbGames >= (p.isPitcher() ? 15 : 40)) {
                p.service++;
                rep.serviceUp++;
            }
            if (p.fortyMan() && p.contractType == ContractModel.MINOR) {
                p.contractType = ContractModel.PRE_ARB; // 아래에서 서비스 연차로 연봉조정 전/연봉조정 결정
            }
            boolean mine = userTeam != 0 && p.team == userTeam;
            switch (p.contractType) {
                case ContractModel.PRE_ARB, ContractModel.ARBITRATION -> {
                    if (mine && p.service >= 6) {
                        p.pending = OffPlayer.PENDING_FA;
                        p.contractYears = 0;
                        rep.pendingFa++;
                    } else if (mine && p.service >= 3) {
                        p.pending = OffPlayer.PENDING_ARB;
                        p.contractType = ContractModel.ARBITRATION;
                        p.contractYears = 6 - p.service;
                        rep.pendingArb++;
                    } else if (p.service >= 6) {
                        release(p);
                        rep.expired++;
                    } else if (p.service >= 3 && p.value() < MLB_CAPABLE) {
                        release(p);
                        rep.nonTendered++;
                    } else {
                        p.contractType = p.service >= 3 ? ContractModel.ARBITRATION : ContractModel.PRE_ARB;
                        p.contractYears = 6 - p.service;
                    }
                }
                default -> {
                    p.contractYears--;
                    if (p.contractYears <= 0 && mine && p.contractType == ContractModel.GUARANTEED) {
                        p.pending = OffPlayer.PENDING_FA;
                        p.contractYears = 0;
                        rep.pendingFa++;
                    } else if (p.contractYears <= 0 && p.contractType == ContractModel.MINOR && p.service == 0) {
                        if (p.proSeasons >= MINOR_LIMIT_SEASONS) {
                            release(p);
                            p.status = OffPlayer.ST_RETIRED;
                            rep.minorOut++;
                        } else {
                            p.contractYears = 1;
                            p.salary = ContractModel.MINOR_SALARY;
                            rep.minorRenewed++;
                        }
                    } else if (p.contractYears <= 0) {
                        release(p);
                        rep.expired++;
                    }
                }
            }
        }
    }

    /** MLB 경력 없이 마이너 6년 이상 = 마이너 계약을 받을 수 없음 */
    static boolean minorWashout(OffPlayer p) {
        return p.service == 0 && p.proSeasons >= MINOR_LIMIT_SEASONS;
    }

    /** 이 나이 시즌까지 계약 가능한 최대 연수 (RETIRE_AGE 시즌까지, 최소 1년) */
    static int yearsToRetire(int ageNextSeason) {
        return Math.max(1, RETIRE_AGE - ageNextSeason + 1);
    }

    /**
     * 연봉 체계 맞춤: 40인인데 마이너 계약이면 연봉조정 전 MLB 계약(최저연봉, 보유 6 - 서비스),
     * 40인 밖 마이너 계약은 마이너 연봉 고정.
     */
    static void normalizeContracts(List<OffPlayer> all) {
        for (OffPlayer p : all) {
            if (p.team == 0 || p.retired()) {
                continue;
            }
            if (p.fortyMan() && p.contractType == ContractModel.MINOR) {
                p.contractType = p.service >= 3 ? ContractModel.ARBITRATION : ContractModel.PRE_ARB;
                p.contractYears = Math.max(1, 6 - p.service);
                p.salary = Math.max(p.salary, ContractModel.MIN_SALARY);
            } else if (!p.fortyMan() && p.contractType == ContractModel.MINOR) {
                p.salary = ContractModel.MINOR_SALARY;
            }
        }
    }

    // ------------------------------------------------------------------ 내 팀 계약 결정

    /** 재계약 연봉 = 다음 시즌 시장가치 (FA 시장에서 받을 금액과 같음, 천 달러) */
    static int resignAsk(OffPlayer p, double rate, int nextSeason) {
        return (int) Math.round(ContractModel.marketValue(p.value(), p.age(nextSeason), rate));
    }

    /**
     * 재계약 최대 연수: 게임 재미용으로 15년까지, 단 45세 시즌을 넘는 기간은 불가 (은퇴 뒤 연봉으로 총액을 부풀리는 것 방지).
     * 연봉은 연수와 관계없이 시장가치 고정.
     */
    static final int RESIGN_MAX_YEARS = 15;

    static int maxYears(OffPlayer p, int nextSeason) {
        return Math.min(RESIGN_MAX_YEARS, yearsToRetire(p.age(nextSeason)));
    }

    /** 결정하지 않았을 때 기본값 = AI 규칙 (FA 자격은 포기, 연봉조정은 가치 45 이상이면 텐더) */
    static int defaultDecision(OffPlayer p) {
        return p.pending == OffPlayer.PENDING_ARB && p.value() >= MLB_CAPABLE ? OffPlayer.DECIDE_KEEP
                : OffPlayer.DECIDE_RELEASE;
    }

    /** 5단계 시작: 내 팀 결정 반영. 재계약 = FA 계약(시장가치, 정한 연수), 포기·논텐더 = 방출 (FA 시장으로) */
    static void applyDecisions(List<OffPlayer> all, double rate, int nextSeason, Report rep) {
        for (OffPlayer p : all) {
            if (p.pending == OffPlayer.PENDING_NONE || p.retired() || p.team == 0) {
                continue;
            }
            int d = p.decision != OffPlayer.DECIDE_NONE ? p.decision : defaultDecision(p);
            if (p.pending == OffPlayer.PENDING_FA) {
                if (d == OffPlayer.DECIDE_KEEP) {
                    p.contractType = ContractModel.GUARANTEED;
                    p.contractYears = Math.clamp(p.decisionYears, 1, maxYears(p, nextSeason));
                    p.salary = resignAsk(p, rate, nextSeason);
                    if (!p.fortyMan()) {
                        p.status = OffPlayer.ST_40MAN;
                    }
                    rep.resigned++;
                } else {
                    release(p);
                    rep.letGo++;
                }
            } else if (d == OffPlayer.DECIDE_KEEP) {
                rep.tendered++;
            } else {
                release(p);
                rep.myNonTendered++;
            }
            p.pending = OffPlayer.PENDING_NONE;
        }
    }

    static void release(OffPlayer p) {
        p.team = 0;
        p.status = OffPlayer.ST_MINOR;
        p.level = 0;
        p.contractType = ContractModel.MINOR;
        p.contractYears = 0;
        p.salary = 0;
    }

    // ------------------------------------------------------------------ 2. 은퇴

    /**
     * MLB 경력 선수: 은퇴 확률 = 로지스틱((나이 - 중심) / 1.2), 중심 = 37 + (가치 - 50) / 4 (좋은 선수일수록 오래 뜀).
     * 마이너 전용 선수: 33세 이상 80%, 30세 이상·가치 44 미만 50%, 27세 이상·가치 38 미만 30%.
     * 30세 이상 미계약 FA 는 +25%p. 45세 시즌을 마친 선수는 무조건 은퇴.
     */
    static void retire(List<OffPlayer> all, long seed, int season, Report rep) {
        for (OffPlayer p : all) {
            if (p.retired()) {
                continue;
            }
            int age = p.age(season);
            if (age >= RETIRE_AGE) {
                release(p);
                p.status = OffPlayer.ST_RETIRED;
                rep.retired++;
                rep.ageRetired++;
                continue;
            }
            double v = p.value();
            double prob;
            if (p.service == 0 && !p.fortyMan()) {
                prob = age >= 33 ? 0.8 : age >= 30 && v < 44 ? 0.5 : age >= 27 && v < 38 ? 0.3 : 0.01;
            } else {
                double center = 37 + (v - 50) / 4;
                prob = 1 / (1 + Math.exp(-(age - center) / 1.2));
            }
            if (p.freeAgent() && age >= 30) {
                prob += 0.25;
            }
            if (rng(seed, season, 1, p.id).nextDouble() < Math.min(0.99, prob)) {
                release(p);
                p.status = OffPlayer.ST_RETIRED;
                rep.retired++;
            }
        }
    }

    // ------------------------------------------------------------------ 3. 성장·노화

    /** 능력치 컬럼 → 나이 곡선 그룹 (-1 = 곡선 없음) 과 배율 */
    private static final int[] GROUP = new int[MasterData.RATING_COLUMNS.length];
    private static final double[] SCALE = new double[MasterData.RATING_COLUMNS.length];

    static {
        Arrays.fill(GROUP, -1);
        set(0, AgingCurves.CONTACT, 1);
        set(1, AgingCurves.POWER, 1);
        set(2, AgingCurves.EYE, 1);
        set(3, AgingCurves.QUALITY, 1);
        set(5, AgingCurves.SPEED, 1);
        set(6, AgingCurves.SPEED, 1);
        set(7, AgingCurves.CONTROL, 1);
        set(8, AgingCurves.SUPPRESSION, 1);
        for (int k = 0; k < MasterData.PITCH_TYPES; k++) {
            set(MasterData.FIRST_PITCH + 2 * k + 1, AgingCurves.STUFF, 1);
        }
        for (int i = 0; i < 8; i++) {
            set(MasterData.DEF_C + i, AgingCurves.SPEED, 0.7); // 수비 범위는 운동능력 곡선을 약하게 따름
        }
        set(MasterData.DEF_C + 8, AgingCurves.SPEED, 0.5);    // 송구
    }

    private static void set(int column, int group, double scale) {
        GROUP[column] = group;
        SCALE[column] = scale;
    }

    /**
     * 변화 = 나이 곡선(데이터 추정) + 잠재력 보정 + 무작위 (선수 공통 σ1.0 + 항목별 σ1.2).
     * 잠재력 보정 = (잠재력 - 50) / 10 × 2 × 젊음 계수 (20세 이하 1.5 → 26세 0.3 → 27세 이상 0).
     * 곡선이 없는 항목: 스태미나 32세부터 -1/년, 프레이밍·블로킹 33세부터 -0.5/년, 내구성 30세부터 -0.5/년, 성향은 무작위만.
     */
    static void develop(List<OffPlayer> all, AgingCurves.Curves curves, long seed, int season, Report rep) {
        for (OffPlayer p : all) {
            if (p.retired()) {
                continue;
            }
            int age = p.age(season);
            SplittableRandom rng = rng(seed, season, 2, p.id);
            double shared = rng.nextGaussian();
            double youth = age <= 20 ? 1.5 : age >= 27 ? 0 : 1.5 - (age - 20) * 0.2;
            double potentialBonus = (p.r[MasterData.POTENTIAL] - 50) / 10.0 * 2 * youth;
            double total = 0;
            int counted = 0;
            for (int c = 0; c < p.r.length; c++) {
                if (p.r[c] < 0 || c == MasterData.POTENTIAL || (c >= MasterData.FIRST_PITCH && c < MasterData.DEF_C
                        && (c - MasterData.FIRST_PITCH) % 2 == 0)) {
                    continue; // NULL, 잠재력, 구사율
                }
                double d;
                if (GROUP[c] >= 0) {
                    d = SCALE[c] * (curves.at(GROUP[c], age) + potentialBonus) + shared + 1.2 * rng.nextGaussian();
                } else if (c == MasterData.STAMINA) {
                    d = (age >= 32 ? -1 : 0) + 0.8 * rng.nextGaussian();
                } else if (c == MasterData.DEF_C + 9 || c == MasterData.DEF_C + 10) {
                    d = (age >= 33 ? -0.5 : 0) + 0.8 * rng.nextGaussian();
                } else if (c == MasterData.DURABILITY) {
                    d = (age >= 30 ? -0.5 : 0) + 0.8 * rng.nextGaussian();
                } else {
                    d = 0.5 * rng.nextGaussian();
                }
                int before = p.r[c];
                p.r[c] = (short) Math.clamp(Math.round(before + d), 20, 80);
                if (GROUP[c] >= 0) {
                    total += p.r[c] - before;
                    counted++;
                }
            }
            if (counted > 0) {
                int band = age <= 24 ? 0 : age <= 29 ? 1 : age <= 33 ? 2 : 3;
                rep.change[band][0] += total / counted;
                rep.change[band][1]++;
            }
        }
    }

    // ------------------------------------------------------------------ 4. 연봉

    /**
     * 연봉 단가: 다음 시즌 40인 연봉 합계가 목표(리그 시장 규모 / 1.1, {@link LeagueBudget})가 되도록 (FA 시장 후보 포함 근사).
     * 연봉조정 전 = 최저연봉, 연봉조정 = 시장가치 × 35/55/75%. FA 계약은 계약 당시 연봉 유지.
     */
    static double reprice(List<OffPlayer> all, int nextSeason, double target, Report rep) {
        double fixed = 0;
        double flexible = 0;
        for (OffPlayer p : all) {
            if (p.retired()) {
                continue;
            }
            int age = p.age(nextSeason);
            if ((p.freeAgent() || p.pending == OffPlayer.PENDING_FA) && p.value() >= FA_MAJOR_MIN_VALUE) {
                fixed += ContractModel.MIN_SALARY;
                flexible += ContractModel.excess(p.value(), age);
            } else if (p.team != 0 && p.fortyMan() && p.pending != OffPlayer.PENDING_FA) {
                switch (p.contractType) {
                    case ContractModel.PRE_ARB -> fixed += ContractModel.preArbSalary(p.service);
                    case ContractModel.ARBITRATION -> {
                        double share = ContractModel.arbitrationShare(p.service);
                        fixed += share * ContractModel.MIN_SALARY;
                        flexible += share * ContractModel.excess(p.value(), age);
                    }
                    default -> fixed += p.salary;
                }
            }
        }
        double rate = flexible > 0 ? Math.clamp((target - fixed) / flexible, 200, 5_000) : 1_000;
        for (OffPlayer p : all) {
            if (p.retired() || p.team == 0 || p.pending == OffPlayer.PENDING_FA) {
                continue; // FA 자격 결정 대상은 재계약 때 시장가치로 정함
            }
            if (p.contractType == ContractModel.MINOR && !p.fortyMan()) {
                p.salary = ContractModel.MINOR_SALARY;
            } else if (p.contractType == ContractModel.PRE_ARB) {
                p.salary = ContractModel.preArbSalary(p.service);
            } else if (p.contractType == ContractModel.ARBITRATION) {
                p.salary = ContractModel.arbitrationSalary(
                        ContractModel.marketValue(p.value(), p.age(nextSeason), rate), p.service);
            }
        }
        rep.salaryRate = rate;
        return rate;
    }

    // ------------------------------------------------------------------ 5~6. 신규 선수 생성

    /**
     * 신규 선수 능력치. 잠재 재능 t ~ N(0,1) 이 현재 능력과 잠재력을 함께 결정.
     *
     * @param mean 현재 능력 평균 (포스팅 50, 대졸 36, 고졸 30)
     */
    static OffPlayer create(int id, long careerId, int season, LocalDate created, boolean pitcher, int age, double mean,
                            double potentialMean, int origin, SplittableRandom rng, String name) {
        double t = rng.nextGaussian();
        short[] r = new short[MasterData.RATING_COLUMNS.length];
        Arrays.fill(r, (short) -1);
        int pos;
        String bats;
        String throwsHand;
        if (pitcher) {
            pos = 1;
            throwsHand = rng.nextDouble() < 0.28 ? "L" : "R";
            bats = throwsHand;
            int stuff = rating(mean + 4 * t, 5, rng);
            r[MasterData.CONTROL] = (short) rating(mean + 4 * t, 5, rng);
            r[MasterData.SUPPRESSION] = (short) rating(mean + 4 * t, 5, rng);
            r[MasterData.CONTROL + 2] = (short) rating(50, 8, rng);
            r[MasterData.STAMINA] = (short) (rng.nextDouble() < 0.55 ? 52 + rng.nextInt(16) : 30 + rng.nextInt(14));
            int[][] mix = {{0, 55}, {3, 30}, {6, 15}}; // 포심·슬라이더·체인지업
            for (int[] m : mix) {
                r[MasterData.FIRST_PITCH + 2 * m[0]] = (short) m[1];
                r[MasterData.FIRST_PITCH + 2 * m[0] + 1] = (short) Math.clamp(stuff + rng.nextInt(7) - 3, 20, 80);
            }
        } else {
            int[] positions = {2, 3, 4, 5, 6, 7, 8, 9};
            pos = positions[rng.nextInt(positions.length)];
            double h = rng.nextDouble();
            bats = h < 0.6 ? "R" : h < 0.9 ? "L" : "S";
            throwsHand = pos == 2 || (pos >= 4 && pos <= 6) ? "R" : rng.nextDouble() < 0.8 ? "R" : "L";
            for (int i = MasterData.CONTACT; i <= MasterData.QUALITY; i++) {
                r[i] = (short) rating(mean + 4 * t, 5, rng);
            }
            r[MasterData.QUALITY + 1] = (short) rating(50, 8, rng);
            int speed = rating(50 + 2 * t, 9, rng);
            r[MasterData.SPEED] = (short) speed;
            r[MasterData.SPEED + 1] = (short) Math.clamp(speed + Math.round(4 * rng.nextGaussian()), 20, 80);
            Integer[] def = Defense.derive(pos, rating(45 + 3 * t, 6, rng));
            for (int i = 0; i < 8; i++) {
                r[MasterData.DEF_C + i] = def[i] == null ? -1 : def[i].shortValue();
            }
            r[MasterData.DEF_C + 8] = (short) rating(50, 8, rng);
            if (pos == 2) {
                r[MasterData.DEF_C + 9] = (short) rating(45, 6, rng);
                r[MasterData.DEF_C + 10] = (short) rating(45, 6, rng);
            }
        }
        r[MasterData.DURABILITY] = (short) rating(50, 8, rng);
        r[MasterData.POTENTIAL] = (short) Math.clamp(Math.round(potentialMean + 9 * t + 5 * rng.nextGaussian()), 20,
                80);
        LocalDate birth = LocalDate.of(season - age, 1 + rng.nextInt(12), 1 + rng.nextInt(28));
        OffPlayer p = new OffPlayer(id, birth, pos, r);
        p.adaptability = RatingSampler.personality(rng);
        p.stability = RatingSampler.personality(rng);
        p.professionalism = RatingSampler.personality(rng);
        p.personRow = new Object[] {careerId, id, name, birth, bats, throwsHand, pos, origin, created};
        return p;
    }

    /** 포스팅: 24~30세, 즉시 전력형 (능력 평균 50, 잠재력 평균 50). FA 시장에 들어감 */
    static List<OffPlayer> posting(int[] nextId, long careerId, long seed, int season, LocalDate created, Report rep) {
        List<OffPlayer> out = new ArrayList<>();
        for (int i = 0; i < POSTING_COUNT; i++) {
            int id = nextId[0]++;
            SplittableRandom rng = rng(seed, season, 3, id);
            int age = 24 + rng.nextInt(7);
            OffPlayer p = create(id, careerId, season, created, rng.nextBoolean(), age, 50, 50, 3,
                    rng, Names.posting(rng));
            p.proSeasons = ContractModel.proSeasonsEstimate(age); // 해외 프로 경력
            out.add(p);
            rep.posted++;
        }
        return out;
    }

    // ------------------------------------------------------------------ 7. FA (마이너 계약)

    /**
     * FA 시장 마감 뒤 남은 FA: 가치 40+ 선수는 마이너 인원이 적은 구단과 마이너 1년 계약
     * (MLB 경력 없이 프로 6년 이상은 제외). MLB 계약은 라운드 입찰 시장({@link FaMarket})에서 끝난다.
     */
    static void minorContracts(List<OffPlayer> all, java.util.Collection<Integer> teams, Report rep) {
        Map<Integer, List<OffPlayer>> org = byTeam(all);
        List<OffPlayer> pool = new ArrayList<>(all.stream().filter(OffPlayer::freeAgent).toList());
        pool.sort(Comparator.comparingDouble(OffPlayer::value).reversed());
        for (OffPlayer fa : pool) {
            if (fa.value() < FA_MINOR_MIN_VALUE || minorWashout(fa)) {
                continue;
            }
            int team = 0;
            int fewest = Integer.MAX_VALUE;
            for (int t : teams) {
                int minors = (int) org.getOrDefault(t, List.of()).stream()
                        .filter(m -> !m.fortyMan() && !m.retired()).count();
                if (minors < fewest) {
                    fewest = minors;
                    team = t;
                }
            }
            if (team != 0 && fewest < ORG_MINOR_CAP) {
                fa.team = team;
                fa.status = OffPlayer.ST_MINOR;
                fa.contractType = ContractModel.MINOR;
                fa.contractYears = 1;
                fa.salary = ContractModel.MINOR_SALARY;
                org.computeIfAbsent(team, k -> new ArrayList<>()).add(fa);
                rep.signedMinor++;
            }
        }
        rep.unsigned = (int) pool.stream().filter(OffPlayer::freeAgent).count();
    }

    // ------------------------------------------------------------------ 8. 드래프트

    /**
     * 지명 순서({@link DraftLottery}, 1라운드 추첨 포함) 1장당 1명 생성(고졸 40% 18세 / 대졸 60% 21~22세) 후 순서대로 지명.
     * AI 구단은 스카우트 추정치(예상 가치 + 구단·선수별 오차 σ3)가 가장 높은 선수를 지명 (같은 커리어면 결정적).
     * 지명 선수: AA, 마이너 계약 6년, 연봉 0. 반환 목록은 지명 순서와 같다.
     *
     * @param picks 지명 순서대로 구단 ID (30구단 × 10라운드)
     */
    static List<OffPlayer> draft(List<Integer> picks, int[] nextId, long careerId, long seed, int season,
                                 LocalDate created, Report rep) {
        return draft(picks, null, nextId, careerId, seed, season, created, rep);
    }

    /**
     * 이미 정한 지명(유저 지명·드래프트 화면에서 진행한 AI 지명)을 반영해 지명. 빈 자리만 AI 가 고른다.
     *
     * @param chosen 지명 순서별 선수 ID (null 이면 전체 AI, 항목이 null 이면 그 지명권만 AI)
     */
    static List<OffPlayer> draft(List<Integer> picks, List<Integer> chosen, int[] nextId, long careerId, long seed,
                                 int season, LocalDate created, Report rep) {
        List<OffPlayer> prospects = prospects(picks.size(), nextId, careerId, seed, season, created);
        int firstId = prospects.isEmpty() ? 0 : prospects.getFirst().id;
        List<OffPlayer> drafted = new ArrayList<>(picks.size());
        boolean[] taken = new boolean[prospects.size()];
        for (int k = 0; k < picks.size(); k++) {
            int team = picks.get(k);
            Integer want = chosen == null ? null : chosen.get(k);
            int best;
            if (want != null) {
                best = want - firstId;
                if (best < 0 || best >= prospects.size() || taken[best]) {
                    throw new IllegalStateException("드래프트 지명 기록이 후보와 맞지 않음: " + want);
                }
            } else {
                best = aiPick(prospects, taken, team, seed, season);
            }
            taken[best] = true;
            OffPlayer p = prospects.get(best);
            sign(p, team);
            drafted.add(p);
            rep.drafted++;
        }
        return drafted;
    }

    /** 드래프트 후보 (ID = nextId 부터 연속, 같은 nextId·seed·시즌이면 항상 같은 후보) */
    static List<OffPlayer> prospects(int count, int[] nextId, long careerId, long seed, int season,
                                     LocalDate created) {
        List<OffPlayer> prospects = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = nextId[0]++;
            SplittableRandom rng = rng(seed, season, 5, id);
            boolean highSchool = rng.nextDouble() < 0.4;
            int age = highSchool ? 18 : 21 + rng.nextInt(2);
            prospects.add(create(id, careerId, season, created, rng.nextBoolean(), age, highSchool ? 30 : 36,
                    highSchool ? 58 : 53, 2, rng, Names.american(rng)));
        }
        return prospects;
    }

    /** AI 구단 지명: 남은 후보 중 구단의 추정치가 가장 높은 선수 (후보 목록 인덱스) */
    static int aiPick(List<OffPlayer> prospects, boolean[] taken, int team, long seed, int season) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < prospects.size(); i++) {
            if (taken[i]) {
                continue;
            }
            OffPlayer p = prospects.get(i);
            double noise = 3 * rng(seed, season, 6, p.id ^ (team * 7919)).nextGaussian();
            double score = 0.5 * p.value() + 0.5 * p.r[MasterData.POTENTIAL] + noise;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            throw new IllegalStateException("남은 드래프트 후보 없음");
        }
        return best;
    }

    /** 지명 선수 계약: AA, 마이너 계약 6년, 연봉 0 */
    private static void sign(OffPlayer p, int team) {
        p.team = team;
        p.level = 2;
        p.status = OffPlayer.ST_MINOR;
        p.contractType = ContractModel.MINOR;
        p.contractYears = 6;
        p.salary = ContractModel.MINOR_SALARY;
        p.draftee = true;
    }

    /** 지난 시즌 성적 역순 (승 적은 팀 먼저, 같으면 득실차 낮은 팀) */
    static List<Integer> draftOrder(Map<Integer, int[]> standings) {
        List<Integer> teams = new ArrayList<>(standings.keySet());
        teams.sort(Comparator.<Integer>comparingInt(t -> standings.get(t)[0])
                .thenComparingInt(t -> standings.get(t)[2] - standings.get(t)[3])
                .thenComparingInt(t -> t));
        return teams;
    }

    // ------------------------------------------------------------------ 9. 인원 정리 + 새 시즌 로스터

    /** 구단 마이너 계약 인원이 상한(56)을 넘으면 가치 낮은 순(같으면 나이 많은 순)으로 방출. 올해 지명 선수 제외 */
    static void trimMinors(List<OffPlayer> all, int season, Report rep) {
        for (List<OffPlayer> mine : byTeam(all).values()) {
            List<OffPlayer> minors = new ArrayList<>(mine.stream()
                    .filter(p -> !p.fortyMan() && !p.retired() && !p.draftee).toList());
            long draftees = mine.stream().filter(p -> p.draftee).count();
            int over = (int) (minors.size() + draftees - ORG_MINOR_CAP);
            if (over <= 0) {
                continue;
            }
            minors.sort(Comparator.comparingDouble(OffPlayer::value).thenComparingInt(p -> -p.age(season)));
            for (int i = 0; i < over && i < minors.size(); i++) {
                release(minors.get(i));
                rep.released++;
            }
        }
    }

    /** 레벨별 최소 인원 {타자, 투수} (출전 가능 기준). MLB 는 26인 구성 그대로 */
    static final int[][] LEVEL_MIN = {{MLB_HITTERS, MLB_PITCHERS}, {11, 11}, {10, 11}};
    static final int AAA_PER_TYPE = 14;

    /** 인원이 모자랄 때 대체 선수를 만드는 쪽 (서비스가 ID·seed 를 관리) */
    interface Signer {
        OffPlayer sign(int team, int level, boolean pitcher);
    }

    /**
     * 새 시즌 개막 로스터 (구단별):
     * <ol>
     *   <li>개막 전 회복분 부상 해제</li>
     *   <li>MLB 26 = 타자 13 (포수 2 보장) + 투수 13 (선발형 5), 가치 순. 뽑힌 선수는 40인 포함</li>
     *   <li>나머지는 유형별로 AAA 14명 (40인 우선, 그다음 가치 순) → 나머지 AA</li>
     *   <li>개막 후에도 부상인 40인은 MLB 부상자 명단, 마이너 계약 부상자는 AA</li>
     *   <li>레벨·유형별 최소 인원(MLB 13/13, AAA 11/11, AA 10/11)에 못 미치면 대체 선수 영입</li>
     * </ol>
     */
    static void assignRosters(List<OffPlayer> all, LocalDate opening, Signer signer) {
        for (Map.Entry<Integer, List<OffPlayer>> e : byTeam(all).entrySet()) {
            int team = e.getKey();
            List<OffPlayer> mine = e.getValue();
            List<OffPlayer> healthy = new ArrayList<>();
            for (OffPlayer p : mine) {
                if (p.injuryUntil != null && !p.injuryUntil.isAfter(opening)) {
                    p.injuryUntil = null;
                }
                if (p.injuryUntil == null) {
                    healthy.add(p);
                } else if (p.fortyMan()) {
                    p.level = 0;
                    p.status = OffPlayer.ST_INJURED;
                } else {
                    p.level = 2;
                }
            }
            healthy.sort(Comparator.comparingDouble(OffPlayer::value).reversed());
            List<OffPlayer> major = new ArrayList<>(26);
            pick(healthy, major, OffPlayer::canCatch, 2);
            pick(healthy, major, p -> !p.isPitcher(), MLB_HITTERS - count(major, false));
            pick(healthy, major, p -> p.isPitcher() && p.starterType(), MLB_STARTERS);
            pick(healthy, major, OffPlayer::isPitcher, MLB_PITCHERS - count(major, true));
            for (OffPlayer p : major) {
                p.level = 0;
                p.status = OffPlayer.ST_ACTIVE;
            }

            int[][] have = new int[3][2];
            have[0][0] = count(major, false);
            have[0][1] = count(major, true);
            for (boolean pitcher : new boolean[] {false, true}) {
                List<OffPlayer> rest = new ArrayList<>(healthy.stream()
                        .filter(p -> !major.contains(p) && p.isPitcher() == pitcher).toList());
                rest.sort(Comparator.comparing((OffPlayer p) -> !p.fortyMan())
                        .thenComparing(Comparator.comparingDouble(OffPlayer::value).reversed()));
                for (int i = 0; i < rest.size(); i++) {
                    OffPlayer p = rest.get(i);
                    p.level = i < AAA_PER_TYPE ? 1 : 2;
                    if (p.fortyMan()) {
                        p.status = OffPlayer.ST_40MAN;
                    }
                    have[p.level][pitcher ? 1 : 0]++;
                }
            }

            for (int level = 0; level <= 2; level++) {
                for (int type = 0; type <= 1; type++) {
                    for (int n = have[level][type]; n < LEVEL_MIN[level][type]; n++) {
                        OffPlayer f = signer.sign(team, level, type == 1);
                        f.team = team;
                        f.level = level;
                        f.status = level == 0 ? OffPlayer.ST_ACTIVE : OffPlayer.ST_MINOR;
                        f.contractType = ContractModel.MINOR;
                        f.contractYears = 1;
                        all.add(f);
                    }
                }
            }
        }
        normalizeContracts(all); // 26인·40인에 오른 마이너 계약 선수 → MLB 계약, 마이너 연봉 고정
    }

    // ------------------------------------------------------------------ 10. 리그 기준 정규화

    /**
     * 정규화 대상: 타자 {컨택, 파워, 선구, 타구질, 스피드, 주루}, 투수 {제구, 장타억제} + 구위(구종별 구위 전체를 같은 폭)
     * + 수비 (타자 주 포지션 수비 평균, 포지션별 수비 8칸 전체를 같은 폭. 성장·드래프트로 리그 수비가 올라 BABIP 가 내려가던 것 방지)
     */
    static final int[] BAT_NORM = {MasterData.CONTACT, MasterData.POWER, MasterData.EYE, MasterData.QUALITY,
            MasterData.SPEED, MasterData.SPEED + 1};
    static final int[] PIT_NORM = {MasterData.CONTROL, MasterData.SUPPRESSION};
    /** 기준·이동 배열에서 구위·수비 자리 */
    static final int STUFF_IDX = BAT_NORM.length + PIT_NORM.length, DEF_IDX = STUFF_IDX + 1;
    static final String[] NORM_NAMES = {"컨택", "파워", "선구", "타구질", "스피드", "주루", "제구", "장타억제", "구위", "수비"};

    /**
     * MLB 개막 로스터(26인)의 항목별 평균. [BAT_NORM..., PIT_NORM..., 구위, 수비]
     *
     * @param hitterPos 타자별 주 포지션 (hitters 와 같은 순서, 수비 평균용)
     */
    static double[] mlbMeans(List<short[]> hitters, List<Integer> hitterPos, List<short[]> pitchers) {
        double[] m = new double[NORM_NAMES.length];
        for (int i = 0; i < BAT_NORM.length; i++) {
            m[i] = mean(hitters, BAT_NORM[i]);
        }
        for (int i = 0; i < PIT_NORM.length; i++) {
            m[BAT_NORM.length + i] = mean(pitchers, PIT_NORM[i]);
        }
        double stuff = 0;
        for (short[] r : pitchers) {
            stuff += ContractModel.stuff(r);
        }
        m[STUFF_IDX] = pitchers.isEmpty() ? 0 : stuff / pitchers.size();
        double def = 0;
        int n = 0;
        for (int i = 0; i < hitters.size(); i++) {
            int pos = hitterPos.get(i);
            int v = pos >= 2 && pos <= 9 ? hitters.get(i)[MasterData.DEF_C + pos - 2] : -1;
            if (v >= 0) {
                def += v;
                n++;
            }
        }
        m[DEF_IDX] = n == 0 ? 0 : def / n;
        return m;
    }

    private static double mean(List<short[]> rs, int col) {
        double sum = 0;
        int n = 0;
        for (short[] r : rs) {
            if (r[col] >= 0) {
                sum += r[col];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * 리그 기준 정규화: 성장·노화·신인 유입 후 MLB 개막 로스터의 항목별 평균이 기준(2026 실제 개막 로스터)과 같도록
     * 전 선수를 같은 폭만큼 옮긴다. 누가 오르고 내렸는지(상대 위치)는 그대로, 리그 전체 수준만 고정 →
     * 해가 지나도 리그 환경(삼진·볼넷·홈런 비율)이 실제와 어긋나며 누적되지 않음.
     *
     * @return 항목별 이동 폭 (리포트용)
     */
    static double[] normalize(List<OffPlayer> all, double[] target) {
        List<short[]> hitters = new ArrayList<>();
        List<Integer> hitterPos = new ArrayList<>();
        List<short[]> pitchers = new ArrayList<>();
        for (OffPlayer p : all) {
            if (p.team != 0 && p.level == 0 && p.status == OffPlayer.ST_ACTIVE) {
                if (p.isPitcher()) {
                    pitchers.add(p.r);
                } else {
                    hitters.add(p.r);
                    hitterPos.add(p.pos);
                }
            }
        }
        double[] now = mlbMeans(hitters, hitterPos, pitchers);
        double[] shift = new double[now.length];
        for (int i = 0; i < shift.length; i++) {
            shift[i] = target[i] - now[i];
        }
        for (OffPlayer p : all) {
            if (p.retired()) {
                continue;
            }
            for (int i = 0; i < BAT_NORM.length; i++) {
                move(p.r, BAT_NORM[i], shift[i]);
            }
            for (int i = 0; i < PIT_NORM.length; i++) {
                move(p.r, PIT_NORM[i], shift[BAT_NORM.length + i]);
            }
            for (int k = 0; k < MasterData.PITCH_TYPES; k++) {
                move(p.r, MasterData.FIRST_PITCH + 2 * k + 1, shift[STUFF_IDX]);
            }
            for (int k = 0; k < 8; k++) {
                move(p.r, MasterData.DEF_C + k, shift[DEF_IDX]);
            }
        }
        return shift;
    }

    private static void move(short[] r, int col, double by) {
        if (r[col] >= 0) {
            r[col] = (short) Math.clamp(Math.round(r[col] + by), 20, 80);
        }
    }

    private static void pick(List<OffPlayer> sorted, List<OffPlayer> into, java.util.function.Predicate<OffPlayer> f,
                             int n) {
        for (OffPlayer p : sorted) {
            if (n <= 0) {
                return;
            }
            if (!into.contains(p) && f.test(p)) {
                into.add(p);
                n--;
            }
        }
    }

    private static int count(List<OffPlayer> list, boolean pitcher) {
        return (int) list.stream().filter(p -> p.isPitcher() == pitcher).count();
    }

    static Map<Integer, List<OffPlayer>> byTeam(List<OffPlayer> all) {
        Map<Integer, List<OffPlayer>> out = new HashMap<>();
        for (OffPlayer p : all) {
            if (p.team != 0 && !p.retired()) {
                out.computeIfAbsent(p.team, k -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    private static int rating(double mean, double sd, SplittableRandom rng) {
        return (int) Math.clamp(Math.round(mean + sd * rng.nextGaussian()), 20, 80);
    }
}
