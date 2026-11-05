package com.mlbgame.roster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mlbgame.sim.Hand;
import com.mlbgame.sim.Performance;
import com.mlbgame.sim.RateModel;
import com.mlbgame.sim.SimBatter;
import com.mlbgame.sim.SimPitcher;
import com.mlbgame.sim.SimTeam;

/**
 * 팀 운영: 라인업·수비 위치·선발 로테이션·불펜 구성. AI 구단은 전부 자동, 유저 팀은 {@link TeamPlan} 지정을 우선 적용하고
 * 지정 선수가 오늘 뛸 수 없는 자리(부상·강등)만 자동으로 메운다.
 * <p>
 * 결과로 선발 투수별 {@link SimTeam} 을 미리 만들어 둔다 → 경기마다 객체를 새로 만들지 않음.
 */
public final class AutoManager {

    /** 수비 배치 순서 (대체 선수가 적은 포지션부터): C, SS, CF, 2B, 3B, RF, LF, 1B */
    private static final int[] FILL_ORDER = {2, 6, 8, 4, 5, 9, 7, 3};
    /** 포지션별 수비 비중 (인덱스 = 포지션 번호) */
    private static final double[] DEF_WEIGHT = {0, 0, 0.8, 0.2, 0.6, 0.5, 0.8, 0.3, 0.7, 0.4};
    private static final int NO_DEF_RATING = 25;
    private static final int ROTATION_SIZE = TeamPlan.ROTATION_SIZE;

    private final RateModel model;

    public AutoManager(RateModel model) {
        this.model = model;
    }

    /**
     * 타순 1벌.
     *
     * @param cards         타순 (0~8)
     * @param positions     타순별 수비 위치 (2~9, 10 = DH)
     * @param sim           cards 와 같은 순서의 시뮬용 타자
     * @param bench         타순에 들지 않은 타자 (공격력 순), 휴식일 대체 선수
     * @param benchSim      bench 와 같은 순서의 시뮬용 타자
     */
    public record Lineup(PlayerCard[] cards, int[] positions, SimBatter[] sim, PlayerCard[] bench,
                         SimBatter[] benchSim, double defenseFactor, double catcherArmZ) {
    }

    /**
     * @param main      기본 타순 (우투수 상대, 지정이 없으면 유일한 타순)
     * @param vsLeft    좌투수 상대 타순 (유저 지정 시에만, 아니면 null)
     * @param byStarter 로테이션 순서대로, 해당 선발이 나오는 경기의 SimTeam (기본 타순)
     * @param manual    유저가 타순을 지정함 → 자동 플래툰 교체 안 함
     * @param autoRest  주전 휴식일 자동 교체 여부
     */
    public record TeamSetup(int teamId, Lineup main, Lineup vsLeft, PlayerCard[] rotation, PlayerCard[] bullpen,
                            SimTeam[] byStarter, boolean manual, boolean autoRest) {

        public PlayerCard[] lineup() {
            return main.cards();
        }

        public int[] positions() {
            return main.positions();
        }

        public PlayerCard[] bench() {
            return main.bench();
        }

        public SimBatter[] benchSim() {
            return main.benchSim();
        }

        /** 상대 선발 손에 맞는 타순 */
        public Lineup lineupFor(Hand opposingStarter) {
            return opposingStarter == Hand.LEFT && vsLeft != null ? vsLeft : main;
        }
    }

    public TeamSetup build(int teamId, List<PlayerCard> roster) {
        return build(teamId, roster, null);
    }

    /** @param plan 유저 지정 (null = 전부 자동) */
    public TeamSetup build(int teamId, List<PlayerCard> roster, TeamPlan plan) {
        List<PlayerCard> hitters = roster.stream().filter(c -> c.batting() != null).toList();
        List<PlayerCard> arms = roster.stream().filter(c -> c.pitching() != null).toList();
        if (hitters.size() < 9) {
            throw new IllegalStateException("팀 " + teamId + ": 타자가 9명 미만 (" + hitters.size() + ")");
        }
        if (arms.isEmpty()) {
            throw new IllegalStateException("팀 " + teamId + ": 투수 없음");
        }

        Lineup main = lineup(hitters, plan == null ? null : plan.vsRight());
        Lineup vsLeft = plan == null || plan.vsLeft() == null ? null : lineup(hitters, plan.vsLeft());

        // ---- 로테이션: 지정 순서 → 모자라면 스태미나 상위로 5명까지
        Map<Integer, PlayerCard> armById = new HashMap<>();
        arms.forEach(c -> armById.put(c.playerId(), c));
        List<PlayerCard> byStamina = new ArrayList<>(arms);
        byStamina.sort(Comparator.comparingInt((PlayerCard c) -> c.pitching().stamina())
                .thenComparingDouble(PlayerCard::pitchingQuality).reversed());
        int rotationSize = Math.min(ROTATION_SIZE, arms.size());
        List<PlayerCard> rotationList = new ArrayList<>(rotationSize);
        Set<Integer> inRotation = new HashSet<>();
        if (plan != null && plan.rotation() != null) {
            for (int id : plan.rotation()) {
                PlayerCard c = armById.get(id);
                if (c != null && rotationList.size() < rotationSize && inRotation.add(id)) {
                    rotationList.add(c);
                }
            }
        }
        for (PlayerCard c : byStamina) {
            if (rotationList.size() >= rotationSize) {
                break;
            }
            if (inRotation.add(c.playerId())) {
                rotationList.add(c);
            }
        }
        PlayerCard[] rotation = rotationList.toArray(PlayerCard[]::new);

        // ---- 불펜: 지정 순서 ([0] = 마무리) → 나머지 구위 순 (맨 뒤 = 패전조)
        List<PlayerCard> pen = new ArrayList<>();
        Set<Integer> inPen = new HashSet<>();
        if (plan != null && plan.bullpen() != null) {
            for (int id : plan.bullpen()) {
                PlayerCard c = armById.get(id);
                if (c != null && !inRotation.contains(id) && inPen.add(id)) {
                    pen.add(c);
                }
            }
        }
        List<PlayerCard> rest = new ArrayList<>();
        for (PlayerCard c : arms) {
            if (!inRotation.contains(c.playerId()) && !inPen.contains(c.playerId())) {
                rest.add(c);
            }
        }
        rest.sort(Comparator.comparingDouble(PlayerCard::pitchingQuality).reversed());
        pen.addAll(rest);
        PlayerCard[] bullpen = pen.toArray(PlayerCard[]::new);
        SimPitcher[] simPen = new SimPitcher[bullpen.length];
        for (int i = 0; i < bullpen.length; i++) {
            simPen[i] = pitcher(bullpen[i]);
        }

        SimTeam[] byStarter = new SimTeam[rotation.length];
        for (int i = 0; i < rotation.length; i++) {
            byStarter[i] = new SimTeam(teamId, main.sim(), pitcher(rotation[i]), simPen, main.defenseFactor(),
                    main.catcherArmZ());
        }
        boolean manual = plan != null && plan.manualLineup();
        boolean autoRest = plan == null || plan.autoRest();
        return new TeamSetup(teamId, main, vsLeft, rotation, bullpen, byStarter, manual, autoRest);
    }

    /**
     * 타순 구성. 지정 자리 중 오늘 뛸 수 있는 선수는 그대로 두고, 빈 수비 위치는 자동 규칙(수비 비중 + 공격력)으로 채운다.
     * 자동으로 채운 선수는 공격력 순으로 빈 타순에 들어간다 (지정이 없으면 = 전체 공격력 순 타순).
     */
    private Lineup lineup(List<PlayerCard> hitters, TeamPlan.Slot[] plan) {
        Map<Integer, PlayerCard> byId = new HashMap<>();
        hitters.forEach(c -> byId.put(c.playerId(), c));
        PlayerCard[] cards = new PlayerCard[9];
        int[] positions = new int[9];
        Set<Integer> used = new HashSet<>();
        boolean[] posTaken = new boolean[TeamPlan.DH + 1];
        if (plan != null) {
            for (int slot = 0; slot < Math.min(9, plan.length); slot++) {
                TeamPlan.Slot s = plan[slot];
                PlayerCard c = byId.get(s.playerId());
                if (c == null || s.pos() < 2 || s.pos() > TeamPlan.DH || posTaken[s.pos()]
                        || !used.add(c.playerId())) {
                    continue;
                }
                cards[slot] = c;
                positions[slot] = s.pos();
                posTaken[s.pos()] = true;
            }
        }

        // ---- 빈 수비 위치 자동 배치 + 지명타자
        List<PlayerCard> filled = new ArrayList<>();
        List<Integer> filledPos = new ArrayList<>();
        for (int pos : FILL_ORDER) {
            if (posTaken[pos]) {
                continue;
            }
            PlayerCard best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (PlayerCard c : hitters) {
                if (used.contains(c.playerId()) || c.def(pos) == 0) {
                    continue;
                }
                double score = c.offense() + DEF_WEIGHT[pos] * (c.def(pos) - 50);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best == null) { // 그 포지션 소화 가능 선수가 없으면 타격 최고 선수를 낮은 수비로 배치
                best = bestOffense(hitters, used);
            }
            used.add(best.playerId());
            filled.add(best);
            filledPos.add(pos);
        }
        if (!posTaken[TeamPlan.DH]) {
            PlayerCard dh = bestOffense(hitters, used);
            used.add(dh.playerId());
            filled.add(dh);
            filledPos.add(TeamPlan.DH);
        }
        Integer[] order = new Integer[filled.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble((Integer i) -> filled.get(i).offense()).reversed());
        int next = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (cards[slot] == null) {
                cards[slot] = filled.get(order[next]);
                positions[slot] = filledPos.get(order[next]);
                next++;
            }
        }

        double defSum = 0;
        int catcherArm = 50;
        SimBatter[] sim = new SimBatter[9];
        for (int slot = 0; slot < 9; slot++) {
            PlayerCard c = cards[slot];
            sim[slot] = batter(c);
            int pos = positions[slot];
            if (pos != TeamPlan.DH) {
                defSum += c.def(pos) == 0 ? NO_DEF_RATING : c.def(pos);
            }
            if (pos == 2) {
                catcherArm = c.arm();
            }
        }

        List<PlayerCard> benchList = new ArrayList<>();
        for (PlayerCard c : hitters) {
            if (!used.contains(c.playerId())) {
                benchList.add(c);
            }
        }
        benchList.sort(Comparator.comparingDouble(PlayerCard::offense).reversed());
        PlayerCard[] bench = benchList.toArray(PlayerCard[]::new);
        SimBatter[] benchSim = new SimBatter[bench.length];
        for (int i = 0; i < bench.length; i++) {
            benchSim[i] = batter(bench[i]);
        }
        return new Lineup(cards, positions, sim, bench, benchSim,
                RateModel.defenseFactor(defSum / FILL_ORDER.length), RateModel.z(catcherArm));
    }

    private SimBatter batter(PlayerCard c) {
        return model.batter(c.playerId(), c.batting(), c.bats(), Performance.NEUTRAL);
    }

    private SimPitcher pitcher(PlayerCard c) {
        return model.pitcher(c.playerId(), c.pitching(), c.throwsHand(), Performance.NEUTRAL);
    }

    private static PlayerCard bestOffense(List<PlayerCard> hitters, Set<Integer> used) {
        PlayerCard best = null;
        for (PlayerCard c : hitters) {
            if (!used.contains(c.playerId()) && (best == null || c.offense() > best.offense())) {
                best = c;
            }
        }
        if (best == null) {
            throw new IllegalStateException("배치할 타자가 부족함");
        }
        return best;
    }
}
