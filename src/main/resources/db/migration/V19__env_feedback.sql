-- 리그 환경 결과 보정치 (offseason.EnvFeedback): 개막 로스터 정규화 기준에 더하는 투수 능력치 보정 "제구,장타억제,구위".
-- 8단계(개막 로스터)에서 지난 시즌 볼넷·삼진·홈런 비율을 실제 2026 과 비교해 정하고, 다음 오프시즌이 이어받는다.
ALTER TABLE c_offseason
    ADD COLUMN norm_bias VARCHAR(64) NULL COMMENT '정규화 기준 보정 (제구,장타억제,구위)' AFTER salary_rate;
