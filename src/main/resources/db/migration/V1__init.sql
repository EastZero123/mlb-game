-- 초기 마이그레이션: 게임 규칙 확정 후 테이블을 추가한다.
-- 공통 규칙: InnoDB, utf8mb4, PK 는 BIGINT/INT, 시간은 DATETIME(애플리케이션 KST 기준).
SELECT 1;
