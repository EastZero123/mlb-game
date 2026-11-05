-- 내 팀 계약 결정 (오프시즌 1단계에서 대상 등록 → 4단계 연봉 재산정 후 화면에서 결정 → 5단계 시작 때 반영).
-- AI 구단은 이 표를 쓰지 않는다 (규칙대로 바로 처리).
CREATE TABLE c_contract_decision (
    career_id INT UNSIGNED      NOT NULL,
    season    SMALLINT UNSIGNED NOT NULL COMMENT '끝난 시즌',
    player_id INT UNSIGNED      NOT NULL,
    kind      TINYINT UNSIGNED  NOT NULL COMMENT '1 FA 자격 (재계약 여부) 2 연봉조정 (텐더 여부)',
    decision  TINYINT UNSIGNED  NULL     COMMENT '1 재계약·텐더 2 포기·논텐더, NULL = 아직 결정 안 함 (AI 기본값)',
    years     TINYINT UNSIGNED  NULL     COMMENT '재계약 연수',
    PRIMARY KEY (career_id, season, player_id)
) ENGINE = InnoDB;
