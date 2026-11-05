package com.mlbgame.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 폼 로그인 + 서버 세션 (쿠키 MLBSID, application.yml). CSRF 켜짐: 모든 POST 는 th:action 폼이라 토큰이 자동 포함되고,
 * htmx 요청도 폼 필드를 그대로 보내므로 별도 헤더 설정이 필요 없다.
 * 임포트·검증·벤치마크(web-application-type=none)에서는 필터 체인을 만들지 않는다.
 * 관리용 엔드포인트(/actuator/**)는 별도 포트(기본 8081) + 127.0.0.1 바인딩이라 서버 내부에서만 닿으므로 로그인 없이 연다
 * (메인 포트에는 actuator 가 없어 같은 경로로 와도 404).
 */
@Configuration
public class SecurityConfig {

    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/signup", "/css/**", "/webjars/**", "/favicon.ico", "/error")
                        .permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll());
        return http.build();
    }

    /** BCrypt 강도 10 (저사양 서버에서 로그인 1회 약 50~100ms) */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
