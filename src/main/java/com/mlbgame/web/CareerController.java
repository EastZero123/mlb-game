package com.mlbgame.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.mlbgame.career.CareerCreator;
import com.mlbgame.season.CareerRuntimeCache;
import com.mlbgame.season.MoveService;
import com.mlbgame.season.RosterMove;

/**
 * 커리어 화면. 모든 커리어 경로는 소유자 확인 후 처리 (남의 커리어 ID 는 404).
 * "다음 날"은 htmx 요청이면 대시보드 본문 조각만, 아니면 전체 페이지로 응답.
 */
@Controller
public class CareerController {

    private final CareerQueries queries;
    private final CareerCreator creator;
    private final PlayService play;
    private final CareerRuntimeCache cache;
    private final JdbcTemplate jdbc;
    private final MoveService moves;
    private final DevTools devTools;

    public CareerController(CareerQueries queries, CareerCreator creator, PlayService play, CareerRuntimeCache cache,
                            JdbcTemplate jdbc, MoveService moves, DevTools devTools) {
        this.queries = queries;
        this.creator = creator;
        this.play = play;
        this.cache = cache;
        this.jdbc = jdbc;
        this.moves = moves;
        this.devTools = devTools;
    }

    // ------------------------------------------------------------------ 슬롯

    @GetMapping("/")
    public String home(@AuthenticationPrincipal GameUser user, Model model) {
        model.addAttribute("user", user.getUsername());
        model.addAttribute("slots", queries.slots(user.userId()));
        model.addAttribute("teams", queries.teams());
        return "home";
    }

    @PostMapping("/careers")
    public String create(@AuthenticationPrincipal GameUser user, @RequestParam int slot, @RequestParam int teamId,
                         RedirectAttributes flash) {
        try {
            long id = creator.create(user.userId(), slot, teamId, null).careerId();
            return "redirect:/careers/" + id;
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
    }

    /** 삭제는 표시만 (deleted_at). 실제 데이터 정리는 보관 기간 후 일괄 처리 예정 */
    @PostMapping("/careers/{id}/delete")
    public String delete(@AuthenticationPrincipal GameUser user, @PathVariable long id) {
        head(id, user);
        jdbc.update("UPDATE career SET deleted_at = NOW() WHERE career_id = ? AND user_id = ?", id, user.userId());
        cache.evict(id);
        return "redirect:/";
    }

    // ------------------------------------------------------------------ 대시보드

    @GetMapping("/careers/{id}")
    public String dashboard(@AuthenticationPrincipal GameUser user, @PathVariable long id, Model model) {
        fillDashboard(head(id, user), model);
        return "dashboard";
    }

    @PostMapping("/careers/{id}/next")
    public String next(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        Views.CareerHead h = head(id, user);
        model.addAttribute("message", play.advance(id, h.phase()));
        fillDashboard(head(id, user), model);
        return htmx != null ? "dashboard :: main" : "dashboard";
    }

    /**
     * 여러 날 진행 (테스트용, {@link DevTools} 가 켜진 환경에서만):
     * mode = week (7일) | phase (지금 단계 끝까지: 정규시즌 → 포스트시즌 시작, 포스트시즌 → 오프시즌 시작)
     */
    @PostMapping("/careers/{id}/sim")
    public String simulate(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                           @RequestParam(defaultValue = "week") String mode,
                           @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        requireDevTools();
        Views.CareerHead h = head(id, user);
        boolean phase = "phase".equals(mode);
        model.addAttribute("message", play.simulate(id, h.phase(), phase ? PlayService.MAX_SIM_DAYS : 7, phase));
        fillDashboard(head(id, user), model);
        return htmx != null ? "dashboard :: main" : "dashboard";
    }

    /** 스카우트 단계 변경 (오프시즌 1~4단계, FA 시장 개장 전까지) */
    @PostMapping("/careers/{id}/scout")
    public String scout(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int level,
                        RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        Views.OffseasonView off = queries.offseason(h);
        if (off == null || !com.mlbgame.offseason.Scouting.changeable(h.phase(), off.done())) {
            flash.addFlashAttribute("message", "스카우트 단계는 오프시즌 FA 시장 개장 전에만 바꿀 수 있습니다.");
        } else if (level < com.mlbgame.offseason.Scouting.MIN || level > com.mlbgame.offseason.Scouting.MAX) {
            flash.addFlashAttribute("message", "스카우트 단계는 1~5입니다.");
        } else {
            jdbc.update("UPDATE career SET scout_level = ? WHERE career_id = ? AND user_id = ?", level, id,
                    user.userId());
            flash.addFlashAttribute("message", String.format("스카우트 %d단계로 변경 (연 비용 $%.0fM, 다음 시즌 예산에서 차감)",
                    level, com.mlbgame.offseason.Scouting.cost(level) / 1000.0));
        }
        return "redirect:/careers/" + id;
    }

    /** 오프시즌 남은 단계 한 번에 (테스트용, {@link DevTools} 가 켜진 환경에서만. 내 결정도 AI 가 대신) */
    @PostMapping("/careers/{id}/offseason/finish")
    public String finishOffseason(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                                  @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        requireDevTools();
        Views.CareerHead h = head(id, user);
        if (h.phase() == com.mlbgame.season.NextDayService.PHASE_OFFSEASON) {
            model.addAttribute("message", play.finishOffseason(id));
        }
        fillDashboard(head(id, user), model);
        return htmx != null ? "dashboard :: main" : "dashboard";
    }

    private void fillDashboard(Views.CareerHead h, Model model) {
        model.addAttribute("h", h);
        model.addAttribute("standings", queries.standings(h));
        model.addAttribute("today", queries.gamesOn(h, h.date()));
        model.addAttribute("recent", queries.recentGames(h, 5));
        model.addAttribute("series", queries.series(h));
        model.addAttribute("off", queries.offseason(h));
        model.addAttribute("devTools", devTools.enabled());
        java.util.List<Views.Option> scouts = new java.util.ArrayList<>();
        for (int lv = com.mlbgame.offseason.Scouting.MIN; lv <= com.mlbgame.offseason.Scouting.MAX; lv++) {
            scouts.add(new Views.Option(lv, CareerQueries.scoutLabel(lv) + String.format(" · 연 $%.0fM",
                    com.mlbgame.offseason.Scouting.cost(lv) / 1000.0)));
        }
        model.addAttribute("scouts", scouts);
        model.addAttribute("scoutNow", CareerQueries.scoutLabel(h.scoutLevel()) + String.format(" · 연 비용 $%.0fM",
                com.mlbgame.offseason.Scouting.cost(h.scoutLevel()) / 1000.0));
    }

    private void requireDevTools() {
        if (!devTools.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ 조회 화면

    @GetMapping("/careers/{id}/standings")
    public String standings(@AuthenticationPrincipal GameUser user, @PathVariable long id, Model model) {
        Views.CareerHead h = head(id, user);
        model.addAttribute("h", h);
        model.addAttribute("standings", queries.standings(h));
        model.addAttribute("series", queries.series(h));
        return "standings";
    }

    @GetMapping("/careers/{id}/teams/{teamId}")
    public String team(@AuthenticationPrincipal GameUser user, @PathVariable long id, @PathVariable int teamId,
                       @RequestParam(defaultValue = "0") int level, Model model) {
        Views.CareerHead h = head(id, user);
        boolean offseason = h.phase() == com.mlbgame.season.NextDayService.PHASE_OFFSEASON;
        // 시즌 중: 메모리 상태를 먼저 적재 (적재 시 40인 초과 정리가 DB 에 반영된 뒤 로스터를 읽도록)
        // 오프시즌: 메모리 상태 없이 DB 에서 바로 (로스터가 단계 처리 중이라 경기용 구성을 만들지 않음)
        MoveService.Counts counts = offseason ? queries.counts(h) : moves.counts(id);
        model.addAttribute("h", h);
        model.addAttribute("teamId", teamId);
        model.addAttribute("teamName", queries.teamName(teamId));
        model.addAttribute("level", Math.clamp(level, 0, 2));
        model.addAttribute("roster", queries.roster(h, teamId, Math.clamp(level, 0, 2)));
        model.addAttribute("teams", queries.teams());
        model.addAttribute("scout", CareerQueries.scoutLabel(h.scoutLevel()));
        boolean mine = teamId == h.teamId();
        model.addAttribute("mine", mine);
        model.addAttribute("movable", mine && !offseason);
        model.addAttribute("offseason", offseason);
        model.addAttribute("counts", counts);
        return "team";
    }

    /** 내 팀 로스터 이동 (콜업·강등·산하 팀 간 이동·40인 등록/제외). 결과는 같은 탭으로 돌아가 한 줄 안내 */
    @PostMapping("/careers/{id}/moves")
    public String move(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int playerId,
                       @RequestParam RosterMove move, @RequestParam(defaultValue = "0") int level,
                       RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        String name = queries.names(id, java.util.List.of(playerId)).getOrDefault(playerId, "선수");
        try {
            moves.move(id, playerId, move);
            flash.addFlashAttribute("message", name + ": " + move.label());
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", name + ": " + e.getMessage());
        }
        return "redirect:/careers/" + id + "/teams/" + h.teamId() + "?level=" + Math.clamp(level, 0, 2);
    }

    @GetMapping("/careers/{id}/players/{playerId}")
    public String player(@AuthenticationPrincipal GameUser user, @PathVariable long id, @PathVariable int playerId,
                         Model model) {
        Views.CareerHead h = head(id, user);
        Views.PlayerPage p = queries.player(h, playerId);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("h", h);
        model.addAttribute("p", p);
        model.addAttribute("scout", CareerQueries.scoutLabel(h.scoutLevel()));
        return "player";
    }

    @GetMapping("/careers/{id}/games/{season}/{gameNo}")
    public String box(@AuthenticationPrincipal GameUser user, @PathVariable long id, @PathVariable int season,
                      @PathVariable int gameNo, Model model) {
        Views.CareerHead h = head(id, user);
        Views.BoxPage box = queries.box(h, season, gameNo);
        if (box == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보관 기간이 지났거나 없는 경기");
        }
        model.addAttribute("h", h);
        model.addAttribute("b", box);
        return "box";
    }

    private Views.CareerHead head(long id, GameUser user) {
        Views.CareerHead h = queries.head(id, user.userId());
        if (h == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return h;
    }
}
