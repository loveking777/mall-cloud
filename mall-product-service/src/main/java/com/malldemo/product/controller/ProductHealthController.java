package com.malldemo.product.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ProductHealthController {

    @GetMapping("/internal/health")
    public Map<String, String> health() {
        return Map.of(
                "service", "mall-product-service",
                "status", "UP"
        );
    }
}