package com.mlbgame.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MlbStatsImporterTest {

    @Test
    void 시즌_표기는_점_앞의_연도만_쓴다() {
        assertThat(MlbStatsImporter.seasonOf("2026")).isEqualTo(2026);
        assertThat(MlbStatsImporter.seasonOf("2018.2")).isEqualTo(2018);
    }

    @Test
    void 포지션_코드_변환() {
        assertThat(MlbStatsImporter.position("1")).isEqualTo(1);
        assertThat(MlbStatsImporter.position("Y")).isEqualTo(10);
        assertThat(MlbStatsImporter.position("O")).isEqualTo(8);
    }
}
