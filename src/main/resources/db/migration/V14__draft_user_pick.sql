-- 드래프트 화면: 지명은 드래프트 단계 중 c_draft_pick.player_id 에 한 장씩 기록된다 (선수 행은 6단계 처리 때 생성).
-- 같은 선수가 두 번 지명되지 않도록 보장 (NULL = 미지명은 여러 행 허용).
ALTER TABLE c_draft_pick
    ADD UNIQUE KEY uk_draft_player (career_id, season, player_id);
