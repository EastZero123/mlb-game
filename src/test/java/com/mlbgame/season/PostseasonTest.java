package com.mlbgame.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mlbgame.career.MasterData;

class PostseasonTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 29);

    /** 리그당 15팀 (지구당 5팀). 팀 ID = 리그(1/2)×100 + 지구(0~2)×10 + 순번(0~4). 승수 = 100 - (지구 내 순번×5) - 지구 */
    private final Map<Integer, MasterData.TeamInfo> teams = new HashMap<>();
    private final Map<Integer, int[]> standings = new HashMap<>();

    PostseasonTest() {
        String[] div = {"E", "C", "W"};
        for (int l = 1; l <= 2; l++) {
            for (int d = 0; d < 3; d++) {
                for (int i = 0; i < 5; i++) {
                    int id = l * 100 + d * 10 + i;
                    teams.put(id, new MasterData.TeamInfo(id, "T" + id, "Team " + id, l == 1 ? "AL" : "NL", div[d]));
                    int wins = 100 - i * 5 - d;
                    standings.put(id, new int[] {wins, 162 - wins, 700, 650, 0});
                }
            }
        }
    }

    @Test
    void 시드는_지구우승_3팀_후_와일드카드_3팀() {
        List<Postseason.Seed> s = Postseason.seeds("AL", standings, teams);
        assertThat(s).extracting(Postseason.Seed::team).containsExactly(100, 110, 120, 101, 111, 121);
        assertThat(s).extracting(Postseason.Seed::seed).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void 와일드카드는_3대6_4대5_상위시드_홈() {
        List<Series> wc = Postseason.wildCard(standings, teams, START);
        assertThat(wc).hasSize(4);
        Series s = Postseason.find(wc, 1);
        assertThat(s.high).isEqualTo(120);
        assertThat(s.low).isEqualTo(121);
        assertThat(s.homeTeam()).isEqualTo(120);
        s.record(121, START);
        assertThat(s.homeTeam()).isEqualTo(120); // 3전은 전부 상위 시드 홈
        assertThat(s.nextDate).isEqualTo(START.plusDays(1));
    }

    @Test
    void 디비전은_2_2_1_홈이_바뀌면_이동일() {
        Series s = new Series(5, Postseason.DIVISION, "AL", 5, 1, 2, 1, 4, START);
        assertThat(s.homeTeam()).isEqualTo(1);
        s.record(1, START);
        assertThat(s.nextDate).isEqualTo(START.plusDays(1));
        s.record(2, START.plusDays(1));
        assertThat(s.homeTeam()).isEqualTo(2);
        assertThat(s.nextDate).isEqualTo(START.plusDays(3)); // 이동일
        s.record(1, START.plusDays(3));
        s.record(1, START.plusDays(4));
        assertThat(s.done()).isTrue();
        assertThat(s.winner).isEqualTo(1);
        assertThat(s.nextDate).isNull();
    }

    @Test
    void 라운드가_끝나야_다음_라운드_생성_월드시리즈까지() {
        List<Series> all = new ArrayList<>(Postseason.wildCard(standings, teams, START));
        assertThat(Postseason.nextRound(all, standings, teams, START)).isEmpty();
        LocalDate d = START;
        int guard = 0;
        while (Postseason.champion(all) == null && guard++ < 200) {
            for (Series s : List.copyOf(all)) {
                if (!s.done() && d.equals(s.nextDate)) {
                    s.record(s.high, d); // 상위 시드가 늘 이김
                }
            }
            all.addAll(Postseason.nextRound(all, standings, teams, d.plusDays(2)));
            d = d.plusDays(1);
        }
        assertThat(all).hasSize(11);
        // 상위 시드 연승 → 각 리그 1번 시드끼리 월드시리즈, 승수 같으면 득실차·ID 순으로 AL(100) 홈
        Series ws = Postseason.find(all, 11);
        assertThat(ws.high).isEqualTo(100);
        assertThat(ws.low).isEqualTo(200);
        assertThat(Postseason.champion(all)).isEqualTo(100);
        // 디비전: 1번 시드는 4·5 승자(4번), 2번 시드는 3·6 승자(3번)
        assertThat(Postseason.find(all, 5).low).isEqualTo(101);
        assertThat(Postseason.find(all, 6).low).isEqualTo(120);
    }
}
