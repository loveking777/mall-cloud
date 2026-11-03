package com.malldemo.trade.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(
        name = "mall-product-service",
        contextId = "productHealthClient"
)
public interface ProductHealthClient {

    @GetMapping("/internal/health")
    Map<String, String> checkProductService();
}