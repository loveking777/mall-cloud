package com.malldemo.trade.controller;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.dto.ProductInfoDTO;
import com.malldemo.product.api.request.StockDeductRequest;
import com.malldemo.product.api.request.StockRestoreRequest;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.trade.client.ProductClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/remote-products")
public class RemoteProductController {

    private final ProductClient productClient;

    public RemoteProductController(ProductClient productClient) {
        this.productClient = productClient;
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductInfoDTO> getProduct(
            @PathVariable Long id) {

        return productClient.getProductInfo(id);
    }

    @PostMapping("/{id}/stock/deduct")
    public ApiResponse<StockDeductResponse> deductStock(
            @PathVariable Long id,
            @RequestBody StockDeductRequest request) {

        return productClient.deductStock(id, request);
    }

    @PostMapping("/{id}/stock/restore")
    public ApiResponse<StockRestoreResponse> restoreStock(
            @PathVariable("id") Long id,
            @RequestBody StockRestoreRequest request) {

        return productClient.restoreStock(id, request);
    }
}