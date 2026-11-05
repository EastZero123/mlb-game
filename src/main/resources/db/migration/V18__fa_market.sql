-- FA 라운드 입찰 시장. 오프시즌을 8단계로: 5 FA 시장 개장 → (라운드 1~6, FA 화면) → 6 FA 시장 마감 → 7 드래프트 → 8 개막.
-- 기존 7단계 기록: 5(포스팅·FA) 이상은 한 칸씩 뒤로 (옛 5 = 새 5·6 모두 끝난 상태).
UPDATE c_offseason SET stage = stage + 1 WHERE stage >= 5;

ALTER TABLE c_offseason
    ADD COLUMN fa_round TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'FA 시장 다음 라운드 (0 = 개장 전, 1~6 = 진행할 라운드, 7 = 라운드 끝)' AFTER stage;

-- 시장 선수 (가치 46+ FA). 요구액은 라운드마다 바뀌고, 최저선·원하는 연수·버티기는 숨김
CREATE TABLE c_fa_player (
    career_id    INT UNSIGNED      NOT NULL,
    season       SMALLINT UNSIGNED NOT NULL COMMENT '끝난 시즌',
    player_id    INT UNSIGNED      NOT NULL,
    market       INT UNSIGNED      NOT NULL COMMENT '시장가치 (천 달러)',
    ask          INT UNSIGNED      NOT NULL COMMENT '현재 요구 연봉',
    pref_years   TINYINT UNSIGNED  NOT NULL COMMENT '원하는 연수 (요구 연수)',
    reserve      INT UNSIGNED      NOT NULL COMMENT '최저선 (숨김)',
    holdout      TINYINT UNSIGNED  NOT NULL COMMENT '경쟁 시 버티는 라운드',
    status       TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '0 시장 1 계약 2 미계약 (마감)',
    team_id      SMALLINT UNSIGNED NULL     COMMENT '계약 구단',
    salary       INT UNSIGNED      NULL,
    years        TINYINT UNSIGNED  NULL,
    signed_round TINYINT UNSIGNED  NULL,
    best_team    SMALLINT UNSIGNED NULL     COMMENT '지난 라운드 최고 제안 구단 (숨김, AI 입찰 기준)',
    best_salary  INT UNSIGNED      NULL,
    offers       TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '지난 라운드 제안 구단 수 (관심 구단)',
    PRIMARY KEY (career_id, season, player_id)
) ENGINE = InnoDB;

-- 내 팀 제안 (라운드가 지나도 유지, 바꾸거나 철회 가능). AI 제안은 라운드마다 계산만 하고 저장하지 않음
CREATE TABLE c_fa_offer (
    career_id INT UNSIGNED      NOT NULL,
    season    SMALLINT UNSIGNED NOT NULL,
    player_id INT UNSIGNED      NOT NULL,
    salary    INT UNSIGNED      NOT NULL,
    years     TINYINT UNSIGNED  NOT NULL,
    reaction  TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '지난 라운드 반응 (FaMarket.R_*)',
    PRIMARY KEY (career_id, season, player_id)
) ENGINE = InnoDB;
