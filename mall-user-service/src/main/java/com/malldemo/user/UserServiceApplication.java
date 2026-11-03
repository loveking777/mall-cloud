package com.malldemo.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类。
 *
 * scanBasePackages 里额外加上了 com.malldemo.common，
 * 这样 mall-common 里的 GlobalExceptionHandler 才会被扫描到。
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.malldemo.user",
                "com.malldemo.common"
        }
)
@EnableDiscoveryClient
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                UserServiceApplication.class,
                args
        );
    }
}
