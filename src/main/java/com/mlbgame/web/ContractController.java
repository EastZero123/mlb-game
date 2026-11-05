package com.mlbgame.web;

import java.util.ArrayList;
import java.util.HashMap;
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

import com.mlbgame.offseason.ContractService;
import com.mlbgame.offseason.OffseasonService;

/**
 * 내 팀 계약 결정 화면 (오프시즌 4단계 뒤): FA 자격 선수 재계약/포기, 연봉조정 선수 텐더/논텐더.
 * 입력값: d_{선수 ID} = 1 재계약·텐더 / 2 포기·논텐더, y_{선수 ID} = 재계약 연수.
 * "저장"은 결정만 저장, "저장하고 다음 단계"는 저장 후 5단계(포스팅·FA) 진행.
 */
@Controller
public class ContractController {

    private final CareerQueries queries;
    private final ContractService contracts;
    private final PlayService play;

    public ContractController(CareerQueries queries, ContractService contracts, PlayService play) {
        this.queries = queries;
        this.contracts = contracts;
        this.play = play;
    }

    @GetMapping("/careers/{id}/contracts")
    public String view(@AuthenticationPrincipal GameUser user, @PathVariable long id, Model model,
                       RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        ContractService.Board b = contracts.board(id);
        if (b == null || b.items().isEmpty()) {
            flash.addFlashAttribute("message", b == null
                    ? "계약 결정은 오프시즌 4단계(연봉 재산정)가 끝난 뒤, 5단계 전에 할 수 있습니다."
                    : "이번 오프시즌 내 팀 계약 결정 대상이 없습니다.");
            return "redirect:/careers/" + id;
        }
        model.addAttribute("h", h);
        model.addAttribute("c", page(h, b, Map.of(), Map.of()));
        return "contracts";
    }

    @PostMapping("/careers/{id}/contracts")
    public String save(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam Map<String, String> form, @RequestParam(defaultValue = "false") boolean go,
                       Model model, RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        Map<Integer, Integer> decisions = numbers(form, "d_");
        Map<Integer, Integer> years = numbers(form, "y_");
        try {
            contracts.save(id, decisions, years);
        } catch (IllegalArgumentException e) {
            // 입력값을 그대로 두고 오류 표시
            ContractService.Board b = contracts.board(id);
            if (b == null) {
                flash.addFlashAttribute("message", e.getMessage());
                return "redirect:/careers/" + id;
            }
            model.addAttribute("h", h);
            model.addAttribute("c", page(h, b, decisions, years));
            model.addAttribute("error", e.getMessage());
            return "contracts";
        } catch (OffseasonService.ConcurrentStageException e) {
            flash.addFlashAttribute("error", "다른 창에서 먼저 진행했습니다. 화면을 새로 고쳤습니다.");
            return "redirect:/careers/" + id + "/contracts";
        }
        if (go) {
            flash.addFlashAttribute("message", play.advance(id, h.phase()));
            return "redirect:/careers/" + id;
        }
        flash.addFlashAttribute("message", "계약 결정을 저장했습니다. 5단계(포스팅·FA) 시작 때 반영됩니다.");
        return "redirect:/careers/" + id + "/contracts";
    }

    // ------------------------------------------------------------------ 화면 모델

    /** 입력값(decisions/years)이 있으면 그 값, 없으면 저장된 결정 → AI 기본값 */
    private Views.ContractPage page(Views.CareerHead h, ContractService.Board b, Map<Integer, Integer> decisions,
                                    Map<Integer, Integer> years) {
        Map<Integer, Views.RosterRow> roster = new HashMap<>();
        for (int level = 0; level <= 2; level++) {
            for (Views.RosterRow r : queries.roster(h, h.teamId(), level)) {
                roster.put(r.playerId(), r);
            }
        }
        List<Views.ContractRow> fa = new ArrayList<>();
        List<Views.ContractRow> arb = new ArrayList<>();
        for (ContractService.Item it : b.items()) {
            boolean isFa = it.kind() == ContractService.FA;
            String fallback = it.fallback() == ContractService.KEEP ? (isFa ? "재계약" : "텐더")
                    : (isFa ? "FA 로 내보냄" : "논텐더 (방출)");
            Views.ContractRow row = new Views.ContractRow(it.playerId(), it.name(),
                    it.pitcher() ? "P" : CareerQueries.POS[Math.clamp(it.pos(), 0, 10)], it.age(), it.service(),
                    CareerQueries.money(it.lastSalary()), it.ask(), CareerQueries.money(it.ask()), it.maxYears(),
                    decisions.getOrDefault(it.playerId(), it.shown()),
                    years.getOrDefault(it.playerId(), it.shownYears()), fallback, roster.get(it.playerId()));
            (isFa ? fa : arb).add(row);
        }
        return new Views.ContractPage(b.season(), b.nextSeason(), b.budget(), b.committed(),
                CareerQueries.money(b.budget()), CareerQueries.money(b.committed()), fa, arb, b.saved(),
                CareerQueries.scoutLabel(h.scoutLevel()));
    }

    /** "d_123" = "1" → {123: 1} (숫자가 아니면 무시) */
    static Map<Integer, Integer> numbers(Map<String, String> form, String prefix) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                try {
                    out.put(Integer.parseInt(e.getKey().substring(prefix.length())), Integer.parseInt(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // 잘못된 입력은 빠진 결정으로 처리
                }
            }
        }
        return out;
    }

    private Views.CareerHead head(long id, GameUser user) {
        Views.CareerHead h = queries.head(id, user.userId());
        if (h == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return h;
    }
}
