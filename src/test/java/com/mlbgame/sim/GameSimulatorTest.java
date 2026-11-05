package com.mlbgame.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class GameSimulatorTest {

    private final GameSimulator sim = new GameSimulator(SimFixtures.LEAGUE);

    @Test
    void 같은_seed는_같은_결과() {
        SimTeam a = SimFixtures.uniformTeam(1, 50);
        SimTeam h = SimFixtures.uniformTeam(2, 50);
        for (long seed = 0; seed < 200; seed++) {
            GameResult r1 = sim.simulate(a, h, seed);
            GameResult r2 = sim.simulate(a, h, seed);
            assertThat(r2.awayRuns()).isEqualTo(r1.awayRuns());
            assertThat(r2.homeRuns()).isEqualTo(r1.homeRuns());
            assertThat(Arrays.deepEquals(r1.batting(), r2.batting())).isTrue();
        }
    }

    @Test
    void 기록_정합성() {
        SimTeam a = SimFixtures.uniformTeam(1, 50);
        SimTeam h = SimFixtures.uniformTeam(2, 50);
        for (long seed = 0; seed < 5_000; seed++) {
            GameResult r = sim.simulate(a, h, seed);

            assertThat(r.innings()).isGreaterThanOrEqualTo(9);
            assertThat(r.awayRuns()).isNotEqualTo(r.homeRuns());
            assertThat(sumLine(r.lineScore()[0])).isEqualTo(r.awayRuns());
            assertThat(sumLine(r.lineScore()[1])).isEqualTo(r.homeRuns());
            assertThat(sumBat(r.batting()[0], BatStat.R)).isEqualTo(r.awayRuns());
            assertThat(sumBat(r.batting()[1], BatStat.R)).isEqualTo(r.homeRuns());

            // 투수 실점 합 = 상대 득점, 피안타 합 = 상대 안타
            assertThat(r.homePitching().stream().mapToInt(PitcherLine::runs).sum()).isEqualTo(r.awayRuns());
            assertThat(r.awayPitching().stream().mapToInt(PitcherLine::runs).sum()).isEqualTo(r.homeRuns());
            assertThat(r.homePitching().stream().mapToInt(PitcherLine::hits).sum())
                    .isEqualTo(sumBat(r.batting()[0], BatStat.H));

            // 원정 공격은 항상 이닝당 3아웃
            assertThat(r.homePitching().stream().mapToInt(PitcherLine::outs).sum()).isEqualTo(r.innings() * 3);

            // 타석 = 타수 + 볼넷 + 사구 + 희생플라이
            for (int[] s : r.batting()[0]) {
                assertThat(s[BatStat.PA]).isEqualTo(s[BatStat.AB] + s[BatStat.BB] + s[BatStat.HBP] + s[BatStat.SF]);
            }

            assertThat(r.winningPitcherId()).isNotZero();
            assertThat(r.losingPitcherId()).isNotZero();
            assertDecisions(r.homeWin() ? r.homePitching() : r.awayPitching(), 'W', r.winningPitcherId());
            assertDecisions(r.homeWin() ? r.awayPitching() : r.homePitching(), 'L', r.losingPitcherId());
        }
    }

    @Test
    void 평균_팀끼리는_리그_평균을_재현한다() {
        SimTeam a = SimFixtures.uniformTeam(1, 50);
        SimTeam h = SimFixtures.uniformTeam(2, 50);
        double[] league = SimFixtures.LEAGUE.rates();

        int games = 20_000;
        long pa = 0, so = 0, bb = 0, hr = 0, runs = 0, homeWins = 0, extra = 0;
        for (long seed = 0; seed < games; seed++) {
            GameResult r = sim.simulate(a, h, seed);
            runs += r.awayRuns() + r.homeRuns();
            if (r.homeWin()) {
                homeWins++;
            }
            if (r.innings() > 9) {
                extra++;
            }
            for (int[][] side : r.batting()) {
                for (int[] s : side) {
                    pa += s[BatStat.PA];
                    so += s[BatStat.SO];
                    bb += s[BatStat.BB];
                    hr += s[BatStat.HR];
                }
            }
        }

        double runsPerTeamGame = runs / (games * 2.0);
        System.out.printf("R/G=%.2f K%%=%.3f BB%%=%.3f HR%%=%.4f 홈승률=%.3f 연장=%.3f%n",
                runsPerTeamGame, (double) so / pa, (double) bb / pa, (double) hr / pa,
                (double) homeWins / games, (double) extra / games);

        // 좌우 상성 비율 때문에 리그 평균에서 약간 벗어나는 것은 허용
        assertThat((double) so / pa).isCloseTo(league[Outcomes.STRIKEOUT], within(0.015));
        assertThat((double) bb / pa).isCloseTo(league[Outcomes.WALK], within(0.01));
        assertThat((double) hr / pa).isCloseTo(league[Outcomes.HOME_RUN], within(0.005));
        // MLB 2024~2025 팀당 경기 득점 약 4.3~4.5
        assertThat(runsPerTeamGame).isBetween(3.6, 5.2);
    }

    @Test
    void 강팀이_더_많이_이긴다() {
        SimTeam strong = SimFixtures.uniformTeam(1, 60);
        SimTeam weak = SimFixtures.uniformTeam(2, 40);
        int games = 4_000;
        int strongWins = 0;
        for (long seed = 0; seed < games; seed++) {
            boolean strongHome = seed % 2 == 0;
            GameResult r = strongHome ? sim.simulate(weak, strong, seed) : sim.simulate(strong, weak, seed);
            if (r.homeWin() == strongHome) {
                strongWins++;
            }
        }
        double pct = (double) strongWins / games;
        System.out.printf("60 vs 40 승률=%.3f%n", pct);
        assertThat(pct).isGreaterThan(0.65);
    }

    private static int sumLine(int[] line) {
        int sum = 0;
        for (int v : line) {
            if (v > 0) {
                sum += v;
            }
        }
        return sum;
    }

    private static int sumBat(int[][] side, int stat) {
        int sum = 0;
        for (int[] s : side) {
            sum += s[stat];
        }
        return sum;
    }

    @Test
    void 실책_폭투가_없으면_9이닝_경기의_실점은_모두_자책() {
        GameSimulator clean = new GameSimulator(SimFixtures.LEAGUE.withMisc(0, 0));
        SimTeam a = SimFixtures.uniformTeam(1, 50);
        SimTeam h = SimFixtures.uniformTeam(2, 50);
        for (long seed = 0; seed < 2_000; seed++) {
            GameResult r = clean.simulate(a, h, seed);
            if (r.innings() == 9) {
                for (PitcherLine l : r.homePitching()) {
                    assertThat(l.earnedRuns()).isEqualTo(l.runs());
                }
            }
        }
    }

    @Test
    void 실책이_많으면_득점과_비자책점이_늘어난다() {
        GameSimulator clean = new GameSimulator(SimFixtures.LEAGUE.withMisc(0, 0));
        GameSimulator sloppy = new GameSimulator(SimFixtures.LEAGUE.withMisc(0.10, 0.10));
        SimTeam a = SimFixtures.uniformTeam(1, 50);
        SimTeam h = SimFixtures.uniformTeam(2, 50);
        long cleanRuns = 0;
        long sloppyRuns = 0;
        long sloppyUnearned = 0;
        for (long seed = 0; seed < 2_000; seed++) {
            GameResult c = clean.simulate(a, h, seed);
            GameResult s = sloppy.simulate(a, h, seed);
            cleanRuns += c.awayRuns() + c.homeRuns();
            sloppyRuns += s.awayRuns() + s.homeRuns();
            for (PitcherLine l : s.homePitching()) {
                assertThat(l.earnedRuns()).isLessThanOrEqualTo(l.runs());
                sloppyUnearned += l.runs() - l.earnedRuns();
            }
        }
        assertThat(sloppyRuns).isGreaterThan(cleanRuns);
        assertThat(sloppyUnearned).isPositive();
    }

    @Test
    void 선발은_타순_세_바퀴를_돌면_다음_이닝에_교체() {
        SimTeam ace = SimFixtures.uniformTeam(1, 75);   // 강한 선발 + 약한 상대 → 투구 수보다 타순 바퀴로 내려감
        SimTeam weak = SimFixtures.uniformTeam(2, 30);
        int maxBf = 0;
        for (long seed = 0; seed < 2_000; seed++) {
            GameResult r = sim.simulate(ace, weak, seed);
            maxBf = Math.max(maxBf, r.awayPitching().getFirst().battersFaced());
        }
        // 27명을 채운 이닝 도중까지만 (이닝당 최대 십수 명)
        assertThat(maxBf).isBetween(GameSimulator.STARTER_MAX_BF, GameSimulator.STARTER_MAX_BF + 15);
    }

    @Test
    void 점수차가_크면_불펜_뒤쪽_추격조가_나온다() {
        SimTeam strong = SimFixtures.uniformTeam(1, 75);
        SimTeam weak = SimFixtures.uniformTeam(2, 25);
        long mopUpId = 2 * 100 + 30 + 7; // 약팀 불펜 마지막 투수
        int used = 0;
        for (long seed = 0; seed < 500; seed++) {
            GameResult r = sim.simulate(strong, weak, seed);
            used += (int) r.homePitching().stream().filter(l -> l.playerId() == mopUpId).count();
        }
        assertThat(used).isPositive();
    }

    private static void assertDecisions(List<PitcherLine> lines, char decision, long playerId) {
        assertThat(lines).anyMatch(l -> l.decision() == decision && l.playerId() == playerId);
    }
}
