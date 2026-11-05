-- 성사된 트레이드 기록 (내 팀 기준). 선수 이동 자체는 c_player.team_id 에 반영되고, 이 표는 이력 표시용.
CREATE TABLE c_trade (
    trade_id     INT UNSIGNED      NOT NULL AUTO_INCREMENT,
    career_id    INT UNSIGNED      NOT NULL,
    season       SMALLINT UNSIGNED NOT NULL,
    trade_date   DATE              NOT NULL,
    partner_team SMALLINT UNSIGNED NOT NULL,
    give_ids     VARCHAR(100)      NOT NULL COMMENT '보낸 선수 ID (쉼표)',
    get_ids      VARCHAR(100)      NOT NULL COMMENT '받은 선수 ID (쉼표)',
    summary      VARCHAR(500)      NOT NULL,
    created_at   TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (trade_id),
    KEY ix_trade_career (career_id, trade_id)
) ENGINE = InnoDB;
