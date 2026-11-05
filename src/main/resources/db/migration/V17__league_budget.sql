-- 구단 예산 = 리그 시장 규모(30구단 예산 합, 매년 +2~6%) × 점유율 (offseason.LeagueBudget).
-- base_share = 기준 비율 (리그 평균 1.0): 시장 규모 지수와 커리어 시작 예산 비율의 평균. 첫 오프시즌에 정해 저장 (NULL = 아직).
ALTER TABLE c_team
    ADD COLUMN base_share DOUBLE NULL COMMENT '예산 기준 비율 (리그 평균 1.0, 성적 회귀 목표)' AFTER payroll;
