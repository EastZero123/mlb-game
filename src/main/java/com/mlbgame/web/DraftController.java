package com.mlbgame.web;

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

import com.mlbgame.career.MasterData;
import com.mlbgame.offseason.DraftService;
import com.mlbgame.offseason.OffseasonService;

/**
 * 드래프트 화면 (오프시즌 6단계 전).
 * <pre>
 * 내 차례까지 진행 → AI 구단이 내 지명권 직전까지 지명
 * 지명            → 내 지명 후 다음 내 차례 직전까지 AI 지명
 * 드래프트 마감   → 내 지명권을 모두 쓴 뒤, 남은 AI 지명 + 지명 선수 생성 (오프시즌 6단계)
 * </pre>
 * 후보 능력치는 스카우트 범위로만 보이고, 순위도 스카우트 추정값(범위 중심) 기준.
 */
@Controller
public class DraftController {

    /** 한 화면에 보여 줄 후보 수 (추정 순위 상위) */
    static final int SHOW = 100;
    /** 지금 차례 앞뒤로 보여 줄 지명권 수 */
    static final int AROUND = 10;

    private final CareerQueries queries;
    private final DraftService draft;
    private final PlayService play;

    public DraftController(CareerQueries queries, DraftService draft, PlayService play) {
        this.queries = queries;
        this.draft = draft;
        this.play = play;
    }

    @GetMapping("/careers/{id}/draft")
    public String view(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam(defaultValue = "all") String type, Model model) {
        Views.CareerHead h = head(id, user);
        DraftService.Board b = draft.board(id);
        if (b == null) {
            return "redirect:/careers/" + id;
        }
        model.addAttribute("h", h);
        model.addAttribute("d", page(h, b, type(type)));
        return "draft";
    }

    @PostMapping("/careers/{id}/draft/auto")
    public String auto(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam(defaultValue = "all") String type, RedirectAttributes flash) {
        head(id, user);
        try {
            int n = draft.autoToMine(id);
            flash.addFlashAttribute("message", n == 0 ? "진행할 AI 지명이 없습니다." : "AI 구단 " + n + "명 지명");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        } catch (OffseasonService.ConcurrentStageException e) {
            flash.addFlashAttribute("error", "다른 창에서 먼저 진행했습니다. 화면을 새로 고쳤습니다.");
        }
        return "redirect:/careers/" + id + "/draft?type=" + type(type);
    }

    @PostMapping("/careers/{id}/draft/pick")
    public String pick(@AuthenticationPrincipal GameUser user, @PathVariable long id, @RequestParam int playerId,
                       @RequestParam(defaultValue = "all") String type, RedirectAttributes flash) {
        head(id, user);
        DraftService.Board before = draft.board(id);
        try {
            int n = draft.pick(id, playerId);
            DraftService.Prospect p = before == null ? null : before.byId().get(playerId);
            String name = p == null ? "선수" : p.name();
            flash.addFlashAttribute("message",
                    name + " 지명" + (n > 1 ? " · 이어서 AI 구단 " + (n - 1) + "명 지명" : ""));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        } catch (OffseasonService.ConcurrentStageException e) {
            flash.addFlashAttribute("error", "다른 창에서 먼저 진행했습니다. 화면을 새로 고쳤습니다.");
        }
        return "redirect:/careers/" + id + "/draft?type=" + type(type);
    }

    /** 내 지명권을 다 쓴 뒤 6단계 처리 (남은 AI 지명 + 선수 생성) → 대시보드 */
    @PostMapping("/careers/{id}/draft/finish")
    public String finish(@AuthenticationPrincipal GameUser user, @PathVariable long id, RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        if (draft.myPicksLeft(id)) {
            flash.addFlashAttribute("error", "아직 행사하지 않은 내 지명권이 있습니다.");
            return "redirect:/careers/" + id + "/draft";
        }
        flash.addFlashAttribute("message", play.advance(id, h.phase()));
        return "redirect:/careers/" + id;
    }

    // ------------------------------------------------------------------ 화면 모델

    /** 후보 필터: all 전체 / h 타자 / p 투수 */
    private static String type(String type) {
        return "h".equals(type) || "p".equals(type) ? type : "all";
    }

    private Views.DraftPage page(Views.CareerHead h, DraftService.Board b, String type) {
        int width = ScoutView.width(h.scoutLevel(), false);
        Map<Integer, DraftService.Prospect> byId = b.byId();
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (DraftService.Pick p : b.picks()) {
            if (p.playerId() != null) {
                taken.add(p.playerId());
            }
        }

        // 남은 후보를 스카우트 추정치(범위 중심) 기준으로 순위
        List<DraftService.Prospect> open = new ArrayList<>();
        Map<Integer, Double> score = new HashMap<>();
        for (DraftService.Prospect p : b.prospects()) {
            if (!taken.contains(p.playerId())) {
                open.add(p);
                score.put(p.playerId(), estimate(p, width, h.seed()));
            }
        }
        open.sort(Comparator.comparingDouble((DraftService.Prospect p) -> -score.get(p.playerId()))
                .thenComparingInt(DraftService.Prospect::playerId));
        List<Views.DraftProspectRow> rows = new ArrayList<>();
        for (int i = 0; i < open.size() && rows.size() < SHOW; i++) {
            DraftService.Prospect p = open.get(i);
            if (type.equals("h") && p.pitcher() || type.equals("p") && !p.pitcher()) {
                continue;
            }
            rows.add(row(p, i + 1, width, h, b.nextSeason()));
        }

        List<Views.DraftPickRow> around = new ArrayList<>();
        int cur = b.current() >= 0 ? b.current() : b.picks().size();
        for (int i = Math.max(0, cur - AROUND); i < Math.min(b.picks().size(), cur + AROUND); i++) {
            around.add(pickRow(b.picks().get(i), byId, b, i == b.current()));
        }
        List<Views.DraftPickRow> mine = new ArrayList<>();
        for (DraftService.Pick p : b.picks()) {
            if (p.teamId() == b.userTeam()) {
                mine.add(pickRow(p, byId, b, b.current() >= 0 && b.picks().get(b.current()) == p));
            }
        }
        String label = null;
        if (b.current() >= 0) {
            DraftService.Pick p = b.picks().get(b.current());
            label = p.round() + "R " + p.pickNo() + "순 " + queries.abbrev(p.teamId());
        }
        return new Views.DraftPage(b.season(), b.myTurn(), label, b.myOpen().size(), type, open.size(), rows, around,
                mine, CareerQueries.scoutLabel(h.scoutLevel()));
    }

    private Views.DraftPickRow pickRow(DraftService.Pick p, Map<Integer, DraftService.Prospect> byId,
                                       DraftService.Board b, boolean current) {
        DraftService.Prospect who = p.playerId() == null ? null : byId.get(p.playerId());
        String player = who == null ? null
                : who.name() + " (" + (who.pitcher() ? "P" : CareerQueries.POS[Math.clamp(who.pos(), 0, 10)]) + ")";
        return new Views.DraftPickRow(p.pickNo(), p.round(), queries.abbrev(p.teamId()), player,
                p.teamId() == b.userTeam(), current);
    }

    private static Views.DraftProspectRow row(DraftService.Prospect p, int rank, int width, Views.CareerHead h,
                                              int nextSeason) {
        int[] r = ints(p.ratings());
        int id = p.playerId();
        long seed = h.seed();
        List<String> shown = keyRatings(p.ratings(), p.pos(), p.pitcher(), width, seed, id);
        int age = CareerQueries.age(p.birth(), java.time.LocalDate.of(nextSeason, 7, 1));
        return new Views.DraftProspectRow(id, rank, p.name(),
                p.pitcher() ? (r[MasterData.STAMINA] >= 50 ? "SP" : "RP") : CareerQueries.POS[Math.clamp(p.pos(), 0, 10)],
                age, age <= 18 ? "고졸" : "대졸", p.bats() + "/" + p.throwsHand(), shown,
                ScoutView.range(r[MasterData.POTENTIAL], width * 3 / 2, seed, id, MasterData.POTENTIAL), p.pitcher());
    }

    /** 주요 능력 5칸 (스카우트 범위): 타자 컨택·파워·선구·스피드·수비 / 투수 구위·제구·장타억제·스태미나·빈칸 */
    static List<String> keyRatings(short[] ratings, int pos, boolean pitcher, int width, long seed, int id) {
        int[] r = ints(ratings);
        List<String> shown = new ArrayList<>();
        if (pitcher) {
            shown.add(ScoutView.range(CareerQueries.stuff(r), width, seed, id, 100));
            shown.add(ScoutView.range(r[MasterData.CONTROL], width, seed, id, MasterData.CONTROL));
            shown.add(ScoutView.range(r[MasterData.SUPPRESSION], width, seed, id, MasterData.SUPPRESSION));
            shown.add(ScoutView.range(r[MasterData.STAMINA], width, seed, id, MasterData.STAMINA));
            shown.add("");
        } else {
            shown.add(ScoutView.range(r[MasterData.CONTACT], width, seed, id, MasterData.CONTACT));
            shown.add(ScoutView.range(r[MasterData.POWER], width, seed, id, MasterData.POWER));
            shown.add(ScoutView.range(r[MasterData.EYE], width, seed, id, MasterData.EYE));
            shown.add(ScoutView.range(r[MasterData.SPEED], width, seed, id, MasterData.SPEED));
            int def = pos >= 2 && pos <= 9 ? r[MasterData.DEF_C + pos - 2] : -1;
            shown.add(ScoutView.range(def, width, seed, id, MasterData.DEF_C + Math.max(0, pos - 2)));
        }
        return shown;
    }

    /** 스카우트 추정 순위 점수: AI 구단과 같은 공식(현재 가치·잠재력 반반)을 실제값 대신 범위 중심으로 */
    static double estimate(DraftService.Prospect p, int width, long seed) {
        return ScoutView.estimate(p.ratings(), p.pos(), width, seed, p.playerId(), 0.5);
    }

    static int[] ints(short[] r) {
        int[] out = new int[r.length];
        for (int i = 0; i < r.length; i++) {
            out[i] = r[i];
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
