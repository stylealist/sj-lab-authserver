package com.example.authserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 이번 단계는 "로그인 서버 발급 + 게이트웨이 라우팅"까지만 구축한다.
 * 다른 서비스(mapservice-rest, scheduler 등)의 API에 이 서버가 발급한 토큰 검증을
 * 강제하는 것은 별도 작업(게이트웨이 전역 필터 추가)이며, 이 서버 자체는 로그인·헬스체크
 * 엔드포인트만 열어 둔다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
