package com.mlbgame.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevToolsTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void local_프로필에서만_켜지고_운영에서는_꺼진다() {
        assertThat(new DevTools(env("local"), "").enabled()).isTrue();
        assertThat(new DevTools(env("prod"), "").enabled()).isFalse();
    }

    @Test
    void 설정값으로_강제할_수_있다() {
        assertThat(new DevTools(env("local"), "false").enabled()).isFalse();
        assertThat(new DevTools(env("prod"), "true").enabled()).isTrue();
    }
}
