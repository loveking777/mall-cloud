
package com.malldemo.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.malldemo.common.ApiResponse;
import com.malldemo.product.dto.request.ProductRequest;
import com.malldemo.product.dto.response.ProductResponse;
import com.malldemo.product.entity.Product;
import com.malldemo.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ApiResponse<List<ProductResponse>> list() {
        List<ProductResponse> products =
                productService.list()
                        .stream()
                        .map(ProductResponse::from)
                        .toList();

        return ApiResponse.success(products);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> detail(
            @PathVariable Long id) {

        Product product = productService.getProductDetail(id);

        if (product == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "商品不存在"
            );
        }

        return ApiResponse.success(
                ProductResponse.from(product)
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(
            @Valid @RequestBody ProductRequest request) {

        Product product = toProduct(request);

        if (!productService.createProduct(product)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "商品新增失败"
            );
        }

        return ApiResponse.success(
                ProductResponse.from(product)
        );
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {

        if (productService.getById(id) == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "商品不存在"
            );
        }

        Product product = toProduct(request);


        if (!productService.updateProductInfo(id, product)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "商品更新失败"
            );
        }

        Product updated = productService.getById(id);

        return ApiResponse.success(
                ProductResponse.from(updated)
        );
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id) {

        if (productService.getById(id) == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "商品不存在"
            );
        }

        if (!productService.deleteProduct(id)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "商品删除失败"
            );
        }

        return ApiResponse.success("删除成功", null);
    }

    @GetMapping("/page")
    public ApiResponse<IPage<ProductResponse>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {

        if (pageNum < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "页码必须大于等于 1"
            );
        }

        if (pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "每页条数必须在 1 到 100 之间"
            );
        }

        if (status != null && status != 0 && status != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "商品状态只能为 0 或 1"
            );
        }

        IPage<Product> result =
                productService.pageProducts(
                        pageNum, pageSize, keyword, status
                );

        IPage<ProductResponse> responsePage =
                result.convert(ProductResponse::from);

        return ApiResponse.success(responsePage);
    }

    private Product toProduct(ProductRequest request) {
        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setStatus(request.getStatus());
        return product;
    }
}
