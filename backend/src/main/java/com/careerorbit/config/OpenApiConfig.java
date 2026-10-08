package com.careerorbit.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI/Swagger 文档配置：定义接口文档的标题、版本与说明。 */
@Configuration
public class OpenApiConfig {

    /** 构建 OpenAPI 元信息，供 /swagger-ui.html 展示。 */
    @Bean
    OpenAPI careerOrbitOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Career Orbit API")
                .version("1.0.0")
                .description("AI 智能求职助手后端接口"));
    }
}
