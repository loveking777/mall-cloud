package com.malldemo.product.controller;

import com.malldemo.product.mapper.ProductMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ProductDbCheckController {

    private final ProductMapper productMapper;

    public ProductDbCheckController(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @GetMapping("/internal/db-check")
    public Map<String, Object> checkDatabase() {
        Long count = productMapper.selectCount(null);

        return Map.of(
                "service", "mall-product-service",
                "database", "connected",
                "productCount", count
        );
    }
}