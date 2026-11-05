package com.mlbgame.sim;

import static com.mlbgame.sim.Outcomes.AIR_OUT;
import static com.mlbgame.sim.Outcomes.DOUBLE;
import static com.mlbgame.sim.Outcomes.GROUND_OUT;
import static com.mlbgame.sim.Outcomes.HIT_BY_PITCH;
import static com.mlbgame.sim.Outcomes.HOME_RUN;
import static com.mlbgame.sim.Outcomes.SINGLE;
import static com.mlbgame.sim.Outcomes.STRIKEOUT;
import static com.mlbgame.sim.Outcomes.TRIPLE;
import static com.mlbgame.sim.Outcomes.WALK;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 타석 단위 경기 시뮬레이터.
 * <p>
 * 내부 상태를 재사용하므로 <b>스레드 안전하지 않다</b>. 스레드당 인스턴스 1개를 쓴다.
 * 같은 입력과 seed 면 항상 같은 결과가 나온다.
 */
public final class GameSimulator {

    public static final int AWAY = 0;
    public static final int HOME = 1;
    /** 안전장치. 연장 승부치기 규칙상 사실상 도달하지 않는다. */
    public static final int MAX_INNINGS = 30;

    private static final int EMPTY = -1;
    /** 결과별 기본 투구 수. 실제 투구 수 = 기본 + 0~3 */
    private static final int[] PITCH_BASE = {3, 4, 1, 2, 2, 2, 2, 2, 2};
    /** 선발은 타순을 세 바퀴(27타자) 돌면 다음 이닝 시작 때 교체 (현대 MLB 운용) */
    static final int STARTER_MAX_BF = 27;
    /** 이 점수차 이상이면 필승조 대신 불펜 뒤쪽(추격조)부터 투입 */
    static final int MOP_UP_MARGIN = 4;
    /** 병살이 아닌 1루 주자 상황 땅볼 중 선행 주자 포스아웃(타자는 1루 생존) 비율 */
    private static final double FIELDERS_CHOICE = 0.40;
    /** 추가 진루 시도 중 주루사 확률: 2루→홈(단타), 1루→3루(단타), 1루→홈(2루타) */
    private static final double OUT_AT_HOME_ON_SINGLE = 0.05;
    private static final double OUT_AT_THIRD = 0.04;
    private static final double OUT_AT_HOME_ON_DOUBLE = 0.06;

    private final double platoonEdge;
    private final double groundErrorRate;
    private final double airErrorRate;
    private final double wildPitchRate;

    private SplittableRandom rng;
    private final SimTeam[] teams = new SimTeam[2];
    private final int[] battingIdx = new int[2];
    private final int[] score = new int[2];
    private final SimPitcher[] curPitcher = new SimPitcher[2];
    private final PitcherLine[] curLine = new PitcherLine[2];
    private final int[] nextReliever = new int[2];
    /** 점수차가 큰 경기용: 불펜 뒤쪽(추격조)부터 거꾸로 */
    private final int[] nextMopUp = new int[2];
    private final boolean[] closerUsed = new boolean[2];
    private final PitcherLine[] winCandidate = new PitcherLine[2];
    private final PitcherLine[] loseCandidate = new PitcherLine[2];

    // 베이스 상태: [0]=1루, [1]=2루, [2]=3루. 값은 타순(0~8), 비었으면 EMPTY
    private final int[] runners = new int[3];
    private final PitcherLine[] runnerPitcher = new PitcherLine[3];
    private final boolean[] runnerEarned = new boolean[3];

    private int inning;
    private int outs;
    /** 실책이 없었다면 아웃이었을 수. 아웃 + 이 값이 3 이상이 된 뒤의 득점은 비자책 */
    private int phantomOuts;
    private int bat;
    private int fld;

    private int[][][] box;
    private int[][] lineScore;
    private List<PitcherLine> awayLines;
    private List<PitcherLine> homeLines;

    public GameSimulator(LeagueBaseline league) {
        this.platoonEdge = league.platoonEdge();
        this.groundErrorRate = league.errorRate() * 1.4;
        this.airErrorRate = league.errorRate() * 0.6;
        this.wildPitchRate = league.wildPitchRate();
    }

    public GameResult simulate(SimTeam away, SimTeam home, long seed) {
        reset(away, home, seed);

        int played = MAX_INNINGS;
        for (inning = 1; inning <= MAX_INNINGS; inning++) {
            playHalf(AWAY);
            if (inning >= 9 && score[HOME] > score[AWAY]) {
                lineScore[HOME][inning - 1] = -1;
                played = inning;
                break;
            }
            playHalf(HOME);
            if (inning >= 9 && score[HOME] != score[AWAY]) {
                played = inning;
                break;
            }
        }

        long wp = 0;
        long lp = 0;
        long sv = 0;
        if (score[HOME] != score[AWAY]) {
            int win = score[HOME] > score[AWAY] ? HOME : AWAY;
            List<PitcherLine> winLines = lines(win);

            PitcherLine w = winCandidate[win];
            if (w.starter && w.outs < 15 && winLines.size() > 1) {
                // 선발이 5이닝 미만이면 첫 구원 투수에게 승리 (간이 규칙)
                w = winLines.get(1);
            }
            PitcherLine l = loseCandidate[1 - win];
            PitcherLine last = winLines.getLast();
            boolean save = last != w && last.leadOnEntry > 0
                    && ((last.leadOnEntry <= 3 && last.outs >= 3) || last.outs >= 9);

            w.decision = 'W';
            l.decision = 'L';
            wp = w.playerId;
            lp = l.playerId;
            if (save) {
                last.decision = 'S';
                sv = last.playerId;
            }
        }

        return new GameResult(score[AWAY], score[HOME], played,
                new int[][] {Arrays.copyOf(lineScore[AWAY], played), Arrays.copyOf(lineScore[HOME], played)},
                box, awayLines, homeLines, wp, lp, sv);
    }

    // ------------------------------------------------------------------ 진행

    private void reset(SimTeam away, SimTeam home, long seed) {
        rng = new SplittableRandom(seed);
        teams[AWAY] = away;
        teams[HOME] = home;
        Arrays.fill(battingIdx, 0);
        Arrays.fill(score, 0);
        Arrays.fill(nextReliever, 1);
        nextMopUp[AWAY] = away.bullpen().length - 1;
        nextMopUp[HOME] = home.bullpen().length - 1;
        Arrays.fill(closerUsed, false);
        Arrays.fill(winCandidate, null);
        Arrays.fill(loseCandidate, null);

        box = new int[2][9][BatStat.COUNT];
        lineScore = new int[2][MAX_INNINGS];
        awayLines = new ArrayList<>(8);
        homeLines = new ArrayList<>(8);

        startPitcher(AWAY, away.starter());
        startPitcher(HOME, home.starter());
    }

    private void playHalf(int side) {
        bat = side;
        fld = 1 - side;
        outs = 0;
        phantomOuts = 0;
        clearBases();
        maybeChangePitcher(true);

        if (inning > 9) {
            // 승부치기: 직전 타순 선수를 2루에. 이 주자의 득점은 비자책
            place(1, (battingIdx[bat] + 8) % 9, curLine[fld], false);
        }

        while (outs < 3) {
            if (bat == HOME && inning >= 9 && score[HOME] > score[AWAY]) {
                return; // 끝내기
            }
            wildPitch();
            if (bat == HOME && inning >= 9 && score[HOME] > score[AWAY]) {
                return; // 폭투 끝내기
            }
            attemptSteal();
            if (outs >= 3) {
                break;
            }
            maybeChangePitcher(false);
            plateAppearance();
        }
    }

    private void plateAppearance() {
        int slot = battingIdx[bat];
        battingIdx[bat] = slot == 8 ? 0 : slot + 1;

        SimBatter b = teams[bat].lineup()[slot];
        PitcherLine pl = curLine[fld];
        int[] s = box[bat][slot];

        int o = drawOutcome(b, curPitcher[fld]);
        s[BatStat.PA]++;
        pl.battersFaced++;
        pl.pitches += PITCH_BASE[o] + rng.nextInt(4);

        switch (o) {
            case STRIKEOUT -> {
                s[BatStat.AB]++;
                s[BatStat.SO]++;
                pl.strikeouts++;
                addOuts(1);
            }
            case WALK -> {
                s[BatStat.BB]++;
                pl.walks++;
                forceAdvance(slot);
            }
            case HIT_BY_PITCH -> {
                s[BatStat.HBP]++;
                pl.hitByPitch++;
                forceAdvance(slot);
            }
            case SINGLE -> {
                s[BatStat.AB]++;
                s[BatStat.H]++;
                pl.hits++;
                single(slot);
            }
            case DOUBLE -> {
                s[BatStat.AB]++;
                s[BatStat.H]++;
                s[BatStat.B2]++;
                pl.hits++;
                doubleHit(slot);
            }
            case TRIPLE -> {
                s[BatStat.AB]++;
                s[BatStat.H]++;
                s[BatStat.B3]++;
                pl.hits++;
                scoreAll(slot);
                place(2, slot, pl, true);
            }
            case HOME_RUN -> {
                s[BatStat.AB]++;
                s[BatStat.H]++;
                s[BatStat.HR]++;
                pl.hits++;
                pl.homeRuns++;
                scoreAll(slot);
                run(slot, pl, true, slot);
            }
            case GROUND_OUT -> groundOut(slot, s);
            case AIR_OUT -> airOut(slot, s);
            default -> throw new IllegalStateException("unknown outcome " + o);
        }
    }

    /**
     * 결과 추첨. 가중치_i = 타자확률_i × 투수비율_i × 보정. 곱셈 9번 + 난수 1개, 할당 없음.
     */
    private int drawOutcome(SimBatter b, SimPitcher p) {
        double[] br = b.rates();
        double[] pr = p.ratios();
        double edge = (b.hand() == Hand.SWITCH || b.hand() != p.hand()) ? 1 + platoonEdge : 1 - platoonEdge;
        double good = b.performance() * edge;
        double bad = p.performance();
        double inPlayHit = good * teams[fld].defenseFactor();

        double w0 = br[0] * pr[0] * bad;
        double w1 = br[1] * pr[1] * good;
        double w2 = br[2] * pr[2] * good;
        double w3 = br[3] * pr[3] * inPlayHit;
        double w4 = br[4] * pr[4] * inPlayHit;
        double w5 = br[5] * pr[5] * inPlayHit;
        double w6 = br[6] * pr[6] * good;
        double w7 = br[7] * pr[7] * bad;
        double w8 = br[8] * pr[8] * bad;

        double x = rng.nextDouble() * (w0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);
        if ((x -= w0) < 0) {
            return STRIKEOUT;
        }
        if ((x -= w1) < 0) {
            return WALK;
        }
        if ((x -= w2) < 0) {
            return HIT_BY_PITCH;
        }
        if ((x -= w3) < 0) {
            return SINGLE;
        }
        if ((x -= w4) < 0) {
            return DOUBLE;
        }
        if ((x -= w5) < 0) {
            return TRIPLE;
        }
        if ((x -= w6) < 0) {
            return HOME_RUN;
        }
        if ((x -= w7) < 0) {
            return GROUND_OUT;
        }
        return AIR_OUT;
    }

    // ------------------------------------------------------------------ 타격 결과 처리

    private void forceAdvance(int slot) {
        if (runners[0] != EMPTY) {
            if (runners[1] != EMPTY) {
                if (runners[2] != EMPTY) {
                    scoreFrom(2, slot);
                }
                move(1, 2);
            }
            move(0, 1);
        }
        place(0, slot, curLine[fld], true);
    }

    private void single(int slot) {
        if (runners[2] != EMPTY) {
            scoreFrom(2, slot);
        }
        if (runners[1] != EMPTY) {
            double p = outs == 2 ? 0.85 : 0.55 + 0.07 * speed(1);
            if (rng.nextDouble() < p) {
                if (rng.nextDouble() < OUT_AT_HOME_ON_SINGLE) {
                    clearBase(1);
                    addOuts(1);
                    if (outs >= 3) {
                        return;
                    }
                } else {
                    scoreFrom(1, slot);
                }
            } else {
                move(1, 2);
            }
        }
        if (runners[0] != EMPTY) {
            if (runners[2] == EMPTY && rng.nextDouble() < 0.28 + 0.06 * speed(0)) {
                if (rng.nextDouble() < OUT_AT_THIRD) {
                    clearBase(0);
                    addOuts(1);
                    if (outs >= 3) {
                        return;
                    }
                } else {
                    move(0, 2);
                }
            } else {
                move(0, 1);
            }
        }
        place(0, slot, curLine[fld], true);
    }

    private void doubleHit(int slot) {
        if (runners[2] != EMPTY) {
            scoreFrom(2, slot);
        }
        if (runners[1] != EMPTY) {
            scoreFrom(1, slot);
        }
        if (runners[0] != EMPTY) {
            double p = (outs == 2 ? 0.60 : 0.40) + 0.08 * speed(0);
            if (rng.nextDouble() < p) {
                if (rng.nextDouble() < OUT_AT_HOME_ON_DOUBLE) {
                    clearBase(0);
                    addOuts(1);
                    if (outs >= 3) {
                        return;
                    }
                } else {
                    scoreFrom(0, slot);
                }
            } else {
                move(0, 2);
            }
        }
        place(1, slot, curLine[fld], true);
    }

    private void groundOut(int slot, int[] s) {
        s[BatStat.AB]++;
        if (reachedOnError(slot, groundErrorRate)) {
            return;
        }

        if (outs < 2 && runners[0] != EMPTY) {
            double dp = Math.clamp(0.45 - 0.06 * teams[bat].lineup()[slot].speedZ(), 0.15, 0.70);
            if (rng.nextDouble() < dp) {
                s[BatStat.GIDP]++;
                clearBase(0);
                addOuts(2);
                if (outs < 3) {
                    if (runners[2] != EMPTY) {
                        scoreFrom(2, EMPTY); // 병살 중 득점은 타점 없음
                    }
                    if (runners[1] != EMPTY) {
                        move(1, 2);
                    }
                }
                return;
            }
        }

        if (outs < 2 && runners[0] != EMPTY && rng.nextDouble() < FIELDERS_CHOICE) {
            // 야수 선택: 1루 주자 2루 포스아웃, 타자 1루. 만루면 3루 주자 득점, 2루 주자는 밀려서 3루
            addOuts(1);
            if (runners[1] != EMPTY) {
                if (runners[2] != EMPTY) {
                    scoreFrom(2, slot);
                }
                move(1, 2);
            }
            clearBase(0);
            place(0, slot, curLine[fld], true);
            return;
        }

        addOuts(1);
        if (outs >= 3) {
            return;
        }
        boolean on1 = runners[0] != EMPTY;
        boolean on2 = runners[1] != EMPTY;
        if (runners[2] != EMPTY && ((on1 && on2) || rng.nextDouble() < 0.5)) {
            scoreFrom(2, slot);
        }
        if (runners[1] != EMPTY && runners[2] == EMPTY && (on1 || rng.nextDouble() < 0.6)) {
            move(1, 2);
        }
        if (runners[0] != EMPTY && runners[1] == EMPTY) {
            move(0, 1);
        }
    }

    private void airOut(int slot, int[] s) {
        s[BatStat.AB]++;
        if (reachedOnError(slot, airErrorRate)) {
            return;
        }
        addOuts(1);
        if (outs >= 3) {
            return;
        }
        if (runners[2] != EMPTY && rng.nextDouble() < 0.45 + 0.06 * speed(2)) {
            scoreFrom(2, slot);
            s[BatStat.AB]--;
            s[BatStat.SF]++;
        }
        if (runners[1] != EMPTY && runners[2] == EMPTY && rng.nextDouble() < 0.25) {
            move(1, 2);
        }
    }

    /**
     * 실책 출루: 타자 1루, 모든 주자 한 베이스 진루. 타자 주자와 이 플레이의 득점은 비자책, 타점 없음.
     * 기록상 타자는 타수 1, 안타 0 (실제 기록 방식과 같음). 수비가 나쁠수록 자주 발생.
     */
    private boolean reachedOnError(int slot, double rate) {
        if (rng.nextDouble() >= rate * teams[fld].defenseFactor()) {
            return false;
        }
        if (runners[2] != EMPTY) {
            runnerEarned[2] = false;
            scoreFrom(2, EMPTY);
        }
        if (runners[1] != EMPTY) {
            move(1, 2);
        }
        if (runners[0] != EMPTY) {
            move(0, 1);
        }
        place(0, slot, curLine[fld], false);
        phantomOuts++;
        return true;
    }

    /** 폭투·포일: 주자가 있을 때 타석 전에 일정 확률로 모든 주자 한 베이스 진루. 3루 주자 득점(타점 없음). */
    private void wildPitch() {
        if (runners[0] == EMPTY && runners[1] == EMPTY && runners[2] == EMPTY) {
            return;
        }
        if (rng.nextDouble() >= wildPitchRate) {
            return;
        }
        if (runners[2] != EMPTY) {
            scoreFrom(2, EMPTY);
        }
        if (runners[1] != EMPTY) {
            move(1, 2);
        }
        if (runners[0] != EMPTY) {
            move(0, 1);
        }
    }

    private void attemptSteal() {
        if (runners[0] == EMPTY || runners[1] != EMPTY) {
            return;
        }
        SimBatter r = teams[bat].lineup()[runners[0]];
        double attempt = Math.min(0.40, 0.08 * Math.exp(0.6 * r.stealZ()));
        if (rng.nextDouble() >= attempt) {
            return;
        }
        double success = Math.clamp(0.78 + 0.05 * r.speedZ() - 0.04 * teams[fld].catcherArmZ(), 0.45, 0.95);
        int[] s = box[bat][runners[0]];
        if (rng.nextDouble() < success) {
            s[BatStat.SB]++;
            move(0, 1);
        } else {
            s[BatStat.CS]++;
            clearBase(0);
            addOuts(1);
        }
    }

    // ------------------------------------------------------------------ 투수 운용

    private void startPitcher(int side, SimPitcher p) {
        PitcherLine line = new PitcherLine(p.playerId(), true, 0);
        lines(side).add(line);
        curPitcher[side] = p;
        curLine[side] = line;
    }

    private void bringIn(SimPitcher p) {
        PitcherLine line = new PitcherLine(p.playerId(), false, score[fld] - score[bat]);
        lines(fld).add(line);
        curPitcher[fld] = p;
        curLine[fld] = line;
    }

    private void maybeChangePitcher(boolean inningStart) {
        SimPitcher[] pen = teams[fld].bullpen();
        PitcherLine line = curLine[fld];

        if (inningStart && inning >= 9 && !closerUsed[fld] && pen.length > 0) {
            int lead = score[fld] - score[bat];
            if ((lead >= 1 && lead <= 3) || (lead == 0 && inning > 9)) {
                closerUsed[fld] = true;
                bringIn(pen[0]);
                return;
            }
        }

        boolean tired = line.pitches >= curPitcher[fld].pitchLimit();
        boolean change = line.starter
                ? tired || (inningStart && (line.runs >= 6 || line.battersFaced >= STARTER_MAX_BF))
                : tired || (inningStart && line.outs >= 3);
        if (!change) {
            return;
        }
        boolean blowout = Math.abs(score[fld] - score[bat]) >= MOP_UP_MARGIN;
        if (blowout && nextMopUp[fld] >= nextReliever[fld]) {
            bringIn(pen[nextMopUp[fld]--]);
        } else if (nextReliever[fld] <= nextMopUp[fld]) {
            bringIn(pen[nextReliever[fld]++]);
        } else if (!closerUsed[fld] && pen.length > 0) {
            closerUsed[fld] = true;
            bringIn(pen[0]);
        }
        // 불펜 소진 시 현재 투수 유지
    }

    // ------------------------------------------------------------------ 베이스/득점 헬퍼

    private double speed(int base) {
        return teams[bat].lineup()[runners[base]].speedZ();
    }

    private void addOuts(int n) {
        outs += n;
        curLine[fld].outs += n;
    }

    private void clearBases() {
        Arrays.fill(runners, EMPTY);
        Arrays.fill(runnerPitcher, null);
    }

    private void clearBase(int base) {
        runners[base] = EMPTY;
        runnerPitcher[base] = null;
    }

    private void place(int base, int slot, PitcherLine responsible, boolean earned) {
        runners[base] = slot;
        runnerPitcher[base] = responsible;
        runnerEarned[base] = earned;
    }

    private void move(int from, int to) {
        runners[to] = runners[from];
        runnerPitcher[to] = runnerPitcher[from];
        runnerEarned[to] = runnerEarned[from];
        clearBase(from);
    }

    private void scoreAll(int rbiSlot) {
        for (int base = 2; base >= 0; base--) {
            if (runners[base] != EMPTY) {
                scoreFrom(base, rbiSlot);
            }
        }
    }

    private void scoreFrom(int base, int rbiSlot) {
        int slot = runners[base];
        PitcherLine responsible = runnerPitcher[base];
        boolean earned = runnerEarned[base];
        clearBase(base);
        run(slot, responsible, earned, rbiSlot);
    }

    /** 득점 처리. 실점은 주자를 내보낸 투수(책임 투수)에게 기록한다. */
    private void run(int runnerSlot, PitcherLine responsible, boolean earned, int rbiSlot) {
        box[bat][runnerSlot][BatStat.R]++;
        if (rbiSlot != EMPTY) {
            box[bat][rbiSlot][BatStat.RBI]++;
        }
        responsible.runs++;
        if (earned && outs + phantomOuts < 3) {
            responsible.earnedRuns++;
        }
        lineScore[bat][inning - 1]++;
        score[bat]++;
        if (score[bat] == score[fld] + 1) {
            // 역전 또는 리드 시작 → 승/패 후보 갱신
            winCandidate[bat] = curLine[bat];
            loseCandidate[fld] = responsible;
        }
    }

    private List<PitcherLine> lines(int side) {
        return side == AWAY ? awayLines : homeLines;
    }
}
