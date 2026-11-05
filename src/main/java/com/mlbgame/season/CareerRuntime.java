package com.mlbgame.season;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.roster.TeamPlan;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.GameSimulator;

/**
 * 진행 중인 커리어 1개의 메모리 상태. "다음 날"은 이 객체만으로 시뮬하고, DB 는 결과를 쓰기만 한다.
 * <p>
 * 스레드 안전하지 않다 → {@link #lock} 을 잡은 상태에서만 사용 (같은 커리어 동시 진행 방지).
 * 저장이 실패하면 메모리 상태가 DB 보다 앞서 있을 수 있으므로 캐시에서 내리고 다시 읽는다.
 */
final class CareerRuntime {

    static final int LEVELS = 3; // 0=MLB 1=AAA 2=AA
    static final int W = 0, L = 1, RF = 2, RA = 3, STREAK = 4;
    static final int ST_MINOR = 0, ST_40MAN = 1, ST_ACTIVE = 2, ST_INJURED = 3;

    /** 커리어 선수 1명의 변하는 상태. dirty = 오늘 바뀌어서 저장 필요 */
    static final class Player {
        final int id;
        final PlayerCard card;
        final int durability;
        int team;
        int level;
        int status;
        /** 복귀 가능일 (epochDay), 0 = 부상 아님 */
        long injuredUntil;
        boolean dirty;

        Player(int id, PlayerCard card, int durability, int team, int level, int status, long injuredUntil) {
            this.id = id;
            this.card = card;
            this.durability = durability;
            this.team = team;
            this.level = level;
            this.status = status;
            this.injuredUntil = injuredUntil;
        }

        boolean available(long today) {
            return injuredUntil <= today;
        }

        /** 이 레벨 경기에 나설 수 있는가: MLB 는 26인만, 마이너는 부상이 아니면 */
        boolean playsAt(int lv, long today) {
            return level == lv && available(today) && (lv == 0 ? status == ST_ACTIVE : status != ST_INJURED);
        }

        boolean isPitcher() {
            return card.pitching() != null && (card.batting() == null || card.primaryPos() == 1);
        }
    }

    final long careerId;
    final long seed;
    final int userTeamId;
    final List<Integer> teamIds;
    final Map<Integer, Integer> teamIndex;
    final Map<Integer, Player> players;
    final AutoManager[] managers;
    /** [레벨][팀 인덱스] 자동 운영 결과 (선발별 SimTeam 미리 구성) */
    final AutoManager.TeamSetup[][] setups;
    /** [레벨][팀 인덱스][승, 패, 득점, 실점, 연승(+)/연패(-)] */
    final int[][][] standings;
    final GameSimulator[] sims;
    /** (레벨, 구단) → 소속 선수. 이동은 {@link #relocate} 로만 (색인 유지) */
    private final Map<Long, List<Player>> groups = new HashMap<>();
    /** 아직 저장하지 않은 마이너 시즌 기록 (7일마다·단계 전환·캐시에서 내릴 때·종료 때 저장) */
    final PendingStats pending = new PendingStats();
    /** 불펜 피로: 투수 → {@link Fatigue} 상태 */
    final Map<Integer, int[]> pitcherLoad = new HashMap<>();
    final ReentrantLock lock = new ReentrantLock();

    int season;
    LocalDate curDate;
    int phase;
    int nextGameNo;
    int version;

    CareerRuntime(long careerId, long seed, int userTeamId, List<Integer> teamIds, Map<Integer, Integer> teamIndex,
                  Map<Integer, Player> players, AutoManager[] managers, int[][][] standings, GameSimulator[] sims) {
        this.careerId = careerId;
        this.seed = seed;
        this.userTeamId = userTeamId;
        this.teamIds = teamIds;
        this.teamIndex = teamIndex;
        this.players = players;
        this.managers = managers;
        this.setups = new AutoManager.TeamSetup[LEVELS][teamIds.size()];
        this.standings = standings;
        this.sims = sims;
        for (Player p : players.values()) {
            groups.computeIfAbsent(key(p.level, p.team), k -> new ArrayList<>()).add(p);
        }
    }

    /** 이번 시즌 포스트시즌 시리즈 (없으면 아직 시작 전) */
    final List<Series> series = new ArrayList<>();

    /** MLB 순위를 팀 ID 기준 맵으로 (포스트시즌 시드 계산용) */
    Map<Integer, int[]> mlbStandings() {
        Map<Integer, int[]> out = new HashMap<>(teamIds.size() * 2);
        for (int team : teamIds) {
            out.put(team, standing(0, team));
        }
        return out;
    }

    /** 유저 팀 MLB 운영 지정 (c_tactic, null = 전부 자동) */
    TeamPlan userPlan;

    /** 그 레벨·팀에 적용할 지정 (유저 팀 MLB 만) */
    TeamPlan planFor(int level, int teamId) {
        return level == 0 && teamId == userTeamId ? userPlan : null;
    }

    /** 유저가 타순·로테이션·불펜에 지정한 선수인가 (자동 강등 대상에서 뒤로 미룸) */
    boolean planned(Player p) {
        TeamPlan plan = userPlan;
        if (plan == null || p.team != userTeamId) {
            return false;
        }
        for (TeamPlan.Slot[] lineup : new TeamPlan.Slot[][] {plan.vsRight(), plan.vsLeft()}) {
            if (lineup != null) {
                for (TeamPlan.Slot s : lineup) {
                    if (s.playerId() == p.id) {
                        return true;
                    }
                }
            }
        }
        for (int[] ids : new int[][] {plan.rotation(), plan.bullpen()}) {
            if (ids != null) {
                for (int id : ids) {
                    if (id == p.id) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 다음 생성 선수 ID (c_person) */
    int nextPersonId = Filler.FIRST_ID;

    void addPlayer(Player p) {
        players.put(p.id, p);
        groups.computeIfAbsent(key(p.level, p.team), k -> new ArrayList<>()).add(p);
    }

    /** 레벨·상태 변경 (색인·저장 표시 함께) */
    void relocate(Player p, int level, int status) {
        if (p.level != level) {
            groups.get(key(p.level, p.team)).remove(p);
            groups.computeIfAbsent(key(level, p.team), k -> new ArrayList<>()).add(p);
            p.level = level;
        }
        p.status = status;
        p.dirty = true;
    }

    /** 다른 구단으로 이동 (트레이드). 레벨·상태는 그대로, 색인·저장 표시 함께 */
    void transfer(Player p, int team) {
        if (p.team == team) {
            return;
        }
        groups.get(key(p.level, p.team)).remove(p);
        groups.computeIfAbsent(key(p.level, team), k -> new ArrayList<>()).add(p);
        p.team = team;
        p.dirty = true;
    }

    /** 레벨·팀의 현재 출전 가능 선수로 자동 운영 다시 구성 (부상·콜업 등 이벤트가 있을 때만) */
    void rebuild(int level, int teamId, long today) {
        int idx = teamIndex.get(teamId);
        try {
            setups[level][idx] = managers[level].build(teamId, roster(level, teamId, today), planFor(level, teamId));
        } catch (IllegalStateException e) {
            // 부상·콜업이 겹쳐 인원이 모자라면 이전 구성을 유지 (드문 경우, 그날 진행을 막지 않기 위함)
            if (setups[level][idx] == null) {
                throw e;
            }
            shortRosterKeeps++;
        }
    }

    /** 인원 부족으로 이전 구성을 유지한 횟수 (벤치마크 점검용) */
    int shortRosterKeeps;

    void rebuildAll(long today) {
        for (int lv = 0; lv < LEVELS; lv++) {
            for (int team : teamIds) {
                try {
                    setups[lv][teamIndex.get(team)] = managers[lv].build(team, roster(lv, team, today),
                            planFor(lv, team));
                } catch (IllegalStateException e) {
                    // 인원 부족: 그 레벨 소속이면 부상 중이어도 포함해서 구성 (이전 구성이 없는 최초 적재용)
                    List<PlayerCard> all = new ArrayList<>();
                    for (Player p : members(lv, team)) {
                        all.add(p.card);
                    }
                    try {
                        setups[lv][teamIndex.get(team)] = managers[lv].build(team, all, planFor(lv, team));
                    } catch (IllegalStateException again) {
                        // 오프시즌: 계약 만료·방출로 로스터가 비어 있는 게 정상 (경기 없음, 개막 로스터 단계에서 채움)
                        if (phase != NextDayService.PHASE_OFFSEASON) {
                            throw again;
                        }
                        setups[lv][teamIndex.get(team)] = null;
                    }
                    shortRosterKeeps++;
                }
            }
        }
    }

    List<PlayerCard> roster(int level, int teamId, long today) {
        List<Player> group = members(level, teamId);
        List<PlayerCard> out = new ArrayList<>(group.size());
        for (Player p : group) {
            if (p.playsAt(level, today)) {
                out.add(p.card);
            }
        }
        return out;
    }

    /** 색인 목록 그대로 (읽기 전용으로 사용, 순회 중 이동 금지) */
    List<Player> members(int level, int teamId) {
        return groups.getOrDefault(key(level, teamId), List.of());
    }

    AutoManager.TeamSetup setup(int level, int teamId) {
        return setups[level][teamIndex.get(teamId)];
    }

    int[] standing(int level, int teamId) {
        return standings[level][teamIndex.get(teamId)];
    }

    void applyResult(int level, int awayId, int homeId, GameResult r) {
        boolean homeWin = r.homeWin();
        record(standing(level, awayId), !homeWin, r.awayRuns(), r.homeRuns());
        record(standing(level, homeId), homeWin, r.homeRuns(), r.awayRuns());
    }

    private static void record(int[] s, boolean win, int runsFor, int runsAgainst) {
        s[win ? W : L]++;
        s[RF] += runsFor;
        s[RA] += runsAgainst;
        if (win) {
            s[STREAK] = s[STREAK] > 0 ? Math.min(s[STREAK] + 1, 127) : 1;
        } else {
            s[STREAK] = s[STREAK] < 0 ? Math.max(s[STREAK] - 1, -128) : -1;
        }
    }

    int team(int playerId) {
        Player p = players.get(playerId);
        return p == null ? 0 : p.team;
    }

    private static long key(int level, int team) {
        return ((long) level << 32) | team;
    }
}
