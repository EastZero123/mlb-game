package com.mlbgame.roster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

/**
 * 유저가 지정한 MLB 팀 운영. null 항목은 AI 자동. {@link AutoManager} 가 지정을 우선 적용하고,
 * 오늘 뛸 수 없는 선수(부상·강등)의 자리만 자동으로 메운다.
 *
 * @param vsRight   우투수 상대 타순 9자리 (null = 자동)
 * @param vsLeft    좌투수 상대 타순 9자리 (null = vsRight 와 같음)
 * @param rotation  선발 로테이션 순서 (null = 자동)
 * @param bullpen   불펜 우선순위, [0] = 마무리 (null = 자동). 빠진 투수는 뒤에 구위 순으로
 * @param autoRest  주전 휴식일 자동 교체
 * @param autoSwap  매주 월요일 AI 자동 교체 (AAA 유망주 ↔ MLB 최하위)
 */
public record TeamPlan(Slot[] vsRight, Slot[] vsLeft, int[] rotation, int[] bullpen, boolean autoRest,
                       boolean autoSwap) {

    /** 타순 한 자리: 선수 + 수비 위치 (2~9, 10 = 지명타자) */
    public record Slot(int playerId, int pos) {
    }

    public static final int DH = 10;
    public static final int ROTATION_SIZE = 5;

    /** 타순을 지정했으면 자동 플래툰 교체를 하지 않는다 (좌투수 상대 타순으로 유저가 직접 정함) */
    public boolean manualLineup() {
        return vsRight != null;
    }

    // ------------------------------------------------------------------ 저장 형식 (c_tactic)

    public static String encodeSlots(Slot[] slots) {
        if (slots == null) {
            return null;
        }
        StringJoiner j = new StringJoiner(",");
        for (Slot s : slots) {
            j.add(s.playerId() + ":" + s.pos());
        }
        return j.toString();
    }

    public static Slot[] decodeSlots(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.split(",");
        Slot[] out = new Slot[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] kv = parts[i].split(":");
            out[i] = new Slot(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
        }
        return out;
    }

    public static String encodeIds(int[] ids) {
        if (ids == null || ids.length == 0) {
            return null;
        }
        StringJoiner j = new StringJoiner(",");
        for (int id : ids) {
            j.add(Integer.toString(id));
        }
        return j.toString();
    }

    public static int[] decodeIds(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Arrays.stream(text.split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
    }

    /**
     * 형식 검사 (선수 소속·유형 검사는 호출자가 로스터 기준으로). 문제가 있으면 메시지 목록.
     */
    public List<String> formatErrors() {
        List<String> errors = new ArrayList<>();
        checkLineup(vsRight, "우투수 상대", errors);
        checkLineup(vsLeft, "좌투수 상대", errors);
        if (vsLeft != null && vsRight == null) {
            errors.add("좌투수 상대 타순만 따로 지정할 수 없습니다");
        }
        if (rotation != null) {
            if (rotation.length != ROTATION_SIZE) {
                errors.add("선발 로테이션은 " + ROTATION_SIZE + "명입니다");
            }
            if (Arrays.stream(rotation).distinct().count() != rotation.length) {
                errors.add("선발 로테이션에 같은 투수가 두 번 있습니다");
            }
        }
        if (bullpen != null) {
            if (Arrays.stream(bullpen).distinct().count() != bullpen.length) {
                errors.add("불펜 순서에 같은 투수가 두 번 있습니다");
            }
            if (rotation != null) {
                for (int id : bullpen) {
                    if (Arrays.stream(rotation).anyMatch(r -> r == id)) {
                        errors.add("선발 로테이션 투수는 불펜에 넣을 수 없습니다");
                        break;
                    }
                }
            }
        }
        return errors;
    }

    private static void checkLineup(Slot[] slots, String label, List<String> errors) {
        if (slots == null) {
            return;
        }
        if (slots.length != 9) {
            errors.add(label + " 타순은 9명입니다");
            return;
        }
        boolean[] pos = new boolean[DH + 1];
        for (Slot s : slots) {
            if (s.pos() < 2 || s.pos() > DH) {
                errors.add(label + ": 수비 위치가 잘못됐습니다");
                return;
            }
            if (pos[s.pos()]) {
                errors.add(label + ": 같은 수비 위치가 두 번 있습니다");
                return;
            }
            pos[s.pos()] = true;
        }
        if (Arrays.stream(slots).mapToInt(Slot::playerId).distinct().count() != 9) {
            errors.add(label + ": 같은 선수가 두 번 있습니다");
        }
    }
}
