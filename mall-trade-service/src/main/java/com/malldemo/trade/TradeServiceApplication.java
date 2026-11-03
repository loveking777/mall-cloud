package com.malldemo.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交易服务启动类。
 *
 * scanBasePackages 额外包含 com.malldemo.common，
 * 复用 mall-common 里的 GlobalExceptionHandler。
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.malldemo.trade",
                "com.malldemo.common"
        }
)
@EnableDiscoveryClient
@EnableFeignClients(
        basePackages = "com.malldemo.trade.client"
)
@EnableScheduling
public class TradeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                TradeServiceApplication.class,
                args
        );
    }
}
