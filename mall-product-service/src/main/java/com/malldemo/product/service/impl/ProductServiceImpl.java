package com.malldemo.product.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.product.entity.Product;
import com.malldemo.product.mapper.ProductMapper;
import com.malldemo.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl
        extends ServiceImpl<ProductMapper, Product>
        implements ProductService {

    @Autowired
    private StockDeductionTransactionService
            stockDeductionTransactionService;

    private final StockDeductionTransactionService transactionService;

    public ProductServiceImpl(
            StockDeductionTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Override
    @Cacheable(
            cacheNames = "productPage",
            key = "'page:' + #p0"
                    + " + ':size:' + #p1"
                    + " + ':keywordNull:' + (#p2 == null)"
                    + " + ':keyword:' + (#p2 == null ? '' : #p2.trim())"
                    + " + ':status:' + (#p3 == null ? 'all' : #p3)"
    )
    public IPage<Product> pageProducts(
            long pageNum,
            long pageSize,
            String keyword,
            Integer status
    ) {
        LambdaQueryWrapper<Product> wrapper =
                new LambdaQueryWrapper<>();

        // 有关键词时，按商品名称模糊搜索
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(
                    Product::getName,
                    keyword.trim()
            );
        }

        // 指定状态时，筛选对应状态的商品
        if (status != null) {
            wrapper.eq(Product::getStatus, status);
        }

        // 优先显示最近创建的商品
        wrapper.orderByDesc(Product::getCreateTime);

        Page<Product> page = new Page<>(pageNum, pageSize);

        return this.page(page, wrapper);
    }

    @Override
    @Cacheable(
            cacheNames = "productDetail",
            key = "#p0",
            sync = true
    )
    public Product getProductDetail(Long id) {
        return getById(id);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(
                    cacheNames = "productDetail",
                    allEntries = true
            ),
            @CacheEvict(
                    cacheNames = "productPage",
                    allEntries = true
            )
    })
    public boolean createProduct(Product product) {
        return save(product);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(
                    cacheNames = "productDetail",
                    key = "#p0"
            ),
            @CacheEvict(
                    cacheNames = "productPage",
                    allEntries = true
            )
    })
    public boolean updateProductInfo(Long id, Product product) {
        product.setId(id);
        return updateById(product);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(
                    cacheNames = "productDetail",
                    key = "#p0"
            ),
            @CacheEvict(
                    cacheNames = "productPage",
                    allEntries = true
            )
    })
    public boolean deleteProduct(Long id) {
        return removeById(id);
    }



    @Caching(evict = {
            @CacheEvict(
                    cacheNames = "productDetail",
                    allEntries = true
            ),
            @CacheEvict(
                    cacheNames = "productPage",
                    allEntries = true
            )
    })
    @Override
    public StockDeductResponse deductStock(
            String requestId,
            Long productId,
            Integer quantity) {

        try {
            return stockDeductionTransactionService.deductOnce(
                    requestId, productId, quantity
            );
        } catch (DuplicateKeyException ex) {
            StockDeductResponse existing =
                    stockDeductionTransactionService
                            .findExistingDeductResult(
                                    requestId, productId, quantity
                            );

            if (existing != null) {
                return existing;
            }

            throw ex;
        }
    }


    @Caching(evict = {
            @CacheEvict(
                    cacheNames = "productDetail",
                    allEntries = true
            ),
            @CacheEvict(
                    cacheNames = "productPage",
                    allEntries = true
            )
    })
    @Override
    public StockRestoreResponse restoreStock(
            String requestId,
            String deductRequestId,
            Long productId,
            Integer quantity) {

        try {
            return stockDeductionTransactionService.restoreOnce(
                    requestId,
                    deductRequestId,
                    productId,
                    quantity
            );
        } catch (DuplicateKeyException ex) {
            StockRestoreResponse existing =
                    stockDeductionTransactionService
                            .findExistingRestoreResult(
                                    requestId,
                                    deductRequestId,
                                    productId,
                                    quantity
                            );

            if (existing != null) {
                return existing;
            }

            throw ex;
        }
    }

}