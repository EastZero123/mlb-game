-- 능력치 효과 배율(결과 그룹별) + 실책·폭투 확률. 검증기 자동 보정으로 채운다.
ALTER TABLE mst_league
    ADD COLUMN bat_spread_k    DOUBLE NOT NULL DEFAULT 1.0 AFTER platoon_edge,
    ADD COLUMN bat_spread_bb   DOUBLE NOT NULL DEFAULT 1.0 AFTER bat_spread_k,
    ADD COLUMN bat_spread_hr   DOUBLE NOT NULL DEFAULT 1.0 AFTER bat_spread_bb,
    ADD COLUMN bat_spread_hit  DOUBLE NOT NULL DEFAULT 1.0 AFTER bat_spread_hr,
    ADD COLUMN pit_spread_k    DOUBLE NOT NULL DEFAULT 1.0 AFTER bat_spread_hit,
    ADD COLUMN pit_spread_bb   DOUBLE NOT NULL DEFAULT 1.0 AFTER pit_spread_k,
    ADD COLUMN pit_spread_hr   DOUBLE NOT NULL DEFAULT 1.0 AFTER pit_spread_bb,
    ADD COLUMN pit_spread_hit  DOUBLE NOT NULL DEFAULT 1.0 AFTER pit_spread_hr,
    ADD COLUMN error_rate      DOUBLE NOT NULL DEFAULT 0.019 AFTER pit_spread_hit,
    ADD COLUMN wild_pitch_rate DOUBLE NOT NULL DEFAULT 0.024 AFTER error_rate;
