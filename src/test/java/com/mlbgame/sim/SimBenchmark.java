package com.mlbgame.sim;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 시뮬레이션 성능 측정. 일반 test 에서는 제외되고 {@code ./gradlew benchmark} 로만 실행된다.
 * 정밀 측정이 필요해지면 JMH 로 옮긴다.
 */
@Tag("benchmark")
class SimBenchmark {

    private static final int SEASON_GAMES = 2_430;

    @Test
    void 시즌_시뮬레이션_속도() {
        GameSimulator sim = new GameSimulator(SimFixtures.LEAGUE);
        SimTeam[] teams = new SimTeam[30];
        for (int i = 0; i < teams.length; i++) {
            teams[i] = SimFixtures.uniformTeam(i + 1, 40 + (i % 21));
        }

        // JIT 워밍업
        long sink = run(sim, teams, 100_000, 0);

        int seasons = 20;
        long start = System.nanoTime();
        sink += run(sim, teams, SEASON_GAMES * seasons, 1_000_000);
        long elapsed = System.nanoTime() - start;

        double perGameMicros = elapsed / 1_000.0 / (SEASON_GAMES * seasons);
        double perSeasonMs = elapsed / 1_000_000.0 / seasons;
        double perDayMs = perGameMicros * 15 / 1_000.0;
        System.out.printf("경기당 %.1f µs | 하루(15경기) %.3f ms | 시즌(2,430경기) %.1f ms | sink=%d%n",
                perGameMicros, perDayMs, perSeasonMs, sink);
    }

    private static long run(GameSimulator sim, SimTeam[] teams, int games, long seedBase) {
        long sink = 0;
        for (int g = 0; g < games; g++) {
            SimTeam away = teams[g % 30];
            SimTeam home = teams[(g * 7 + 1) % 30];
            if (away == home) {
                home = teams[(g + 1) % 30];
            }
            GameResult r = sim.simulate(away, home, seedBase + g);
            sink += r.awayRuns() + r.homeRuns();
        }
        return sink;
    }
}
