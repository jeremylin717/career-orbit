package com.careerorbit.config;

import com.careerorbit.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 * 无状态（STATELESS）：不建会话，认证完全靠 JWT 过滤器。
 * 放行认证与文档接口；/api/admin/** 需 ADMIN；其余接口需登录。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 密码编码器：BCrypt（自带盐、慢哈希，抗彩虹表）。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt) throws Exception {
        return http
                .csrf(c -> c.disable())                       // 前后端分离 + JWT，无需 CSRF
                .cors(c -> { })                               // 使用默认 CORS 配置
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**", "/actuator/health",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
