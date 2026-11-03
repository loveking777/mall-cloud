package com.malldemo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.malldemo.trade.constant.InventoryOperationStatus;
import com.malldemo.trade.entity.TradeInventoryOperation;
import com.malldemo.trade.mapper.TradeInventoryOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TradeInventoryOperationPersistenceService {

    /**
     * trade_inventory_operation.last_error 字段的长度。
     */
    private static final int MAX_ERROR_LENGTH = 500;

    private final TradeInventoryOperationMapper mapper;

    /**
     * 在调用商品服务之前，
     * 将本次订单涉及的全部库存操作保存到数据库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createPreparedOperations(
            String orderNo,
            List<TradeInventoryOperation> operations) {

        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("订单号不能为空");
        }

        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("库存操作列表不能为空");
        }

        for (TradeInventoryOperation operation : operations) {

            if (operation.getProductId() == null
                    || operation.getQuantity() == null
                    || operation.getQuantity() <= 0
                    || !StringUtils.hasText(operation.getDeductRequestId())) {
                throw new IllegalArgumentException("库存操作参数不合法");
            }

            operation.setOrderNo(orderNo);
            operation.setDeductStatus(
                    InventoryOperationStatus.PENDING);

            operation.setRestoreRequestId(null);
            operation.setRestoreStatus(
                    InventoryOperationStatus.NOT_REQUIRED);

            mapper.insert(operation);
        }
    }

    /**
     * 查询某笔订单对应的全部库存操作。
     */
    @Transactional(readOnly = true)
    public List<TradeInventoryOperation> findByOrderNo(
            String orderNo) {

        return mapper.selectList(
                new QueryWrapper<TradeInventoryOperation>()
                        .eq("order_no", orderNo)
                        .orderByAsc("id")
        );
    }

    /**
     * 根据扣库存请求 ID 查询记录。
     */
    @Transactional(readOnly = true)
    public TradeInventoryOperation findByDeductRequestId(
            String deductRequestId) {

        return mapper.selectOne(
                new QueryWrapper<TradeInventoryOperation>()
                        .eq("deduct_request_id", deductRequestId)
        );
    }

    /**
     * 查询需要恢复任务处理记录。
     */
    @Transactional(readOnly = true)
    public List<TradeInventoryOperation> findRecoverable(
            LocalDateTime staleBefore,
            int limit) {

        return mapper.findRecoverable(staleBefore, limit);
    }

    /**
     * 商品服务明确返回扣减成功后调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markDeductSucceeded(String deductRequestId) {

        updateDeductStatus(
                deductRequestId,
                InventoryOperationStatus.SUCCEEDED,
                null
        );
    }

    /**
     * 商品服务明确返回业务失败后调用。
     *
     * 例如库存不足、商品不存在。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markDeductRejected(
            String deductRequestId,
            String errorMessage) {

        updateDeductStatus(
                deductRequestId,
                InventoryOperationStatus.REJECTED,
                errorMessage
        );
    }

    /**
     * 网络超时等情况导致无法确定扣减结果时调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markDeductUnknown(
            String deductRequestId,
            String errorMessage) {

        updateDeductStatus(
                deductRequestId,
                InventoryOperationStatus.UNKNOWN,
                errorMessage
        );
    }

    /**
     * 扣减状态更新。
     *
     * 用条件 UPDATE 完成"判断 + 修改"，
     * 并发时不会出现两个线程互相覆盖。
     */
    private void updateDeductStatus(
            String deductRequestId,
            String targetStatus,
            String errorMessage) {

        int affected = mapper.updateDeductStatus(
                deductRequestId,
                targetStatus,
                brief(errorMessage)
        );

        if (affected == 1) {
            return;
        }

        // 没有更新成功，需要区分两种情况：
        // 1. 记录已经是目标状态（幂等重复调用）
        // 2. 记录已经进入终态（不能被覆盖）
        TradeInventoryOperation current =
                requireByDeductRequestId(deductRequestId);

        if (Objects.equals(current.getDeductStatus(), targetStatus)) {
            return;
        }

        throw new IllegalStateException(
                "扣减操作已经处于终态，不能覆盖："
                        + current.getDeductStatus()
        );
    }

    /**
     * 在调用商品服务恢复库存之前调用。
     *
     * 先保存恢复请求 ID 和 PENDING 状态，
     * 再执行真正的远程恢复操作。
     */
    @Transactional(rollbackFor = Exception.class)
    public TradeInventoryOperation prepareRestore(
            String deductRequestId,
            String restoreRequestId) {

        if (!StringUtils.hasText(restoreRequestId)) {
            throw new IllegalArgumentException("恢复请求 ID 不能为空");
        }

        int affected = mapper.prepareRestore(
                deductRequestId,
                restoreRequestId
        );

        TradeInventoryOperation operation =
                requireByDeductRequestId(deductRequestId);

        if (affected == 1) {
            return operation;
        }

        // 没有更新成功：给出尽可能精确的原因
        String deductStatus = operation.getDeductStatus();

        // 扣减结果可能是 UNKNOWN：调用超时了，但商品服务实际可能已经扣成功，
        // 这种情况也要允许"尝试恢复"，由商品服务判断这笔扣减是否真实存在。
        if (!InventoryOperationStatus.SUCCEEDED.equals(deductStatus)
                && !InventoryOperationStatus.UNKNOWN.equals(deductStatus)) {

            throw new IllegalStateException(
                    "只有扣减成功或结果未知的操作才能尝试恢复库存");
        }

        // 同一笔扣减操作，恢复请求 ID 必须保持稳定。
        if (StringUtils.hasText(operation.getRestoreRequestId())
                && !operation.getRestoreRequestId().equals(
                restoreRequestId)) {

            throw new IllegalStateException(
                    "同一笔库存扣减不能使用不同的恢复请求 ID");
        }

        // 已经恢复成功，不再重复安排（幂等）。
        return operation;
    }

    /**
     * 商品服务明确返回恢复成功后调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRestoreSucceeded(String deductRequestId) {

        updateRestoreStatus(
                deductRequestId,
                InventoryOperationStatus.RESTORE_SUCCEEDED,
                null
        );
    }

    /**
     * 恢复操作明确失败后调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRestoreFailed(
            String deductRequestId,
            String errorMessage) {

        updateRestoreStatus(
                deductRequestId,
                InventoryOperationStatus.RESTORE_FAILED,
                errorMessage
        );
    }

    /**
     * 恢复操作因超时等原因无法确认结果时调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRestoreUnknown(
            String deductRequestId,
            String errorMessage) {

        updateRestoreStatus(
                deductRequestId,
                InventoryOperationStatus.RESTORE_UNKNOWN,
                errorMessage
        );
    }

    /**
     * 商品服务明确表示"没有找到对应的原始扣减记录"。
     *
     * 这说明这次扣减根本没有发生，本来就不需要恢复，
     * 因此把恢复状态直接收敛为 NOT_REQUIRED。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRestoreNotRequired(
            String deductRequestId,
            String reason) {

        updateRestoreStatus(
                deductRequestId,
                InventoryOperationStatus.NOT_REQUIRED,
                reason
        );
    }

    /**
     * 恢复状态更新（条件 UPDATE）。
     */
    private void updateRestoreStatus(
            String deductRequestId,
            String targetStatus,
            String errorMessage) {

        int affected = mapper.updateRestoreStatus(
                deductRequestId,
                targetStatus,
                brief(errorMessage)
        );

        if (affected == 1) {
            return;
        }

        TradeInventoryOperation current =
                requireByDeductRequestId(deductRequestId);

        if (!StringUtils.hasText(current.getRestoreRequestId())) {
            throw new IllegalStateException(
                    "尚未准备恢复操作，不能更新恢复状态");
        }

        if (Objects.equals(current.getRestoreStatus(), targetStatus)) {
            return;
        }

        throw new IllegalStateException(
                "库存恢复已经处于终态，不能覆盖："
                        + current.getRestoreStatus()
        );
    }

    /**
     * 查询记录，不存在时直接报错。
     */
    private TradeInventoryOperation requireByDeductRequestId(
            String deductRequestId) {

        TradeInventoryOperation operation =
                findByDeductRequestId(deductRequestId);

        if (operation == null) {
            throw new IllegalArgumentException(
                    "库存操作记录不存在：" + deductRequestId);
        }

        return operation;
    }

    /**
     * last_error 只有 500 个字符，
     * 异常信息过长时必须截断，否则更新会直接报错。
     */
    private String brief(String message) {

        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }

        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
