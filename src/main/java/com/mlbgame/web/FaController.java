package com.mlbgame.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
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

import com.mlbgame.offseason.FaService;
import com.mlbgame.offseason.OffseasonService;

/**
 * FA 시장 화면 (오프시즌 5단계 개장 뒤 6라운드).
 * <pre>
 * 제안   선수마다 연봉($M)·연수 → 라운드가 지나도 유지 (수정·철회 가능)
 * 라운드 진행 → AI 구단 제안과 함께 선수가 결정 (즉시 수락·경쟁·요구액 변화), 내 제안에는 반응 문구만
 * 6라운드가 끝나면 "FA 시장 마감" (오프시즌 6단계: 남은 FA 마이너 계약) → 대시보드
 * </pre>
 * 후보 능력치는 스카우트 범위, 순위는 스카우트 추정 현재 가치 기준.
 */
@Controller
public class FaController {

    static final int SHOW = 150;

    private final CareerQueries queries;
    private final FaService market;
    private final PlayService play;

    public FaController(CareerQueries queries, FaService market, PlayService play) {
        this.queries = queries;
        this.market = market;
        this.play = play;
    }

    @GetMapping("/careers/{id}/fa")
    public String view(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam(defaultValue = "all") String type, Model model, RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        FaService.Board b = market.board(id);
        if (b == null) {
            flash.addFlashAttribute("message", "FA 시장은 오프시즌 5단계(FA 시장 개장)가 끝난 뒤 열립니다.");
            return "redirect:/careers/" + id;
        }
        model.addAttribute("h", h);
        model.addAttribute("f", page(h, b, type(type)));
        return "fa";
    }

    @PostMapping("/careers/{id}/fa/offer")
    public String offer(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int playerId,
                        @RequestParam String salary, @RequestParam int years,
                        @RequestParam(defaultValue = "all") String type, RedirectAttributes flash) {
        head(id, user);
        try {
            market.offer(id, playerId, thousands(salary), years);
            flash.addFlashAttribute("message", "제안을 넣었습니다. 라운드를 진행하면 선수가 답합니다.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/careers/" + id + "/fa?type=" + type(type);
    }

    @PostMapping("/careers/{id}/fa/withdraw")
    public String withdraw(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int playerId,
                           @RequestParam(defaultValue = "all") String type, RedirectAttributes flash) {
        head(id, user);
        try {
            market.withdraw(id, playerId);
            flash.addFlashAttribute("message", "제안을 철회했습니다.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/careers/" + id + "/fa?type=" + type(type);
    }

    @PostMapping("/careers/{id}/fa/round")
    public String round(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                        @RequestParam(defaultValue = "all") String type, RedirectAttributes flash) {
        head(id, user);
        try {
            flash.addFlashAttribute("message", market.advance(id));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        } catch (OffseasonService.ConcurrentStageException e) {
            flash.addFlashAttribute("error", "다른 창에서 먼저 진행했습니다. 화면을 새로 고쳤습니다.");
        }
        return "redirect:/careers/" + id + "/fa?type=" + type(type);
    }

    /** 라운드가 끝난 뒤 6단계 (FA 시장 마감) → 대시보드 */
    @PostMapping("/careers/{id}/fa/close")
    public String close(@AuthenticationPrincipal GameUser user, @PathVariable long id, RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        if (market.roundsLeft(id)) {
            flash.addFlashAttribute("error", "아직 진행할 라운드가 남았습니다.");
            return "redirect:/careers/" + id + "/fa";
        }
        flash.addFlashAttribute("message", play.advance(id, h.phase()));
        return "redirect:/careers/" + id;
    }

    // ------------------------------------------------------------------ 화면 모델

    private Views.FaPage page(Views.CareerHead h, FaService.Board b, String type) {
        int width = ScoutView.width(h.scoutLevel(), false);
        Map<Integer, Double> score = new HashMap<>();
        for (FaService.Item it : b.items()) {
            score.put(it.playerId(), ScoutView.estimate(it.ratings(), it.pos(), width, h.seed(), it.playerId(), 0));
        }
        List<FaService.Item> sorted = new ArrayList<>(b.items());
        sorted.sort(Comparator.comparingDouble((FaService.Item it) -> -score.get(it.playerId()))
                .thenComparingInt(FaService.Item::playerId));

        List<Views.FaRow> open = new ArrayList<>();
        List<Views.FaRow> signed = new ArrayList<>();
        List<Views.FaRow> mine = new ArrayList<>();
        int rank = 0;
        for (FaService.Item it : sorted) {
            rank++;
            Views.FaRow row = row(it, rank, width, h, b.userTeam());
            if (it.mySalary() > 0) {
                mine.add(row);
            }
            if (it.status() == FaService.SIGNED) {
                signed.add(row);
            } else if (it.status() == FaService.OPEN && open.size() < SHOW
                    && !(type.equals("h") && it.pitcher() || type.equals("p") && !it.pitcher())) {
                open.add(row);
            }
        }
        signed.sort(Comparator.comparingInt((Views.FaRow r) -> -r.askSalary()));
        return new Views.FaPage(b.season(), b.round(), FaService.ROUNDS, CareerQueries.money(b.budget()),
                CareerQueries.money(b.payroll()), CareerQueries.money(b.offered()), money(b.room()), b.room() < 0, type,
                open, signed, mine, CareerQueries.scoutLabel(h.scoutLevel()));
    }

    private Views.FaRow row(FaService.Item it, int rank, int width, Views.CareerHead h, int userTeam) {
        String pos = it.pitcher() ? (it.ratings()[com.mlbgame.career.MasterData.STAMINA] >= 50 ? "SP" : "RP")
                : CareerQueries.POS[Math.clamp(it.pos(), 0, 10)];
        String result = it.status() == FaService.SIGNED
                ? String.format("%s %d년 $%.1fM (%dR)", queries.abbrev(it.team()), it.years(), it.salary() / 1000.0,
                it.signedRound())
                : null;
        // 계약 선수는 계약 연봉으로 정렬하도록 askSalary 자리에 계약 연봉
        int sortSalary = it.status() == FaService.SIGNED ? it.salary() : it.ask();
        return new Views.FaRow(it.playerId(), rank, it.name(), pos, it.age(), "",
                DraftController.keyRatings(it.ratings(), it.pos(), it.pitcher(), width, h.seed(), it.playerId()),
                String.format("연 $%.1fM × %d년", it.ask() / 1000.0, it.askYears()), sortSalary, it.askYears(),
                it.offers(), result, it.status() == FaService.SIGNED && it.team() == userTeam,
                it.mySalary() > 0 ? String.format("%.1f", it.mySalary() / 1000.0) : "", it.myYears(), it.reaction(),
                it.maxYears(), it.pitcher());
    }

    /** "25.5" ($M) → 25500 (천 달러). 숫자가 아니면 0 (범위 검사에서 걸림) */
    static int thousands(String millions) {
        try {
            return new BigDecimal(millions.trim()).multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return 0;
        }
    }

    private static String money(int thousands) {
        return thousands == 0 ? "$0" : (thousands < 0 ? "-" : "") + CareerQueries.money(Math.abs(thousands));
    }

    private static String type(String type) {
        return "h".equals(type) || "p".equals(type) ? type : "all";
    }

    private Views.CareerHead head(long id, GameUser user) {
        Views.CareerHead h = queries.head(id, user.userId());
        if (h == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return h;
    }
}
