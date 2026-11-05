package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mlbgame.roster.AutoManager;
import com.mlbgame.roster.PlayerCard;
import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.GameSimulator;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.PitcherRatings;

class RosterMovesTest {

    private static final int TEAM = 147;
    private static final int OTHER = 111;
    private static final long DAY = 20_000;

    private CareerRuntime rt;
    private int nextId;

    /**
     * MLB 현역 타자 13 (1~13) + 투수 13 (14~26), 부상자 1 (27).
     * AAA 타자 12 + 투수 12 (40인 4명씩, 나머지 마이너 계약), AA 타자 10 + 투수 11 (최소 인원 딱 맞음).
     */
    @BeforeEach
    void setUp() {
        Map<Integer, CareerRuntime.Player> players = new HashMap<>();
        rt = new CareerRuntime(1, 7, TEAM, List.of(TEAM, OTHER), new HashMap<>(Map.of(TEAM, 0, OTHER, 1)), players,
                new AutoManager[3], new int[3][2][5], new GameSimulator[3]);
        nextId = 1;
        add(13, false, 0, CareerRuntime.ST_ACTIVE, 50);
        add(13, true, 0, CareerRuntime.ST_ACTIVE, 50);
        add(1, false, 0, CareerRuntime.ST_INJURED, 50).injuredUntil = DAY + 10;
        add(4, false, 1, CareerRuntime.ST_40MAN, 45);
        add(8, false, 1, CareerRuntime.ST_MINOR, 40);
        add(4, true, 1, CareerRuntime.ST_40MAN, 45);
        add(8, true, 1, CareerRuntime.ST_MINOR, 40);
        add(10, false, 2, CareerRuntime.ST_MINOR, 35);
        add(11, true, 2, CareerRuntime.ST_MINOR, 35);
    }

    private CareerRuntime.Player add(int n, boolean pitcher, int level, int status, int value) {
        CareerRuntime.Player last = null;
        for (int i = 0; i < n; i++) {
            int id = nextId++;
            PlayerCard card = pitcher
                    ? new PlayerCard(id, TEAM, 1, Hand.RIGHT, Hand.RIGHT, null,
                            new PitcherRatings(value + i % 3, value, value, value, 50), new int[8], 50)
                    : new PlayerCard(id, TEAM, 4, Hand.RIGHT, Hand.RIGHT,
                            new BatterRatings(value + i % 3, value, value, value, 50, 50, 50), null, new int[8], 50);
            last = new CareerRuntime.Player(id, card, 50, TEAM, level, status, 0);
            rt.addPlayer(last);
        }
        return last;
    }

    private CareerRuntime.Player first(int level, int status, boolean pitcher) {
        return rt.members(level, TEAM).stream()
                .filter(p -> p.status == status && p.isPitcher() == pitcher).findFirst().orElseThrow();
    }

    @Test
    void 기본_현황() {
        assertThat(RosterMoves.active(rt, TEAM, null)).isEqualTo(26);
        assertThat(RosterMoves.fortyMan(rt, TEAM)).isEqualTo(26 + 1 + 8);
    }

    @Test
    void 꽉_찬_26인엔_콜업_불가_내리면_가능() {
        CareerRuntime.Player prospect = first(1, CareerRuntime.ST_40MAN, false);
        assertThatThrownBy(() -> RosterMoves.apply(rt, prospect.id, RosterMove.CALL_UP, DAY))
                .hasMessageContaining("26인");

        CareerRuntime.Player regular = first(0, CareerRuntime.ST_ACTIVE, false);
        RosterMoves.apply(rt, regular.id, RosterMove.TO_AAA, DAY);
        assertThat(regular.level).isEqualTo(1);
        assertThat(regular.status).isEqualTo(CareerRuntime.ST_40MAN); // 40인은 유지
        assertThat(regular.dirty).isTrue();

        RosterMoves.apply(rt, prospect.id, RosterMove.CALL_UP, DAY);
        assertThat(prospect.level).isZero();
        assertThat(prospect.status).isEqualTo(CareerRuntime.ST_ACTIVE);
        assertThat(rt.members(0, TEAM)).contains(prospect);
        assertThat(RosterMoves.active(rt, TEAM, null)).isEqualTo(26);
    }

    @Test
    void 마이너_계약_선수_콜업은_40인_자리가_있어야() {
        RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id, RosterMove.TO_AAA, DAY);
        // 40인을 40명으로 채움 (마이너 계약 5명 등록)
        for (int i = 0; i < 5; i++) {
            RosterMoves.apply(rt, first(1, CareerRuntime.ST_MINOR, false).id, RosterMove.ADD_40, DAY);
        }
        assertThat(RosterMoves.fortyMan(rt, TEAM)).isEqualTo(40);
        CareerRuntime.Player minor = first(1, CareerRuntime.ST_MINOR, true);
        assertThatThrownBy(() -> RosterMoves.apply(rt, minor.id, RosterMove.CALL_UP, DAY))
                .hasMessageContaining("40인");
        assertThatThrownBy(() -> RosterMoves.apply(rt, minor.id, RosterMove.ADD_40, DAY))
                .hasMessageContaining("40인");

        // 한 명 빼면 콜업 가능 (등록도 같이 됨)
        RosterMoves.apply(rt, first(1, CareerRuntime.ST_40MAN, false).id, RosterMove.REMOVE_40, DAY);
        RosterMoves.apply(rt, minor.id, RosterMove.CALL_UP, DAY);
        assertThat(RosterMoves.fortyMan(rt, TEAM)).isEqualTo(40);
    }

    @Test
    void 부상자는_콜업도_강등도_불가() {
        CareerRuntime.Player hurtMinor = first(1, CareerRuntime.ST_40MAN, true);
        hurtMinor.injuredUntil = DAY + 3;
        RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id, RosterMove.TO_AA, DAY);
        assertThatThrownBy(() -> RosterMoves.apply(rt, hurtMinor.id, RosterMove.CALL_UP, DAY))
                .hasMessageContaining("부상");

        CareerRuntime.Player il = first(0, CareerRuntime.ST_INJURED, false);
        assertThatThrownBy(() -> RosterMoves.apply(rt, il.id, RosterMove.TO_AAA, DAY))
                .hasMessageContaining("부상자 명단");
    }

    @Test
    void 산하_팀_최소_인원과_MLB_유형별_최소_인원을_지킨다() {
        // AA 타자는 최소 10명 딱 맞음 → AAA 로 못 올림
        CareerRuntime.Player aaHitter = first(2, CareerRuntime.ST_MINOR, false);
        assertThatThrownBy(() -> RosterMoves.apply(rt, aaHitter.id, RosterMove.TO_AAA, DAY))
                .hasMessageContaining("AA 타자");
        // AAA 타자 12명 → 1명은 AA 로 내릴 수 있음
        RosterMoves.apply(rt, first(1, CareerRuntime.ST_MINOR, false).id, RosterMove.TO_AA, DAY);

        // MLB 투수 13 → 10 까지만
        for (int i = 0; i < 3; i++) {
            RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id, RosterMove.TO_AAA, DAY);
        }
        assertThatThrownBy(() -> RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id,
                RosterMove.TO_AAA, DAY)).hasMessageContaining("투수");
    }

    @Test
    void 남의_팀_선수와_맞지_않는_이동은_거부() {
        CareerRuntime.Player stranger = new CareerRuntime.Player(9999, first(0, CareerRuntime.ST_ACTIVE, false).card,
                50, OTHER, 1, CareerRuntime.ST_40MAN, 0);
        rt.addPlayer(stranger);
        assertThatThrownBy(() -> RosterMoves.apply(rt, 9999, RosterMove.CALL_UP, DAY)).hasMessageContaining("내 팀");
        assertThatThrownBy(() -> RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, false).id,
                RosterMove.CALL_UP, DAY)).hasMessageContaining("이미 MLB");
        assertThatThrownBy(() -> RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, false).id,
                RosterMove.REMOVE_40, DAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사십인이_넘치면_산하_팀_40인_최하위부터_제외() {
        add(5, false, 2, CareerRuntime.ST_40MAN, 30); // 가치 30~32 → 40명
        CareerRuntime.Player lowest = add(1, false, 2, CareerRuntime.ST_40MAN, 20);
        CareerRuntime.Player nextLowest = add(1, false, 2, CareerRuntime.ST_40MAN, 25);
        assertThat(RosterMoves.fortyMan(rt, TEAM)).isEqualTo(42);

        assertThat(DayRules.trimFortyMan(rt, TEAM)).isEqualTo(2);
        assertThat(RosterMoves.fortyMan(rt, TEAM)).isEqualTo(40);
        assertThat(lowest.status).isEqualTo(CareerRuntime.ST_MINOR);
        assertThat(nextLowest.status).isEqualTo(CareerRuntime.ST_MINOR);
        assertThat(lowest.level).isEqualTo(2); // 레벨은 그대로
        assertThat(lowest.dirty).isTrue();
        // MLB 선수는 건드리지 않음, 40 이하면 할 일 없음
        assertThat(RosterMoves.active(rt, TEAM, null)).isEqualTo(26);
        assertThat(DayRules.trimFortyMan(rt, TEAM)).isZero();
    }

    @Test
    void 비운_MLB_자리는_모자란_유형부터_산하_팀_최고_선수로_채운다() {
        RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id, RosterMove.TO_AA, DAY);
        RosterMoves.apply(rt, first(0, CareerRuntime.ST_ACTIVE, true).id, RosterMove.TO_AA, DAY);
        assertThat(RosterMoves.active(rt, TEAM, true)).isEqualTo(11);

        DayRules.Events ev = new DayRules.Events();
        DayRules.fillMlb(rt, TEAM, DAY, ev);

        assertThat(RosterMoves.active(rt, TEAM, null)).isEqualTo(26);
        assertThat(RosterMoves.active(rt, TEAM, true)).isEqualTo(13);
        assertThat(ev.moves).isEqualTo(2);
        // 26인이면 아무 일도 없음
        DayRules.fillMlb(rt, TEAM, DAY, ev);
        assertThat(ev.moves).isEqualTo(2);
    }
}
