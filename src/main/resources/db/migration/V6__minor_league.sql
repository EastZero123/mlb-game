-- =====================================================================
-- 마이너 리그 (AAA·AA). 설계: docs/CAREER.md 6번
-- =====================================================================

-- 마이너 팀 (모기업 MLB 팀과 연결)
CREATE TABLE mst_affiliate (
    team_id        SMALLINT UNSIGNED NOT NULL COMMENT 'MiLB 팀 ID',
    parent_team_id SMALLINT UNSIGNED NOT NULL COMMENT '모기업 MLB 팀 ID',
    level          TINYINT UNSIGNED  NOT NULL COMMENT '1=AAA 2=AA',
    abbrev         VARCHAR(8)        NOT NULL,
    name           VARCHAR(50)       NOT NULL,
    PRIMARY KEY (team_id),
    KEY ix_mst_affiliate_parent (parent_team_id, level)
) ENGINE = InnoDB;

-- team_id 는 소속 구단(모기업). 마이너 계약 선수는 roster_status = 0
ALTER TABLE mst_player
    ADD COLUMN level TINYINT UNSIGNED NULL
        COMMENT '2026 개막 시점 0=MLB 1=AAA 2=AA, NULL=부상·하위 리그 등 미배정' AFTER roster_status;

ALTER TABLE mst_player_rating
    ADD COLUMN source TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '0=MLB 기록·Statcast 1=마이너 기록 환산 2=레벨 분포 무작위' AFTER sample_bf;

-- 마이너 연도별 기록 (MLB 기록 테이블과 분리: MLB 능력치 산정·검증 쿼리에 섞이지 않도록)
CREATE TABLE mst_bat_season_minor (
    player_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    level          TINYINT UNSIGNED  NOT NULL COMMENT '1=AAA 2=AA',
    team_id        SMALLINT UNSIGNED NOT NULL,
    g SMALLINT UNSIGNED NOT NULL, pa SMALLINT UNSIGNED NOT NULL, ab SMALLINT UNSIGNED NOT NULL,
    h SMALLINT UNSIGNED NOT NULL, b2 SMALLINT UNSIGNED NOT NULL, b3 SMALLINT UNSIGNED NOT NULL,
    hr SMALLINT UNSIGNED NOT NULL, bb SMALLINT UNSIGNED NOT NULL, so SMALLINT UNSIGNED NOT NULL,
    hbp SMALLINT UNSIGNED NOT NULL, sb SMALLINT UNSIGNED NOT NULL, cs SMALLINT UNSIGNED NOT NULL,
    sf SMALLINT UNSIGNED NOT NULL, go SMALLINT UNSIGNED NOT NULL, ao SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (player_id, season, level, team_id)
) ENGINE = InnoDB;

CREATE TABLE mst_pit_season_minor (
    player_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    level          TINYINT UNSIGNED  NOT NULL,
    team_id        SMALLINT UNSIGNED NOT NULL,
    g SMALLINT UNSIGNED NOT NULL, gs SMALLINT UNSIGNED NOT NULL, outs SMALLINT UNSIGNED NOT NULL,
    bf SMALLINT UNSIGNED NOT NULL, h SMALLINT UNSIGNED NOT NULL, er SMALLINT UNSIGNED NOT NULL,
    bb SMALLINT UNSIGNED NOT NULL, so SMALLINT UNSIGNED NOT NULL, hr SMALLINT UNSIGNED NOT NULL,
    hbp SMALLINT UNSIGNED NOT NULL, go SMALLINT UNSIGNED NOT NULL, ao SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (player_id, season, level, team_id)
) ENGINE = InnoDB;

-- 레벨 → MLB 환산 계수 (같은 시즌 두 레벨을 함께 뛴 선수 짝으로 추정). 마이너 경기 시뮬 기준값에도 사용
CREATE TABLE mst_level_factor (
    season   SMALLINT UNSIGNED NOT NULL,
    level    TINYINT UNSIGNED  NOT NULL COMMENT '1=AAA 2=AA',
    side     CHAR(1)           NOT NULL COMMENT 'B=타자 P=투수',
    f_so     DOUBLE NOT NULL, f_bb DOUBLE NOT NULL, f_hbp DOUBLE NOT NULL,
    f_1b     DOUBLE NOT NULL, f_2b DOUBLE NOT NULL, f_3b DOUBLE NOT NULL, f_hr DOUBLE NOT NULL,
    pairs    INT UNSIGNED NOT NULL COMMENT '추정에 쓴 선수-시즌 짝 수',
    PRIMARY KEY (season, level, side)
) ENGINE = InnoDB;
