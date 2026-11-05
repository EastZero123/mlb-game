package com.mlbgame.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 처리 시간 한 줄 로그 (부하 점검용): {@code http  GET /careers/{id}/teams/{teamId} 200 38ms}.
 * 경로는 ID 를 뺀 매핑 모양이라 경로별로 묶어 보기 쉽다. 정적 파일은 기록하지 않는다.
 * <pre>
 * app.ops.request-log  true(기본) = 모든 요청 INFO, false = 느린 요청만
 * app.ops.slow-request-ms  이 시간 이상이면 WARN (기본 500)
 * </pre>
 * 경로별 횟수·시간 합계·최대는 Spring 기본 지표 /actuator/metrics/http.server.requests 에도 쌓인다.
 */
@Component
@ConditionalOnWebApplication
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 보안 필터(로그인 확인)까지 포함한 전체 시간
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    private final boolean all;
    private final long slowMs;

    public RequestTimingFilter(@Value("${app.ops.request-log:true}") boolean all,
                               @Value("${app.ops.slow-request-ms:500}") long slowMs) {
        this.all = all;
        this.slowMs = slowMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/css/") || uri.startsWith("/webjars/") || uri.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms >= slowMs || all && log.isInfoEnabled()) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String path = pattern != null ? pattern.toString() : request.getRequestURI();
                String htmx = request.getHeader("HX-Request") != null ? " htmx" : "";
                if (ms >= slowMs) {
                    log.warn("{} {} {} {}ms{} slow", request.getMethod(), path, response.getStatus(), ms, htmx);
                } else {
                    log.info("{} {} {} {}ms{}", request.getMethod(), path, response.getStatus(), ms, htmx);
                }
            }
        }
    }
}
