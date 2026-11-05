-- 포스트시즌 시리즈 (2022~ MLB 12팀 방식). 경기 자체는 c_game (game_no 10000 이상), 기록은 c_bat/pit_season level 3
CREATE TABLE c_series (
    career_id   INT UNSIGNED      NOT NULL,
    season      SMALLINT UNSIGNED NOT NULL,
    series_id   TINYINT UNSIGNED  NOT NULL COMMENT '1~4 와일드카드, 5~8 디비전, 9~10 챔피언십, 11 월드시리즈',
    round       TINYINT UNSIGNED  NOT NULL COMMENT '1=WC 2=DS 3=CS 4=WS',
    league      CHAR(2)           NULL     COMMENT 'AL/NL, 월드시리즈는 NULL',
    best_of     TINYINT UNSIGNED  NOT NULL,
    high_team   SMALLINT UNSIGNED NOT NULL COMMENT '상위 시드 (홈 어드밴티지)',
    low_team    SMALLINT UNSIGNED NOT NULL,
    high_seed   TINYINT UNSIGNED  NOT NULL,
    low_seed    TINYINT UNSIGNED  NOT NULL,
    high_wins   TINYINT UNSIGNED  NOT NULL DEFAULT 0,
    low_wins    TINYINT UNSIGNED  NOT NULL DEFAULT 0,
    next_date   DATE              NULL     COMMENT '다음 경기일, 끝나면 NULL',
    winner      SMALLINT UNSIGNED NULL,
    PRIMARY KEY (career_id, season, series_id)
) ENGINE = InnoDB;
