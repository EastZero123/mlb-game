package com.mlbgame.boxscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mlbgame.sim.BatterRatings;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.GameSimulator;
import com.mlbgame.sim.Hand;
import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.PitcherLine;
import com.mlbgame.sim.PitcherRatings;
import com.mlbgame.sim.RateModel;
import com.mlbgame.sim.SimBatter;
import com.mlbgame.sim.SimPitcher;
import com.mlbgame.sim.SimTeam;

class BoxScoreCodecTest {

    private static final LeagueBaseline LEAGUE = LeagueBaseline.mlbDefault();
    private static final RateModel MODEL = new RateModel(LEAGUE);

    @Test
    void 인코딩_디코딩_왕복() {
        GameSimulator sim = new GameSimulator(LEAGUE);
        SimTeam away = team(100, 55);
        SimTeam home = team(200, 45);

        int maxSize = 0;
        long totalSize = 0;
        int games = 3_000;
        for (long seed = 0; seed < games; seed++) {
            GameResult r = sim.simulate(away, home, seed);
            byte[] bytes = BoxScoreCodec.encode(r, away, home);
            maxSize = Math.max(maxSize, bytes.length);
            totalSize += bytes.length;

            BoxScore box = BoxScoreCodec.decode(bytes);
            assertThat(box.innings()).isEqualTo(r.innings());
            assertThat(Arrays.deepEquals(box.lineScore(), r.lineScore())).isTrue();
            assertThat(Arrays.deepEquals(box.batting(), r.batting())).isTrue();
            assertThat(box.lineupIds()[0][0]).isEqualTo((int) away.lineup()[0].playerId());
            assertThat(box.lineupIds()[1][8]).isEqualTo((int) home.lineup()[8].playerId());
            assertPitching(box.pitching().get(0), r.awayPitching());
            assertPitching(box.pitching().get(1), r.homePitching());
        }
        System.out.printf("박스스코어 평균 %d바이트, 최대 %d바이트%n", totalSize / games, maxSize);
        assertThat(maxSize).isLessThanOrEqualTo(2048); // c_game.box VARBINARY(2048)
    }

    @Test
    void 버전이_다르면_거부() {
        assertThatThrownBy(() -> BoxScoreCodec.decode(new byte[] {99, 9}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertPitching(List<BoxScore.PitcherBox> decoded, List<PitcherLine> original) {
        assertThat(decoded).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            BoxScore.PitcherBox d = decoded.get(i);
            PitcherLine o = original.get(i);
            assertThat(d.playerId()).isEqualTo((int) o.playerId());
            assertThat(d.starter()).isEqualTo(o.starter());
            assertThat(d.decision()).isEqualTo(o.decision());
            assertThat(d.outs()).isEqualTo(o.outs());
            assertThat(d.pitches()).isEqualTo(Math.min(o.pitches(), 255));
            assertThat(d.runs()).isEqualTo(o.runs());
            assertThat(d.earnedRuns()).isEqualTo(o.earnedRuns());
            assertThat(d.strikeouts()).isEqualTo(o.strikeouts());
        }
    }

    private static SimTeam team(int idBase, int rating) {
        SimBatter[] lineup = new SimBatter[9];
        for (int i = 0; i < 9; i++) {
            BatterRatings r = new BatterRatings(rating, rating, rating, rating, 50, rating, rating);
            lineup[i] = MODEL.batter(660_000L + idBase + i, r, i % 2 == 0 ? Hand.LEFT : Hand.RIGHT, 1.0);
        }
        SimPitcher starter = MODEL.pitcher(670_000L + idBase, new PitcherRatings(rating, rating, rating, 50, 70),
                Hand.RIGHT, 1.0);
        PitcherRatings pr = new PitcherRatings(rating, rating, rating, 50, 30);
        SimPitcher[] pen = new SimPitcher[8];
        for (int i = 0; i < pen.length; i++) {
            pen[i] = MODEL.pitcher(680_000L + idBase + i, pr, Hand.LEFT, 1.0);
        }
        return new SimTeam(idBase, lineup, starter, pen, 1.0, 0.0);
    }
}
