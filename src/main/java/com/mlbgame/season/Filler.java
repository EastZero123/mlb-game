package com.mlbgame.season;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.SplittableRandom;

import com.mlbgame.career.CareerCreator;
import com.mlbgame.career.ContractModel;
import com.mlbgame.career.MasterData;
import com.mlbgame.career.RatingSampler;
import com.mlbgame.roster.Defense;

/**
 * 시즌 중 대체 영입 선수 (마이너 FA 계약 수준). 구단 산하 인원이 부상으로 바닥났을 때만 생성한다.
 * <ul>
 *   <li>능력치: 레벨 대체 수준 (AAA 평균 약 41, AA 약 38, 각 σ 5). 마이너 레벨 분포 역산값(AAA z≈-0.85, AA≈-1.1)과 맞춤</li>
 *   <li>나이 23~28, 마이너 계약 1년 (연봉 0)</li>
 *   <li>타자는 산하 팀에 포수가 부족하면 포수, 아니면 내·외야 무작위</li>
 * </ul>
 * ID 는 10억부터 커리어별 순번, 신상은 c_person 에 저장. 난수는 커리어 seed + ID 로 결정.
 */
final class Filler {

    static final int FIRST_ID = 1_000_000_000;
    static final int ORIGIN_IN_SEASON = 1;
    private static final int[] FIELD_POSITIONS = {3, 4, 5, 6, 7, 8, 9};
    private static final String[] FIRST = {"Alex", "Ben", "Carlos", "Daniel", "Eli", "Felix", "Gabe", "Hector", "Ian",
            "Jake", "Kevin", "Luis", "Mason", "Nick", "Owen", "Pedro", "Ryan", "Sam", "Tyler", "Victor", "Wes", "Yuki",
            "Zach", "Andres", "Brandon", "Cody", "Diego", "Evan", "Jose", "Miguel"};
    private static final String[] LAST = {"Adams", "Baker", "Castro", "Diaz", "Evans", "Flores", "Garcia", "Hayes",
            "Ito", "Jensen", "Kim", "Lopez", "Martinez", "Nelson", "Ortiz", "Perez", "Quinn", "Reyes", "Smith", "Torres",
            "Underwood", "Vargas", "Walker", "Young", "Zimmerman", "Brooks", "Cruz", "Morales", "Park", "Silva"};

    /** 생성 결과: 런타임 선수 + 저장할 행 (c_person, c_player) */
    record Signed(CareerRuntime.Player player, Object[] personRow, Object[] playerRow) {
    }

    private Filler() {
    }

    static Signed sign(CareerRuntime rt, int team, int level, boolean pitcher, boolean needCatcher, LocalDate date) {
        int id = rt.nextPersonId++;
        SplittableRandom rng = new SplittableRandom(rt.seed ^ (id * 0x9E3779B97F4A7C15L));
        double mean = level == 1 ? 41 : 38;
        short[] r = new short[MasterData.RATING_COLUMNS.length];
        Arrays.fill(r, (short) -1);

        int pos;
        String bats;
        String throwsHand;
        if (pitcher) {
            pos = 1;
            throwsHand = rng.nextDouble() < 0.28 ? "L" : "R";
            bats = throwsHand;
            int stuff = rating(mean, 5, rng);
            r[MasterData.CONTROL] = (short) rating(mean, 5, rng);
            r[MasterData.SUPPRESSION] = (short) rating(mean, 5, rng);
            r[MasterData.CONTROL + 2] = (short) rating(50, 8, rng);                       // 땅볼 유도
            r[MasterData.STAMINA] = (short) (rng.nextBoolean() ? 55 + rng.nextInt(11) : 30 + rng.nextInt(11));
            setPitch(r, 0, 55, stuff); // 포심
            setPitch(r, 3, 30, stuff); // 슬라이더
            setPitch(r, 6, 15, stuff); // 체인지업
        } else {
            pos = needCatcher ? 2 : FIELD_POSITIONS[rng.nextInt(FIELD_POSITIONS.length)];
            double hand = rng.nextDouble();
            bats = hand < 0.6 ? "R" : hand < 0.9 ? "L" : "S";
            throwsHand = pos == 2 || pos >= 4 && pos <= 6 ? "R" : (rng.nextDouble() < 0.8 ? "R" : "L");
            for (int i = MasterData.CONTACT; i <= MasterData.QUALITY; i++) {
                r[i] = (short) rating(mean, 5, rng);
            }
            r[MasterData.QUALITY + 1] = (short) rating(50, 8, rng);            // 타구 성향
            int speed = rating(45, 8, rng);
            r[MasterData.SPEED] = (short) speed;
            r[MasterData.SPEED + 1] = (short) Math.clamp(speed + Math.round(4 * rng.nextGaussian()), 20, 80);
            Integer[] def = Defense.derive(pos, rating(45, 5, rng));
            for (int i = 0; i < 8; i++) {
                r[MasterData.DEF_C + i] = def[i] == null ? -1 : def[i].shortValue();
            }
            r[MasterData.DEF_C + 8] = (short) rating(45, 6, rng); // 송구
            if (pos == 2) {
                r[MasterData.DEF_C + 9] = 45;  // 프레이밍
                r[MasterData.DEF_C + 10] = 45; // 블로킹
            }
        }
        int age = 23 + rng.nextInt(6);
        LocalDate birth = LocalDate.of(date.getYear() - age, 1 + rng.nextInt(12), 1 + rng.nextInt(28));
        r[MasterData.DURABILITY] = (short) rating(50, 8, rng);
        r[MasterData.POTENTIAL] = (short) Math.clamp(50 + (27 - age) * 3, 30, 75);

        String name = FIRST[rng.nextInt(FIRST.length)] + " " + LAST[rng.nextInt(LAST.length)];
        Object[] person = {rt.careerId, id, name, birth, bats, throwsHand, pos, ORIGIN_IN_SEASON, date};
        Object[] row = CareerCreator.playerValues(rt.careerId, id, team, CareerRuntime.ST_MINOR, level,
                ContractModel.MINOR, 1, ContractModel.MINOR_SALARY, 0, r,
                RatingSampler.personality(rng), RatingSampler.personality(rng), RatingSampler.personality(rng), date,
                null, ContractModel.proSeasonsEstimate(age));

        int[] ri = new int[r.length];
        for (int i = 0; i < r.length; i++) {
            ri[i] = r[i];
        }
        CareerRuntimeLoader.Profile profile = new CareerRuntimeLoader.Profile(pos, CareerRuntimeLoader.hand(bats),
                CareerRuntimeLoader.hand(throwsHand));
        CareerRuntime.Player p = new CareerRuntime.Player(id, CareerRuntimeLoader.card(id, team, profile, ri),
                r[MasterData.DURABILITY], team, level, CareerRuntime.ST_MINOR, 0);
        return new Signed(p, person, row);
    }

    private static void setPitch(short[] r, int pitchIndex, int use, int stuff) {
        r[MasterData.FIRST_PITCH + 2 * pitchIndex] = (short) use;
        r[MasterData.FIRST_PITCH + 2 * pitchIndex + 1] = (short) stuff;
    }

    private static int rating(double mean, double sd, SplittableRandom rng) {
        return (int) Math.clamp(Math.round(mean + sd * rng.nextGaussian()), 20, 80);
    }
}
