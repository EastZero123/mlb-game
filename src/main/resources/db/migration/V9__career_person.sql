-- 커리어 안에서 새로 생긴 선수의 신상 (마스터에 없는 선수: 시즌 중 대체 영입, 이후 드래프트·포스팅)
-- 능력치·소속·계약은 다른 선수와 똑같이 c_player 에 있다. player_id 는 10억부터 (MLBAM ID 와 겹치지 않음)
CREATE TABLE c_person (
    career_id    INT UNSIGNED      NOT NULL,
    player_id    INT UNSIGNED      NOT NULL,
    full_name    VARCHAR(60)       NOT NULL,
    birth_date   DATE              NOT NULL,
    bats         CHAR(1)           NOT NULL,
    throws       CHAR(1)           NOT NULL,
    primary_pos  TINYINT UNSIGNED  NOT NULL,
    origin       TINYINT UNSIGNED  NOT NULL COMMENT '1=시즌 중 대체 영입 2=드래프트 3=포스팅',
    created_on   DATE              NOT NULL,
    PRIMARY KEY (career_id, player_id)
) ENGINE = InnoDB;
