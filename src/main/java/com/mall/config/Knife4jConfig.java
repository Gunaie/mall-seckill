package com.mall.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（Knife4j + springdoc），开启 JWT 全局 Authorization 按钮
 *
 * @author mall
 */
@Configuration
public class Knife4jConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("mall-seckill 电商秒杀系统 API")
                        .version("1.0.0")
                        .description("Java 后端简历项目 - 用户认证 / RBAC / 商品 / 订单 / 秒杀"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
