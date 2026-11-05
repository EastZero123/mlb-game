package com.mlbgame.league;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mlbgame.sim.LeagueBaseline;
import com.mlbgame.sim.Outcomes;
import com.mlbgame.sim.RateCoefficients;
import com.mlbgame.sim.Spread;

/** 시즌별 리그 기준값 (mst_league). 보정된 값이 없으면 호출 측에서 {@link LeagueBaseline#mlbDefault()} 사용. */
@Repository
public class LeagueBaselineRepository {

    private final JdbcTemplate jdbc;

    public LeagueBaselineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LeagueBaseline> find(int season) {
        List<LeagueBaseline> list = jdbc.query("""
                SELECT p_so, p_bb, p_hbp, p_1b, p_2b, p_3b, p_hr, p_go, p_ao, platoon_edge,
                       bat_spread_k, bat_spread_bb, bat_spread_hr, bat_spread_hit,
                       pit_spread_k, pit_spread_bb, pit_spread_hr, pit_spread_hit,
                       error_rate, wild_pitch_rate, rate_coef
                FROM mst_league WHERE season = ?
                """, (rs, n) -> {
            double[] rates = new double[Outcomes.COUNT];
            for (int i = 0; i < Outcomes.COUNT; i++) {
                rates[i] = rs.getDouble(i + 1);
            }
            Spread bat = new Spread(rs.getDouble(11), rs.getDouble(12), rs.getDouble(13), rs.getDouble(14));
            Spread pit = new Spread(rs.getDouble(15), rs.getDouble(16), rs.getDouble(17), rs.getDouble(18));
            String coef = rs.getString(21);
            return new LeagueBaseline(rates, rs.getDouble(10),
                    coef == null ? RateCoefficients.defaults() : RateCoefficients.decode(coef),
                    bat, pit, rs.getDouble(19), rs.getDouble(20));
        }, season);
        return list.stream().findFirst();
    }

    public void save(int season, LeagueBaseline b, String note) {
        double[] r = b.rates();
        Spread bs = b.batterSpread();
        Spread ps = b.pitcherSpread();
        jdbc.update("""
                INSERT INTO mst_league (season, p_so, p_bb, p_hbp, p_1b, p_2b, p_3b, p_hr, p_go, p_ao, platoon_edge,
                                        bat_spread_k, bat_spread_bb, bat_spread_hr, bat_spread_hit,
                                        pit_spread_k, pit_spread_bb, pit_spread_hr, pit_spread_hit,
                                        error_rate, wild_pitch_rate, rate_coef, calibrated_at, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?) AS n
                ON DUPLICATE KEY UPDATE p_so = n.p_so, p_bb = n.p_bb, p_hbp = n.p_hbp, p_1b = n.p_1b,
                    p_2b = n.p_2b, p_3b = n.p_3b, p_hr = n.p_hr, p_go = n.p_go, p_ao = n.p_ao,
                    platoon_edge = n.platoon_edge,
                    bat_spread_k = n.bat_spread_k, bat_spread_bb = n.bat_spread_bb,
                    bat_spread_hr = n.bat_spread_hr, bat_spread_hit = n.bat_spread_hit,
                    pit_spread_k = n.pit_spread_k, pit_spread_bb = n.pit_spread_bb,
                    pit_spread_hr = n.pit_spread_hr, pit_spread_hit = n.pit_spread_hit,
                    error_rate = n.error_rate, wild_pitch_rate = n.wild_pitch_rate, rate_coef = n.rate_coef,
                    calibrated_at = n.calibrated_at, note = n.note
                """, season, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], b.platoonEdge(),
                bs.strikeout(), bs.walk(), bs.homeRun(), bs.hit(),
                ps.strikeout(), ps.walk(), ps.homeRun(), ps.hit(),
                b.errorRate(), b.wildPitchRate(), b.coefficients().encode(), note);
    }

    /**
     * 마이너 레벨(1=AAA, 2=AA) 리그 환경. 환산 계수(mst_level_factor)의 타자 쪽(더 강한 투수 상대 효과 포함)과
     * 투수 쪽(더 강한 타자 상대 효과 포함)은 상대 수준 효과가 반대 방향이므로, 기하평균 = 순수 리그 환경 차이.
     * <pre>
     * 레벨 비율_i = MLB 비율_i ÷ √(타자 계수_i × 투수 계수_i)   (i = 삼진 ~ 홈런), 인플레이 아웃은 나머지
     * </pre>
     * 계수가 없으면 MLB 와 같은 환경.
     */
    public LeagueBaseline forLevel(LeagueBaseline mlb, int season, int level) {
        List<double[]> rows = jdbc.query("""
                SELECT f_so, f_bb, f_hbp, f_1b, f_2b, f_3b, f_hr FROM mst_level_factor
                WHERE season = ? AND level = ? ORDER BY side
                """, (rs, n) -> {
            double[] f = new double[7];
            for (int i = 0; i < 7; i++) {
                f[i] = rs.getDouble(i + 1);
            }
            return f;
        }, season, level);
        if (rows.size() != 2) {
            return mlb;
        }
        double[] l = mlb.rates();
        double[] r = new double[Outcomes.COUNT];
        double used = 0;
        for (int i = 0; i < 7; i++) {
            r[i] = l[i] / Math.sqrt(rows.get(0)[i] * rows.get(1)[i]);
            used += r[i];
        }
        double outs = Math.max(0.2, 1 - used);
        double groundShare = l[Outcomes.GROUND_OUT] / (l[Outcomes.GROUND_OUT] + l[Outcomes.AIR_OUT]);
        r[Outcomes.GROUND_OUT] = outs * groundShare;
        r[Outcomes.AIR_OUT] = outs * (1 - groundShare);
        return mlb.withRates(r);
    }
}
