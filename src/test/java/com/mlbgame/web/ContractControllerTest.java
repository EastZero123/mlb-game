package com.mlbgame.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ContractControllerTest {

    @Test
    void 입력값은_접두어별_선수_ID_로_묶고_숫자가_아니면_무시() {
        Map<String, String> form = Map.of("d_101", "1", "d_102", "2", "y_101", "3", "y_abc", "1", "d_103", "x",
                "go", "true", "_csrf", "token");
        assertThat(ContractController.numbers(form, "d_")).containsOnly(Map.entry(101, 1), Map.entry(102, 2));
        assertThat(ContractController.numbers(form, "y_")).containsOnly(Map.entry(101, 3));
    }
}
