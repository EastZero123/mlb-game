package com.mlbgame.season;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 유저 팀 로스터 직접 이동. 검사({@link RosterMoves}) → c_player 1행 즉시 저장 → 해당 레벨 팀 구성 재생성.
 * 진행("다음 날")과 같은 커리어 잠금을 쓴다. 오프시즌에는 로스터를 일괄 재배치하므로 막는다.
 */
@Service
public class MoveService {

    private final JdbcTemplate jdbc;
    private final CareerRuntimeCache cache;

    MoveService(JdbcTemplate jdbc, CareerRuntimeCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /** 로스터 현황 (화면 표시용) */
    public record Counts(int active, int fortyMan) {
    }

    public Counts counts(long careerId) {
        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            return new Counts(RosterMoves.active(rt, rt.userTeamId, null), RosterMoves.fortyMan(rt, rt.userTeamId));
        } finally {
            rt.lock.unlock();
        }
    }

    /** @throws IllegalArgumentException 규칙 위반 (메시지 = 화면 안내) */
    public void move(long careerId, int playerId, RosterMove m) {
        CareerRuntime rt = cache.get(careerId);
        rt.lock.lock();
        try {
            if (rt.phase == NextDayService.PHASE_OFFSEASON) {
                throw new IllegalArgumentException("오프시즌에는 로스터를 옮길 수 없습니다 (오프시즌 진행 후 개막 로스터로 재배치)");
            }
            long day = rt.curDate.toEpochDay() + 1;
            CareerRuntime.Player p = rt.players.get(playerId);
            int fromLevel = p == null ? 0 : p.level;
            RosterMoves.apply(rt, playerId, m, day);
            try {
                jdbc.update("""
                        UPDATE c_player SET team_id = ?, level = ?, roster_status = ?, injury_until = ?
                        WHERE career_id = ? AND player_id = ?
                        """, p.team, p.level, p.status,
                        p.injuredUntil > 0 ? LocalDate.ofEpochDay(p.injuredUntil) : null, careerId, p.id);
            } catch (RuntimeException e) {
                cache.evict(careerId); // 메모리만 바뀐 상태 → 다음 요청 때 DB 기준으로 다시 만든다
                throw e;
            }
            p.dirty = false;
            rt.rebuild(fromLevel, rt.userTeamId, day);
            if (p.level != fromLevel) {
                rt.rebuild(p.level, rt.userTeamId, day);
            }
        } finally {
            rt.lock.unlock();
        }
    }
}
