package com.mlbgame.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CsvTableTest {

    // Savant 실제 형식: BOM, 따옴표 안 쉼표, 따옴표로 감싼 숫자, 빈 값, CRLF
    private static final String SAMPLE = "﻿\"last_name, first_name\",\"player_id\",\"xba\",\"hp_to_1b\",\"sprint_speed\"\r\n"
            + "\"Witt Jr., Bobby\",677951,\".301\",,\"30.1\"\r\n"
            + "\"O\"\"Neill, Tyler\",641933,\".240\",\"4.30\",\"27.5\"\r\n";

    @Test
    void Savant_형식을_파싱한다() {
        CsvTable t = CsvTable.parse("sample", SAMPLE);

        assertThat(t.rows()).hasSize(2);
        String[] witt = t.rows().get(0);
        assertThat(t.str(witt, "last_name, first_name")).isEqualTo("Witt Jr., Bobby");
        assertThat(t.integer(witt, "player_id")).isEqualTo(677951);
        assertThat(t.num(witt, "xba")).isEqualTo(0.301);
        assertThat(t.num(witt, "hp_to_1b")).isNull();
        assertThat(t.num(witt, "sprint_speed")).isEqualTo(30.1);

        assertThat(t.str(t.rows().get(1), "last_name, first_name")).isEqualTo("O\"Neill, Tyler");
    }

    @Test
    void 없는_컬럼은_목록과_함께_실패() {
        CsvTable t = CsvTable.parse("sample", SAMPLE);
        assertThatThrownBy(() -> t.num(t.rows().getFirst(), "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("player_id");
    }
}
