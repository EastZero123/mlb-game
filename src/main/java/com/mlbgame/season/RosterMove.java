package com.mlbgame.season;

/** 유저 팀 로스터 직접 이동 종류 */
public enum RosterMove {
    /** 산하 팀 → MLB 26인 (마이너 계약이면 40인 등록도 함께) */
    CALL_UP("MLB 콜업"),
    /** MLB → AAA (40인 유지) 또는 AA → AAA */
    TO_AAA("AAA 로 이동"),
    /** MLB → AA (40인 유지) 또는 AAA → AA */
    TO_AA("AA 로 이동"),
    /** 산하 팀 마이너 계약 선수 → 40인 명단 등록 */
    ADD_40("40인 등록"),
    /** 산하 팀 40인 선수 → 40인 제외 (마이너 계약 신분) */
    REMOVE_40("40인 제외");

    private final String label;

    RosterMove(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
