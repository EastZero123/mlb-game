package com.mlbgame.season;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.sim.BatStat;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.PitcherLine;
import com.mlbgame.sim.RateModel;
import com.mlbgame.sim.SimBatter;
import com.mlbgame.sim.SimPitcher;
import com.mlbgame.sim.SimTeam;

/**
 * 시즌 중 운영 규칙 (AI 구단 공통). 모든 무작위는 경기 seed 에서 파생 → 같은 상태면 같은 결과.
 * <ul>
 *   <li>휴식: 경기마다 포수 22%, 야수 10%, 지명타자 8% 확률로 벤치 선수와 교체
 *       (플래툰·대타·수비 교체가 없는 만큼 휴식으로 주전 출전량을 실제 수준에 맞춤)</li>
 *   <li>플래툰: 상대 선발과 같은 손 타자는 반대 손 벤치 타자(공격력 5 이내)로 교체</li>
 *   <li>불펜 피로: {@link Fatigue}</li>
 *   <li>구단 산하 인원이 모자라면 대체 선수 영입 ({@link Filler})</li>
 *   <li>부상: 출전 1경기당 타자 0.85%, 선발 3.5%, 구원 1.8% × 내구성 보정 → 팀당 시즌 약 30건 (실제 IL 등재 수준)</li>
 *   <li>부상 기간: 10일 + 로그정규 (중앙 약 22일, 긴 꼬리), 투수 ×1.3, 최대 180일</li>
 *   <li>MLB 부상 → 같은 구단 AAA(없으면 AA)에서 같은 유형 콜업. 복귀 → 같은 유형 최하위 강등</li>
 *   <li>매일 산하 팀 인원 재조정: AA 가 모자라면 AAA 여유분을 내리고, AAA 가 모자라면 AA 에서 올림</li>
 *   <li>매주 월요일: 40인 AAA 선수가 같은 유형 MLB 최하위보다 6 이상 좋으면 1명 교체 (유형별 팀당 1명).
 *       유저 팀은 운영 지정에서 끌 수 있음</li>
 *   <li>경기 전: MLB 현역이 26명보다 적으면 모자란 유형부터 산하 팀 최고 선수로 채움 ({@link #fillMlb}).
 *       부상 복귀자는 26인을 넘을 때만 최하위를 내림 → 유저가 직접 비워 둔 자리는 그대로 복귀</li>
 *   <li>40인 명단 40명 유지: 자동 콜업으로 넘치면 산하 팀 40인 최하위부터 제외 ({@link #trimFortyMan})</li>
 *   <li>유저 직접 이동 규칙: {@link RosterMoves}</li>
 * </ul>
 */
final class DayRules {

    static final double REST_CATCHER = 0.22;
    static final double REST_FIELDER = 0.10;
    static final double REST_DH = 0.08;
    static final double INJURY_BATTER = 0.0085;
    static final double INJURY_STARTER = 0.035;
    static final double INJURY_RELIEVER = 0.018;
    static final double SWAP_MARGIN = 6;
    static final int EMERGENCY_PEN = 3;
    /** 플래툰 교체 허용 공격력 차이 (벤치 타자가 이 이상 뒤지면 불리해도 주전 유지) */
    static final double PLATOON_MARGIN = 5;
    /** 산하 팀 유형별 최소 인원 [타자, 투수] (출전 가능 기준) */
    static final int[] MIN_AAA = {11, 11};
    static final int[] MIN_AA = {10, 11};

    /** 하루 이벤트 (리포트·저장·재구성 대상) */
    static final class Events {
        int injuries;
        int mlbInjuries;
        int returns;
        int moves;
        final List<CareerRuntime.Player> mlbInjured = new ArrayList<>();
        /** 오늘 영입한 대체 선수 (c_person·c_player INSERT) */
        final List<Filler.Signed> signed = new ArrayList<>();
        /** 다시 구성할 (레벨, 구단) */
        final Set<Long> touched = new HashSet<>();

        void touch(int level, int team) {
            touched.add(((long) level << 32) | team);
        }
    }

    private DayRules() {
    }

    // ------------------------------------------------------------------ 경기 전

    /** 부상 복귀 + 월요일 정기 교체 → 바뀐 팀 재구성 */
    static void startOfDay(CareerRuntime rt, LocalDate date, Events ev) {
        long today = date.toEpochDay();
        List<CareerRuntime.Player> back = new ArrayList<>();
        for (CareerRuntime.Player p : rt.players.values()) {
            if (p.injuredUntil > 0 && p.injuredUntil <= today) {
                p.injuredUntil = 0;
                p.dirty = true;
                ev.returns++;
                ev.touch(p.level, p.team);
                if (p.level == 0 && p.status == CareerRuntime.ST_INJURED) {
                    p.status = CareerRuntime.ST_ACTIVE;
                    back.add(p);
                }
            }
        }
        for (CareerRuntime.Player p : back) {
            // 26인을 넘을 때만 (유저가 미리 자리를 비워 뒀으면 그대로 복귀)
            if (RosterMoves.active(rt, p.team, null) > MLB_ACTIVE) {
                sendDown(rt, p.team, p.isPitcher(), p, ev);
            }
        }
        if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
            for (int team : rt.teamIds) {
                if (team == rt.userTeamId && rt.userPlan != null && !rt.userPlan.autoSwap()) {
                    continue; // 유저가 끔
                }
                weeklySwap(rt, team, false, today, ev);
                weeklySwap(rt, team, true, today, ev);
            }
        }
        for (int team : rt.teamIds) {
            fillMlb(rt, team, today, ev);
            trimFortyMan(rt, team);
        }
        // 유저가 경기 사이에 옮긴 결과로 산하 팀이 모자라면 경기 전에 재조정 (AI 구단은 전날 밤에 이미 맞춰져 있음)
        if (rt.teamIndex.containsKey(rt.userTeamId)) {
            balance(rt, rt.userTeamId, false, today, ev);
            balance(rt, rt.userTeamId, true, today, ev);
        }
        rebuildTouched(rt, today, ev);
    }

    // ------------------------------------------------------------------ 경기

    /** 포스트시즌 선발 인원 (상위 4명) */
    static final int POSTSEASON_ROTATION = 4;

    /** 포스트시즌: 그 팀이 포스트시즌에 치른 경기 수 % 4 → 상위 4선발 */
    static SimTeam postseasonTeam(CareerRuntime rt, int teamId) {
        SimTeam[] byStarter = rt.setup(0, teamId).byStarter();
        int played = 0;
        for (Series s : rt.series) {
            if (s.involves(teamId)) {
                played += s.played();
            }
        }
        return byStarter[played % Math.min(POSTSEASON_ROTATION, byStarter.length)];
    }

    /** 오늘 로테이션 선발이 나오는 미리 만든 팀 (치른 경기 수 % 선발 인원) */
    static SimTeam baseTeam(CareerRuntime rt, int level, int teamId) {
        AutoManager.TeamSetup setup = rt.setup(level, teamId);
        int[] s = rt.standing(level, teamId);
        return setup.byStarter()[(s[CareerRuntime.W] + s[CareerRuntime.L]) % setup.byStarter().length];
    }

    /**
     * 오늘 경기에 나설 팀: 로테이션 선발 + 휴식 교체 + 플래툰 + 피로한 불펜 제외. 바뀐 게 없으면 미리 만든 객체 그대로.
     *
     * @param base            오늘 선발이 정해진 미리 만든 팀 ({@link #baseTeam} 또는 포스트시즌 로테이션)
     * @param opposingStarter 상대 선발의 투구 손 (플래툰 판단)
     * @param allowRest       false 면 휴식 교체 없음 (포스트시즌)
     */
    static SimTeam gameTeam(CareerRuntime rt, int level, int teamId, SimTeam base, long today, SplittableRandom rng,
                            Hand opposingStarter, boolean allowRest) {
        AutoManager.TeamSetup setup = rt.setup(level, teamId);
        // 유저가 좌투수 상대 타순을 따로 정했으면 그 타순으로 (수비력·포수 송구도 그 타순 기준)
        AutoManager.Lineup lu = setup.lineupFor(opposingStarter);
        boolean switched = lu != setup.main();

        SimBatter[] lineup = switched ? lu.sim().clone() : null;
        boolean[] usedBench = new boolean[lu.bench().length];
        for (int slot = 0; slot < 9; slot++) {
            int pos = lu.positions()[slot];
            PlayerCard regular = lu.cards()[slot];
            double p = pos == 2 ? REST_CATCHER : pos == 10 ? REST_DH : REST_FIELDER;
            // 난수는 항상 같은 횟수만 뽑는다 (설정과 무관하게 이후 판정 흐름 유지)
            boolean rest = rng.nextDouble() < p && allowRest && setup.autoRest();
            // 플래툰: 상대 선발과 같은 손(불리)이면 반대 손 벤치 타자 중 공격력이 크게 뒤지지 않는 선수로
            // (유저가 타순을 지정했으면 하지 않음)
            boolean platoon = !rest && !setup.manual() && regular.bats() != Hand.SWITCH
                    && regular.bats() == opposingStarter;
            if (!rest && !platoon) {
                continue;
            }
            for (int k = 0; k < usedBench.length; k++) {
                PlayerCard sub = lu.bench()[k];
                if (usedBench[k] || (pos != 10 && sub.def(pos) == 0)) {
                    continue;
                }
                if (platoon && (sub.bats() == opposingStarter
                        || sub.offense() < regular.offense() - PLATOON_MARGIN)) {
                    continue;
                }
                if (lineup == null) {
                    lineup = base.lineup().clone();
                }
                lineup[slot] = lu.benchSim()[k];
                usedBench[k] = true;
                break;
            }
        }

        SimPitcher[] pen = base.bullpen();
        List<SimPitcher> rested = new ArrayList<>(pen.length);
        for (SimPitcher p : pen) {
            if (Fatigue.available(rt.pitcherLoad, (int) p.playerId(), today)) {
                rested.add(p);
            }
        }
        SimPitcher[] usable = rested.size() == pen.length || rested.size() < EMERGENCY_PEN
                ? pen : rested.toArray(SimPitcher[]::new);

        if (lineup == null && usable == pen) {
            return base;
        }
        return new SimTeam(base.teamId(), lineup != null ? lineup : base.lineup(), base.starter(), usable,
                switched ? lu.defenseFactor() : base.defenseFactor(),
                switched ? lu.catcherArmZ() : base.catcherArmZ());
    }

    /** 경기 후: 구원 투수 피로 기록 + 부상 판정 */
    static void afterGame(CareerRuntime rt, int level, GameResult r, SimTeam away, SimTeam home, long today,
                          SplittableRandom rng, Events ev) {
        for (List<PitcherLine> lines : List.of(r.awayPitching(), r.homePitching())) {
            for (PitcherLine l : lines) {
                int id = (int) l.playerId();
                if (!l.starter()) {
                    Fatigue.record(rt.pitcherLoad, id, today, l.pitches());
                }
                injury(rt, level, id, l.starter() ? INJURY_STARTER : INJURY_RELIEVER, true, today, rng, ev);
            }
        }
        SimTeam[] teams = {away, home};
        for (int side = 0; side < 2; side++) {
            for (int slot = 0; slot < 9; slot++) {
                if (r.batting()[side][slot][BatStat.PA] > 0) {
                    injury(rt, level, (int) teams[side].lineup()[slot].playerId(), INJURY_BATTER, false, today, rng,
                            ev);
                }
            }
        }
    }

    private static void injury(CareerRuntime rt, int level, int id, double base, boolean pitcher, long today,
                               SplittableRandom rng, Events ev) {
        CareerRuntime.Player p = rt.players.get(id);
        double prob = base * Math.exp(-0.35 * RateModel.z(p == null ? 50 : p.durability));
        if (rng.nextDouble() >= prob || p == null || !p.available(today)) {
            return;
        }
        p.injuredUntil = today + 1 + injuryDays(rng, pitcher);
        p.dirty = true;
        ev.injuries++;
        ev.touch(level, p.team);
        if (level == 0) {
            p.status = CareerRuntime.ST_INJURED;
            ev.mlbInjuries++;
            ev.mlbInjured.add(p);
        }
    }

    static int injuryDays(SplittableRandom rng, boolean pitcher) {
        double d = 10 + Math.exp(Math.log(12) + 0.9 * rng.nextGaussian());
        return (int) Math.min(180, Math.round(pitcher ? d * 1.3 : d));
    }

    // ------------------------------------------------------------------ 경기 후

    /** MLB 부상자 콜업 → 산하 팀 인원 재조정 → 바뀐 팀 재구성 (다음 날부터 반영) */
    static void endOfDay(CareerRuntime rt, long today, Events ev) {
        long tomorrow = today + 1;
        for (CareerRuntime.Player hurt : ev.mlbInjured) {
            callUp(rt, hurt, tomorrow, ev);
        }
        for (int team : rt.teamIds) {
            balance(rt, team, false, tomorrow, ev);
            balance(rt, team, true, tomorrow, ev);
            trimFortyMan(rt, team);
        }
        rebuildTouched(rt, tomorrow, ev);
    }

    /**
     * 40인 명단이 넘치면 (마이너 계약 선수 자동 콜업 등) 산하 팀 40인 중 가치가 낮은 선수부터 40인 제외
     * (실제의 지명할당). 산하 팀 40인이 더 없으면 그대로 둔다 (MLB 현역 + 부상자만으로 40 초과는 사실상 없음).
     *
     * @return 제외한 인원
     */
    static int trimFortyMan(CareerRuntime rt, int team) {
        int over = RosterMoves.fortyMan(rt, team) - RosterMoves.FORTY_MAN;
        int removed = 0;
        for (; over > 0; over--) {
            CareerRuntime.Player worst = null;
            for (int level = 1; level <= 2; level++) {
                for (CareerRuntime.Player p : rt.members(level, team)) {
                    if (p.status == CareerRuntime.ST_40MAN && (worst == null || value(p) < value(worst))) {
                        worst = p;
                    }
                }
            }
            if (worst == null) {
                break;
            }
            rt.relocate(worst, worst.level, CareerRuntime.ST_MINOR); // 마이너 경기 출전은 그대로 → 팀 재구성 불필요
            removed++;
        }
        return removed;
    }

    private static void callUp(CareerRuntime rt, CareerRuntime.Player hurt, long day, Events ev) {
        boolean pitcher = hurt.isPitcher();
        boolean starter = pitcher && hurt.card.pitching().stamina() >= 50;
        CareerRuntime.Player best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int level = 1; level <= 2 && best == null; level++) {
            for (CareerRuntime.Player c : rt.members(level, hurt.team)) {
                if (!c.available(day) || c.isPitcher() != pitcher) {
                    continue;
                }
                double score = value(c);
                if (pitcher && starter && c.card.pitching().stamina() >= 50) {
                    score += 5; // 선발 부상엔 선발 자원 우선
                }
                if (!pitcher && c.card.def(Math.clamp(hurt.card.primaryPos(), 2, 9)) > 0) {
                    score += 5; // 같은 포지션 소화 가능 우선
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        if (best != null) {
            move(rt, best, 0, CareerRuntime.ST_ACTIVE, ev);
        }
    }

    /**
     * 산하 팀 인원 재조정 (유형별): AA 가 최소 미만이고 AAA 에 여유가 있으면 AAA 최하위(마이너 계약 우선)를 AA 로,
     * AAA 가 최소 미만이고 AA 에 여유가 있으면 AA 최고 선수를 AAA 로.
     */
    private static void balance(CareerRuntime rt, int team, boolean pitcher, long day, Events ev) {
        int type = pitcher ? 1 : 0;
        int aaa = count(rt, 1, team, pitcher, day);
        int aa = count(rt, 2, team, pitcher, day);
        while (aa < MIN_AA[type] && aaa > MIN_AAA[type]) {
            CareerRuntime.Player down = pick(rt, 1, team, pitcher, day, false);
            if (down == null) {
                break;
            }
            move(rt, down, 2, down.status, ev);
            aaa--;
            aa++;
        }
        while (aaa < MIN_AAA[type] && aa > MIN_AA[type]) {
            CareerRuntime.Player up = pick(rt, 2, team, pitcher, day, true);
            if (up == null) {
                break;
            }
            move(rt, up, 1, up.status, ev);
            aaa++;
            aa--;
        }
        // 구단 전체가 모자라면 대체 선수 영입 (실제로도 시즌 중 마이너 FA 를 데려와 메운다)
        for (int level = 1; level <= 2; level++) {
            int have = level == 1 ? aaa : aa;
            int need = (level == 1 ? MIN_AAA : MIN_AA)[type];
            for (; have < need; have++) {
                boolean needCatcher = !pitcher && catchers(rt, level, team, day) < 2;
                Filler.Signed s = Filler.sign(rt, team, level, pitcher, needCatcher, LocalDate.ofEpochDay(day));
                rt.addPlayer(s.player());
                ev.signed.add(s);
                ev.touch(level, team);
            }
        }
    }

    private static int catchers(CareerRuntime rt, int level, int team, long day) {
        int n = 0;
        for (CareerRuntime.Player p : rt.members(level, team)) {
            if (p.available(day) && p.card.batting() != null && p.card.def(2) > 0) {
                n++;
            }
        }
        return n;
    }

    static int count(CareerRuntime rt, int level, int team, boolean pitcher, long day) {
        int n = 0;
        for (CareerRuntime.Player p : rt.members(level, team)) {
            if (p.isPitcher() == pitcher && p.available(day)) {
                n++;
            }
        }
        return n;
    }

    /** best=true: 가장 좋은 선수, false: 가장 낮은 선수 (40인은 내리지 않도록 마이너 계약 우선) */
    private static CareerRuntime.Player pick(CareerRuntime rt, int level, int team, boolean pitcher, long day,
                                             boolean best) {
        CareerRuntime.Player chosen = null;
        double chosenScore = 0;
        for (CareerRuntime.Player p : rt.members(level, team)) {
            if (p.isPitcher() != pitcher || !p.available(day)) {
                continue;
            }
            double score = best ? value(p) : -value(p) + (p.status == CareerRuntime.ST_MINOR ? 100 : 0);
            if (chosen == null || score > chosenScore) {
                chosen = p;
                chosenScore = score;
            }
        }
        return chosen;
    }

    /** 같은 유형 MLB 현역 중 가장 낮은 선수를 AAA 로 (유저 지정 선수는 다른 후보가 없을 때만) */
    private static void sendDown(CareerRuntime rt, int team, boolean pitcher, CareerRuntime.Player keep, Events ev) {
        CareerRuntime.Player worst = null;
        for (CareerRuntime.Player p : rt.members(0, team)) {
            if (p == keep || p.status != CareerRuntime.ST_ACTIVE || p.isPitcher() != pitcher) {
                continue;
            }
            if (worst == null || demoteScore(rt, p) < demoteScore(rt, worst)) {
                worst = p;
            }
        }
        if (worst != null) {
            move(rt, worst, 1, CareerRuntime.ST_40MAN, ev);
        }
    }

    /**
     * 트레이드 직후 구단 정리: MLB 현역이 26명을 넘으면 많은 유형부터 가장 낮은 선수를 AAA 로, 모자라면 산하 팀에서 채운 뒤
     * 바뀐 레벨의 팀 구성을 다시 만든다.
     */
    static void afterTrade(CareerRuntime rt, int team, long day) {
        Events ev = new Events();
        for (int level = 0; level < CareerRuntime.LEVELS; level++) {
            ev.touch(level, team);
        }
        for (int guard = 0; guard < 10 && RosterMoves.active(rt, team, null) > MLB_ACTIVE; guard++) {
            int pitchers = RosterMoves.active(rt, team, true);
            int hitters = RosterMoves.active(rt, team, false);
            sendDown(rt, team, pitchers > hitters || pitchers > MLB_PER_TYPE && hitters <= MLB_PER_TYPE, null, ev);
        }
        fillMlb(rt, team, day, ev);
        rebuildTouched(rt, day, ev);
    }

    /** MLB 현역 26인 */
    static final int MLB_ACTIVE = 26;
    /** MLB 현역 유형별 기본 구성 (타자 13 + 투수 13) */
    static final int MLB_PER_TYPE = 13;

    /**
     * MLB 현역이 26명보다 적으면 (유저가 내리기만 했거나 대체 콜업 실패) 모자란 유형부터 산하 팀 최고 선수로 채운다.
     * AI 구단은 부상 대체·복귀 강등이 짝을 이뤄 평소엔 할 일이 없다.
     */
    static void fillMlb(CareerRuntime rt, int team, long day, Events ev) {
        int[] have = new int[2];
        for (CareerRuntime.Player p : rt.members(0, team)) {
            if (p.status == CareerRuntime.ST_ACTIVE) {
                have[p.isPitcher() ? 1 : 0]++;
            }
        }
        while (have[0] + have[1] < MLB_ACTIVE) {
            boolean pitcher = have[0] >= MLB_PER_TYPE || (have[1] < MLB_PER_TYPE && have[1] < have[0]);
            CareerRuntime.Player best = bestMinor(rt, team, pitcher, day);
            if (best == null) {
                pitcher = !pitcher;
                best = bestMinor(rt, team, pitcher, day);
            }
            if (best == null) {
                return;
            }
            move(rt, best, 0, CareerRuntime.ST_ACTIVE, ev);
            have[pitcher ? 1 : 0]++;
        }
    }

    private static CareerRuntime.Player bestMinor(CareerRuntime rt, int team, boolean pitcher, long day) {
        CareerRuntime.Player best = null;
        for (int level = 1; level <= 2 && best == null; level++) {
            for (CareerRuntime.Player p : rt.members(level, team)) {
                if (p.isPitcher() == pitcher && p.available(day) && (best == null || value(p) > value(best))) {
                    best = p;
                }
            }
        }
        return best;
    }

    private static void weeklySwap(CareerRuntime rt, int team, boolean pitcher, long today, Events ev) {
        CareerRuntime.Player worst = null;
        for (CareerRuntime.Player p : rt.members(0, team)) {
            if (p.status == CareerRuntime.ST_ACTIVE && p.isPitcher() == pitcher && !rt.planned(p)
                    && (worst == null || value(p) < value(worst))) {
                worst = p;
            }
        }
        if (worst == null) {
            return;
        }
        CareerRuntime.Player best = null;
        for (CareerRuntime.Player p : rt.members(1, team)) {
            if (p.status == CareerRuntime.ST_40MAN && p.available(today) && p.isPitcher() == pitcher
                    && (pitcher || canReplace(p.card, worst.card))
                    && (best == null || value(p) > value(best))) {
                best = p;
            }
        }
        if (best != null && value(best) - value(worst) >= SWAP_MARGIN) {
            move(rt, worst, 1, CareerRuntime.ST_40MAN, ev);
            move(rt, best, 0, CareerRuntime.ST_ACTIVE, ev);
        }
    }

    private static boolean canReplace(PlayerCard in, PlayerCard out) {
        int pos = out.primaryPos();
        return pos < 2 || pos > 9 || pos == 3 || in.def(pos) > 0;
    }

    private static void move(CareerRuntime rt, CareerRuntime.Player p, int level, int status, Events ev) {
        ev.touch(p.level, p.team);
        rt.relocate(p, level, status);
        ev.moves++;
        ev.touch(level, p.team);
    }

    /** 강등 순위: 낮을수록 먼저 내림. 유저 지정 선수는 맨 뒤로 */
    private static double demoteScore(CareerRuntime rt, CareerRuntime.Player p) {
        return value(p) + (rt.planned(p) ? 1000 : 0);
    }

    static double value(CareerRuntime.Player p) {
        return p.isPitcher() ? p.card.pitchingQuality() : p.card.offense();
    }

    private static void rebuildTouched(CareerRuntime rt, long day, Events ev) {
        for (long key : ev.touched) {
            rt.rebuild((int) (key >>> 32), (int) key, day);
        }
        ev.touched.clear();
    }
}
