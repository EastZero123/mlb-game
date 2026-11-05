package com.mlbgame.offseason;

import java.time.LocalDate;

import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;

/** 오프시즌 처리 중인 선수 1명 (c_player 1행 + 신상). 오프시즌은 전 선수를 한 번에 읽고, 바꾸고, 한 번에 쓴다. */
final class OffPlayer {

    static final int ST_MINOR = 0, ST_40MAN = 1, ST_ACTIVE = 2, ST_INJURED = 3, ST_RETIRED = 4;

    final int id;
    final LocalDate birth;
    final int pos;
    final short[] r;
    /** 0 = 소속 없음 (FA) */
    int team;
    int level;
    int status;
    int contractType;
    int contractYears;
    int salary;
    int service;
    int adaptability;
    int stability;
    int professionalism;
    LocalDate injuryUntil;
    /** 프로 경력 연차 (매 오프시즌 1단계 +1, 마이너 6년 규칙) */
    int proSeasons;
    /** 지난 시즌 MLB 출전 (타자 경기 수 / 투수 등판 수) */
    int mlbGames;
    /** 이번 오프시즌에 새로 생긴 선수 (드래프트·포스팅): c_person 행 */
    Object[] personRow;
    /** 이번 오프시즌 드래프트 지명 (방출 대상에서 제외) */
    boolean draftee;

    static final int PENDING_NONE = 0, PENDING_FA = 1, PENDING_ARB = 2;
    static final int DECIDE_NONE = 0, DECIDE_KEEP = 1, DECIDE_RELEASE = 2;
    /** 내 팀 계약 결정 대상 (c_contract_decision.kind): 1 FA 자격 = 재계약 여부, 2 연봉조정 = 텐더 여부 */
    int pending;
    /** 유저 결정 (0 = 아직 안 함 → AI 기본값), 재계약 연수 */
    int decision;
    int decisionYears;

    OffPlayer(int id, LocalDate birth, int pos, short[] r) {
        this.id = id;
        this.birth = birth;
        this.pos = pos;
        this.r = r;
    }

    boolean isPitcher() {
        return r[MasterData.CONTROL] >= 0 && (r[MasterData.CONTACT] < 0 || pos == 1);
    }

    boolean retired() {
        return status == ST_RETIRED;
    }

    boolean freeAgent() {
        return team == 0 && !retired();
    }

    /** 40인 명단(계약상 MLB 선수) 여부 */
    boolean fortyMan() {
        return status == ST_40MAN || status == ST_ACTIVE || status == ST_INJURED;
    }

    double value() {
        return ContractModel.value(r, pos);
    }

    /** 시즌 중반(7월 1일) 기준 나이 */
    int age(int season) {
        return AgingCurves.age(birth, season);
    }

    boolean starterType() {
        return r[MasterData.STAMINA] >= 50;
    }

    boolean canCatch() {
        return !isPitcher() && r[MasterData.DEF_C] > 0;
    }
}
