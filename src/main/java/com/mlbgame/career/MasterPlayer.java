package com.mlbgame.career;

import java.time.LocalDate;

import com.mlbgame.sim.Hand;

/**
 * 마스터 선수 1명 (읽기 전용 캐시). 능력치는 {@link MasterData#RATING_COLUMNS} 순서, NULL = -1.
 *
 * @param teamId        소속 구단 (MLB 팀, 마이너 선수는 모기업)
 * @param rosterStatus  0=마이너 계약 1=40인 2=개막 26인
 * @param level         개막 레벨 0=MLB 1=AAA 2=AA, -1=미배정(부상·하위 리그)
 * @param source        능력치 출처 0=MLB 기록 1=마이너 환산 2=무작위
 * @param sample        2026 표본 (타석 + 상대 타자)
 * @param serviceYears  2026 이전 MLB 출전 시즌 수 (서비스 연차 근사)
 * @param bats          타석 (좌/우/양)
 * @param throwsHand    투구 손
 */
public record MasterPlayer(int id, LocalDate birth, int primaryPos, int teamId, int rosterStatus, int level,
                           int source, int sample, int serviceYears, short[] ratings, Hand bats,
                           Hand throwsHand) {

    public int rating(int column) {
        return ratings[column];
    }

    public boolean has(int column) {
        return ratings[column] >= 0;
    }

    public boolean isPitcher() {
        return primaryPos == 1;
    }

    /** 시즌 기준일(4월 1일) 나이 */
    public int age(int season) {
        return java.time.Period.between(birth, LocalDate.of(season, 4, 1)).getYears();
    }
}
