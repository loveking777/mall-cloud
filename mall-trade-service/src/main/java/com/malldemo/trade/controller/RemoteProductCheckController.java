package com.malldemo.trade.controller;

import com.malldemo.trade.client.ProductHealthClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/test")
public class RemoteProductCheckController {

    private final ProductHealthClient productHealthClient;

    public RemoteProductCheckController(
            ProductHealthClient productHealthClient) {
        this.productHealthClient = productHealthClient;
    }

    @GetMapping("/product-health")
    public Map<String, Object> checkProductHealth() {
        Map<String, String> productResult =
                productHealthClient.checkProductService();

        return Map.of(
                "callerService", "mall-trade-service",
                "remoteResult", productResult
        );
    }
}