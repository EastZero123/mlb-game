-- =====================================================================
-- 핵심 스키마. 설계 근거: docs/SCHEMA.md
--  mst_  : 마스터 (실제 데이터, 읽기 전용, 모든 커리어 공유)
--  app_  : 계정
--  c_    : 커리어 데이터 (PK 선두 = career_id, FK 없음)
-- 능력치는 20~80 TINYINT UNSIGNED, 해당 없음은 NULL
-- =====================================================================

-- ---------------------------------------------------------------------
-- 마스터
-- ---------------------------------------------------------------------
CREATE TABLE mst_team (
    team_id        SMALLINT UNSIGNED NOT NULL COMMENT 'MLB 팀 ID',
    abbrev         VARCHAR(4)        NOT NULL,
    name           VARCHAR(40)       NOT NULL,
    league         CHAR(2)           NOT NULL COMMENT 'AL/NL',
    division       CHAR(1)           NOT NULL COMMENT 'E/C/W',
    venue_name     VARCHAR(60)       NOT NULL,
    park_run       TINYINT UNSIGNED  NOT NULL DEFAULT 100 COMMENT '구장 득점 보정 (100=중립)',
    park_hr        TINYINT UNSIGNED  NOT NULL DEFAULT 100 COMMENT '구장 홈런 보정 (100=중립)',
    PRIMARY KEY (team_id)
) ENGINE = InnoDB;

CREATE TABLE mst_player (
    player_id      INT UNSIGNED      NOT NULL COMMENT 'MLBAM 선수 ID',
    full_name      VARCHAR(60)       NOT NULL,
    birth_date     DATE              NOT NULL,
    bats           CHAR(1)           NOT NULL COMMENT 'L/R/S',
    throws         CHAR(1)           NOT NULL COMMENT 'L/R',
    primary_pos    TINYINT UNSIGNED  NOT NULL COMMENT '1=P 2=C 3=1B 4=2B 5=3B 6=SS 7=LF 8=CF 9=RF 10=DH',
    team_id        SMALLINT UNSIGNED NULL     COMMENT '2026 개막 40인 소속, NULL=FA',
    roster_status  TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '0=없음 1=40인 2=개막 26인',
    PRIMARY KEY (player_id),
    KEY ix_mst_player_team (team_id)
) ENGINE = InnoDB;

-- 실제 데이터 기반 능력치 원본. 커리어 생성 시 분포 샘플링의 중앙값
CREATE TABLE mst_player_rating (
    player_id      INT UNSIGNED      NOT NULL,
    -- 타격
    contact        TINYINT UNSIGNED  NULL,
    power          TINYINT UNSIGNED  NULL,
    eye            TINYINT UNSIGNED  NULL,
    quality        TINYINT UNSIGNED  NULL,
    bb_tendency    TINYINT UNSIGNED  NULL COMMENT '타구 성향 (50 초과 땅볼형)',
    speed          TINYINT UNSIGNED  NULL,
    baserunning    TINYINT UNSIGNED  NULL,
    -- 투구
    control        TINYINT UNSIGNED  NULL,
    suppression    TINYINT UNSIGNED  NULL,
    groundball     TINYINT UNSIGNED  NULL,
    stamina        TINYINT UNSIGNED  NULL,
    -- 구종: 구사율(%) / 구위. FF=포심 SI=싱커 FC=커터 SL=슬라이더 ST=스위퍼 CU=커브 CH=체인지업 FS=스플리터
    ff_use TINYINT UNSIGNED NULL, ff_stuff TINYINT UNSIGNED NULL,
    si_use TINYINT UNSIGNED NULL, si_stuff TINYINT UNSIGNED NULL,
    fc_use TINYINT UNSIGNED NULL, fc_stuff TINYINT UNSIGNED NULL,
    sl_use TINYINT UNSIGNED NULL, sl_stuff TINYINT UNSIGNED NULL,
    st_use TINYINT UNSIGNED NULL, st_stuff TINYINT UNSIGNED NULL,
    cu_use TINYINT UNSIGNED NULL, cu_stuff TINYINT UNSIGNED NULL,
    ch_use TINYINT UNSIGNED NULL, ch_stuff TINYINT UNSIGNED NULL,
    fs_use TINYINT UNSIGNED NULL, fs_stuff TINYINT UNSIGNED NULL,
    -- 수비 (포지션별, NULL = 소화 불가)
    def_c  TINYINT UNSIGNED NULL, def_1b TINYINT UNSIGNED NULL, def_2b TINYINT UNSIGNED NULL,
    def_3b TINYINT UNSIGNED NULL, def_ss TINYINT UNSIGNED NULL, def_lf TINYINT UNSIGNED NULL,
    def_cf TINYINT UNSIGNED NULL, def_rf TINYINT UNSIGNED NULL,
    arm            TINYINT UNSIGNED  NULL,
    framing        TINYINT UNSIGNED  NULL,
    blocking       TINYINT UNSIGNED  NULL,
    -- 숨김
    durability     TINYINT UNSIGNED  NOT NULL DEFAULT 50,
    potential      TINYINT UNSIGNED  NOT NULL DEFAULT 50,
    -- 분포 샘플링 가중치 근거 (표본이 작을수록 σ 증가)
    sample_pa      SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '2026 타석',
    sample_bf      SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '2026 상대 타자',
    PRIMARY KEY (player_id)
) ENGINE = InnoDB;

-- 2026 실제 일정 (모든 커리어 공유, 복사하지 않음)
CREATE TABLE mst_schedule (
    game_no        SMALLINT UNSIGNED NOT NULL COMMENT '시즌 내 경기 순번 1~2430',
    game_date      DATE              NOT NULL,
    away_team_id   SMALLINT UNSIGNED NOT NULL,
    home_team_id   SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (game_no),
    KEY ix_mst_schedule_date (game_date)
) ENGINE = InnoDB;

-- 2025 까지 실제 시즌 기록 (화면 표시 전용)
CREATE TABLE mst_bat_season (
    player_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    team_id        SMALLINT UNSIGNED NOT NULL,
    g SMALLINT UNSIGNED NOT NULL, pa SMALLINT UNSIGNED NOT NULL, ab SMALLINT UNSIGNED NOT NULL,
    r SMALLINT UNSIGNED NOT NULL, h SMALLINT UNSIGNED NOT NULL, b2 SMALLINT UNSIGNED NOT NULL,
    b3 SMALLINT UNSIGNED NOT NULL, hr SMALLINT UNSIGNED NOT NULL, rbi SMALLINT UNSIGNED NOT NULL,
    bb SMALLINT UNSIGNED NOT NULL, so SMALLINT UNSIGNED NOT NULL, hbp SMALLINT UNSIGNED NOT NULL,
    sb SMALLINT UNSIGNED NOT NULL, cs SMALLINT UNSIGNED NOT NULL, sf SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (player_id, season, team_id)
) ENGINE = InnoDB;

CREATE TABLE mst_pit_season (
    player_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    team_id        SMALLINT UNSIGNED NOT NULL,
    g SMALLINT UNSIGNED NOT NULL, gs SMALLINT UNSIGNED NOT NULL,
    w SMALLINT UNSIGNED NOT NULL, l SMALLINT UNSIGNED NOT NULL, sv SMALLINT UNSIGNED NOT NULL,
    outs SMALLINT UNSIGNED NOT NULL, h SMALLINT UNSIGNED NOT NULL, r SMALLINT UNSIGNED NOT NULL,
    er SMALLINT UNSIGNED NOT NULL, bb SMALLINT UNSIGNED NOT NULL, so SMALLINT UNSIGNED NOT NULL,
    hr SMALLINT UNSIGNED NOT NULL, hbp SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (player_id, season, team_id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- 계정 / 커리어
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    user_id        INT UNSIGNED      NOT NULL AUTO_INCREMENT,
    login_id       VARCHAR(30)       NOT NULL,
    password_hash  VARCHAR(100)      NOT NULL,
    created_at     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY ux_app_user_login (login_id)
) ENGINE = InnoDB;

CREATE TABLE career (
    career_id      INT UNSIGNED      NOT NULL AUTO_INCREMENT,
    user_id        INT UNSIGNED      NOT NULL,
    slot           TINYINT UNSIGNED  NOT NULL COMMENT '1~3',
    team_id        SMALLINT UNSIGNED NOT NULL COMMENT '유저가 맡은 팀',
    seed           BIGINT            NOT NULL COMMENT '분포 샘플링·경기 시뮬 재현용',
    season         SMALLINT UNSIGNED NOT NULL,
    cur_date       DATE              NOT NULL,
    next_game_no   SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '다음에 치를 경기 순번',
    scout_level    TINYINT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '1~5',
    version        INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '동시 진행 방지',
    created_at     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at     DATETIME          NULL,
    PRIMARY KEY (career_id),
    -- 삭제된 커리어는 슬롯을 점유하지 않도록 함수형 인덱스 대신 애플리케이션에서 검사
    KEY ix_career_user (user_id, slot)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- 커리어 데이터
-- ---------------------------------------------------------------------
CREATE TABLE c_player (
    career_id      INT UNSIGNED      NOT NULL,
    player_id      INT UNSIGNED      NOT NULL,
    team_id        SMALLINT UNSIGNED NULL     COMMENT 'NULL=FA',
    roster_status  TINYINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '0=없음 1=40인 2=26인 3=부상자명단',
    -- 능력치 (mst_player_rating 과 동일 구성, 커리어별 샘플링 값)
    contact TINYINT UNSIGNED NULL, power TINYINT UNSIGNED NULL, eye TINYINT UNSIGNED NULL,
    quality TINYINT UNSIGNED NULL, bb_tendency TINYINT UNSIGNED NULL, speed TINYINT UNSIGNED NULL,
    baserunning TINYINT UNSIGNED NULL,
    control TINYINT UNSIGNED NULL, suppression TINYINT UNSIGNED NULL, groundball TINYINT UNSIGNED NULL,
    stamina TINYINT UNSIGNED NULL,
    ff_use TINYINT UNSIGNED NULL, ff_stuff TINYINT UNSIGNED NULL,
    si_use TINYINT UNSIGNED NULL, si_stuff TINYINT UNSIGNED NULL,
    fc_use TINYINT UNSIGNED NULL, fc_stuff TINYINT UNSIGNED NULL,
    sl_use TINYINT UNSIGNED NULL, sl_stuff TINYINT UNSIGNED NULL,
    st_use TINYINT UNSIGNED NULL, st_stuff TINYINT UNSIGNED NULL,
    cu_use TINYINT UNSIGNED NULL, cu_stuff TINYINT UNSIGNED NULL,
    ch_use TINYINT UNSIGNED NULL, ch_stuff TINYINT UNSIGNED NULL,
    fs_use TINYINT UNSIGNED NULL, fs_stuff TINYINT UNSIGNED NULL,
    def_c  TINYINT UNSIGNED NULL, def_1b TINYINT UNSIGNED NULL, def_2b TINYINT UNSIGNED NULL,
    def_3b TINYINT UNSIGNED NULL, def_ss TINYINT UNSIGNED NULL, def_lf TINYINT UNSIGNED NULL,
    def_cf TINYINT UNSIGNED NULL, def_rf TINYINT UNSIGNED NULL,
    arm TINYINT UNSIGNED NULL, framing TINYINT UNSIGNED NULL, blocking TINYINT UNSIGNED NULL,
    -- 숨김 (고정)
    durability       TINYINT UNSIGNED NOT NULL,
    potential        TINYINT UNSIGNED NOT NULL,
    adaptability     TINYINT UNSIGNED NOT NULL,
    stability        TINYINT UNSIGNED NOT NULL,
    professionalism  TINYINT UNSIGNED NOT NULL,
    -- 상태 (값 + 기준일 저장, 시간 경과분은 읽을 때 계산)
    morale         TINYINT UNSIGNED  NOT NULL DEFAULT 50,
    familiarity    TINYINT UNSIGNED  NOT NULL DEFAULT 50,
    trust          TINYINT UNSIGNED  NOT NULL DEFAULT 50,
    state_date     DATE              NOT NULL,
    injury_until   DATE              NULL,
    PRIMARY KEY (career_id, player_id),
    KEY ix_c_player_team (career_id, team_id, roster_status)
) ENGINE = InnoDB;

-- 순위표 겸 팀 운영 설정
CREATE TABLE c_team (
    career_id      INT UNSIGNED      NOT NULL,
    team_id        SMALLINT UNSIGNED NOT NULL,
    wins           SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    losses         SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    runs_for       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    runs_against   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    streak         TINYINT           NOT NULL DEFAULT 0 COMMENT '+연승 / -연패',
    lineup         JSON              NULL COMMENT '타순·수비 위치',
    rotation       JSON              NULL COMMENT '선발 로테이션',
    bullpen        JSON              NULL COMMENT '불펜 역할',
    PRIMARY KEY (career_id, team_id)
) ENGINE = InnoDB;

-- 치른 경기. 일정 정보는 mst_schedule(game_no) 참조
CREATE TABLE c_game (
    career_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    game_no        SMALLINT UNSIGNED NOT NULL,
    away_runs      TINYINT UNSIGNED  NOT NULL,
    home_runs      TINYINT UNSIGNED  NOT NULL,
    innings        TINYINT UNSIGNED  NOT NULL,
    box            VARBINARY(2048)   NOT NULL COMMENT '박스스코어 바이너리 (BoxScoreCodec)',
    PRIMARY KEY (career_id, season, game_no)
) ENGINE = InnoDB;

-- 시즌 누적 기록. 하루치를 메모리에서 합산해 INSERT ... ON DUPLICATE KEY UPDATE 1번
CREATE TABLE c_player_season (
    career_id      INT UNSIGNED      NOT NULL,
    season         SMALLINT UNSIGNED NOT NULL,
    player_id      INT UNSIGNED      NOT NULL,
    team_id        SMALLINT UNSIGNED NOT NULL,
    -- 타격
    g SMALLINT UNSIGNED NOT NULL DEFAULT 0, pa SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    ab SMALLINT UNSIGNED NOT NULL DEFAULT 0, r SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    h SMALLINT UNSIGNED NOT NULL DEFAULT 0, b2 SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    b3 SMALLINT UNSIGNED NOT NULL DEFAULT 0, hr SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    rbi SMALLINT UNSIGNED NOT NULL DEFAULT 0, bb SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    so SMALLINT UNSIGNED NOT NULL DEFAULT 0, hbp SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    sb SMALLINT UNSIGNED NOT NULL DEFAULT 0, cs SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    sf SMALLINT UNSIGNED NOT NULL DEFAULT 0, gidp SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    -- 투구
    p_g SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_gs SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_w SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_l SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_sv SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_outs SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_bf SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_pitches SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_h SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_r SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_er SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_bb SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_so SMALLINT UNSIGNED NOT NULL DEFAULT 0, p_hr SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    p_hbp SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (career_id, season, player_id)
) ENGINE = InnoDB;
