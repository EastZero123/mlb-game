package com.mlbgame.offseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;

class OffseasonRulesTest {

    private static final int SEASON = 2026;

    /** 모든 능력치 value, 해당 포지션 수비 가능. 투수는 pos 1 (타격 NULL) */
    private static OffPlayer player(int id, int pos, int value, int birthYear) {
        short[] r = new short[MasterData.RATING_COLUMNS.length];
        Arrays.fill(r, (short) value);
        if (pos == 1) {
            for (int i = MasterData.CONTACT; i <= 6; i++) {
                r[i] = -1;
            }
            for (int i = 0; i < 8; i++) {
                r[MasterData.DEF_C + i] = -1;
            }
        } else {
            r[MasterData.CONTROL] = -1;
            for (int i = MasterData.CONTROL; i <= MasterData.STAMINA; i++) {
                r[i] = -1;
            }
            for (int i = MasterData.FIRST_PITCH; i < MasterData.DEF_C; i++) {
                r[i] = -1;
            }
            for (int i = 0; i < 8; i++) {
                r[MasterData.DEF_C + i] = (short) (i + 2 == pos ? value : -1);
            }
        }
        OffPlayer p = new OffPlayer(id, LocalDate.of(birthYear, 3, 1), pos, r);
        p.team = 147;
        p.status = OffPlayer.ST_40MAN;
        return p;
    }

    @Test
    void 서비스_6년이_되면_FA_연봉조정_대상_약체는_논텐더() {
        OffPlayer veteran = player(1, 6, 60, 1995);
        veteran.contractType = ContractModel.ARBITRATION;
        veteran.service = 5;
        veteran.mlbGames = 120;
        OffPlayer weak = player(2, 6, 40, 1997);
        weak.contractType = ContractModel.ARBITRATION;
        weak.service = 3;
        weak.mlbGames = 50;
        OffPlayer young = player(3, 6, 55, 2002);
        young.contractType = ContractModel.PRE_ARB;
        young.service = 1;
        young.mlbGames = 100;
        OffPlayer signed = player(4, 6, 60, 1993);
        signed.contractType = ContractModel.GUARANTEED;
        signed.contractYears = 2;

        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.closeSeason(List.of(veteran, weak, young, signed), rep);

        assertThat(veteran.freeAgent()).isTrue();
        assertThat(weak.freeAgent()).isTrue();
        assertThat(rep.nonTendered).isEqualTo(1);
        assertThat(young.team).isEqualTo(147);
        assertThat(young.service).isEqualTo(2);
        assertThat(young.contractYears).isEqualTo(4);
        assertThat(signed.contractYears).isEqualTo(1);
        assertThat(signed.team).isEqualTo(147);
    }

    @Test
    void 내_팀은_계약_만료와_연봉조정_대상을_방출하지_않고_결정_대상으로_남긴다() {
        OffPlayer veteran = player(1, 6, 60, 1995);
        veteran.contractType = ContractModel.ARBITRATION;
        veteran.service = 5;
        veteran.mlbGames = 120;
        OffPlayer weak = player(2, 6, 40, 1997);
        weak.contractType = ContractModel.ARBITRATION;
        weak.service = 3;
        OffPlayer expiring = player(3, 6, 55, 1990);
        expiring.contractType = ContractModel.GUARANTEED;
        expiring.contractYears = 1;
        OffPlayer minor = player(4, 6, 40, 1999);
        minor.status = OffPlayer.ST_MINOR;
        minor.contractType = ContractModel.MINOR;
        minor.contractYears = 1;
        minor.proSeasons = 5; // 마감 때 6년 → MLB 경력 없음 → 방출(은퇴)
        OffPlayer other = player(5, 6, 55, 1990); // 다른 구단: 규칙대로 FA
        other.team = 111;
        other.contractType = ContractModel.GUARANTEED;
        other.contractYears = 1;

        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.closeSeason(List.of(veteran, weak, expiring, minor, other), 147, rep);

        assertThat(veteran.pending).isEqualTo(OffPlayer.PENDING_FA);
        assertThat(veteran.team).isEqualTo(147);
        assertThat(weak.pending).isEqualTo(OffPlayer.PENDING_ARB);
        assertThat(weak.team).isEqualTo(147);
        assertThat(expiring.pending).isEqualTo(OffPlayer.PENDING_FA);
        assertThat(minor.retired()).isTrue(); // 마이너 계약 만료는 자동
        assertThat(other.freeAgent()).isTrue();
        assertThat(rep.pendingFa).isEqualTo(2);
        assertThat(rep.pendingArb).isEqualTo(1);
    }

    @Test
    void 계약_결정_반영_재계약은_시장가치_FA_계약_미결정은_AI_기본값() {
        OffPlayer resign = player(1, 6, 60, 1998);
        resign.pending = OffPlayer.PENDING_FA;
        resign.decision = OffPlayer.DECIDE_KEEP;
        resign.decisionYears = 20; // 최대 연수(15년)로 잘림
        OffPlayer letGo = player(2, 6, 60, 1998);
        letGo.pending = OffPlayer.PENDING_FA;
        letGo.decision = OffPlayer.DECIDE_RELEASE;
        OffPlayer faDefault = player(3, 6, 60, 1998);
        faDefault.pending = OffPlayer.PENDING_FA;
        OffPlayer tender = player(4, 6, 50, 1998);
        tender.pending = OffPlayer.PENDING_ARB;
        tender.contractType = ContractModel.ARBITRATION;
        tender.salary = 5_000;
        OffPlayer nonTender = player(5, 6, 50, 1998);
        nonTender.pending = OffPlayer.PENDING_ARB;
        nonTender.decision = OffPlayer.DECIDE_RELEASE;
        OffPlayer weakArb = player(6, 6, 40, 1998);
        weakArb.pending = OffPlayer.PENDING_ARB;

        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.applyDecisions(List.of(resign, letGo, faDefault, tender, nonTender, weakArb), 1000, 2027, rep);

        assertThat(resign.team).isEqualTo(147);
        assertThat(resign.contractType).isEqualTo(ContractModel.GUARANTEED);
        assertThat(resign.contractYears).isEqualTo(OffseasonRules.RESIGN_MAX_YEARS);
        assertThat(resign.salary).isEqualTo(OffseasonRules.resignAsk(resign, 1000, 2027));
        assertThat(letGo.freeAgent()).isTrue();
        assertThat(faDefault.freeAgent()).isTrue();
        assertThat(tender.team).isEqualTo(147);
        assertThat(tender.salary).isEqualTo(5_000);
        assertThat(nonTender.freeAgent()).isTrue();
        assertThat(weakArb.freeAgent()).isTrue(); // 가치 45 미만 미결정 = 논텐더
        assertThat(rep.resigned).isEqualTo(1);
        assertThat(rep.letGo).isEqualTo(2);
        assertThat(rep.tendered).isEqualTo(1);
        assertThat(rep.myNonTendered).isEqualTo(2);
        assertThat(List.of(resign, tender)).allMatch(p -> p.pending == OffPlayer.PENDING_NONE);
    }

    @Test
    void 마이너_계약_만료_6년_미만은_보류_MLB_경력자는_마이너_FA_40인_마이너_계약은_MLB_계약으로() {
        OffPlayer young = player(1, 6, 40, 2003);
        young.status = OffPlayer.ST_MINOR;
        young.contractType = ContractModel.MINOR;
        young.contractYears = 1;
        young.proSeasons = 2;
        OffPlayer vet = player(2, 6, 45, 1994);
        vet.status = OffPlayer.ST_MINOR;
        vet.contractType = ContractModel.MINOR;
        vet.contractYears = 1;
        vet.service = 2;
        vet.proSeasons = 10;
        OffPlayer promoted = player(3, 6, 50, 2002); // 시즌 중 40인 등록된 드래프티
        promoted.contractType = ContractModel.MINOR;
        promoted.contractYears = 4;
        promoted.mlbGames = 50;

        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.closeSeason(List.of(young, vet, promoted), rep);

        assertThat(young.team).isEqualTo(147);
        assertThat(young.contractYears).isEqualTo(1);
        assertThat(young.proSeasons).isEqualTo(3);
        assertThat(vet.freeAgent()).isTrue();
        assertThat(OffseasonRules.minorWashout(vet)).isFalse(); // MLB 경력이 있으면 마이너 계약 가능
        assertThat(promoted.contractType).isEqualTo(ContractModel.PRE_ARB);
        assertThat(promoted.contractYears).isEqualTo(5); // 6 - 서비스 1
        assertThat(rep.minorRenewed).isEqualTo(1);
    }

    @Test
    void 사십오세_시즌을_마치면_무조건_은퇴하고_계약은_사십오세_시즌까지만() {
        OffPlayer old = player(1, 6, 80, SEASON - 45); // 이번 시즌 45세, 능력 최상
        old.service = 20;
        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.retire(List.of(old), 42, SEASON, rep);
        assertThat(old.retired()).isTrue();
        assertThat(rep.ageRetired).isEqualTo(1);

        OffPlayer p40 = player(2, 6, 60, SEASON + 1 - 40); // 다음 시즌 40세 → 40~45세 6년
        assertThat(OffseasonRules.maxYears(p40, SEASON + 1)).isEqualTo(6);
        OffPlayer p45 = player(3, 6, 60, SEASON + 1 - 45);
        assertThat(OffseasonRules.maxYears(p45, SEASON + 1)).isEqualTo(1);
        OffPlayer p25 = player(4, 6, 60, SEASON + 1 - 25);
        assertThat(OffseasonRules.maxYears(p25, SEASON + 1)).isEqualTo(OffseasonRules.RESIGN_MAX_YEARS);
    }

    @Test
    void 은퇴는_나이가_많고_가치가_낮을수록_많다() {
        List<OffPlayer> old = new ArrayList<>();
        List<OffPlayer> prime = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            OffPlayer o = player(10_000 + i, 6, 45, 1986); // 40세
            o.service = 10;
            old.add(o);
            OffPlayer p = player(20_000 + i, 6, 55, 1998); // 28세
            p.service = 4;
            prime.add(p);
        }
        OffseasonRules.Report rep = new OffseasonRules.Report();
        OffseasonRules.retire(old, 7, SEASON, rep);
        int oldRetired = rep.retired;
        OffseasonRules.retire(prime, 7, SEASON, rep);
        assertThat(oldRetired).isGreaterThan(450);
        assertThat(rep.retired - oldRetired).isLessThan(10);
    }

    @Test
    void 성장_노화는_결정적이고_범위_안() {
        double[][] delta = new double[AgingCurves.GROUPS][AgingCurves.MAX_AGE - AgingCurves.MIN_AGE + 1];
        for (double[] d : delta) {
            for (int a = 0; a < d.length; a++) {
                d[a] = a + AgingCurves.MIN_AGE < 27 ? 2 : -2;
            }
        }
        AgingCurves.Curves curves = new AgingCurves.Curves(delta, delta);
        OffPlayer a = player(1, 6, 50, 2005);
        OffPlayer b = player(1, 6, 50, 2005);
        OffseasonRules.develop(List.of(a), curves, 3, SEASON, new OffseasonRules.Report());
        OffseasonRules.develop(List.of(b), curves, 3, SEASON, new OffseasonRules.Report());
        assertThat(a.r).isEqualTo(b.r);
        for (short v : a.r) {
            assertThat((int) v).isBetween(-1, 80);
        }
        // 젊은 선수(21세)는 평균적으로 상승
        double avg = 0;
        for (int i = 0; i < 300; i++) {
            OffPlayer p = player(100 + i, 6, 50, 2005);
            OffseasonRules.develop(List.of(p), curves, 3, SEASON, new OffseasonRules.Report());
            avg += p.r[MasterData.CONTACT] - 50;
        }
        assertThat(avg / 300).isGreaterThan(1.0);
    }

    @Test
    void 드래프트는_역순위로_10라운드_팀당_10명() {
        Map<Integer, int[]> standings = new HashMap<>();
        for (int t = 1; t <= 30; t++) {
            standings.put(t, new int[] {50 + t, 112 - t, 600, 600});
        }
        List<Integer> order = OffseasonRules.draftOrder(standings);
        assertThat(order.getFirst()).isEqualTo(1);
        List<Integer> picks = DraftLottery.teams(DraftLottery.order(standings, java.util.Set.of(), 42, 2026, 10));
        int[] nextId = {1_000_000_000};
        OffseasonRules.Report rep = new OffseasonRules.Report();
        List<OffPlayer> drafted = OffseasonRules.draft(picks, nextId, 9, 42, 2027, LocalDate.of(2026, 11, 5), rep);
        assertThat(drafted).hasSize(300);
        assertThat(drafted.stream().filter(p -> p.team == 1)).hasSize(10);
        assertThat(drafted.get(0).team).isEqualTo(picks.get(0)); // 반환 순서 = 지명 순서
        assertThat(drafted).allMatch(p -> p.level == 2 && p.contractType == ContractModel.MINOR && p.draftee);
        assertThat(drafted).allMatch(p -> p.personRow != null);
    }

    private static List<Integer> draftPicks() {
        Map<Integer, int[]> standings = new HashMap<>();
        for (int t = 1; t <= 30; t++) {
            standings.put(t, new int[] {50 + t, 112 - t, 600, 600});
        }
        return DraftLottery.teams(DraftLottery.order(standings, java.util.Set.of(), 42, 2026, 10));
    }

    @Test
    void 드래프트_화면에서_한_장씩_AI_지명해도_한_번에_처리한_결과와_같다() {
        List<Integer> picks = draftPicks();
        LocalDate created = LocalDate.of(2026, 11, 5);
        List<OffPlayer> once = OffseasonRules.draft(picks, new int[] {1_000_000_000}, 9, 42, 2027, created,
                new OffseasonRules.Report());

        // DraftService 와 같은 방식: 후보를 다시 만들어 한 장씩 aiPick → 기록 → 6단계에서 기록 반영
        List<OffPlayer> pool = OffseasonRules.prospects(picks.size(), new int[] {1_000_000_000}, 9, 42, 2027,
                created);
        boolean[] taken = new boolean[pool.size()];
        List<Integer> chosen = new ArrayList<>();
        for (int k = 0; k < picks.size(); k++) {
            int i = OffseasonRules.aiPick(pool, taken, picks.get(k), 42, 2027);
            taken[i] = true;
            chosen.add(k < 150 ? pool.get(i).id : null); // 앞 절반만 기록, 나머지는 6단계 AI
        }
        List<OffPlayer> staged = OffseasonRules.draft(picks, chosen, new int[] {1_000_000_000}, 9, 42, 2027, created,
                new OffseasonRules.Report());
        assertThat(staged.stream().map(p -> p.id).toList()).isEqualTo(once.stream().map(p -> p.id).toList());
        assertThat(staged.get(0).r).isEqualTo(once.get(0).r);
    }

    @Test
    void 유저가_고른_선수는_그_지명권에_들어가고_AI_는_나머지에서_고른다() {
        List<Integer> picks = draftPicks();
        LocalDate created = LocalDate.of(2026, 11, 5);
        List<OffPlayer> once = OffseasonRules.draft(picks, new int[] {1_000_000_000}, 9, 42, 2027, created,
                new OffseasonRules.Report());
        int last = once.getLast().id; // AI 라면 마지막에 남는 선수를 1순위로
        List<Integer> chosen = new ArrayList<>(java.util.Collections.nCopies(picks.size(), (Integer) null));
        chosen.set(0, last);
        List<OffPlayer> drafted = OffseasonRules.draft(picks, chosen, new int[] {1_000_000_000}, 9, 42, 2027,
                created, new OffseasonRules.Report());
        assertThat(drafted.getFirst().id).isEqualTo(last);
        assertThat(drafted.getFirst().team).isEqualTo(picks.getFirst());
        assertThat(drafted.stream().map(p -> p.id).distinct()).hasSize(picks.size());
    }

    @Test
    void 후보에_없는_선수_기록은_오류() {
        List<Integer> picks = draftPicks();
        List<Integer> chosen = new ArrayList<>(java.util.Collections.nCopies(picks.size(), (Integer) null));
        chosen.set(0, 5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OffseasonRules.draft(picks, chosen,
                new int[] {1_000_000_000}, 9, 42, 2027, LocalDate.of(2026, 11, 5), new OffseasonRules.Report()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 개막_로스터는_26인_포수_2명_선발형_투수_포함() {
        List<OffPlayer> org = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < 3; i++) {
            org.add(player(id++, 2, 45 + i, 1996));
        }
        for (int i = 0; i < 20; i++) {
            org.add(player(id++, 3 + i % 7, 40 + i, 1996));
        }
        for (int i = 0; i < 25; i++) {
            OffPlayer p = player(id++, 1, 40 + i, 1996);
            p.r[MasterData.STAMINA] = (short) (i % 3 == 0 ? 60 : 35);
            org.add(p);
        }
        int[] next = {900};
        List<OffPlayer> signed = new ArrayList<>();
        OffseasonRules.assignRosters(org, LocalDate.of(2027, 3, 24), (team, level, pitcher) -> {
            OffPlayer f = player(next[0]++, pitcher ? 1 : 6, 35, 2001);
            signed.add(f);
            return f;
        });
        List<OffPlayer> major = org.stream()
                .filter(p -> p.level == 0 && p.status == OffPlayer.ST_ACTIVE && !signed.contains(p)).toList();
        assertThat(major).hasSize(26);
        assertThat(major.stream().filter(OffPlayer::canCatch).count()).isGreaterThanOrEqualTo(2);
        assertThat(major.stream().filter(p -> p.isPitcher() && p.starterType()).count()).isGreaterThanOrEqualTo(5);
        assertThat(major.stream().filter(OffPlayer::isPitcher)).hasSize(13);
        // 레벨·유형별 최소 인원 보장 (모자라면 대체 선수)
        for (int level = 1; level <= 2; level++) {
            for (boolean pitcher : new boolean[] {false, true}) {
                int lv = level;
                long n = org.stream().filter(p -> p.level == lv && p.isPitcher() == pitcher && p.injuryUntil == null)
                        .count();
                assertThat(n).isGreaterThanOrEqualTo(OffseasonRules.LEVEL_MIN[level][pitcher ? 1 : 0]);
            }
        }
        assertThat(signed).isNotEmpty().allMatch(p -> p.team == 147 && p.contractType == ContractModel.MINOR);
    }

    @Test
    void 정규화는_MLB_평균을_기준에_맞추고_상대_차이는_유지() {
        List<OffPlayer> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            OffPlayer h = player(i, 6, 55 + i, 1996); // 컨택 55~64, 평균 59.5
            h.level = 0;
            h.status = OffPlayer.ST_ACTIVE;
            all.add(h);
        }
        OffPlayer minor = player(99, 6, 40, 2003);
        minor.level = 2;
        all.add(minor);
        double[] target = new double[OffseasonRules.NORM_NAMES.length];
        java.util.Arrays.fill(target, 50);
        double[] shift = OffseasonRules.normalize(all, target);
        assertThat(shift[0]).isEqualTo(-9.5);
        double mean = all.subList(0, 10).stream().mapToInt(p -> p.r[MasterData.CONTACT]).average().orElse(0);
        assertThat(mean).isBetween(49.5, 50.5);
        assertThat(all.get(9).r[MasterData.CONTACT] - all.get(0).r[MasterData.CONTACT]).isEqualTo(9);
        assertThat(minor.r[MasterData.CONTACT]).isEqualTo((short) 31); // 마이너도 같은 폭 이동 (40 - 9.5 → 31)
    }

    @Test
    void 신규_선수_생성은_결정적() {
        OffPlayer a = OffseasonRules.create(5, 1, 2027, LocalDate.of(2026, 11, 5), false, 18, 30, 58, 2,
                new SplittableRandom(3), "A");
        OffPlayer b = OffseasonRules.create(5, 1, 2027, LocalDate.of(2026, 11, 5), false, 18, 30, 58, 2,
                new SplittableRandom(3), "A");
        assertThat(a.r).isEqualTo(b.r);
        assertThat(a.age(2027)).isBetween(17, 18);
    }
}
