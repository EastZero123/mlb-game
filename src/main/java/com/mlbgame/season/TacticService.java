package com.mlbgame.season;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.roster.TeamPlan;

/**
 * 유저 팀 MLB 운영 지정 (라인업·로테이션·불펜) 조회·저장. 저장하면 메모리 팀 구성을 바로 다시 만든다 → 다음 경기부터 반영.
 * 진행("다음 날")과 같은 커리어 잠금을 쓴다.
 */
@Service
public class TacticService {

    private static final String UPSERT = """
            INSERT INTO c_tactic (career_id, lineup_r, lineup_l, rotation, bullpen, auto_rest, auto_swap)
            VALUES (?, ?, ?, ?, ?, ?, ?) AS n
            ON DUPLICATE KEY UPDATE lineup_r = n.lineup_r, lineup_l = n.lineup_l, rotation = n.rotation,
                                    bullpen = n.bullpen, auto_rest = n.auto_rest, auto_swap = n.auto_swap
            """;

    private final JdbcTemplate jdbc;
    private final CareerRuntimeCache cache;

    TacticService(JdbcTemplate jdbc, CareerRuntimeCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /**
     * 오늘 실제로 적용되는 구성 (지정 + 자동으로 메운 자리).
     *
     * @param vsLeft      좌투수 상대 타순 (따로 지정하지 않았으면 null)
     * @param nextStarter 다음 정규시즌 경기 선발의 로테이션 순번 (0부터)
     */
    public record Effective(List<TeamPlan.Slot> vsRight, List<TeamPlan.Slot> vsLeft, List<Integer> rotation,
                            List<Integer> bullpen, int nextStarter) {
    }

    /** @param plan 저장된 지정 (없으면 null) */
    public record State(TeamPlan plan, Effective effective) {
    }

    public State current(long careerId) {
        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            AutoManager.TeamSetup s = rt.setup(0, rt.userTeamId);
            int[] st = rt.standing(0, rt.userTeamId);
            int next = s.byStarter().length == 0 ? 0 : (st[CareerRuntime.W] + st[CareerRuntime.L]) % s.byStarter().length;
            Effective e = new Effective(slots(s.main()), s.vsLeft() == null ? null : slots(s.vsLeft()),
                    ids(s.rotation()), ids(s.bullpen()), next);
            return new State(rt.userPlan, e);
        } finally {
            rt.lock.unlock();
        }
    }

    /** 검사 후 저장. 문제가 있으면 저장하지 않고 메시지 목록을 돌려준다 (빈 목록 = 저장됨) */
    public List<String> save(long careerId, TeamPlan plan) {
        List<String> errors = new ArrayList<>(plan.formatErrors());
        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            if (errors.isEmpty()) {
                checkRoster(rt, plan, errors);
            }
            if (!errors.isEmpty()) {
                return errors;
            }
            jdbc.update(UPSERT, careerId, TeamPlan.encodeSlots(plan.vsRight()), TeamPlan.encodeSlots(plan.vsLeft()),
                    TeamPlan.encodeIds(plan.rotation()), TeamPlan.encodeIds(plan.bullpen()), plan.autoRest() ? 1 : 0,
                    plan.autoSwap() ? 1 : 0);
            rt.userPlan = plan;
            rt.rebuild(0, rt.userTeamId, rt.curDate.toEpochDay() + 1);
            return errors;
        } finally {
            rt.lock.unlock();
        }
    }

    /** 지정 전부 해제 → AI 자동 운영 */
    public void reset(long careerId) {
        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            jdbc.update("DELETE FROM c_tactic WHERE career_id = ?", careerId);
            rt.userPlan = null;
            rt.rebuild(0, rt.userTeamId, rt.curDate.toEpochDay() + 1);
        } finally {
            rt.lock.unlock();
        }
    }

    /** 지정 선수는 내 팀 MLB 로스터(부상자 포함)여야 하고, 타순은 타자·로테이션과 불펜은 투수여야 한다 */
    private static void checkRoster(CareerRuntime rt, TeamPlan plan, List<String> errors) {
        for (TeamPlan.Slot[] lineup : Arrays.asList(plan.vsRight(), plan.vsLeft())) {
            if (lineup == null) {
                continue;
            }
            for (TeamPlan.Slot s : lineup) {
                CareerRuntime.Player p = mine(rt, s.playerId());
                if (p == null || p.card.batting() == null) {
                    errors.add("타순에 내 팀 MLB 타자가 아닌 선수가 있습니다 (" + s.playerId() + ")");
                    return;
                }
            }
        }
        for (int[] ids : Arrays.asList(plan.rotation(), plan.bullpen())) {
            if (ids == null) {
                continue;
            }
            for (int id : ids) {
                CareerRuntime.Player p = mine(rt, id);
                if (p == null || p.card.pitching() == null) {
                    errors.add("투수 지정에 내 팀 MLB 투수가 아닌 선수가 있습니다 (" + id + ")");
                    return;
                }
            }
        }
    }

    private static CareerRuntime.Player mine(CareerRuntime rt, int id) {
        CareerRuntime.Player p = rt.players.get(id);
        boolean ok = p != null && p.team == rt.userTeamId && p.level == 0
                && (p.status == CareerRuntime.ST_ACTIVE || p.status == CareerRuntime.ST_INJURED);
        return ok ? p : null;
    }

    static TeamPlan loadPlan(JdbcTemplate jdbc, long careerId) {
        List<TeamPlan> found = jdbc.query("""
                SELECT lineup_r, lineup_l, rotation, bullpen, auto_rest, auto_swap FROM c_tactic WHERE career_id = ?
                """, (rs, i) -> new TeamPlan(TeamPlan.decodeSlots(rs.getString(1)), TeamPlan.decodeSlots(rs.getString(2)),
                TeamPlan.decodeIds(rs.getString(3)), TeamPlan.decodeIds(rs.getString(4)), rs.getInt(5) == 1,
                rs.getInt(6) == 1), careerId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<TeamPlan.Slot> slots(AutoManager.Lineup l) {
        List<TeamPlan.Slot> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            out.add(new TeamPlan.Slot(l.cards()[i].playerId(), l.positions()[i]));
        }
        return out;
    }

    private static List<Integer> ids(PlayerCard[] cards) {
        List<Integer> out = new ArrayList<>(cards.length);
        for (PlayerCard c : cards) {
            out.add(c.playerId());
        }
        return out;
    }
}
