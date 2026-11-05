-- 프로 경력 연차: 마이너 계약 만료 때 6년 이상 + MLB 경력(서비스) 없음이면 방출(은퇴 처리)하는 규칙용.
-- 매 오프시즌 1단계에서 소속 선수 +1. 기존 선수는 나이로 추정 (19세 입단 가정, 0~15).
-- MySQL 은 ALTER 가 바로 확정되므로, 중간에 실패한 뒤 다시 실행해도 되도록 컬럼이 없을 때만 추가한다.
SET @has_pro := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'c_player' AND column_name = 'pro_seasons');
SET @ddl := IF(@has_pro = 0,
    'ALTER TABLE c_player ADD COLUMN pro_seasons TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''프로 경력 연차 (마이너 6년 규칙)''',
    'SELECT 1');
PREPARE add_pro FROM @ddl;
EXECUTE add_pro;
DEALLOCATE PREPARE add_pro;

-- season 은 부호 없는 형이라 19세 미만(음수)에서 범위 오류가 나므로 SIGNED 로 계산
UPDATE c_player c
    JOIN career k ON k.career_id = c.career_id
    LEFT JOIN mst_player m ON m.player_id = c.player_id
    LEFT JOIN c_person p ON p.career_id = c.career_id AND p.player_id = c.player_id
SET c.pro_seasons = LEAST(15, GREATEST(0,
        CAST(k.season AS SIGNED) - CAST(YEAR(COALESCE(m.birth_date, p.birth_date)) AS SIGNED) - 19))
WHERE COALESCE(m.birth_date, p.birth_date) IS NOT NULL;

-- 마이너 계약 연봉 0 → 7.5만 달러 고정 (예산·연봉 합계에는 넣지 않음)
UPDATE c_player SET salary = 75
WHERE contract_type = 0 AND team_id IS NOT NULL AND roster_status <> 4;
