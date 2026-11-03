package com.malldemo.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由表：把路径转发到对应的服务。
 *
 * 用代码写而不是写 YAML，是为了避开 Spring Cloud Gateway 5.x
 * 配置前缀变化带来的困惑，同时让路由一眼可见。
 *
 * lb:// 表示"通过注册中心找到服务实例并负载均衡"。
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {

        return builder.routes()
                .route(
                        "mall-user-service",
                        route -> route.path("/user/**")
                                .uri("lb://mall-user-service")
                )
                .route(
                        "mall-product-service",
                        route -> route.path("/product/**")
                                .uri("lb://mall-product-service")
                )
                .route(
                        "mall-trade-service",
                        route -> route.path("/orders/**")
                                .uri("lb://mall-trade-service")
                )
                .build();
    }
}
