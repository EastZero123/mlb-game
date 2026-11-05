package com.mlbgame.importer;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.mlbgame.importer.Composite.Obs;

/**
 * 실제 기록 + Statcast → 마스터 능력치 ({@code mst_player_rating}).
 * <p>
 * - 전통 기록(삼진·볼넷·홈런 등)은 최근 3시즌을 3:2:1 로 가중 (표본이 적은 선수 보정)<br>
 * - Statcast 지표는 2026 시즌<br>
 * - 지표 → 능력치 변환과 표본 보정은 {@link Composite}
 */
@Component
@Profile("import")
class RatingImporter {

    private static final Logger log = LoggerFactory.getLogger(RatingImporter.class);
    private static final String SAVANT = "https://baseballsavant.mlb.com";
    private static final String CSV = "text/csv,*/*";

    /** 구종 그룹 (DB 컬럼 순서). 세부 구종은 가장 가까운 그룹으로 합친다 */
    private static final String[] PITCH_GROUPS = {"FF", "SI", "FC", "SL", "ST", "CU", "CH", "FS"};
    private static final Map<String, String> PITCH_MAP = Map.ofEntries(
            Map.entry("FF", "FF"), Map.entry("SI", "SI"), Map.entry("FC", "FC"),
            Map.entry("SL", "SL"), Map.entry("SV", "SL"), Map.entry("ST", "ST"),
            Map.entry("CU", "CU"), Map.entry("KC", "CU"), Map.entry("CS", "CU"), Map.entry("EP", "CU"),
            Map.entry("CH", "CH"), Map.entry("SC", "CH"), Map.entry("KN", "CH"),
            Map.entry("FS", "FS"), Map.entry("FO", "FS"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    RatingImporter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Summary(int batters, int pitchers, int withStatcast, int rows, String leagueBaseline) {
    }

    private record Player(int id, LocalDate birth, int primaryPos) {
    }

    /** 최근 3시즌 가중 합산 (가중치 3:2:1 을 3으로 나눔 → 최근 시즌 1.0) */
    private static final class Bat {
        double pa, ab, h, b2, b3, hr, bb, so, hbp, sb, cs;
        int pa2026;
    }

    private static final class Pit {
        double bf, outs, h, bb, so, hr, hbp;
        double stG, stGs, stOuts; // 스태미나용 (최근 2시즌)
        int bf2026;
    }

    private static final class PitchAgg {
        double pitches, usage, whiff, rv, velo, veloN;
    }

    Summary importRatings(CachedHttpClient http, int season) {
        Map<Integer, Player> players = loadPlayers();
        Map<Integer, Bat> bat = loadBatting(season);
        Map<Integer, Pit> pit = loadPitching(season);

        Set<Integer> batters = new HashSet<>();
        Set<Integer> pitchers = new HashSet<>();
        Set<Integer> catchers = new HashSet<>();
        for (Player p : players.values()) {
            if (p.primaryPos() != 1) {
                batters.add(p.id());
            }
            if (p.primaryPos() == 2) {
                catchers.add(p.id());
            }
            Pit pl = pit.get(p.id());
            if (p.primaryPos() == 1 || (p.primaryPos() == 10 && pl != null && pl.bf >= 50)) {
                pitchers.add(p.id()); // 투타겸업 포함
            }
        }

        // ---------------- Savant 다운로드
        CsvTable sBat = csv(http, season, "batter", "/leaderboard/custom?year=" + season
                + "&type=batter&filter=&min=1&selections=pa,k_percent,bb_percent,xba,exit_velocity_avg,launch_angle_avg,"
                + "sweet_spot_percent,barrel_batted_rate,hard_hit_percent,whiff_percent,oz_swing_percent,"
                + "groundballs_percent,flyballs_percent,pull_percent&chart=false&csv=true");
        CsvTable sPit = csv(http, season, "pitcher", "/leaderboard/custom?year=" + season
                + "&type=pitcher&filter=&min=1&selections=pa,k_percent,bb_percent,exit_velocity_avg,barrel_batted_rate,"
                + "hard_hit_percent,whiff_percent,groundballs_percent,in_zone_percent&chart=false&csv=true");
        CsvTable sSprint = csv(http, season, "sprint", "/leaderboard/sprint_speed?year=" + season
                + "&position=&team=&min=0&csv=true");
        CsvTable sOaa = csv(http, season, "oaa", "/leaderboard/outs_above_average?type=Fielder&startYear=" + season
                + "&endYear=" + season + "&split=no&team=&range=year&min=1&pos=&roles=&viz=hide&csv=true");
        CsvTable sArm = csv(http, season, "arm", "/leaderboard/arm-strength?type=player&year=" + season
                + "&minThrows=1&pos=&team=&csv=true");
        CsvTable sFrame = csv(http, season, "framing", "/leaderboard/catcher-framing?type=catcher&seasonStart="
                + season + "&seasonEnd=" + season + "&team=&min=1&sortColumn=rv_tot&sortDirection=desc&csv=true");
        CsvTable sBlock = csv(http, season, "blocking", "/leaderboard/catcher-blocking?game_type=Regular&n=1&season_end="
                + season + "&season_start=" + season + "&split=no&team=&type=Cat&with_team_only=1&csv=true");
        CsvTable sPop = csv(http, season, "poptime", "/leaderboard/poptime?year=" + season
                + "&team=&min2b=1&min3b=0&csv=true");
        CsvTable sArsenal = csv(http, season, "arsenal", "/leaderboard/pitch-arsenal-stats?type=pitcher&pitchType=&year="
                + season + "&team=&min=1&csv=true");
        CsvTable sVelo = csv(http, season, "velocity", "/leaderboard/pitch-arsenals?year=" + season
                + "&min=1&type=avg_speed&hand=&csv=true");

        // ---------------- 타자
        Map<Integer, Obs> kRate = new HashMap<>(), bbRate = new HashMap<>(), hrRate = new HashMap<>(),
                iso = new HashMap<>(), sbAtt = new HashMap<>(), sbPct = new HashMap<>();
        bat.forEach((id, b) -> {
            if (b.pa <= 0) {
                return;
            }
            kRate.put(id, new Obs(b.so / b.pa, b.pa));
            bbRate.put(id, new Obs(b.bb / b.pa, b.pa));
            hrRate.put(id, new Obs(b.hr / b.pa, b.pa));
            if (b.ab > 0) {
                iso.put(id, new Obs((b.b2 + 2 * b.b3 + 3 * b.hr) / b.ab, b.ab));
            }
            double opp = (b.h - b.b2 - b.b3 - b.hr) + b.bb + b.hbp;
            if (opp > 0) {
                sbAtt.put(id, new Obs((b.sb + b.cs) / opp, opp));
            }
            if (b.sb + b.cs > 0) {
                sbPct.put(id, new Obs(b.sb / (b.sb + b.cs), b.sb + b.cs));
            }
        });
        Map<Integer, Obs> whiff = metric(sBat, "player_id", "whiff_percent", "pa");
        Map<Integer, Obs> xba = metric(sBat, "player_id", "xba", "pa");
        Map<Integer, Obs> barrel = metric(sBat, "player_id", "barrel_batted_rate", "pa");
        Map<Integer, Obs> ev = metric(sBat, "player_id", "exit_velocity_avg", "pa");
        Map<Integer, Obs> hard = metric(sBat, "player_id", "hard_hit_percent", "pa");
        Map<Integer, Obs> sweet = metric(sBat, "player_id", "sweet_spot_percent", "pa");
        Map<Integer, Obs> chase = metric(sBat, "player_id", "oz_swing_percent", "pa");
        Map<Integer, Obs> gb = metric(sBat, "player_id", "groundballs_percent", "pa");
        Map<Integer, Obs> sprint = metric(sSprint, "player_id", "sprint_speed", "competitive_runs");

        Map<Integer, Integer> contact = new Composite(-0.8)
                .add(0.40, 60, false, kRate).add(0.40, 100, false, whiff).add(0.20, 300, true, xba).rate(batters);
        Map<Integer, Integer> power = new Composite(-0.8)
                .add(0.35, 150, true, barrel).add(0.20, 80, true, ev).add(0.30, 170, true, hrRate)
                .add(0.15, 160, true, iso).rate(batters);
        Map<Integer, Integer> eye = new Composite(-0.5)
                .add(0.50, 120, true, bbRate).add(0.50, 100, false, chase).rate(batters);
        Map<Integer, Integer> quality = new Composite(-0.8)
                .add(0.40, 80, true, hard).add(0.25, 150, true, sweet).add(0.35, 300, true, xba).rate(batters);
        Map<Integer, Integer> tendency = new Composite(0).add(1.0, 80, true, gb).rate(batters);
        Map<Integer, Integer> speed = new Composite(-0.3).add(1.0, 10, true, sprint).rate(batters);
        Map<Integer, Integer> running = new Composite(-0.3)
                .add(0.50, 60, true, sbAtt).add(0.20, 20, true, sbPct).add(0.30, 10, true, sprint).rate(batters);

        // ---------------- 투수
        Map<Integer, Obs> pBb = new HashMap<>(), pHr = new HashMap<>(), pK = new HashMap<>();
        pit.forEach((id, p) -> {
            if (p.bf <= 0) {
                return;
            }
            pBb.put(id, new Obs(p.bb / p.bf, p.bf));
            pHr.put(id, new Obs(p.hr / p.bf, p.bf));
            pK.put(id, new Obs(p.so / p.bf, p.bf));
        });
        Map<Integer, Obs> pBarrel = metric(sPit, "player_id", "barrel_batted_rate", "pa");
        Map<Integer, Obs> pEv = metric(sPit, "player_id", "exit_velocity_avg", "pa");
        Map<Integer, Obs> pHard = metric(sPit, "player_id", "hard_hit_percent", "pa");
        Map<Integer, Obs> pGb = metric(sPit, "player_id", "groundballs_percent", "pa");
        Map<Integer, Obs> pZone = metric(sPit, "player_id", "in_zone_percent", "pa");
        Map<Integer, Obs> pWhiff = metric(sPit, "player_id", "whiff_percent", "pa");

        Map<Integer, Integer> control = new Composite(-0.5)
                .add(0.70, 170, false, pBb).add(0.30, 150, true, pZone).rate(pitchers);
        Map<Integer, Integer> suppression = new Composite(-0.5)
                .add(0.35, 200, false, pBarrel).add(0.20, 150, false, pEv).add(0.30, 400, false, pHr)
                .add(0.15, 150, false, pHard).rate(pitchers);
        Map<Integer, Integer> groundball = new Composite(0).add(1.0, 100, true, pGb).rate(pitchers);
        // 구종 데이터가 없는 투수용 대체 구위
        Map<Integer, Integer> fallbackStuff = new Composite(-0.5)
                .add(0.60, 70, true, pK).add(0.40, 150, true, pWhiff).rate(pitchers);
        Map<Integer, Map<String, int[]>> arsenal = buildArsenal(sArsenal, sVelo, pitchers);

        // ---------------- 수비
        Map<Integer, int[]> oaa = new HashMap<>(); // id → {포지션, OAA}
        List<Double> oaaValues = new ArrayList<>();
        for (String[] row : sOaa.rows()) {
            Integer id = sOaa.integer(row, "player_id");
            Integer value = sOaa.integer(row, "outs_above_average");
            int pos = positionCode(sOaa.str(row, "primary_pos_formatted"));
            if (id != null && value != null && pos >= 3) {
                oaa.put(id, new int[] {pos, value});
                oaaValues.add(value.doubleValue());
            }
        }
        double oaaSd = Math.max(1.0, sd(oaaValues));

        Map<Integer, Obs> framingRv = metric(sFrame, "id", "rv_tot", "pitches");
        Map<Integer, Obs> blockingRuns = metric(sBlock, "player_id", "catcher_blocking_runs", "pitches");
        Map<Integer, Obs> popTime = metric(sPop, "entity_id", "pop_2b_sba", "pop_2b_sba_count");
        Map<Integer, Obs> catcherArm = metric(sPop, "entity_id", "maxeff_arm_2b_3b_sba", "pop_2b_sba_count");
        Map<Integer, Obs> armOverall = metric(sArm, "player_id", "arm_overall", "total_throws");

        Map<Integer, Integer> catcherDef = new Composite(-0.5)
                .add(0.50, 2000, true, framingRv).add(0.25, 1500, true, blockingRuns)
                .add(0.25, 10, false, popTime).rate(catchers);
        Map<Integer, Integer> framing = new Composite(-0.5).add(1.0, 2000, true, framingRv).rate(catchers);
        Map<Integer, Integer> blocking = new Composite(-0.5).add(1.0, 1500, true, blockingRuns).rate(catchers);
        Map<Integer, Integer> arm = new Composite(-0.3).add(1.0, 30, true, armOverall).rate(batters);
        Map<Integer, Integer> cArm = new Composite(-0.3).add(1.0, 10, true, catcherArm).rate(catchers);

        // ---------------- 행 구성
        List<Object[]> rows = new ArrayList<>(players.size());
        int withStatcast = 0;
        for (Player p : players.values()) {
            int id = p.id();
            boolean isBatter = batters.contains(id);
            boolean isPitcher = pitchers.contains(id);
            if (sBatHas(whiff, id) || pWhiff.containsKey(id)) {
                withStatcast++;
            }

            Object[] r = new Object[43];
            int c = 0;
            r[c++] = id;
            // 타격 7
            r[c++] = isBatter ? contact.get(id) : null;
            r[c++] = isBatter ? power.get(id) : null;
            r[c++] = isBatter ? eye.get(id) : null;
            r[c++] = isBatter ? quality.get(id) : null;
            r[c++] = isBatter ? tendency.get(id) : null;
            r[c++] = isBatter ? speed.get(id) : null;
            r[c++] = isBatter ? running.get(id) : null;
            // 투구 4
            r[c++] = isPitcher ? control.get(id) : null;
            r[c++] = isPitcher ? suppression.get(id) : null;
            r[c++] = isPitcher ? groundball.get(id) : null;
            r[c++] = isPitcher ? stamina(pit.get(id)) : null;
            // 구종 8 × 2
            Map<String, int[]> pitches = isPitcher ? arsenal.get(id) : null;
            if (isPitcher && pitches == null) {
                int s = fallbackStuff.get(id);
                pitches = Map.of("FF", new int[] {55, s}, "SL", new int[] {30, s}, "CH", new int[] {15, s});
            }
            for (String g : PITCH_GROUPS) {
                int[] u = pitches == null ? null : pitches.get(g);
                r[c++] = u == null ? null : u[0];
                r[c++] = u == null ? null : u[1];
            }
            // 수비 8 + 송구·프레이밍·블로킹
            Integer[] def = isBatter ? defense(p, oaa.get(id), oaaSd, catcherDef.get(id)) : new Integer[8];
            for (Integer d : def) {
                r[c++] = d;
            }
            r[c++] = !isBatter ? null : p.primaryPos() == 2 ? cArm.get(id) : arm.get(id);
            r[c++] = p.primaryPos() == 2 ? framing.get(id) : null;
            r[c++] = p.primaryPos() == 2 ? blocking.get(id) : null;
            // 숨김·표본
            r[c++] = 50; // 내구성: 부상 이력 데이터 연동 전까지 중립
            r[c++] = potential(p.birth(), season);
            Bat b = bat.get(id);
            Pit pl = pit.get(id);
            r[c++] = b == null ? 0 : Math.min(b.pa2026, 65535);
            r[c++] = pl == null ? 0 : Math.min(pl.bf2026, 65535);
            if (c != r.length) {
                throw new IllegalStateException("컬럼 수 불일치: " + c);
            }
            rows.add(r);
        }

        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM mst_player_rating");
            jdbc.batchUpdate("""
                    INSERT INTO mst_player_rating (player_id,
                        contact, power, eye, quality, bb_tendency, speed, baserunning,
                        control, suppression, groundball, stamina,
                        ff_use, ff_stuff, si_use, si_stuff, fc_use, fc_stuff, sl_use, sl_stuff,
                        st_use, st_stuff, cu_use, cu_stuff, ch_use, ch_stuff, fs_use, fs_stuff,
                        def_c, def_1b, def_2b, def_3b, def_ss, def_lf, def_cf, def_rf,
                        arm, framing, blocking, durability, potential, sample_pa, sample_bf)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        });

        String baseline = leagueBaseline(season);
        log.info("능력치 {}명 (타자 {}, 투수 {}, Statcast 보유 {})", rows.size(), batters.size(), pitchers.size(),
                withStatcast);
        return new Summary(batters.size(), pitchers.size(), withStatcast, rows.size(), baseline);
    }

    private static boolean sBatHas(Map<Integer, Obs> m, int id) {
        return m.containsKey(id);
    }

    // ------------------------------------------------------------------ DB 로드

    private Map<Integer, Player> loadPlayers() {
        Map<Integer, Player> out = new HashMap<>();
        jdbc.query("SELECT player_id, birth_date, primary_pos FROM mst_player", rs -> {
            out.put(rs.getInt(1), new Player(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getInt(3)));
        });
        return out;
    }

    private Map<Integer, Bat> loadBatting(int season) {
        Map<Integer, Bat> out = new HashMap<>();
        jdbc.query("""
                SELECT player_id, season, pa, ab, h, b2, b3, hr, bb, so, hbp, sb, cs
                FROM mst_bat_season WHERE season BETWEEN ? AND ?
                """, rs -> {
            int s = rs.getInt(2);
            double w = (3 - (season - s)) / 3.0;
            Bat b = out.computeIfAbsent(rs.getInt(1), k -> new Bat());
            b.pa += w * rs.getInt(3);
            b.ab += w * rs.getInt(4);
            b.h += w * rs.getInt(5);
            b.b2 += w * rs.getInt(6);
            b.b3 += w * rs.getInt(7);
            b.hr += w * rs.getInt(8);
            b.bb += w * rs.getInt(9);
            b.so += w * rs.getInt(10);
            b.hbp += w * rs.getInt(11);
            b.sb += w * rs.getInt(12);
            b.cs += w * rs.getInt(13);
            if (s == season) {
                b.pa2026 += rs.getInt(3);
            }
        }, season - 2, season);
        return out;
    }

    private Map<Integer, Pit> loadPitching(int season) {
        Map<Integer, Pit> out = new HashMap<>();
        jdbc.query("""
                SELECT player_id, season, g, gs, outs, h, bb, so, hr, hbp
                FROM mst_pit_season WHERE season BETWEEN ? AND ?
                """, rs -> {
            int s = rs.getInt(2);
            double w = (3 - (season - s)) / 3.0;
            int g = rs.getInt(3), gs = rs.getInt(4), outs = rs.getInt(5), h = rs.getInt(6), bb = rs.getInt(7);
            int hbp = rs.getInt(10);
            int bf = outs + h + bb + hbp; // 상대 타자 수 근사 (실책·야수선택 제외)
            Pit p = out.computeIfAbsent(rs.getInt(1), k -> new Pit());
            p.bf += w * bf;
            p.outs += w * outs;
            p.h += w * h;
            p.bb += w * bb;
            p.so += w * rs.getInt(8);
            p.hr += w * rs.getInt(9);
            p.hbp += w * hbp;
            if (season - s <= 1) {
                p.stG += g;
                p.stGs += gs;
                p.stOuts += outs;
            }
            if (s == season) {
                p.bf2026 += bf;
            }
        }, season - 2, season);
        return out;
    }

    // ------------------------------------------------------------------ 구종

    private static Map<Integer, Map<String, int[]>> buildArsenal(CsvTable ars, CsvTable velo, Set<Integer> pitchers) {
        Map<Integer, Map<String, PitchAgg>> agg = new HashMap<>();
        for (String[] row : ars.rows()) {
            Integer id = ars.integer(row, "player_id");
            String group = PITCH_MAP.get(ars.str(row, "pitch_type"));
            Double pitches = ars.num(row, "pitches");
            Double usage = ars.num(row, "pitch_usage");
            if (id == null || group == null || pitches == null || pitches <= 0 || usage == null) {
                continue;
            }
            PitchAgg a = agg.computeIfAbsent(id, k -> new HashMap<>()).computeIfAbsent(group, k -> new PitchAgg());
            a.pitches += pitches;
            a.usage += usage;
            Double w = ars.num(row, "whiff_percent");
            Double rv = ars.num(row, "run_value_per_100");
            a.whiff += (w == null ? 0 : w) * pitches;
            a.rv += (rv == null ? 0 : rv) * pitches;
        }
        // 구속 (가로형 CSV: ff_avg_speed, sl_avg_speed ...)
        for (String[] row : velo.rows()) {
            Integer id = velo.integer(row, "pitcher");
            Map<String, PitchAgg> m = id == null ? null : agg.get(id);
            if (m == null) {
                continue;
            }
            for (String src : List.of("ff", "si", "fc", "sl", "ch", "cu", "fs", "kn", "st", "sv")) {
                Double v = velo.num(row, src + "_avg_speed");
                PitchAgg a = v == null ? null : m.get(PITCH_MAP.get(src.toUpperCase()));
                if (a != null) {
                    a.velo += v;
                    a.veloN++;
                }
            }
        }

        Map<Integer, Map<String, int[]>> out = new HashMap<>();
        for (String group : PITCH_GROUPS) {
            Map<Integer, Obs> whiff = new HashMap<>(), rv = new HashMap<>(), vel = new HashMap<>();
            List<Integer> who = new ArrayList<>();
            agg.forEach((id, m) -> {
                PitchAgg a = m.get(group);
                if (a == null || !pitchers.contains(id)) {
                    return;
                }
                who.add(id);
                whiff.put(id, new Obs(a.whiff / a.pitches, a.pitches));
                rv.put(id, new Obs(a.rv / a.pitches, a.pitches));
                if (a.veloN > 0) {
                    vel.put(id, new Obs(a.velo / a.veloN, a.pitches));
                }
            });
            // 같은 구종끼리 비교 (슬라이더는 슬라이더끼리)
            Map<Integer, Integer> stuff = new Composite(-0.5)
                    .add(0.45, 200, true, whiff).add(0.35, 600, true, rv).add(0.20, 50, true, vel).rate(who);
            for (int id : who) {
                int usage = (int) Math.round(agg.get(id).get(group).usage);
                if (usage >= 1) {
                    out.computeIfAbsent(id, k -> new HashMap<>()).put(group, new int[] {Math.min(usage, 100), stuff.get(id)});
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 수비 / 기타

    /**
     * 포지션별 수비 [C, 1B, 2B, 3B, SS, LF, CF, RF]. OAA 의 포지션을 주 포지션으로 보고,
     * 이동 난이도에 따라 다른 포지션 값을 파생한다.
     */
    private static Integer[] defense(Player p, int[] oaa, double oaaSd, Integer catcherDef) {
        int pos;
        int rating;
        if (p.primaryPos() == 2) {
            pos = 2;
            rating = catcherDef == null ? 45 : catcherDef;
        } else if (oaa != null) {
            pos = oaa[0];
            rating = Composite.toRating(oaa[1] / oaaSd * 0.8);
        } else if (p.primaryPos() >= 3 && p.primaryPos() <= 9) {
            pos = p.primaryPos();
            rating = 45;
        } else {
            return deriveDefense(10, 35);
        }
        return deriveDefense(pos, rating);
    }

    static Integer[] deriveDefense(int pos, int rating) {
        return com.mlbgame.roster.Defense.derive(pos, rating);
    }

    /**
     * 스태미나 = 한 경기 소화 가능 투구 수. 시뮬레이터 공식과 짝: 투구 한계 = 15 + (스태미나 - 20) × 1.6
     * (20 → 15구, 50 → 63구, 80 → 111구)
     */
    static int stamina(Pit p) {
        return p == null ? 40 : stamina(p.stG, p.stGs, p.stOuts);
    }

    /** @param g 등판 수, gs 선발 수, outs 아웃 수 (최근 2시즌) */
    static int stamina(double g, double gs, double outs) {
        if (g <= 0) {
            return 40;
        }
        double outsPerApp;
        if (gs / g >= 0.5) {
            double reliefOuts = (g - gs) * 3.2;
            outsPerApp = Math.max(3, (outs - reliefOuts) / Math.max(1, gs));
        } else {
            outsPerApp = outs / g;
        }
        double pitches = outsPerApp * 5.3 + 6;
        return Composite.clampRating((int) Math.round(20 + (pitches - 15) / 1.6));
    }

    /** 성장 여지 (임시: 나이 기반). 50 = 성장/하락 없음, 높을수록 성장 여지 큼 */
    static int potential(LocalDate birth, int season) {
        int age = Period.between(birth, LocalDate.of(season, 4, 1)).getYears();
        return Math.clamp(50 + (27 - age) * 3, 30, 75);
    }

    private static int positionCode(String pos) {
        if (pos == null) {
            return -1;
        }
        return switch (pos) {
            case "C" -> 2;
            case "1B" -> 3;
            case "2B" -> 4;
            case "3B" -> 5;
            case "SS" -> 6;
            case "LF" -> 7;
            case "CF" -> 8;
            case "RF" -> 9;
            default -> -1;
        };
    }

    /** 2026 실제 리그 평균 (시뮬레이터 LeagueBaseline 교체용으로 로그에 출력) */
    private String leagueBaseline(int season) {
        return jdbc.query("""
                SELECT SUM(pa), SUM(so), SUM(bb), SUM(hbp), SUM(h), SUM(b2), SUM(b3), SUM(hr)
                FROM mst_bat_season WHERE season = ?
                """, rs -> {
            if (!rs.next() || rs.getLong(1) == 0) {
                return "데이터 없음";
            }
            double pa = rs.getLong(1);
            double so = rs.getLong(2), bb = rs.getLong(3), hbp = rs.getLong(4), h = rs.getLong(5);
            double b2 = rs.getLong(6), b3 = rs.getLong(7), hr = rs.getLong(8);
            double outsInPlay = pa - so - bb - hbp - h;
            return String.format(
                    "K %.3f / BB %.3f / HBP %.3f / 1B %.3f / 2B %.3f / 3B %.3f / HR %.3f / 인플레이 아웃 %.3f (로스터 선수 기준)",
                    so / pa, bb / pa, hbp / pa, (h - b2 - b3 - hr) / pa, b2 / pa, b3 / pa, hr / pa, outsInPlay / pa);
        }, season);
    }

    // ------------------------------------------------------------------ CSV 헬퍼

    private static CsvTable csv(CachedHttpClient http, int season, String name, String path) {
        return CsvTable.parse(name, http.get("savant/" + season + "/" + name + ".csv", SAVANT + path, CSV));
    }

    private static Map<Integer, Obs> metric(CsvTable t, String idCol, String valueCol, String nCol) {
        Map<Integer, Obs> out = new HashMap<>();
        for (String[] row : t.rows()) {
            Integer id = t.integer(row, idCol);
            Double v = t.num(row, valueCol);
            Double n = t.num(row, nCol);
            if (id != null && v != null && n != null && n > 0) {
                out.put(id, new Obs(v, n));
            }
        }
        return out;
    }

    private static double sd(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sq = 0;
        for (double v : values) {
            sq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sq / values.size());
    }
}
