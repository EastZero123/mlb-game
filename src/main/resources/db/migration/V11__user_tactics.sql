-- 유저 팀 MLB 운영 지정 (커리어당 1행). 비어 있는 항목은 AI 자동 운영.
-- 지정 선수가 부상·강등 등으로 빠지면 그 자리만 자동으로 메우고, 돌아오면 다시 지정대로.
CREATE TABLE c_tactic (
    career_id  INT UNSIGNED     NOT NULL,
    lineup_r   VARCHAR(255)     NULL COMMENT '우투수 상대 타순: "선수ID:수비위치" 9개 쉼표 구분 (위치 2~9, 10=지명타자)',
    lineup_l   VARCHAR(255)     NULL COMMENT '좌투수 상대 타순 (NULL = 우투수 상대와 같음)',
    rotation   VARCHAR(255)     NULL COMMENT '선발 로테이션 순서: 선수ID 쉼표 구분',
    bullpen    VARCHAR(255)     NULL COMMENT '불펜 우선순위: [0]=마무리, 이후 투입 순서',
    auto_rest  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 = 주전 휴식일 자동 교체',
    updated_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (career_id)
) ENGINE = InnoDB;
