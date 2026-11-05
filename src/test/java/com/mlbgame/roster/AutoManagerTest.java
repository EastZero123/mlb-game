package com.mlbgame.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.PitcherRatings;
import com.mlbgame.sim.RateModel;

class AutoManagerTest {

    private final AutoManager manager = new AutoManager(new RateModel(LeagueBaseline.mlbDefault()));

    @Test
    void 개막_로스터로_팀을_구성한다() {
        List<PlayerCard> roster = new ArrayList<>();
        // 포지션별 야수 1명씩 (C, 1B, 2B, 3B, SS, LF, CF, RF) + 백업 4명 + 수비 불가 거포 1명
        for (int pos = 2; pos <= 9; pos++) {
            roster.add(hitter(pos, pos, 50, 60));
        }
        for (int i = 0; i < 4; i++) {
            roster.add(hitter(20 + i, 4 + i, 40, 45));
        }
        roster.add(new PlayerCard(30, 1, 10, Hand.LEFT, Hand.RIGHT,
                new BatterRatings(75, 75, 75, 75, 50, 40, 40), null, new int[8], 50));
        // 투수 13명: 스태미나 높은 5명 + 불펜 8명 (id 112 가 구위 최고)
        for (int i = 0; i < 13; i++) {
            int stamina = i < 5 ? 70 + i : 30;
            int stuff = i == 12 ? 75 : 50;
            roster.add(new PlayerCard(100 + i, 1, 1, Hand.RIGHT, Hand.RIGHT, null,
                    new PitcherRatings(stuff, 50, 50, 50, stamina), new int[8], 50));
        }

        AutoManager.TeamSetup setup = manager.build(1, roster);

        assertThat(setup.lineup()).hasSize(9).doesNotHaveDuplicates();
        assertThat(setup.positions()).containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7, 8, 9, 10);
        // 수비 불가 거포는 지명타자, 타격 1위라 1번 타순
        assertThat(setup.lineup()[0].playerId()).isEqualTo(30);
        assertThat(setup.positions()[0]).isEqualTo(10);
        // 포수는 포수 수비가 되는 선수
        int catcherSlot = indexOf(setup.positions(), 2);
        assertThat(setup.lineup()[catcherSlot].def(2)).isPositive();

        assertThat(setup.rotation()).extracting(PlayerCard::playerId).containsExactly(104, 103, 102, 101, 100);
        assertThat(setup.bullpen()[0].playerId()).isEqualTo(112); // 마무리 = 구위 최고
        assertThat(setup.byStarter()).hasSize(5);
        assertThat(setup.byStarter()[0].starter().playerId()).isEqualTo(104);
        // 라인업에 못 든 타자는 벤치 (공격력 순), 시뮬용 타자도 같은 순서
        assertThat(setup.bench().length + 9).isEqualTo(roster.stream().filter(c -> c.batting() != null).count());
        assertThat(setup.benchSim()).hasSameSizeAs(setup.bench());
        for (int i = 1; i < setup.bench().length; i++) {
            assertThat(setup.bench()[i - 1].offense()).isGreaterThanOrEqualTo(setup.bench()[i].offense());
        }
        // 선발별 SimTeam 은 타순·불펜을 공유 (객체 재사용)
        assertThat(setup.byStarter()[1].lineup()).isSameAs(setup.byStarter()[0].lineup());
    }

    /** 위 테스트와 같은 구성: 야수 2~9, 백업 20~23, 거포 30, 투수 100~112 (100~104 선발형, 112 구위 최고) */
    private static List<PlayerCard> standardRoster() {
        List<PlayerCard> roster = new ArrayList<>();
        for (int pos = 2; pos <= 9; pos++) {
            roster.add(hitter(pos, pos, 50, 60));
        }
        for (int i = 0; i < 4; i++) {
            roster.add(hitter(20 + i, 4 + i, 40, 45));
        }
        roster.add(new PlayerCard(30, 1, 10, Hand.LEFT, Hand.RIGHT,
                new BatterRatings(75, 75, 75, 75, 50, 40, 40), null, new int[8], 50));
        for (int i = 0; i < 13; i++) {
            int stamina = i < 5 ? 70 + i : 30;
            int stuff = i == 12 ? 75 : 50;
            roster.add(new PlayerCard(100 + i, 1, 1, Hand.RIGHT, Hand.RIGHT, null,
                    new PitcherRatings(stuff, 50, 50, 50, stamina), new int[8], 50));
        }
        return roster;
    }

    /** 2~9 번 선수를 자기 위치에, 30 번을 지명타자로. 타순은 포수부터 */
    private static TeamPlan.Slot[] plannedLineup() {
        TeamPlan.Slot[] slots = new TeamPlan.Slot[9];
        for (int pos = 2; pos <= 9; pos++) {
            slots[pos - 2] = new TeamPlan.Slot(pos, pos);
        }
        slots[8] = new TeamPlan.Slot(30, TeamPlan.DH);
        return slots;
    }

    @Test
    void 지정한_타순과_수비_위치를_그대로_쓴다() {
        TeamPlan plan = new TeamPlan(plannedLineup(), null, null, null, true, true);
        AutoManager.TeamSetup setup = manager.build(1, standardRoster(), plan);

        assertThat(setup.lineup()).extracting(PlayerCard::playerId).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 30);
        assertThat(setup.positions()).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(setup.manual()).isTrue();
        assertThat(setup.lineupFor(Hand.LEFT)).isSameAs(setup.main());
        // 지정 안 한 로테이션·불펜은 자동
        assertThat(setup.rotation()).extracting(PlayerCard::playerId).containsExactly(104, 103, 102, 101, 100);
    }

    @Test
    void 지정_선수가_빠지면_그_자리만_같은_위치_가능한_선수로_메운다() {
        List<PlayerCard> roster = new ArrayList<>(standardRoster());
        roster.removeIf(c -> c.playerId() == 6); // 유격수 부상
        AutoManager.TeamSetup setup = manager.build(1, roster, new TeamPlan(plannedLineup(), null, null, null, true, true));

        assertThat(setup.positions()).containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(setup.lineup()[4].def(6)).isPositive(); // 5번 타순 유격수 자리
        assertThat(setup.lineup()[4].playerId()).isNotEqualTo(6);
        // 나머지 지정 자리는 그대로
        assertThat(setup.lineup()[0].playerId()).isEqualTo(2);
        assertThat(setup.lineup()[8].playerId()).isEqualTo(30);
    }

    @Test
    void 좌투수_상대_타순을_따로_쓴다() {
        TeamPlan.Slot[] left = plannedLineup().clone();
        left[8] = new TeamPlan.Slot(20, TeamPlan.DH); // 좌투수 상대엔 20 번이 지명타자
        AutoManager.TeamSetup setup = manager.build(1, standardRoster(),
                new TeamPlan(plannedLineup(), left, null, null, false, true));

        assertThat(setup.lineupFor(Hand.RIGHT)).isSameAs(setup.main());
        assertThat(setup.lineupFor(Hand.LEFT).cards()[8].playerId()).isEqualTo(20);
        assertThat(setup.lineupFor(Hand.LEFT).bench()).extracting(PlayerCard::playerId).contains(30);
        assertThat(setup.autoRest()).isFalse();
    }

    @Test
    void 로테이션과_불펜_지정_순서를_쓰고_빈_자리는_자동으로() {
        // 999 = 로스터에 없는 투수 → 남은 선발형 중 스태미나 최고(104)로 메움
        TeamPlan plan = new TeamPlan(null, null, new int[] {100, 101, 102, 103, 999}, new int[] {105, 106}, true, true);
        AutoManager.TeamSetup setup = manager.build(1, standardRoster(), plan);

        assertThat(setup.rotation()).extracting(PlayerCard::playerId).containsExactly(100, 101, 102, 103, 104);
        assertThat(setup.byStarter()[0].starter().playerId()).isEqualTo(100);
        assertThat(setup.bullpen()[0].playerId()).isEqualTo(105); // 지정 마무리
        assertThat(setup.bullpen()[1].playerId()).isEqualTo(106);
        assertThat(setup.bullpen()[2].playerId()).isEqualTo(112); // 나머지는 구위 순
        assertThat(setup.bullpen()).hasSize(8);
        assertThat(setup.manual()).isFalse();
    }

    private static PlayerCard hitter(int id, int pos, int offense, int defense) {
        int[] def = new int[8];
        def[pos - 2] = defense;
        return new PlayerCard(id, 1, pos, Hand.RIGHT, Hand.RIGHT,
                new BatterRatings(offense, offense, offense, offense, 50, 50, 50), null, def, 55);
    }

    private static int indexOf(int[] arr, int v) {
        return Arrays.stream(arr).boxed().toList().indexOf(v);
    }
}
