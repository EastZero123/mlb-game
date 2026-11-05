-- 유저 팀 로스터 직접 조작 옵션: 매주 월요일 AI 자동 교체(AAA 유망주 ↔ MLB 최하위) 사용 여부
ALTER TABLE c_tactic
    ADD COLUMN auto_swap TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 = 월요일 자동 교체 사용' AFTER auto_rest;
