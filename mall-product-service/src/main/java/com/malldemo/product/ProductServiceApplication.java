package com.malldemo.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品服务启动类。
 *
 * scanBasePackages 额外包含 com.malldemo.common，
 * 这样 mall-common 里的 GlobalExceptionHandler 才会生效：
 * 所有接口出错时都返回统一的 {"code","message","data"} 结构。
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.malldemo.product",
                "com.malldemo.common"
        }
)
@EnableDiscoveryClient
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                ProductServiceApplication.class,
                args
        );
    }
}
