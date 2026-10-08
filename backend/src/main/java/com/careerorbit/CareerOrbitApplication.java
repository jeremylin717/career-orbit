package com.careerorbit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** 应用入口。排除默认的 UserDetailsService 自动配置，认证完全由自定义 JWT 过滤器负责。 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CareerOrbitApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareerOrbitApplication.class, args);
    }
}
