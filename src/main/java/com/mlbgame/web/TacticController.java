package com.mlbgame.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.mlbgame.roster.TeamPlan;
import com.mlbgame.season.TacticService;

/**
 * 운영 지정 화면 (내 팀 MLB): 타순(우투수/좌투수 상대)·선발 로테이션·불펜 순서·자동 휴식.
 * 항목마다 "지정"을 끄면 그 항목은 AI 자동. 검사에 걸리면 입력값을 그대로 두고 오류를 보여준다.
 */
@Controller
public class TacticController {

    private final CareerQueries queries;
    private final TacticService tactics;

    public TacticController(CareerQueries queries, TacticService tactics) {
        this.queries = queries;
        this.tactics = tactics;
    }

    @GetMapping("/careers/{id}/tactics")
    public String view(@AuthenticationPrincipal GameUser user, @PathVariable long id, Model model,
                       RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        if (offseason(h, flash)) {
            return "redirect:/careers/" + id;
        }
        TacticService.State state = tactics.current(id);
        List<Views.RosterRow> roster = queries.roster(h, h.teamId(), 0);
        fill(model, h, state, roster, fromState(state, penSlots(roster)));
        return "tactics";
    }

    @PostMapping("/careers/{id}/tactics")
    public String save(@AuthenticationPrincipal GameUser user, @PathVariable long id,
                       @RequestParam(name = "rp", required = false) List<String> rp,
                       @RequestParam(name = "rpos", required = false) List<String> rpos,
                       @RequestParam(name = "lp", required = false) List<String> lp,
                       @RequestParam(name = "lpos", required = false) List<String> lpos,
                       @RequestParam(name = "rot", required = false) List<String> rot,
                       @RequestParam(name = "pen", required = false) List<String> pen,
                       @RequestParam(defaultValue = "false") boolean lineupOn,
                       @RequestParam(defaultValue = "false") boolean leftOn,
                       @RequestParam(defaultValue = "false") boolean rotOn,
                       @RequestParam(defaultValue = "false") boolean penOn,
                       @RequestParam(defaultValue = "false") boolean autoRest,
                       @RequestParam(defaultValue = "false") boolean autoSwap,
                       Model model, RedirectAttributes flash) {
        Views.CareerHead h = head(id, user);
        if (offseason(h, flash)) {
            return "redirect:/careers/" + id;
        }
        List<Views.RosterRow> roster = queries.roster(h, h.teamId(), 0);
        TacticService.State state = tactics.current(id);
        Views.TacticForm defaults = fromState(state, penSlots(roster));
        // 꺼진 항목은 입력칸이 비활성(전송 안 됨) → 지금 구성으로 채워 다시 보여줌
        Views.TacticForm form = new Views.TacticForm(
                lineupOn ? rows(rp, rpos) : defaults.vsRight(),
                lineupOn && leftOn ? rows(lp, lpos) : defaults.vsLeft(),
                rotOn ? ints(rot) : defaults.rotation(),
                penOn ? ints(pen) : defaults.bullpen(),
                lineupOn, lineupOn && leftOn, rotOn, penOn, autoRest, autoSwap);

        List<String> errors = new ArrayList<>();
        TeamPlan plan = toPlan(form, errors);
        if (errors.isEmpty()) {
            errors.addAll(tactics.save(id, plan));
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            fill(model, h, state, roster, form);
            return "tactics";
        }
        flash.addFlashAttribute("message", "저장했습니다. 다음 경기부터 적용됩니다.");
        return "redirect:/careers/" + id + "/tactics";
    }

    @PostMapping("/careers/{id}/tactics/reset")
    public String reset(@AuthenticationPrincipal GameUser user, @PathVariable long id, RedirectAttributes flash) {
        if (offseason(head(id, user), flash)) {
            return "redirect:/careers/" + id;
        }
        tactics.reset(id);
        flash.addFlashAttribute("message", "모든 지정을 해제했습니다. AI 가 자동으로 운영합니다.");
        return "redirect:/careers/" + id + "/tactics";
    }

    /** 오프시즌에는 로스터가 단계 처리 중이라 운영 지정 화면을 열지 않음 (새 시즌 개막 로스터가 정해진 뒤) */
    private static boolean offseason(Views.CareerHead h, RedirectAttributes flash) {
        if (h.phase() != com.mlbgame.season.NextDayService.PHASE_OFFSEASON) {
            return false;
        }
        flash.addFlashAttribute("message", "운영 지정은 오프시즌이 끝나고 개막 로스터가 정해진 뒤 바꿀 수 있습니다.");
        return true;
    }

    // ------------------------------------------------------------------ 입력 ↔ 지정

    /** 입력값 → 지정. 빈 칸이 있으면 오류 */
    static TeamPlan toPlan(Views.TacticForm f, List<String> errors) {
        TeamPlan.Slot[] right = f.lineupOn() ? slots(f.vsRight(), "우투수 상대", errors) : null;
        TeamPlan.Slot[] left = f.leftOn() ? slots(f.vsLeft(), "좌투수 상대", errors) : null;
        int[] rotation = null;
        if (f.rotOn()) {
            if (f.rotation().size() != TeamPlan.ROTATION_SIZE || f.rotation().contains(0)) {
                errors.add("선발 로테이션 " + TeamPlan.ROTATION_SIZE + "명을 모두 고르세요");
            }
            rotation = f.rotation().stream().mapToInt(Integer::intValue).toArray();
        }
        int[] bullpen = null;
        if (f.penOn()) {
            bullpen = f.bullpen().stream().filter(v -> v != 0).mapToInt(Integer::intValue).toArray();
            if (bullpen.length == 0) {
                errors.add("불펜 순서를 지정하려면 최소 마무리 1명을 고르세요");
            }
        }
        return new TeamPlan(right, left, rotation, bullpen, f.autoRest(), f.autoSwap());
    }

    private static TeamPlan.Slot[] slots(List<Views.LineupRow> rows, String label, List<String> errors) {
        if (rows.size() != 9 || rows.stream().anyMatch(r -> r.playerId() == 0 || r.pos() == 0)) {
            errors.add(label + " 타순 9자리의 선수와 수비 위치를 모두 고르세요");
        }
        return rows.stream().map(r -> new TeamPlan.Slot(r.playerId(), r.pos())).toArray(TeamPlan.Slot[]::new);
    }

    /** 저장된 지정 우선, 없는 항목은 지금 적용 중인 구성으로 */
    static Views.TacticForm fromState(TacticService.State s, int penSlots) {
        TeamPlan plan = s.plan();
        TacticService.Effective e = s.effective();
        List<Views.LineupRow> right = plan != null && plan.vsRight() != null ? rows(plan.vsRight()) : rows(e.vsRight());
        List<Views.LineupRow> left = plan != null && plan.vsLeft() != null ? rows(plan.vsLeft())
                : e.vsLeft() != null ? rows(e.vsLeft()) : right;
        List<Integer> rotation = plan != null && plan.rotation() != null ? boxed(plan.rotation()) : e.rotation();
        List<Integer> bullpen = new ArrayList<>(plan != null && plan.bullpen() != null ? boxed(plan.bullpen())
                : e.bullpen());
        while (bullpen.size() < penSlots) {
            bullpen.add(0);
        }
        return new Views.TacticForm(right, left, rotation, bullpen,
                plan != null && plan.vsRight() != null, plan != null && plan.vsLeft() != null,
                plan != null && plan.rotation() != null, plan != null && plan.bullpen() != null,
                plan == null || plan.autoRest(), plan == null || plan.autoSwap());
    }

    // ------------------------------------------------------------------ 화면

    private void fill(Model model, Views.CareerHead h, TacticService.State state, List<Views.RosterRow> roster,
                      Views.TacticForm form) {
        Map<Integer, String> names = new HashMap<>();
        List<Views.Option> hitters = new ArrayList<>();
        List<Views.Option> pitchers = new ArrayList<>();
        hitters.add(new Views.Option(0, "— 선택 —"));
        pitchers.add(new Views.Option(0, "— 선택 —"));
        Set<Integer> onRoster = new HashSet<>();
        for (Views.RosterRow r : roster) {
            names.put(r.playerId(), r.name());
            onRoster.add(r.playerId());
            boolean hurt = r.status().startsWith("부상");
            String[] hands = r.hands().split("/");
            if (r.pitcher()) {
                pitchers.add(new Views.Option(r.playerId(), r.name() + " · " + hands[hands.length - 1] + "투 · 스태미나 "
                        + r.ratings().get(3) + (hurt ? " · 부상" : "")));
            } else {
                hitters.add(new Views.Option(r.playerId(), r.name() + " · " + r.pos() + " · " + hands[0] + "타"
                        + (hurt ? " · 부상" : "")));
            }
        }
        List<Views.Option> positions = new ArrayList<>();
        positions.add(new Views.Option(0, "-"));
        for (int pos = 2; pos <= TeamPlan.DH; pos++) {
            positions.add(new Views.Option(pos, CareerQueries.POS[pos]));
        }

        // 로스터에서 빠진 지정 선수는 빈 칸으로 (선택 목록에 없으므로)
        form = new Views.TacticForm(clear(form.vsRight(), onRoster), clear(form.vsLeft(), onRoster),
                pad(clearIds(form.rotation(), onRoster), TeamPlan.ROTATION_SIZE), clearIds(form.bullpen(), onRoster),
                form.lineupOn(),
                form.leftOn(), form.rotOn(), form.penOn(), form.autoRest(), form.autoSwap());

        TacticService.Effective e = state.effective();
        String next = e.rotation().isEmpty() ? "-"
                : (e.nextStarter() + 1) + "번 " + names.getOrDefault(e.rotation().get(e.nextStarter()), "?");
        model.addAttribute("h", h);
        model.addAttribute("t", new Views.TacticPage(form, hitters, pitchers, positions, next,
                notes(state, names), roster, queries.defense(h)));
    }

    /** 지정했지만 오늘 실제 구성에 없는 선수 (부상·강등 등) 안내 */
    private static List<String> notes(TacticService.State s, Map<Integer, String> names) {
        List<String> out = new ArrayList<>();
        TeamPlan plan = s.plan();
        if (plan == null) {
            return out;
        }
        TacticService.Effective e = s.effective();
        Set<Integer> playing = new HashSet<>();
        e.vsRight().forEach(x -> playing.add(x.playerId()));
        if (e.vsLeft() != null) {
            e.vsLeft().forEach(x -> playing.add(x.playerId()));
        }
        Set<Integer> reported = new HashSet<>();
        for (TeamPlan.Slot[] lineup : new TeamPlan.Slot[][] {plan.vsRight(), plan.vsLeft()}) {
            if (lineup == null) {
                continue;
            }
            for (TeamPlan.Slot x : lineup) {
                if (!playing.contains(x.playerId()) && reported.add(x.playerId())) {
                    out.add(label(names, x.playerId()) + ": 지금 출전할 수 없어 그 자리는 자동으로 대체 중");
                }
            }
        }
        if (plan.rotation() != null) {
            for (int id : plan.rotation()) {
                if (!e.rotation().contains(id) && reported.add(id)) {
                    out.add(label(names, id) + ": 로테이션 이탈 중, 자동으로 대체 선발 투입");
                }
            }
        }
        if (plan.bullpen() != null) {
            for (int id : plan.bullpen()) {
                if (!e.bullpen().contains(id) && reported.add(id)) {
                    out.add(label(names, id) + ": 불펜 이탈 중");
                }
            }
        }
        return out;
    }

    private static String label(Map<Integer, String> names, int id) {
        return names.getOrDefault(id, "MLB 로스터에서 빠진 선수");
    }

    private static int penSlots(List<Views.RosterRow> roster) {
        long arms = roster.stream().filter(Views.RosterRow::pitcher).count();
        return (int) Math.max(1, arms - TeamPlan.ROTATION_SIZE);
    }

    private static List<Views.LineupRow> clear(List<Views.LineupRow> rows, Set<Integer> onRoster) {
        return rows.stream().map(r -> onRoster.contains(r.playerId()) ? r : new Views.LineupRow(r.order(), 0, r.pos()))
                .toList();
    }

    private static List<Integer> clearIds(List<Integer> ids, Set<Integer> onRoster) {
        return ids.stream().map(v -> onRoster.contains(v) ? v : 0).toList();
    }

    private static List<Integer> pad(List<Integer> ids, int size) {
        List<Integer> out = new ArrayList<>(ids.subList(0, Math.min(size, ids.size())));
        while (out.size() < size) {
            out.add(0);
        }
        return out;
    }

    private static List<Views.LineupRow> rows(TeamPlan.Slot[] slots) {
        return rows(List.of(slots));
    }

    private static List<Views.LineupRow> rows(List<TeamPlan.Slot> slots) {
        List<Views.LineupRow> out = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            out.add(new Views.LineupRow(i + 1, slots.get(i).playerId(), slots.get(i).pos()));
        }
        return out;
    }

    private static List<Views.LineupRow> rows(List<String> players, List<String> pos) {
        List<Views.LineupRow> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            out.add(new Views.LineupRow(i + 1, at(players, i), at(pos, i)));
        }
        return out;
    }

    private static List<Integer> ints(List<String> values) {
        List<Integer> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                out.add(parse(v));
            }
        }
        return out;
    }

    private static int at(List<String> values, int i) {
        return values != null && i < values.size() ? parse(values.get(i)) : 0;
    }

    private static int parse(String v) {
        try {
            return v == null || v.isBlank() ? 0 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Integer> boxed(int[] ids) {
        List<Integer> out = new ArrayList<>(ids.length);
        for (int id : ids) {
            out.add(id);
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
