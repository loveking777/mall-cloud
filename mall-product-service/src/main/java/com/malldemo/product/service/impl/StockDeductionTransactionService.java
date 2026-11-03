package com.malldemo.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.product.entity.Product;
import com.malldemo.product.entity.ProductStockOperation;
import com.malldemo.product.exception.StockOperationConflictException;
import com.malldemo.product.mapper.ProductMapper;
import com.malldemo.product.mapper.ProductStockOperationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class StockDeductionTransactionService {

    private final ProductMapper productMapper;
    private final ProductStockOperationMapper operationMapper;

    public StockDeductionTransactionService(
            ProductMapper productMapper,
            ProductStockOperationMapper operationMapper) {

        this.productMapper = productMapper;
        this.operationMapper = operationMapper;
    }

    /**
     * 扣减库存，并记录成功的操作。
     */
    @Transactional(rollbackFor = Exception.class)
    public StockDeductResponse deductOnce(
            String requestId,
            Long productId,
            Integer quantity) {

        ProductStockOperation existing =
                findOperation(requestId);

        // 相同请求已经成功处理，直接返回原结果
        if (existing != null) {
            verifyOperation(
                    existing, "DEDUCT", productId, quantity
            );

            return new StockDeductResponse(
                    existing.getProductId(),
                    existing.getQuantity(),
                    existing.getRemainingStock()
            );
        }

        // 数据库原子扣减：库存不足时返回 0
        int affected = productMapper.deductStock(
                productId, quantity
        );

        if (affected == 0) {
            return null;
        }

        Product product = productMapper.selectById(productId);

        if (product == null) {
            throw new IllegalStateException(
                    "扣减库存后无法找到商品"
            );
        }

        // 与库存扣减处于同一个事务
        ProductStockOperation operation =
                new ProductStockOperation();

        operation.setRequestId(requestId);
        operation.setReferenceRequestId(null);
        operation.setOperationType("DEDUCT");
        operation.setProductId(productId);
        operation.setQuantity(quantity);
        operation.setRemainingStock(product.getStock());

        operationMapper.insert(operation);

        return new StockDeductResponse(
                productId,
                quantity,
                product.getStock()
        );
    }

    /**
     * 恢复库存，只允许恢复一笔真实成功过的扣减。
     */
    @Transactional(rollbackFor = Exception.class)
    public StockRestoreResponse restoreOnce(
            String requestId,
            String deductRequestId,
            Long productId,
            Integer quantity) {

        // 情况一：这个恢复 requestId 以前已经使用过
        ProductStockOperation existing =
                findOperation(requestId);

        if (existing != null) {
            verifyOperation(
                    existing, "RESTORE", productId, quantity
            );

            if (!Objects.equals(
                    existing.getReferenceRequestId(),
                    deductRequestId)) {

                throw new StockOperationConflictException(
                        "恢复请求与原始扣减请求不匹配"
                );
            }

            return toRestoreResponse(existing);
        }

        // 情况二：这笔原始扣减已经被恢复过
        ProductStockOperation previousRestore =
                findRestoreByDeductRequestId(deductRequestId);

        if (previousRestore != null) {
            verifyOperation(
                    previousRestore,
                    "RESTORE",
                    productId,
                    quantity
            );

            return toRestoreResponse(previousRestore);
        }

        // 情况三：必须找到真实成功过的原始扣减
        ProductStockOperation deduction =
                findOperation(deductRequestId);

        if (deduction == null) {
            throw new StockOperationConflictException(
                    "原始扣减记录不存在，不能恢复库存"
            );
        }

        verifyOperation(
                deduction, "DEDUCT", productId, quantity
        );

        // 执行恢复 SQL
        int affected = productMapper.restoreStock(
                productId, quantity
        );

        if (affected == 0) {
            return null;
        }

        Product product = productMapper.selectById(productId);

        if (product == null) {
            throw new IllegalStateException(
                    "恢复库存后无法找到商品"
            );
        }

        // 记录本次恢复，并关联原始扣减请求
        ProductStockOperation operation =
                new ProductStockOperation();

        operation.setRequestId(requestId);
        operation.setReferenceRequestId(deductRequestId);
        operation.setOperationType("RESTORE");
        operation.setProductId(productId);
        operation.setQuantity(quantity);
        operation.setRemainingStock(product.getStock());

        operationMapper.insert(operation);

        return toRestoreResponse(operation);
    }

    /**
     * 当并发请求发生唯一键冲突时，
     * 在原事务回滚后读取已提交的扣减结果。
     */
    public StockDeductResponse findExistingDeductResult(
            String requestId,
            Long productId,
            Integer quantity) {

        ProductStockOperation existing =
                findOperation(requestId);

        if (existing == null) {
            return null;
        }

        verifyOperation(
                existing, "DEDUCT", productId, quantity
        );

        return new StockDeductResponse(
                existing.getProductId(),
                existing.getQuantity(),
                existing.getRemainingStock()
        );
    }

    /**
     * 当并发恢复请求发生唯一键冲突时，
     * 在原事务回滚后读取已经提交的恢复结果。
     */
    public StockRestoreResponse findExistingRestoreResult(
            String requestId,
            String deductRequestId,
            Long productId,
            Integer quantity) {

        ProductStockOperation existing =
                findOperation(requestId);

        if (existing != null) {
            verifyOperation(
                    existing, "RESTORE", productId, quantity
            );

            if (!Objects.equals(
                    existing.getReferenceRequestId(),
                    deductRequestId)) {

                throw new StockOperationConflictException(
                        "恢复请求与原始扣减请求不匹配"
                );
            }

            return toRestoreResponse(existing);
        }

        ProductStockOperation existingRestore =
                findRestoreByDeductRequestId(deductRequestId);

        if (existingRestore == null) {
            return null;
        }

        verifyOperation(
                existingRestore,
                "RESTORE",
                productId,
                quantity
        );

        return toRestoreResponse(existingRestore);
    }

    private ProductStockOperation findOperation(
            String requestId) {

        return operationMapper.selectOne(
                new LambdaQueryWrapper<ProductStockOperation>()
                        .eq(
                                ProductStockOperation::getRequestId,
                                requestId
                        )
        );
    }

    private ProductStockOperation findRestoreByDeductRequestId(
            String deductRequestId) {

        return operationMapper.selectOne(
                new LambdaQueryWrapper<ProductStockOperation>()
                        .eq(
                                ProductStockOperation::getReferenceRequestId,
                                deductRequestId
                        )
                        .eq(
                                ProductStockOperation::getOperationType,
                                "RESTORE"
                        )
        );
    }

    private void verifyOperation(
            ProductStockOperation operation,
            String expectedType,
            Long productId,
            Integer quantity) {

        if (!Objects.equals(
                operation.getOperationType(), expectedType)
                || !Objects.equals(
                operation.getProductId(), productId)
                || !Objects.equals(
                operation.getQuantity(), quantity)) {

            throw new StockOperationConflictException(
                    "同一个请求 ID 不能用于不同的库存操作"
            );
        }
    }

    private StockRestoreResponse toRestoreResponse(
            ProductStockOperation operation) {

        return new StockRestoreResponse(
                operation.getProductId(),
                operation.getQuantity(),
                operation.getRemainingStock()
        );
    }
}