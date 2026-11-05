package com.mlbgame.offseason;

import java.util.Locale;

/**
 * 리그 환경 결과 보정: 능력치 정규화({@link OffseasonRules#normalize}) 의 기준값을 지난 시즌 결과에 맞춰 조금씩 옮긴다.
 * <pre>
 * 정규화는 "개막 26인 평균 = 2026 실제 개막 로스터 평균" 만 맞춘다. 그런데 실제 2026 은 개막 때 부상자 명단에 있던
 * 주전이 시즌 중 돌아와 26인 제구가 49.6 → 51.4 로 오르고 (실제 결과는 이 시즌 중 구성 기준),
 * 커리어 2027년부터는 시즌 중 오히려 1~1.5 내려가 (대체 선수·얇은 마이너) 볼넷%가 실제보다 7~9% 높았다 (보정 전 벤치).
 * 그래서 결과(볼넷·홈런·삼진·BABIP)를 실제 2026 과 비교해 기준값에 보정치를 더한다.
 * 보정 뒤 벤치 (2027~2031): 볼넷% .086~.092 (실제 .089), 개막 제구 약 52 → 시즌 끝 약 51.
 *
 *   제구 보정     += 0.7 × (시뮬 볼넷% - 실제) / 0.0021   볼넷이 많으면 투수 제구 기준을 올림
 *   장타억제 보정 += 0.7 × (시뮬 홈런% - 실제) / 0.0008   홈런이 많으면 장타억제 기준을 올림
 *   구위 보정     -= 0.7 × (시뮬 삼진% - 실제) / 0.0030   삼진이 많으면 구위 기준을 내림
 *   수비 보정     += 0.7 × (시뮬 BABIP - 실제) / 0.0015   BABIP 가 낮으면 (안타가 덜 나오면) 타자 수비 기준을 내림
 *   한 해 변화 ±2, 누적 ±6 으로 제한. 첫 오프시즌(이전 값 없음)은 벤치 결과로 정한 초기값
 *   {제구 +2.5, 장타억제 +0.5, 구위 +1.0, 수비 0} 에서 시작. 수비 보정이 없던 저장값(3칸)은 수비 0 으로 읽는다.
 * BABIP 는 실제 쪽 자료(팀 시즌 합계)에 희생플라이·타수가 없어 양쪽 모두 (안타-홈런) / (타석-볼넷-사구-삼진-홈런) 근사로 비교.
 * 수비를 지렛대로 쓰는 이유: 타구질을 올리면 홈런도 함께 늘지만, 수비는 인플레이 타구에만 작용한다.
 * </pre>
 * 감도(능력치 1점당 비율 변화)는 벤치 추이에서 어림한 값이라 정확하지 않아도 된다: 방향만 맞으면 해마다 오차가 줄어든다.
 */
final class EnvFeedback {

    /** 보정 순서: 제구, 장타억제, 구위, 수비 */
    static final int CONTROL = 0, SUPPRESSION = 1, STUFF = 2, DEFENSE = 3;
    static final int SIZE = 4;
    static final double[] PRIOR = {2.5, 0.5, 1.0, 0.0};
    static final double GAIN = 0.7, STEP = 2.0, LIMIT = 6.0;
    static final double SENS_BB = 0.0021, SENS_HR = 0.0008, SENS_K = 0.0030, SENS_BABIP = 0.0015;

    /** 타석당 비율, babip 는 위 근사식 */
    record Rates(double bb, double k, double hr, double babip) {

        /** SUM(pa), SUM(bb), SUM(so), SUM(hr), SUM(h), SUM(hbp) 로 계산 (타석 0 이면 null) */
        static Rates of(double pa, double bb, double so, double hr, double h, double hbp) {
            if (pa <= 0) {
                return null;
            }
            double bip = pa - bb - hbp - so - hr;
            return new Rates(bb / pa, so / pa, hr / pa, bip > 0 ? (h - hr) / bip : 0);
        }
    }

    private EnvFeedback() {
    }

    /**
     * @param prev 지난 오프시즌 보정치 (없으면 null → 초기값)
     * @param sim  지난 시즌 MLB 결과 (null 이면 보정 없이 prev 유지)
     */
    static double[] next(double[] prev, Rates sim, Rates real) {
        double[] base = prev == null ? PRIOR.clone() : java.util.Arrays.copyOf(prev, SIZE);
        if (sim == null || real == null) {
            return base;
        }
        double[] step = new double[SIZE];
        step[CONTROL] = GAIN * (sim.bb() - real.bb()) / SENS_BB;
        step[SUPPRESSION] = GAIN * (sim.hr() - real.hr()) / SENS_HR;
        step[STUFF] = -GAIN * (sim.k() - real.k()) / SENS_K;
        step[DEFENSE] = GAIN * (sim.babip() - real.babip()) / SENS_BABIP;
        double[] out = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            out[i] = Math.clamp(base[i] + Math.clamp(step[i], -STEP, STEP), -LIMIT, LIMIT);
        }
        return out;
    }

    static String encode(double[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            s.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.3f", i < b.length ? b[i] : 0));
        }
        return s.toString();
    }

    /** 3칸(수비 보정 이전 저장값)이나 4칸. 잘못된 값이면 null */
    static double[] decode(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] p = s.split(",");
        if (p.length < 3 || p.length > SIZE) {
            return null;
        }
        double[] out = new double[SIZE];
        try {
            for (int i = 0; i < p.length; i++) {
                out[i] = Double.parseDouble(p[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
