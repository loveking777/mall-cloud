package com.malldemo.trade.client;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.dto.ProductInfoDTO;
import com.malldemo.product.api.request.StockDeductRequest;
import com.malldemo.product.api.request.StockRestoreRequest;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "mall-product-service",
        contextId = "productClient"
)
public interface ProductClient {

    @GetMapping("/internal/products/{id}")
    ApiResponse<ProductInfoDTO> getProductInfo(
            @PathVariable("id") Long id
    );

    @PostMapping("/internal/products/{id}/stock/deduct")
    ApiResponse<StockDeductResponse> deductStock(
            @PathVariable("id") Long id,
            @RequestBody StockDeductRequest request
    );

    @PostMapping("/internal/products/{id}/stock/restore")
    ApiResponse<StockRestoreResponse> restoreStock(
            @PathVariable("id") Long id,
            @RequestBody StockRestoreRequest request
    );
}