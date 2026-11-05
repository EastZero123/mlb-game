-- 오프시즌 단계 진행 (커리어·시즌당 1행). 단계마다 선수 상태를 c_player 에 저장하므로 중간에 멈춰도 이어서 진행.
CREATE TABLE c_offseason (
    career_id   INT UNSIGNED      NOT NULL,
    season      SMALLINT UNSIGNED NOT NULL COMMENT '끝난 시즌',
    stage       TINYINT UNSIGNED  NOT NULL DEFAULT 0
        COMMENT '끝낸 단계: 1 시즌 마감·추첨 2 은퇴 3 성장·노화 4 연봉 재산정 5 포스팅·FA 6 드래프트 7 개막 로스터(완료)',
    salary_rate DOUBLE            NULL     COMMENT '연봉 단가 (4단계 결과, 5단계 FA 시장가 계산용)',
    report      TEXT              NULL     COMMENT '단계별 결과 요약 (줄 단위 누적)',
    PRIMARY KEY (career_id, season)
) ENGINE = InnoDB;

-- 드래프트 지명권 (시즌 마감 때 1라운드 추첨 포함 순서 확정, 드래프트 단계에서 지명 선수 기록)
CREATE TABLE c_draft_pick (
    career_id INT UNSIGNED      NOT NULL,
    season    SMALLINT UNSIGNED NOT NULL COMMENT '끝난 시즌 (그해 오프시즌 드래프트)',
    pick_no   SMALLINT UNSIGNED NOT NULL COMMENT '전체 순번 1~300',
    round     TINYINT UNSIGNED  NOT NULL,
    team_id   SMALLINT UNSIGNED NOT NULL,
    pre_rank  TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '1라운드 추첨 대상의 추첨 전 순위 (1 = 최저 성적), 대상 아니면 0',
    player_id INT UNSIGNED      NULL     COMMENT '지명 선수 (드래프트 전 NULL)',
    PRIMARY KEY (career_id, season, pick_no)
) ENGINE = InnoDB;
