package com.malldemo.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.product.entity.Product;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

public interface ProductService extends IService<Product> {

    IPage<Product> pageProducts(
            long pageNum,
            long pageSize,
            String keyword,
            Integer status
    );

    Product getProductDetail(Long id);

    boolean createProduct(Product product);

    boolean updateProductInfo(Long id, Product product);

    boolean deleteProduct(Long id);




    StockDeductResponse deductStock(
            String requestId,
            Long productId,
            Integer quantity);

    StockRestoreResponse restoreStock(
            String requestId,
            String deductRequestId,
            Long productId,
            Integer quantity
    );
}