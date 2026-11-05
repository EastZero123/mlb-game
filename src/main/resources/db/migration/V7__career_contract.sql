-- =====================================================================
-- 커리어 생성: 레벨(MLB/AAA/AA) · 계약 · 예산 · 시즌 단계. 설계: docs/CAREER.md
-- =====================================================================

ALTER TABLE career
    ADD COLUMN phase TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '1=정규시즌 2=포스트시즌 3=오프시즌' AFTER cur_date;

ALTER TABLE c_player
    ADD COLUMN level          TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '0=MLB 1=AAA 2=AA' AFTER roster_status,
    ADD COLUMN contract_type  TINYINT UNSIGNED  NOT NULL DEFAULT 0
        COMMENT '0=마이너 계약 1=연봉조정 전(최저연봉) 2=연봉조정 3=FA 계약' AFTER level,
    ADD COLUMN contract_years TINYINT UNSIGNED  NOT NULL DEFAULT 1
        COMMENT '남은 계약 연수 (연봉조정 전·연봉조정은 FA 까지 남은 보유 연수)' AFTER contract_type,
    ADD COLUMN salary         INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '연봉 (천 달러). 마이너 계약 0' AFTER contract_years,
    ADD COLUMN service_years  TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT 'MLB 서비스 연차' AFTER salary,
    DROP INDEX ix_c_player_team,
    ADD KEY ix_c_player_team (career_id, team_id, level, roster_status);

ALTER TABLE c_team
    ADD COLUMN budget  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '연봉 예산 (천 달러)' AFTER streak,
    ADD COLUMN payroll INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '40인 연봉 합계 (천 달러)' AFTER budget;
