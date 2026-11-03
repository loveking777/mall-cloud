package com.malldemo.trade.service;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.request.StockRestoreRequest;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.trade.client.ProductClient;
import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.constant.InventoryOperationStatus;
import com.malldemo.trade.entity.MallOrder;
import com.malldemo.trade.entity.TradeInventoryOperation;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 库存补偿恢复任务。
 *
 * 下单失败时，交易服务会尽力把已经扣掉的库存还回去；
 * 但补偿本身也是远程调用，可能失败、超时，甚至进程直接挂掉。
 * 这个任务负责"兜底"：定时扫描账本，把没收敛的记录继续处理。
 *
 * 设计要点：
 * 1. 幂等：恢复用固定的 R-扣减ID，商品服务会去重，重复调用安全；
 * 2. 退避：每次处理都会刷新 update_time，配合 update_time < 阈值，
 *    天然做到"处理过一次，至少隔一段时间才再处理"；
 * 3. 不碰成功订单：订单状态是 CREATED 的直接跳过；
 * 4. 收敛订单状态：账本全部处理完，才把卡住的订单改成 FAILED。
 */
@Service
public class TradeInventoryRecoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(TradeInventoryRecoveryService.class);

    private static final int MAX_ERROR_LENGTH = 500;

    private final TradeInventoryOperationPersistenceService inventoryPersistence;
    private final TradeOrderPersistenceService orderPersistence;
    private final ProductClient productClient;

    @Value("${mall.inventory.recovery.enabled:true}")
    private boolean enabled;

    @Value("${mall.inventory.recovery.stale-minutes:2}")
    private long staleMinutes;

    @Value("${mall.inventory.recovery.batch-size:100}")
    private int batchSize;

    public TradeInventoryRecoveryService(
            TradeInventoryOperationPersistenceService inventoryPersistence,
            TradeOrderPersistenceService orderPersistence,
            ProductClient productClient) {
        this.inventoryPersistence = inventoryPersistence;
        this.orderPersistence = orderPersistence;
        this.productClient = productClient;
    }

    /**
     * 每隔一段时间执行一次。
     *
     * fixedDelay 表示"上一次执行结束后，再等 interval"，
     * 避免任务重叠执行。
     */
    @Scheduled(
            fixedDelayString =
                    "${mall.inventory.recovery.interval-ms:60000}"
    )
    public void recoverStaleOperations() {

        if (!enabled) {
            return;
        }

        LocalDateTime staleBefore = LocalDateTime.now()
                .minusMinutes(staleMinutes);

        List<TradeInventoryOperation> operations;

        try {
            operations = inventoryPersistence.findRecoverable(
                    staleBefore,
                    batchSize
            );
        } catch (Exception ex) {
            log.error("扫描待恢复库存记录失败", ex);
            return;
        }

        if (operations.isEmpty()) {
            return;
        }

        log.info("发现 {} 条待恢复库存记录", operations.size());

        // 同一笔订单的记录放在一起处理，最后统一收敛订单状态
        Map<String, List<TradeInventoryOperation>> byOrder =
                operations.stream().collect(
                        Collectors.groupingBy(
                                TradeInventoryOperation::getOrderNo,
                                LinkedHashMap::new,
                                Collectors.toList()
                        )
                );

        for (Map.Entry<String, List<TradeInventoryOperation>> entry
                : byOrder.entrySet()) {

            try {
                recoverOrder(entry.getKey(), entry.getValue());
            } catch (Exception ex) {
                log.error(
                        "恢复订单库存失败，orderNo={}",
                        entry.getKey(),
                        ex
                );
            }
        }
    }

    private void recoverOrder(
            String orderNo,
            List<TradeInventoryOperation> operations) {

        MallOrder order;

        try {
            order = orderPersistence.findByOrderNo(orderNo);
        } catch (Exception ex) {
            log.error("查询订单失败，orderNo={}", orderNo, ex);
            return;
        }

        if (order == null) {
            log.warn("库存账本对应的订单不存在，orderNo={}", orderNo);
            return;
        }

        // 订单已经成功，说明库存扣减是有效的，绝不能补偿
        if (Objects.equals(order.getStatus(), OrderStatus.CREATED)) {
            return;
        }

        boolean allSettled = true;

        // 倒序处理，和下单失败时的补偿顺序保持一致
        for (int i = operations.size() - 1; i >= 0; i--) {

            if (!settle(operations.get(i))) {
                allSettled = false;
            }
        }

        if (!allSettled) {
            return;
        }

        boolean unfinished =
                Objects.equals(order.getStatus(), OrderStatus.PROCESSING)
                        || Objects.equals(
                        order.getStatus(),
                        OrderStatus.COMPENSATION_PENDING
                );

        if (unfinished && orderPersistence.markUnfinishedFailed(orderNo)) {
            log.info(
                    "订单库存已全部收敛，订单置为 FAILED，orderNo={}",
                    orderNo
            );
        }
    }

    /**
     * 处理单条库存操作。
     *
     * @return true 表示这条记录已经处理完毕（不需要再管）
     */
    private boolean settle(TradeInventoryOperation operation) {

        String deductStatus = operation.getDeductStatus();

        // 明确被拒绝：没有扣成功，不需要恢复
        if (InventoryOperationStatus.REJECTED.equals(deductStatus)) {
            return true;
        }

        // 长时间停在 PENDING：说明当时没拿到结果，
        // 按"结果未知"处理，交给后面的恢复流程判定
        if (InventoryOperationStatus.PENDING.equals(deductStatus)) {

            try {
                inventoryPersistence.markDeductUnknown(
                        operation.getDeductRequestId(),
                        "超过 " + staleMinutes + " 分钟未确认扣减结果"
                );
            } catch (Exception ex) {
                log.error(
                        "把超时 PENDING 转为 UNKNOWN 失败，deductRequestId={}",
                        operation.getDeductRequestId(),
                        ex
                );
                return false;
            }
        }

        if (InventoryOperationStatus.RESTORE_SUCCEEDED.equals(
                operation.getRestoreStatus())) {
            return true;
        }

        return restore(operation);
    }

    /**
     * 执行一次库存恢复。
     *
     * 商品服务的恢复接口是幂等的：
     * 同一个 restoreRequestId 重复调用会返回第一次的结果。
     */
    private boolean restore(TradeInventoryOperation operation) {

        String deductRequestId = operation.getDeductRequestId();
        String restoreRequestId = "R-" + deductRequestId;

        try {
            inventoryPersistence.prepareRestore(
                    deductRequestId,
                    restoreRequestId
            );
        } catch (Exception ex) {
            log.error(
                    "准备库存恢复失败，deductRequestId={}",
                    deductRequestId,
                    ex
            );
            return false;
        }

        try {
            StockRestoreRequest request = new StockRestoreRequest();
            request.setRequestId(restoreRequestId);
            request.setDeductRequestId(deductRequestId);
            request.setQuantity(operation.getQuantity());

            ApiResponse<StockRestoreResponse> result =
                    productClient.restoreStock(
                            operation.getProductId(),
                            request
                    );

            if (result == null || result.getData() == null) {
                safeMarkRestoreUnknown(
                        deductRequestId,
                        "商品服务未返回库存恢复结果"
                );
                return false;
            }

            inventoryPersistence.markRestoreSucceeded(deductRequestId);
            return true;

        } catch (FeignException ex) {

            if (ex.status() == 409) {
                // 商品服务找不到原始扣减记录：
                // 说明当时这笔扣减并没有真正发生。
                try {
                    inventoryPersistence.markRestoreNotRequired(
                            deductRequestId,
                            brief(ex)
                    );
                } catch (Exception stateException) {
                    log.error(
                            "更新恢复状态失败，deductRequestId={}",
                            deductRequestId,
                            stateException
                    );
                    return false;
                }

                // 把扣减状态也收敛为 REJECTED，
                // 否则它会一直满足"待恢复"的查询条件，导致反复扫描。
                try {
                    inventoryPersistence.markDeductRejected(
                            deductRequestId,
                            "恢复时未找到原始扣减记录，判定为未扣减"
                    );
                } catch (Exception stateException) {
                    log.warn(
                            "扣减状态无法收敛为 REJECTED，deductRequestId={}",
                            deductRequestId,
                            stateException
                    );
                }

                return true;
            }

            safeMarkRestoreUnknown(deductRequestId, brief(ex));
            return false;

        } catch (Exception ex) {
            safeMarkRestoreUnknown(deductRequestId, brief(ex));
            return false;
        }
    }

    private void safeMarkRestoreUnknown(
            String deductRequestId,
            String message) {

        try {
            inventoryPersistence.markRestoreUnknown(
                    deductRequestId,
                    brief(message)
            );
        } catch (Exception ex) {
            log.error(
                    "标记恢复结果未知失败，deductRequestId={}",
                    deductRequestId,
                    ex
            );
        }
    }

    private String brief(Exception ex) {

        String message = ex.getMessage();

        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }

        return brief(message);
    }

    private String brief(String message) {

        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }

        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
