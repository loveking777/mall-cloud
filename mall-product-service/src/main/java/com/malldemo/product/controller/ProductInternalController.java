package com.malldemo.product.controller;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.dto.ProductInfoDTO;
import com.malldemo.product.api.request.StockRestoreRequest;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.product.entity.Product;
import com.malldemo.product.exception.StockOperationConflictException;
import com.malldemo.product.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.malldemo.product.api.request.StockDeductRequest;


@RestController
@RequestMapping("/internal/products")
public class ProductInternalController {

    private final ProductService productService;

    public ProductInternalController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductInfoDTO> getProductInfo(
            @PathVariable Long id) {

        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "商品 ID 必须大于 0"
            );
        }

        Product product = productService.getProductDetail(id);

        if (product == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "商品不存在"
            );
        }

        ProductInfoDTO dto = new ProductInfoDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setStock(product.getStock());
        dto.setStatus(product.getStatus());

        return ApiResponse.success(dto);
    }

    @PostMapping("/{id}/stock/deduct")
    public ApiResponse<StockDeductResponse> deductStock(
            @PathVariable Long id,
            @RequestBody StockDeductRequest request) {

        if (id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "商品 ID 必须大于 0"
            );
        }

        if (request == null
                || request.getRequestId() == null
                || request.getRequestId().isBlank()
                || request.getRequestId().length() > 64
                || request.getQuantity() == null
                || request.getQuantity() <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "requestId 和扣减数量不合法"
            );
        }

        StockDeductResponse result;

        try {
            result = productService.deductStock(
                    request.getRequestId().trim(),
                    id,
                    request.getQuantity()
            );
        } catch (StockOperationConflictException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ex.getMessage()
            );
        }

        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "商品不存在、未上架或库存不足"
            );
        }

        return ApiResponse.success(result);
    }

    @PostMapping("/{id}/stock/restore")
    public ApiResponse<StockRestoreResponse> restoreStock(
            @PathVariable Long id,
            @RequestBody StockRestoreRequest request) {

        if (id <= 0
                || request == null
                || request.getRequestId() == null
                || request.getRequestId().isBlank()
                || request.getRequestId().length() > 64
                || request.getDeductRequestId() == null
                || request.getDeductRequestId().isBlank()
                || request.getDeductRequestId().length() > 64
                || request.getQuantity() == null
                || request.getQuantity() <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "恢复请求参数不合法"
            );
        }

        StockRestoreResponse result;

        try {
            result = productService.restoreStock(
                    request.getRequestId().trim(),
                    request.getDeductRequestId().trim(),
                    id,
                    request.getQuantity()
            );
        } catch (StockOperationConflictException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ex.getMessage()
            );
        }

        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "商品不存在，无法恢复库存"
            );
        }

        return ApiResponse.success(result);
    }
}