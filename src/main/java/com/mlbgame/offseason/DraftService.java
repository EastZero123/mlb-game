package com.mlbgame.offseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.career.MasterData;
import com.mlbgame.season.NextDayService;

/**
 * 드래프트 화면 (오프시즌 6단계 전). 지명 순서는 1단계에서 확정된 c_draft_pick.
 * <pre>
 * 후보 300명은 저장하지 않는다: 커리어 seed + 시즌 + 첫 신규 ID 로 6단계 처리와 똑같이 다시 만든다.
 * 지명은 c_draft_pick.player_id 에 한 장씩 기록 (유저 지명 + 유저 차례 전까지의 AI 지명).
 * 6단계 처리 때 기록된 지명은 그대로 쓰고 빈 지명권만 AI 가 채운 뒤 지명 선수를 생성.
 * </pre>
 * 동시에 두 창에서 누르면 career.version 확인으로 한쪽만 반영 (6단계 처리와도 같은 version 을 쓴다).
 */
@Service
public class DraftService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterData master;

    public DraftService(JdbcTemplate jdbc, TransactionTemplate tx, MasterData master) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.master = master;
    }

    /** 드래프트 후보 1명 (화면은 ratings 를 스카우트 범위로만 보여 준다) */
    public record Prospect(int playerId, String name, int pos, boolean pitcher, LocalDate birth, String bats,
                           String throwsHand, short[] ratings) {
    }

    /** 지명권 1장 (playerId = 지명 전 null) */
    public record Pick(int pickNo, int round, int teamId, Integer playerId) {
    }

    /**
     * 드래프트 현황.
     *
     * @param prospects 후보 전체 (지명된 선수 포함, ID 순)
     * @param current   다음 지명권 인덱스 (모두 지명했으면 -1)
     */
    public record Board(int season, int nextSeason, int userTeam, long seed, List<Pick> picks,
                        List<Prospect> prospects, int current) {

        public Map<Integer, Prospect> byId() {
            Map<Integer, Prospect> out = new HashMap<>();
            for (Prospect p : prospects) {
                out.put(p.playerId(), p);
            }
            return out;
        }

        public boolean myTurn() {
            return current >= 0 && picks.get(current).teamId() == userTeam;
        }

        /** 아직 행사하지 않은 내 지명권 */
        public List<Pick> myOpen() {
            return picks.stream().filter(p -> p.teamId() == userTeam && p.playerId() == null).toList();
        }
    }

    /** 드래프트 단계가 아니면 null */
    public Board board(long careerId) {
        State s = load(careerId);
        return s == null ? null : s.board();
    }

    /** "다음 단계"로 넘어가기 전 확인용: 행사하지 않은 내 지명권이 남았나 (드래프트 단계가 아니면 false) */
    public boolean myPicksLeft(long careerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM career c
                JOIN c_offseason o ON o.career_id = c.career_id AND o.season = c.season
                JOIN c_draft_pick d ON d.career_id = c.career_id AND d.season = c.season AND d.team_id = c.team_id
                WHERE c.career_id = ? AND c.phase = ? AND o.stage = ? AND d.player_id IS NULL
                """, Integer.class, careerId, NextDayService.PHASE_OFFSEASON, OffseasonService.Stage.DRAFT.ordinal());
        return n != null && n > 0;
    }

    /** 내 차례 직전까지 AI 구단 지명 (내 지명권이 더 없으면 끝까지). @return 지명 수 */
    public int autoToMine(long careerId) {
        State s = require(careerId);
        List<Object[]> writes = new ArrayList<>();
        s.advanceAi(writes);
        s.save(writes);
        return writes.size();
    }

    /**
     * 내 지명 → 이어서 다음 내 차례 직전까지 AI 지명.
     *
     * @return 이번 요청의 전체 지명 수 (내 지명 포함)
     * @throws IllegalArgumentException 내 차례가 아니거나 이미 지명된 선수
     */
    public int pick(long careerId, int playerId) {
        State s = require(careerId);
        if (!s.board().myTurn()) {
            throw new IllegalArgumentException("지금은 내 지명 차례가 아닙니다.");
        }
        int index = playerId - s.firstId;
        if (index < 0 || index >= s.prospects.size()) {
            throw new IllegalArgumentException("드래프트 후보가 아닙니다.");
        }
        if (s.taken[index]) {
            throw new IllegalArgumentException("이미 지명된 선수입니다.");
        }
        List<Object[]> writes = new ArrayList<>();
        s.record(index, writes);
        s.advanceAi(writes);
        s.save(writes);
        return writes.size();
    }

    // ------------------------------------------------------------------ 내부

    private State require(long careerId) {
        State s = load(careerId);
        if (s == null) {
            throw new IllegalArgumentException("드래프트 진행 중이 아닙니다.");
        }
        return s;
    }

    private State load(long careerId) {
        Object[] c = jdbc.query("""
                SELECT c.seed, c.season, c.version, c.team_id, o.stage FROM career c
                JOIN c_offseason o ON o.career_id = c.career_id AND o.season = c.season
                WHERE c.career_id = ? AND c.phase = ? AND c.deleted_at IS NULL
                """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5)} : null, careerId, NextDayService.PHASE_OFFSEASON);
        if (c == null || (int) c[4] != OffseasonService.Stage.DRAFT.ordinal()) {
            return null;
        }
        State s = new State();
        s.careerId = careerId;
        s.seed = (long) c[0];
        s.season = (int) c[1];
        s.version = (int) c[2];
        s.userTeam = (int) c[3];
        jdbc.query("""
                SELECT pick_no, round, team_id, player_id FROM c_draft_pick
                WHERE career_id = ? AND season = ? ORDER BY pick_no
                """, rs -> {
            s.picks.add(new Pick(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, Integer.class)));
        }, careerId, s.season);
        // 6단계 처리(OffseasonService)와 같은 인자로 후보 생성 → 같은 후보
        int[] nextId = {OffseasonService.firstNewId(jdbc, careerId)};
        s.firstId = nextId[0];
        LocalDate created = OffseasonService.processDay(master.get(), s.season);
        s.prospects = OffseasonRules.prospects(s.picks.size(), nextId, careerId, s.seed, s.season + 1, created);
        s.taken = new boolean[s.prospects.size()];
        for (Pick p : s.picks) {
            if (p.playerId() != null) {
                int i = p.playerId() - s.firstId;
                if (i < 0 || i >= s.taken.length) {
                    throw new IllegalStateException("드래프트 지명 기록이 후보와 맞지 않음: " + p.playerId());
                }
                s.taken[i] = true;
            }
        }
        return s;
    }

    private final class State {
        long careerId;
        long seed;
        int season;
        int version;
        int userTeam;
        int firstId;
        final List<Pick> picks = new ArrayList<>();
        List<OffPlayer> prospects;
        boolean[] taken;

        int current() {
            for (int i = 0; i < picks.size(); i++) {
                if (picks.get(i).playerId() == null) {
                    return i;
                }
            }
            return -1;
        }

        /** 다음 지명권에 후보 index 기록 */
        void record(int index, List<Object[]> writes) {
            int k = current();
            Pick p = picks.get(k);
            int id = prospects.get(index).id;
            taken[index] = true;
            picks.set(k, new Pick(p.pickNo(), p.round(), p.teamId(), id));
            writes.add(new Object[] {id, careerId, season, p.pickNo()});
        }

        /** 다음 내 지명권 직전까지 (없으면 끝까지) AI 지명 */
        void advanceAi(List<Object[]> writes) {
            for (int k = current(); k >= 0 && picks.get(k).teamId() != userTeam; k = current()) {
                record(OffseasonRules.aiPick(prospects, taken, picks.get(k).teamId(), seed, season + 1), writes);
            }
        }

        void save(List<Object[]> writes) {
            if (writes.isEmpty()) {
                return;
            }
            tx.executeWithoutResult(st -> {
                int updated = jdbc.update("""
                        UPDATE career SET version = version + 1
                        WHERE career_id = ? AND version = ? AND deleted_at IS NULL
                        """, careerId, version);
                if (updated != 1) {
                    throw new OffseasonService.ConcurrentStageException(careerId);
                }
                jdbc.batchUpdate("""
                        UPDATE c_draft_pick SET player_id = ?
                        WHERE career_id = ? AND season = ? AND pick_no = ? AND player_id IS NULL
                        """, writes);
            });
        }

        Board board() {
            List<Prospect> out = new ArrayList<>(prospects.size());
            for (OffPlayer p : prospects) {
                Object[] person = p.personRow;
                out.add(new Prospect(p.id, (String) person[2], p.pos, p.isPitcher(), p.birth, (String) person[4],
                        (String) person[5], p.r));
            }
            return new Board(season, season + 1, userTeam, seed, List.copyOf(picks), out, current());
        }
    }
}
