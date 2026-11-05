-- 실제 팀 시즌 결과 (순위표 + 팀 타격 합계). 검증·보정의 기준값
CREATE TABLE mst_team_season (
    team_id        SMALLINT UNSIGNED NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    wins           SMALLINT UNSIGNED NOT NULL,
    losses         SMALLINT UNSIGNED NOT NULL,
    runs_scored    SMALLINT UNSIGNED NOT NULL,
    runs_allowed   SMALLINT UNSIGNED NOT NULL,
    pa             MEDIUMINT UNSIGNED NOT NULL,
    so             SMALLINT UNSIGNED NOT NULL,
    bb             SMALLINT UNSIGNED NOT NULL,
    hbp            SMALLINT UNSIGNED NOT NULL,
    h              SMALLINT UNSIGNED NOT NULL,
    b2             SMALLINT UNSIGNED NOT NULL,
    b3             SMALLINT UNSIGNED NOT NULL,
    hr             SMALLINT UNSIGNED NOT NULL,
    ground_outs    SMALLINT UNSIGNED NOT NULL,
    air_outs       SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (team_id, season)
) ENGINE = InnoDB;

-- 시뮬레이터 리그 기준값 (능력치 50 대 50 의 타석 결과 확률).
-- 검증기의 자동 보정 결과를 저장하고, 게임 서버는 기동 시 1번 읽어 캐시한다.
CREATE TABLE mst_league (
    season         SMALLINT UNSIGNED NOT NULL,
    p_so           DOUBLE            NOT NULL,
    p_bb           DOUBLE            NOT NULL,
    p_hbp          DOUBLE            NOT NULL,
    p_1b           DOUBLE            NOT NULL,
    p_2b           DOUBLE            NOT NULL,
    p_3b           DOUBLE            NOT NULL,
    p_hr           DOUBLE            NOT NULL,
    p_go           DOUBLE            NOT NULL,
    p_ao           DOUBLE            NOT NULL,
    platoon_edge   DOUBLE            NOT NULL,
    calibrated_at  DATETIME          NOT NULL,
    note           VARCHAR(200)      NULL,
    PRIMARY KEY (season)
) ENGINE = InnoDB;
