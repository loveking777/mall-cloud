package com.malldemo.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类。
 *
 * 网关是客户端访问后端的唯一入口：
 * 1. 按路径把请求转发到对应服务（/user、/product、/orders）；
 * 2. 统一校验 JWT，校验通过后向下游注入 X-User-Id；
 * 3. 下游服务不再自己解析 token。
 *
 * 注意：网关不扫描 com.malldemo.common，
 * 因为 mall-common 里的 GlobalExceptionHandler 是给 Servlet 服务用的，
 * 网关运行在 WebFlux（响应式）栈上，异常处理方式不同。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                GatewayApplication.class,
                args
        );
    }
}
