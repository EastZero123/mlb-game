package com.mlbgame.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 테스트용 일괄 진행 기능 (대시보드 "1주", "정규시즌/포스트시즌 끝까지", 오프시즌 "남은 단계 한 번에").
 * 기본: local 프로필에서만 켜짐, 운영(prod)에서는 화면에서 숨기고 요청도 404.
 * {@code app.dev-tools: true/false} 로 프로필과 관계없이 강제할 수 있다.
 */
@Component
public class DevTools {

    private final boolean enabled;

    public DevTools(Environment env, @Value("${app.dev-tools:}") String flag) {
        this.enabled = flag == null || flag.isBlank() ? env.matchesProfiles("local") : Boolean.parseBoolean(flag.trim());
    }

    public boolean enabled() {
        return enabled;
    }
}
