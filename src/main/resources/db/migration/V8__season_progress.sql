-- =====================================================================
-- "다음 날" 진행용: 시즌 기록을 타격/투구로 분리 (매일 갱신하는 행 폭 절반), 레벨 포함, 마이너 순위
-- c_player_season 은 아직 데이터가 쌓이기 전이라 교체한다.
-- =====================================================================

DROP TABLE c_player_season;

-- 시즌 누적 타격. 하루치를 메모리에서 합산해 INSERT ... ON DUPLICATE KEY UPDATE (가산) 1번
CREATE TABLE c_bat_season (
    career_id  INT UNSIGNED      NOT NULL,
    season     SMALLINT UNSIGNED NOT NULL,
    level      TINYINT UNSIGNED  NOT NULL COMMENT '0=MLB 1=AAA 2=AA',
    player_id  INT UNSIGNED      NOT NULL,
    team_id    SMALLINT UNSIGNED NOT NULL COMMENT '마지막으로 뛴 구단',
    g SMALLINT UNSIGNED NOT NULL, pa SMALLINT UNSIGNED NOT NULL, ab SMALLINT UNSIGNED NOT NULL,
    r SMALLINT UNSIGNED NOT NULL, h SMALLINT UNSIGNED NOT NULL, b2 SMALLINT UNSIGNED NOT NULL,
    b3 SMALLINT UNSIGNED NOT NULL, hr SMALLINT UNSIGNED NOT NULL, rbi SMALLINT UNSIGNED NOT NULL,
    bb SMALLINT UNSIGNED NOT NULL, so SMALLINT UNSIGNED NOT NULL, hbp SMALLINT UNSIGNED NOT NULL,
    sb SMALLINT UNSIGNED NOT NULL, cs SMALLINT UNSIGNED NOT NULL, sf SMALLINT UNSIGNED NOT NULL,
    gidp SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (career_id, season, level, player_id)
) ENGINE = InnoDB;

CREATE TABLE c_pit_season (
    career_id  INT UNSIGNED      NOT NULL,
    season     SMALLINT UNSIGNED NOT NULL,
    level      TINYINT UNSIGNED  NOT NULL,
    player_id  INT UNSIGNED      NOT NULL,
    team_id    SMALLINT UNSIGNED NOT NULL,
    g SMALLINT UNSIGNED NOT NULL, gs SMALLINT UNSIGNED NOT NULL, w SMALLINT UNSIGNED NOT NULL,
    l SMALLINT UNSIGNED NOT NULL, sv SMALLINT UNSIGNED NOT NULL, outs SMALLINT UNSIGNED NOT NULL,
    bf SMALLINT UNSIGNED NOT NULL, pitches MEDIUMINT UNSIGNED NOT NULL, h SMALLINT UNSIGNED NOT NULL,
    r SMALLINT UNSIGNED NOT NULL, er SMALLINT UNSIGNED NOT NULL, bb SMALLINT UNSIGNED NOT NULL,
    so SMALLINT UNSIGNED NOT NULL, hr SMALLINT UNSIGNED NOT NULL, hbp SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (career_id, season, level, player_id)
) ENGINE = InnoDB;

-- 마이너 순위 (팀 = 구단 + 레벨). 마이너 경기는 박스스코어를 저장하지 않는다
CREATE TABLE c_team_minor (
    career_id    INT UNSIGNED      NOT NULL,
    level        TINYINT UNSIGNED  NOT NULL COMMENT '1=AAA 2=AA',
    team_id      SMALLINT UNSIGNED NOT NULL COMMENT '모기업 구단',
    wins         SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    losses       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    runs_for     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    runs_against SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (career_id, level, team_id)
) ENGINE = InnoDB;
