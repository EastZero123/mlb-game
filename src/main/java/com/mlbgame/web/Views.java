package com.mlbgame.web;

import java.time.LocalDate;
import java.util.List;

/** 화면용 읽기 모델 (템플릿에서 record 접근자로 사용: ${row.name()}) */
public final class Views {

    private Views() {
    }

    public record Slot(int slot, Long careerId, String team, int season, LocalDate date, String phase) {
    }

    /** 커리어 요약 (소유자 확인 후 반환) */
    public record CareerHead(long careerId, int userId, int teamId, String teamName, int season, LocalDate date,
                             int phase, String phaseName, int scoutLevel, long seed) {
    }

    public record StandingRow(int teamId, String abbrev, String name, int wins, int losses, String pct, String gb,
                              int runsFor, int runsAgainst, String streak, boolean mine) {
    }

    public record Division(String name, List<StandingRow> rows) {
    }

    public record GameRow(int season, int gameNo, String away, int awayRuns, String home, int homeRuns, int innings,
                          boolean mine) {
    }

    public record SeriesRow(String round, String league, String high, int highSeed, int highWins, String low,
                            int lowSeed, int lowWins, String winner) {
    }

    /**
     * @param statusCode c_player.roster_status (0 마이너 계약, 1 40인, 2 26인, 3 부상자 명단)
     * @param injured    다음 경기일에 부상 중
     * @param overall    종합 = 스카우트 추정 현재 가치 (45 대체 선수, 55 주전급, 65+ 올스타급)
     */
    public record RosterRow(int playerId, String name, String pos, int age, String hands, String status,
                            String salary, List<String> ratings, String stats, boolean pitcher, int statusCode,
                            boolean injured, int overall) {
    }

    public record Rating(String label, String range) {
    }

    public record StatLine(int season, String level, String team, String line) {
    }

    public record PlayerPage(int playerId, String name, String pos, int age, String hands, String team, String level,
                             String status, String contract, String injury, boolean pitcher, List<Rating> ratings,
                             List<Rating> hidden, List<StatLine> stats, List<StatLine> realStats) {
    }

    // ------------------------------------------------------------------ 오프시즌

    /** 드래프트 지명권 한 장. preRank = 1라운드 추첨 전 순위 (추첨 대상 아니면 0), player = 지명 선수 (지명 전 null) */
    public record DraftRow(int pickNo, int round, String team, int preRank, String player, boolean mine) {

        /** "3위 ↑2" 처럼 추첨 전 순위와 이동 폭 (대상 아니면 "-") */
        public String preLabel() {
            if (preRank == 0) {
                return "-";
            }
            int move = preRank - pickNo;
            return preRank + "위" + (move > 0 ? " ↑" + move : move < 0 ? " ↓" + (-move) : "");
        }
    }

    /**
     * 오프시즌 진행 현황.
     *
     * @param done       끝낸 단계 수 (0~7)
     * @param nextLabel  다음 단계 이름 (다 끝났으면 null)
     * @param report     단계별 결과 요약 줄
     * @param firstRound 1라운드 30장 (추첨 전이면 빈 목록)
     * @param myPicks    내 팀 지명권 10장
     * @param contracts  내 팀 계약 결정 대상 수 (전체 / 아직 결정 안 함)
     */
    public record OffseasonView(int done, int total, String nextLabel, List<String> report, List<DraftRow> firstRound,
                                List<DraftRow> myPicks, int contracts, int contractsOpen, int faRound) {

        /** FA 라운드 진행 중 (5단계 끝, 라운드 1~6 남음) */
        public boolean faOpen() {
            return done == com.mlbgame.offseason.OffseasonService.Stage.FREE_AGENCY.ordinal() && faRound >= 1
                    && faRound <= com.mlbgame.offseason.FaService.ROUNDS;
        }

        /** 다음 단계가 포스팅·FA 이고 계약 결정 대상이 있음 (결정 화면 안내) */
        public boolean contractStage() {
            return done == com.mlbgame.offseason.OffseasonService.Stage.MARKET.ordinal() && contracts > 0;
        }

        /** 스카우트 단계 변경 가능 (FA 시장 개장 전) */
        public boolean scoutChangeable() {
            return com.mlbgame.offseason.Scouting.changeable(com.mlbgame.season.NextDayService.PHASE_OFFSEASON, done);
        }

        /** "다음 단계" 버튼 대신 다른 화면으로 안내해야 함 */
        public boolean blocked() {
            return draftNext() || contractStage() && contractsOpen > 0 || faOpen();
        }

        public String reportText() {
            return String.join(System.lineSeparator(), report);
        }

        /** 다음 단계가 드래프트 (대시보드는 "다음 단계" 대신 드래프트 화면으로 안내) */
        public boolean draftNext() {
            return done == com.mlbgame.offseason.OffseasonService.Stage.DRAFT.ordinal();
        }
    }

    // ------------------------------------------------------------------ 계약 결정

    /**
     * 계약 결정 대상 1명.
     *
     * @param roster     능력치 범위·기록 (내 팀 로스터 행, 없으면 null)
     * @param ask        FA 자격 = 재계약 요구 연봉, 연봉조정 = 조정 연봉 (천 달러)
     * @param fallbackLabel 결정하지 않으면 적용될 값
     */
    public record ContractRow(int playerId, String name, String pos, int age, int service, String lastSalary,
                              int ask, String askLabel, int maxYears, int choice, int years, String fallbackLabel,
                              RosterRow roster) {
    }

    /**
     * @param budget 다음 시즌 예산, committed 결정 대상 외 확정 연봉 (천 달러)
     */
    public record ContractPage(int season, int nextSeason, int budget, int committed, String budgetLabel,
                               String committedLabel, List<ContractRow> fa, List<ContractRow> arb, boolean saved,
                               String scout) {
    }

    // ------------------------------------------------------------------ 트레이드

    /**
     * 트레이드 화면 선수 1명.
     *
     * @param salaryK  연봉 (천 달러, 미리보기 합계용)
     * @param fortyMan 40인 명단 (연봉 합계·40인 인원에 들어감)
     * @param contract "FA 3년" / "연봉조정 2년" / "최저 5년" / "마이너"
     */
    public record TradeRow(int playerId, String name, String pos, int age, String level, String status,
                           String salary, int salaryK, boolean fortyMan, String contract, List<String> ratings,
                           boolean pitcher, int overall) {
    }

    // ------------------------------------------------------------------ FA 시장

    /**
     * FA 시장 선수 1명.
     *
     * @param rank    내 스카우트 추정 순위 (시장 전체 기준)
     * @param ask     "연 $25.0M × 6년"
     * @param result  계약 성사 시 "LAD 12년 $52.0M (3R)"
     */
    public record FaRow(int playerId, int rank, String name, String pos, int age, String hands, List<String> ratings,
                        String ask, int askSalary, int askYears, int offers, String result, boolean mineSigned,
                        String mySalary, int myYears, String reaction, int maxYears, boolean pitcher) {
    }

    /**
     * @param round   진행할 라운드 (7 = 라운드 끝)
     * @param room    예산 - 확정 연봉 - 제안 합계
     */
    public record FaPage(int season, int round, int rounds, String budget, String payroll, String offered, String room,
                         boolean overBudget, String type, List<FaRow> open, List<FaRow> signed, List<FaRow> myOffers,
                         String scout) {
    }

    // ------------------------------------------------------------------ 드래프트

    /**
     * 드래프트 후보 (능력치는 스카우트 범위).
     *
     * @param rank   내 스카우트 추정 순위 (남은 후보 전체 기준)
     * @param school 고졸 / 대졸
     */
    public record DraftProspectRow(int playerId, int rank, String name, String pos, int age, String school,
                                   String hands, List<String> ratings, String potential, boolean pitcher) {
    }

    /** 지명권 한 장 (player = 지명 전 null, current = 지금 지명 차례) */
    public record DraftPickRow(int pickNo, int round, String team, String player, boolean mine, boolean current) {
    }

    /**
     * @param currentLabel 지금 차례 ("3R 72순 BOS", 끝났으면 null)
     * @param myLeft       남은 내 지명권 수
     * @param type         후보 필터 all / h / p
     * @param available    남은 후보 수 (필터 적용 전)
     */
    public record DraftPage(int season, boolean myTurn, String currentLabel, int myLeft, String type, int available,
                            List<DraftProspectRow> prospects, List<DraftPickRow> around, List<DraftPickRow> myPicks,
                            String scout) {
    }

    // ------------------------------------------------------------------ 운영 지정

    /** 선택 목록 항목 (id 0 = 비어 있음) */
    public record Option(int id, String label) {
    }

    /** 타순 한 자리 (order 1~9, playerId 0 = 비어 있음) */
    public record LineupRow(int order, int playerId, int pos) {
    }

    /**
     * 운영 지정 입력값. 저장된 지정이 없는 항목은 지금 AI 가 쓰는 구성으로 채워 보여준다.
     *
     * @param lineupOn 타순 지정 사용, leftOn 좌투수 상대 타순 따로, rotOn 로테이션 지정, penOn 불펜 순서 지정
     */
    public record TacticForm(List<LineupRow> vsRight, List<LineupRow> vsLeft, List<Integer> rotation,
                             List<Integer> bullpen, boolean lineupOn, boolean leftOn, boolean rotOn, boolean penOn,
                             boolean autoRest, boolean autoSwap) {
    }

    /**
     * @param nextStarter 다음 경기 선발 (로테이션 순번 포함)
     * @param notes       지정 선수가 빠져 자동으로 대체 중인 자리 안내
     * @param defense     타자별 수비 가능 위치와 추정 범위
     */
    public record TacticPage(TacticForm form, List<Option> hitters, List<Option> pitchers, List<Option> positions,
                             String nextStarter, List<String> notes, List<RosterRow> roster,
                             java.util.Map<Integer, String> defense) {
    }

    public record BoxBatter(String name, int ab, int r, int h, int rbi, int bb, int so, int hr) {
    }

    public record BoxPitcher(String name, String ip, int h, int r, int er, int bb, int so, int hr, int pitches,
                             String decision) {
    }

    public record BoxPage(int season, int gameNo, String away, String home, List<Integer> awayLine,
                          List<Integer> homeLine, int awayRuns, int homeRuns, List<BoxBatter> awayBatting,
                          List<BoxBatter> homeBatting, List<BoxPitcher> awayPitching, List<BoxPitcher> homePitching) {
    }
}
