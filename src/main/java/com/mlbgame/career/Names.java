package com.mlbgame.career;

import java.util.SplittableRandom;

/** 커리어에서 새로 생기는 선수 이름 (드래프트·포스팅·대체 영입). 실존 인물과 겹칠 수 있는 단순 조합. */
public final class Names {

    private static final String[] FIRST = {"Alex", "Ben", "Carlos", "Daniel", "Eli", "Felix", "Gabe", "Hector", "Ian",
            "Jake", "Kevin", "Luis", "Mason", "Nick", "Owen", "Pedro", "Ryan", "Sam", "Tyler", "Victor", "Wes", "Zach",
            "Andres", "Brandon", "Cody", "Diego", "Evan", "Jose", "Miguel", "Logan", "Caleb", "Rafael", "Trey", "Jalen"};
    private static final String[] LAST = {"Adams", "Baker", "Castro", "Diaz", "Evans", "Flores", "Garcia", "Hayes",
            "Jensen", "Lopez", "Martinez", "Nelson", "Ortiz", "Perez", "Quinn", "Reyes", "Smith", "Torres", "Underwood",
            "Vargas", "Walker", "Young", "Zimmerman", "Brooks", "Cruz", "Morales", "Silva", "Ramos", "Bennett", "Cole"};
    private static final String[] JP_FIRST = {"Haruto", "Sota", "Yuto", "Ren", "Kaito", "Riku", "Daiki", "Shota"};
    private static final String[] JP_LAST = {"Sato", "Suzuki", "Takahashi", "Tanaka", "Watanabe", "Ito", "Yamamoto",
            "Nakamura", "Kobayashi", "Matsuda"};
    private static final String[] KR_FIRST = {"Min-jun", "Seo-jun", "Ji-ho", "Hyun-woo", "Jun-young", "Do-hyun",
            "Sung-min", "Jae-won"};
    private static final String[] KR_LAST = {"Kim", "Lee", "Park", "Choi", "Jung", "Kang", "Cho", "Yoon"};

    private Names() {
    }

    public static String american(SplittableRandom rng) {
        return pick(FIRST, rng) + " " + pick(LAST, rng);
    }

    /** 포스팅 선수: 일본 60% / 한국 40% */
    public static String posting(SplittableRandom rng) {
        return rng.nextDouble() < 0.6 ? pick(JP_FIRST, rng) + " " + pick(JP_LAST, rng)
                : pick(KR_FIRST, rng) + " " + pick(KR_LAST, rng);
    }

    private static String pick(String[] a, SplittableRandom rng) {
        return a[rng.nextInt(a.length)];
    }
}
