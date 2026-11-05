package com.mlbgame.season;

/**
 * 유저 팀 로스터 이동 규칙 (검사 + 메모리 반영). 저장은 {@link MoveService}.
 * <ul>
 *   <li>MLB 현역 최대 26명, 40인 명단 최대 40명 (MLB 현역 + MLB 부상자 + 산하 팀 40인)</li>
 *   <li>부상 중인 선수는 콜업 불가, MLB 부상자 명단 선수는 내릴 수 없음</li>
 *   <li>MLB 에서 내릴 때 같은 유형 현역이 {@link #MLB_MIN_PER_TYPE} 명 미만이 되면 불가
 *       (26명보다 적어진 자리는 다음 경기 전에 AI 가 산하 팀 최고 선수로 채움)</li>
 *   <li>AAA ↔ AA 이동은 떠나는 팀 유형별 최소 인원 유지 (아니면 매일 재조정이 되돌림)</li>
 * </ul>
 */
final class RosterMoves {

    static final int FORTY_MAN = 40;
    static final int MLB_MIN_PER_TYPE = 10;

    private RosterMoves() {
    }

    /**
     * 검사 후 메모리 상태에 반영. 규칙에 어긋나면 {@link IllegalArgumentException} (메시지 = 화면 안내).
     *
     * @param day 다음 경기일 (부상 판단 기준)
     */
    static void apply(CareerRuntime rt, int playerId, RosterMove m, long day) {
        CareerRuntime.Player p = rt.players.get(playerId);
        require(p != null && p.team == rt.userTeamId, "내 팀 선수가 아닙니다");
        int team = rt.userTeamId;
        switch (m) {
            case CALL_UP -> {
                require(p.level != 0, "이미 MLB 선수입니다");
                require(p.available(day), "부상 중이라 콜업할 수 없습니다");
                require(active(rt, team, null) < DayRules.MLB_ACTIVE, "MLB 26인이 꽉 찼습니다. 먼저 한 명을 내리세요");
                if (p.status == CareerRuntime.ST_MINOR) {
                    require(fortyMan(rt, team) < FORTY_MAN,
                            "40인 명단이 꽉 찼습니다 (마이너 계약 선수는 콜업하면 40인에 등록됩니다)");
                }
                rt.relocate(p, 0, CareerRuntime.ST_ACTIVE);
            }
            case TO_AAA, TO_AA -> {
                int target = m == RosterMove.TO_AAA ? 1 : 2;
                require(p.level != target, "이미 그 레벨 선수입니다");
                if (p.level == 0) {
                    require(p.status == CareerRuntime.ST_ACTIVE, "부상자 명단 선수는 내릴 수 없습니다");
                    require(active(rt, team, p.isPitcher()) - 1 >= MLB_MIN_PER_TYPE,
                            "MLB " + (p.isPitcher() ? "투수" : "타자") + "가 " + MLB_MIN_PER_TYPE
                                    + "명 미만이 됩니다. 먼저 같은 유형을 콜업하세요");
                    rt.relocate(p, target, CareerRuntime.ST_40MAN);
                } else {
                    checkMinorFloor(rt, p, day);
                    rt.relocate(p, target, p.status);
                }
            }
            case ADD_40 -> {
                require(p.level != 0 && p.status == CareerRuntime.ST_MINOR, "마이너 계약 산하 팀 선수만 등록할 수 있습니다");
                require(fortyMan(rt, team) < FORTY_MAN, "40인 명단이 꽉 찼습니다");
                rt.relocate(p, p.level, CareerRuntime.ST_40MAN);
            }
            case REMOVE_40 -> {
                require(p.level != 0 && p.status == CareerRuntime.ST_40MAN,
                        "산하 팀의 40인 선수만 제외할 수 있습니다 (MLB 선수는 먼저 내리세요)");
                rt.relocate(p, p.level, CareerRuntime.ST_MINOR);
            }
        }
    }

    /** 산하 팀끼리 옮길 때 떠나는 팀이 최소 인원 아래로 내려가지 않게 (부상 선수는 원래 세지 않음) */
    private static void checkMinorFloor(CareerRuntime rt, CareerRuntime.Player p, long day) {
        if (!p.available(day)) {
            return;
        }
        int type = p.isPitcher() ? 1 : 0;
        int min = (p.level == 1 ? DayRules.MIN_AAA : DayRules.MIN_AA)[type];
        int have = DayRules.count(rt, p.level, p.team, p.isPitcher(), day);
        require(have - 1 >= min, (p.level == 1 ? "AAA" : "AA") + " " + (type == 1 ? "투수" : "타자") + "가 최소 "
                + min + "명 아래로 내려갑니다");
    }

    /** MLB 현역 수 (pitcher null = 전체) */
    static int active(CareerRuntime rt, int team, Boolean pitcher) {
        int n = 0;
        for (CareerRuntime.Player p : rt.members(0, team)) {
            if (p.status == CareerRuntime.ST_ACTIVE && (pitcher == null || p.isPitcher() == pitcher)) {
                n++;
            }
        }
        return n;
    }

    /** 40인 명단 = MLB 현역 + MLB 부상자 + 산하 팀 40인 */
    static int fortyMan(CareerRuntime rt, int team) {
        int n = 0;
        for (int level = 0; level < CareerRuntime.LEVELS; level++) {
            for (CareerRuntime.Player p : rt.members(level, team)) {
                if (p.status != CareerRuntime.ST_MINOR) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
