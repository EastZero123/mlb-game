package com.mlbgame.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 작은 CSV 파서 (Baseball Savant 형식: UTF-8 BOM, 따옴표 안의 쉼표, 빈 값).
 * 없는 컬럼을 요청하면 사용 가능한 컬럼 목록과 함께 예외를 던진다 → 사이트 형식 변경을 바로 알 수 있음.
 */
final class CsvTable {

    private final String name;
    private final Map<String, Integer> index;
    private final List<String[]> rows;

    private CsvTable(String name, Map<String, Integer> index, List<String[]> rows) {
        this.name = name;
        this.index = index;
        this.rows = rows;
    }

    static CsvTable parse(String name, String text) {
        List<String[]> records = parseRecords(text);
        if (records.isEmpty()) {
            throw new IllegalArgumentException(name + ": 빈 CSV");
        }
        String[] header = records.getFirst();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim(), i);
        }
        return new CsvTable(name, index, records.subList(1, records.size()));
    }

    List<String[]> rows() {
        return rows;
    }

    /** 빈 값이면 null */
    String str(String[] row, String column) {
        int i = col(column);
        if (i >= row.length) {
            return null;
        }
        String v = row[i].trim();
        return v.isEmpty() ? null : v;
    }

    /** 숫자가 아니거나 비었으면 null */
    Double num(String[] row, String column) {
        String v = str(row, column);
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Integer integer(String[] row, String column) {
        Double v = num(row, column);
        return v == null ? null : (int) Math.round(v);
    }

    private int col(String column) {
        Integer i = index.get(column);
        if (i == null) {
            throw new IllegalArgumentException(name + ": 컬럼 '" + column + "' 없음. 사용 가능: " + index.keySet());
        }
        return i;
    }

    private static List<String[]> parseRecords(String text) {
        List<String[]> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int start = !text.isEmpty() && text.charAt(0) == '﻿' ? 1 : 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(cur.toString());
                cur.setLength(0);
                if (!(fields.size() == 1 && fields.getFirst().isEmpty())) {
                    out.add(fields.toArray(String[]::new));
                }
                fields.clear();
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || !fields.isEmpty()) {
            fields.add(cur.toString());
            out.add(fields.toArray(String[]::new));
        }
        return out;
    }
}
