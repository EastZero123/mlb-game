package com.mlbgame.sim;

/** 테스트용 팀 생성. 모든 능력치를 같은 값으로 채운다. */
final class SimFixtures {

    static final LeagueBaseline LEAGUE = LeagueBaseline.mlbDefault();
    static final RateModel MODEL = new RateModel(LEAGUE);

    private SimFixtures() {
    }

    static SimTeam uniformTeam(long teamId, int rating) {
        SimBatter[] lineup = new SimBatter[9];
        for (int i = 0; i < 9; i++) {
            BatterRatings r = new BatterRatings(rating, rating, rating, rating, 50, rating, rating);
            // 좌타 3명 / 우타 6명 (실제 리그 비율 근사)
            Hand hand = i % 3 == 0 ? Hand.LEFT : Hand.RIGHT;
            lineup[i] = MODEL.batter(teamId * 100 + i, r, hand, Performance.NEUTRAL);
        }

        // 선발 스태미나 70(약 95구), 불펜 30(약 31구)
        PitcherRatings starterRatings = new PitcherRatings(rating, rating, rating, 50, 70);
        SimPitcher starter = MODEL.pitcher(teamId * 100 + 20, starterRatings, Hand.RIGHT, Performance.NEUTRAL);

        SimPitcher[] bullpen = new SimPitcher[8];
        for (int i = 0; i < bullpen.length; i++) {
            PitcherRatings rr = new PitcherRatings(rating, rating, rating, 50, 30);
            Hand hand = i % 3 == 0 ? Hand.LEFT : Hand.RIGHT;
            bullpen[i] = MODEL.pitcher(teamId * 100 + 30 + i, rr, hand, Performance.NEUTRAL);
        }
        return new SimTeam(teamId, lineup, starter, bullpen, RateModel.defenseFactor(rating), RateModel.z(rating));
    }
}
