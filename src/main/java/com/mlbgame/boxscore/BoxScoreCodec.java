package com.mlbgame.boxscore;

import java.util.ArrayList;
import java.util.List;

import com.mlbgame.sim.BatStat;
import com.mlbgame.sim.GameResult;
import com.mlbgame.sim.PitcherLine;
import com.mlbgame.sim.SimTeam;

/**
 * 박스스코어 ↔ 바이너리 변환. 경기당 약 500바이트, {@code c_game.box} 에 저장한다.
 * <p>
 * 한 경기 안의 개별 기록은 모두 0~255 범위이므로 1바이트(unsigned)로 저장한다.
 * 압축은 하지 않는다 (CPU 를 쓰는 것보다 500바이트를 그대로 쓰는 편이 저사양 서버에 유리).
 *
 * <pre>
 * [version 1B][innings 1B]
 * [원정 이닝별 득점 innings B][홈 이닝별 득점 innings B]          0xFF = 말 공격 없음
 * side 2회 × 타순 9회 × [선수 ID 4B][BatStat 15B]
 * side 2회 × [투수 수 1B] × [선수 ID 4B][선발 여부 1B][결정 1B][아웃..사구 10B]
 * </pre>
 */
public final class BoxScoreCodec {

    public static final byte VERSION = 1;

    private static final int BATTER_BYTES = 4 + BatStat.COUNT;
    private static final int PITCHER_BYTES = 4 + 2 + 10;
    private static final int NO_HALF = 0xFF;

    private BoxScoreCodec() {
    }

    public static byte[] encode(GameResult r, SimTeam away, SimTeam home) {
        int[][] ids = new int[2][9];
        for (int i = 0; i < 9; i++) {
            ids[0][i] = toInt(away.lineup()[i].playerId());
            ids[1][i] = toInt(home.lineup()[i].playerId());
        }
        return encode(r, ids);
    }

    public static byte[] encode(GameResult r, int[][] lineupIds) {
        int innings = r.innings();
        List<PitcherLine> awayP = r.awayPitching();
        List<PitcherLine> homeP = r.homePitching();
        int size = 2 + innings * 2 + 2 * 9 * BATTER_BYTES + 2 + (awayP.size() + homeP.size()) * PITCHER_BYTES;

        byte[] b = new byte[size];
        int p = 0;
        b[p++] = VERSION;
        b[p++] = u8(innings);

        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < innings; i++) {
                int v = r.lineScore()[side][i];
                b[p++] = (byte) (v < 0 ? NO_HALF : checkU8(v));
            }
        }

        for (int side = 0; side < 2; side++) {
            for (int slot = 0; slot < 9; slot++) {
                p = putInt(b, p, lineupIds[side][slot]);
                int[] s = r.batting()[side][slot];
                for (int k = 0; k < BatStat.COUNT; k++) {
                    b[p++] = u8(s[k]);
                }
            }
        }

        p = putPitchers(b, p, awayP);
        p = putPitchers(b, p, homeP);

        if (p != size) {
            throw new IllegalStateException("인코딩 크기 불일치: " + p + " != " + size);
        }
        return b;
    }

    public static BoxScore decode(byte[] b) {
        int p = 0;
        if (b[p++] != VERSION) {
            throw new IllegalArgumentException("지원하지 않는 박스스코어 버전: " + b[0]);
        }
        int innings = b[p++] & 0xFF;

        int[][] lineScore = new int[2][innings];
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < innings; i++) {
                int v = b[p++] & 0xFF;
                lineScore[side][i] = v == NO_HALF ? -1 : v;
            }
        }

        int[][] ids = new int[2][9];
        int[][][] batting = new int[2][9][BatStat.COUNT];
        for (int side = 0; side < 2; side++) {
            for (int slot = 0; slot < 9; slot++) {
                ids[side][slot] = getInt(b, p);
                p += 4;
                for (int k = 0; k < BatStat.COUNT; k++) {
                    batting[side][slot][k] = b[p++] & 0xFF;
                }
            }
        }

        List<List<BoxScore.PitcherBox>> pitching = new ArrayList<>(2);
        for (int side = 0; side < 2; side++) {
            int n = b[p++] & 0xFF;
            List<BoxScore.PitcherBox> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int id = getInt(b, p);
                p += 4;
                boolean starter = b[p++] != 0;
                char decision = (char) (b[p++] & 0xFF);
                list.add(new BoxScore.PitcherBox(id, starter, decision,
                        b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF,
                        b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF, b[p++] & 0xFF));
            }
            pitching.add(List.copyOf(list));
        }
        return new BoxScore(innings, lineScore, ids, batting, List.copyOf(pitching));
    }

    private static int putPitchers(byte[] b, int p, List<PitcherLine> lines) {
        b[p++] = u8(lines.size());
        for (PitcherLine l : lines) {
            p = putInt(b, p, toInt(l.playerId()));
            b[p++] = (byte) (l.starter() ? 1 : 0);
            b[p++] = (byte) l.decision();
            b[p++] = u8(l.outs());
            b[p++] = u8(l.battersFaced());
            b[p++] = u8(l.pitches());
            b[p++] = u8(l.hits());
            b[p++] = u8(l.runs());
            b[p++] = u8(l.earnedRuns());
            b[p++] = u8(l.walks());
            b[p++] = u8(l.strikeouts());
            b[p++] = u8(l.homeRuns());
            b[p++] = u8(l.hitByPitch());
        }
        return p;
    }

    private static int putInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
        return p + 4;
    }

    private static int getInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** 기록 값. 255 초과(초장기 연장전의 투구 수 등)는 255로 저장해 하루 진행이 실패하지 않게 한다. */
    private static byte u8(int v) {
        if (v < 0) {
            throw new IllegalArgumentException("음수 기록: " + v);
        }
        return (byte) Math.min(v, 255);
    }

    /** 이닝 득점. 0xFF 는 '말 공격 없음' 표시라 254 까지만 허용. */
    private static int checkU8(int v) {
        if (v < 0 || v > 254) {
            throw new IllegalArgumentException("1바이트 범위 초과: " + v);
        }
        return v;
    }

    private static int toInt(long id) {
        if (id < 0 || id > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("선수 ID 범위 초과: " + id);
        }
        return (int) id;
    }
}
