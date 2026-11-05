-- 능력치 → 결과 계수 (실제 기록 회귀 추정, 숫자 70개 쉼표 구분). NULL 이면 코드 기본값 사용
ALTER TABLE mst_league ADD COLUMN rate_coef TEXT NULL AFTER platoon_edge;
