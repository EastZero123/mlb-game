package com.mlbgame.web;

import org.springframework.stereotype.Service;

import com.mlbgame.offseason.ContractService;
import com.mlbgame.offseason.DraftService;
import com.mlbgame.offseason.FaService;
import com.mlbgame.offseason.OffseasonService;
import com.mlbgame.season.NextDayService;

/** "다음 날" 버튼: 시즌 중이면 하루 진행, 오프시즌이면 다음 단계 1개 (또는 남은 단계 전부). 결과를 한 줄 요약으로 돌려준다. */
@Service
public class PlayService {

    private final NextDayService nextDay;
    private final OffseasonService offseason;
    private final DraftService draft;
    private final ContractService contracts;
    private final FaService market;

    public PlayService(NextDayService nextDay, OffseasonService offseason, DraftService draft,
                       ContractService contracts, FaService market) {
        this.nextDay = nextDay;
        this.offseason = offseason;
        this.draft = draft;
        this.contracts = contracts;
        this.market = market;
    }

    /** 오프시즌 남은 단계를 한 번에 (테스트용, 로컬에서만 {@link DevTools}. 내 드래프트 지명·계약 결정·FA 라운드도 AI 가 대신) */
    public String finishOffseason(long careerId) {
        try {
            OffseasonService.Result r = offseason.run(careerId);
            return String.format("%d 오프시즌 완료 → %d 시즌 개막 %s", r.season(), r.nextSeason(), r.nextOpening());
        } catch (OffseasonService.ConcurrentStageException e) {
            return "다른 창에서 먼저 진행했습니다. 화면을 새로 고칩니다.";
        }
    }

    /** 한 번에 진행할 최대 일수 (여러 날 진행은 테스트용, 로컬에서만 {@link DevTools}) ("단계 끝까지"의 안전장치: 정규시즌 약 190일) */
    static final int MAX_SIM_DAYS = 220;

    /**
     * 여러 날 연속 진행 ("다음 날"을 반복하는 것과 결과가 같다). 하루마다 따로 저장되므로 중간에 오류가 나도 거기까지는 남는다.
     *
     * @param days       최대 일수
     * @param untilPhase true 면 단계(정규시즌 → 포스트시즌 → 오프시즌)가 바뀌는 날 멈춤
     */
    public String simulate(long careerId, int phase, int days, boolean untilPhase) {
        if (phase == NextDayService.PHASE_OFFSEASON) {
            return "오프시즌에는 단계 버튼으로 진행합니다.";
        }
        long t0 = System.nanoTime();
        int n = 0;
        int mlbGames = 0;
        int injuries = 0;
        int moves = 0;
        java.time.LocalDate first = null;
        java.time.LocalDate last = null;
        int endPhase = phase;
        String error = null;
        try {
            while (n < Math.min(days, MAX_SIM_DAYS)) {
                NextDayService.DayReport d = nextDay.advance(careerId);
                n++;
                first = first == null ? d.date() : first;
                last = d.date();
                mlbGames += d.mlbGames();
                injuries += d.injuries();
                moves += d.moves();
                endPhase = d.phase();
                if (endPhase != phase && (untilPhase || endPhase == NextDayService.PHASE_OFFSEASON)) {
                    break;
                }
            }
        } catch (NextDayService.ConcurrentAdvanceException e) {
            error = "다른 창에서 먼저 진행해 멈췄습니다";
        } catch (RuntimeException e) {
            error = "오류로 멈췄습니다 (" + e.getMessage() + ")";
        }
        if (n == 0) {
            return error != null ? error : "진행한 날이 없습니다.";
        }
        StringBuilder b = new StringBuilder();
        b.append(n).append("일 진행 (").append(first).append(" ~ ").append(last).append(") · MLB ")
                .append(mlbGames).append("경기 · 부상 ").append(injuries).append("건 · 로스터 이동 ").append(moves)
                .append("회 · ").append((System.nanoTime() - t0) / 1_000_000_000).append("초");
        if (endPhase == NextDayService.PHASE_POSTSEASON && phase == NextDayService.PHASE_REGULAR) {
            b.append(" · 정규시즌 종료, 포스트시즌 시작");
        } else if (endPhase == NextDayService.PHASE_OFFSEASON) {
            b.append(" · 시즌 종료, 오프시즌 시작");
        }
        if (error != null) {
            b.append(" · ").append(error);
        }
        return b.toString();
    }

    public String advance(long careerId, int phase) {
        try {
            if (phase == NextDayService.PHASE_OFFSEASON) {
                if (draft.myPicksLeft(careerId)) {
                    return "드래프트 화면에서 내 지명을 마쳐 주세요.";
                }
                if (contracts.undecided(careerId)) {
                    return "계약 결정 화면에서 내 팀 재계약·텐더를 결정해 주세요.";
                }
                if (market.roundsLeft(careerId)) {
                    return "FA 시장 화면에서 라운드를 진행해 주세요.";
                }
                OffseasonService.StageResult r = offseason.runStage(careerId);
                if (r.finished()) {
                    return String.format("%d 오프시즌 완료 → %d 시즌 개막 %s", r.season(), r.nextSeason(),
                            r.nextOpening());
                }
                return String.format("오프시즌 %d/%d단계 완료: %s", r.stage().no(), OffseasonService.STAGES,
                        r.stage().label());
            }
            NextDayService.DayReport d = nextDay.advance(careerId);
            StringBuilder b = new StringBuilder();
            b.append(d.date()).append(": ");
            if (d.mlbGames() == 0 && d.minorGames() == 0) {
                b.append("경기 없음");
            } else {
                b.append("MLB ").append(d.mlbGames()).append("경기");
                if (d.minorGames() > 0) {
                    b.append(", 마이너 ").append(d.minorGames()).append("경기");
                }
            }
            if (d.injuries() > 0) {
                b.append(" · 부상 ").append(d.injuries()).append("건");
            }
            if (d.moves() > 0) {
                b.append(" · 로스터 이동 ").append(d.moves()).append("회");
            }
            if (d.phase() == NextDayService.PHASE_POSTSEASON && d.mlbGames() > 0) {
                b.append(" (포스트시즌)");
            }
            if (d.phase() == NextDayService.PHASE_OFFSEASON) {
                b.append(" · 시즌 종료");
            }
            return b.toString();
        } catch (NextDayService.ConcurrentAdvanceException | OffseasonService.ConcurrentStageException e) {
            return "다른 창에서 먼저 진행했습니다. 화면을 새로 고칩니다.";
        }
    }
}
