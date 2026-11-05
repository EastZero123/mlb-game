package com.mlbgame.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.mlbgame.season.TradeService;

/**
 * 트레이드 화면: 상대 구단을 고르고 양쪽 로스터(MLB·AAA·AA)에서 선수를 체크해 제안 → AI 가 바로 수락/거절.
 * 오가는 연봉·트레이드 뒤 40인·연봉 합계는 화면에서 미리 계산해 보여 주고, 판정과 규칙 검사는 서버({@link TradeService}).
 */
@Controller
public class TradeController {

    private static final String[] LEVEL = {"MLB", "AAA", "AA"};

    private final CareerQueries queries;
    private final TradeService trades;

    public TradeController(CareerQueries queries, TradeService trades) {
        this.queries = queries;
        this.trades = trades;
    }

    @GetMapping("/careers/{id}/trade")
    public String view(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam(defaultValue = "0") int team, Model model) {
        Views.CareerHead h = head(id, user);
        TradeService.Window w = trades.window(id);
        model.addAttribute("h", h);
        model.addAttribute("w", w);
        model.addAttribute("teams", queries.teams().stream().filter(t -> t.teamId() != h.teamId()).toList());
        model.addAttribute("partner", team);
        model.addAttribute("max", TradeService.MAX_PER_SIDE);
        List<String> history = new ArrayList<>();
        for (TradeService.History t : trades.history(id)) {
            history.add(t.date() + " · " + t.summary());
        }
        model.addAttribute("history", history);
        if (team != 0 && team != h.teamId()) {
            Map<Integer, String> contracts = trades.contracts(id, h.teamId(), team);
            Map<Integer, TradeService.TeamInfo> info = trades.teams(id, h.teamId(), team);
            model.addAttribute("partnerName", queries.teamName(team));
            model.addAttribute("mine", rows(h, h.teamId(), contracts));
            model.addAttribute("theirs", rows(h, team, contracts));
            model.addAttribute("myInfo", info.get(h.teamId())); // 상대 구단 연봉·예산·40인은 보여 주지 않음
        }
        return "trade";
    }

    @PostMapping("/careers/{id}/trade")
    public String propose(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int team,
                          @RequestParam(name = "give", required = false) List<Integer> give,
                          @RequestParam(name = "get", required = false) List<Integer> get,
                          RedirectAttributes flash) {
        head(id, user);
        try {
            TradeService.Result r = trades.propose(id, team, give, get);
            flash.addFlashAttribute(r.accepted() ? "message" : "error", r.message());
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/careers/" + id + "/trade?team=" + team;
    }

    /** 구단 로스터 (MLB → AAA → AA, 각 레벨 안은 로스터 화면 순서) */
    private List<Views.TradeRow> rows(Views.CareerHead h, int team, Map<Integer, String> contracts) {
        List<Views.TradeRow> out = new ArrayList<>();
        for (int level = 0; level <= 2; level++) {
            for (Views.RosterRow r : queries.roster(h, team, level)) {
                boolean forty = r.statusCode() >= 1 && r.statusCode() <= 3;
                out.add(new Views.TradeRow(r.playerId(), r.name(), r.pos(), r.age(), LEVEL[level], r.status(),
                        r.salary(), salaryOf(r), forty, contracts.getOrDefault(r.playerId(), "-"), r.ratings(),
                        r.pitcher(), r.overall()));
            }
        }
        return out;
    }

    /** "$1.25M" / "$75K" → 천 달러 (미리보기 합계용) */
    private static int salaryOf(Views.RosterRow r) {
        String s = r.salary();
        try {
            if (s.endsWith("M")) {
                return (int) Math.round(Double.parseDouble(s.substring(1, s.length() - 1)) * 1000);
            }
            if (s.endsWith("K")) {
                return Integer.parseInt(s.substring(1, s.length() - 1));
            }
        } catch (NumberFormatException ignored) {
            // 표시 형식이 다르면 0
        }
        return 0;
    }

    private Views.CareerHead head(long id, GameUser user) {
        Views.CareerHead h = queries.head(id, user.userId());
        if (h == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return h;
    }
}
