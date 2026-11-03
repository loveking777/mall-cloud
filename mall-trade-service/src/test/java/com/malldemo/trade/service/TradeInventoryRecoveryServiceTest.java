package com.malldemo.trade.service;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.trade.client.ProductClient;
import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.constant.InventoryOperationStatus;
import com.malldemo.trade.entity.MallOrder;
import com.malldemo.trade.entity.TradeInventoryOperation;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存补偿恢复任务单元测试。
 *
 * 覆盖四种情况：
 * 1. 卡在 PENDING 的记录能收敛；
 * 2. UNKNOWN 的记录能恢复成功；
 * 3. 成功订单绝不补偿；
 * 4. 恢复失败时订单不会被误判为已收敛。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeInventoryRecoveryServiceTest {

    @Mock
    private TradeInventoryOperationPersistenceService inventoryPersistence;

    @Mock
    private TradeOrderPersistenceService orderPersistence;

    @Mock
    private ProductClient productClient;

    private TradeInventoryRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new TradeInventoryRecoveryService(
                inventoryPersistence,
                orderPersistence,
                productClient
        );

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "staleMinutes", 2L);
        ReflectionTestUtils.setField(service, "batchSize", 100);
    }

    @Test
    @DisplayName("卡在 PENDING：转 UNKNOWN，商品服务无记录则收敛为 REJECTED")
    void shouldConvergeStuckPendingRecord() {

        TradeInventoryOperation op = operation(
                "ORD-R1", 1L,
                InventoryOperationStatus.PENDING,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findRecoverable(any(), anyInt()))
                .thenReturn(List.of(op));
        when(orderPersistence.findByOrderNo("ORD-R1"))
                .thenReturn(order("ORD-R1", OrderStatus.PROCESSING));
        when(inventoryPersistence.prepareRestore(anyString(), anyString()))
                .thenReturn(op);
        when(productClient.restoreStock(eq(1L), any()))
                .thenThrow(conflict());

        service.recoverStaleOperations();

        verify(inventoryPersistence)
                .markDeductUnknown(anyString(), anyString());
        verify(inventoryPersistence)
                .markRestoreNotRequired(anyString(), anyString());
        verify(inventoryPersistence)
                .markDeductRejected(anyString(), anyString());
        verify(orderPersistence).markUnfinishedFailed("ORD-R1");
    }

    @Test
    @DisplayName("UNKNOWN 且有真实扣减：恢复成功，订单收敛为 FAILED")
    void shouldRestoreUnknownDeduction() {

        TradeInventoryOperation op = operation(
                "ORD-R2", 1L,
                InventoryOperationStatus.UNKNOWN,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findRecoverable(any(), anyInt()))
                .thenReturn(List.of(op));
        when(orderPersistence.findByOrderNo("ORD-R2"))
                .thenReturn(order("ORD-R2", OrderStatus.COMPENSATION_PENDING));
        when(inventoryPersistence.prepareRestore(anyString(), anyString()))
                .thenReturn(op);
        when(productClient.restoreStock(eq(1L), any()))
                .thenReturn(ApiResponse.success(
                        new StockRestoreResponse(1L, 1, 20)));

        service.recoverStaleOperations();

        verify(inventoryPersistence).markRestoreSucceeded(anyString());
        verify(orderPersistence).markUnfinishedFailed("ORD-R2");
    }

    @Test
    @DisplayName("订单已成功：恢复任务绝不补偿")
    void shouldNotTouchSucceededOrder() {

        TradeInventoryOperation op = operation(
                "ORD-R3", 1L,
                InventoryOperationStatus.SUCCEEDED,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findRecoverable(any(), anyInt()))
                .thenReturn(List.of(op));
        when(orderPersistence.findByOrderNo("ORD-R3"))
                .thenReturn(order("ORD-R3", OrderStatus.CREATED));

        service.recoverStaleOperations();

        verify(productClient, never()).restoreStock(anyLong(), any());
        verify(orderPersistence, never()).markUnfinishedFailed(anyString());
    }

    @Test
    @DisplayName("恢复失败：记录保持待恢复，订单状态不收敛")
    void shouldKeepOrderWhenRestoreFails() {

        TradeInventoryOperation op = operation(
                "ORD-R4", 1L,
                InventoryOperationStatus.SUCCEEDED,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findRecoverable(any(), anyInt()))
                .thenReturn(List.of(op));
        when(orderPersistence.findByOrderNo("ORD-R4"))
                .thenReturn(order("ORD-R4", OrderStatus.PROCESSING));
        when(inventoryPersistence.prepareRestore(anyString(), anyString()))
                .thenReturn(op);
        when(productClient.restoreStock(eq(1L), any()))
                .thenThrow(new RuntimeException("read timeout"));

        service.recoverStaleOperations();

        verify(inventoryPersistence)
                .markRestoreUnknown(anyString(), anyString());
        verify(orderPersistence, never()).markUnfinishedFailed(anyString());
    }

    // ------------------------------------------------------------

    private static MallOrder order(String orderNo, int status) {
        MallOrder order = new MallOrder();
        order.setOrderNo(orderNo);
        order.setStatus(status);
        order.setUserId(2L);
        return order;
    }

    private static TradeInventoryOperation operation(
            String orderNo,
            Long productId,
            String deductStatus,
            String restoreStatus) {

        TradeInventoryOperation op = new TradeInventoryOperation();
        op.setOrderNo(orderNo);
        op.setProductId(productId);
        op.setQuantity(1);
        op.setDeductRequestId("D-" + orderNo + "-" + productId);
        op.setDeductStatus(deductStatus);
        op.setRestoreStatus(restoreStatus);
        return op;
    }

    private static FeignException conflict() {

        Request request = Request.create(
                Request.HttpMethod.POST,
                "http://mall-product-service/internal/products/1/stock/restore",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8
        );

        return new FeignException.Conflict(
                "原始扣减记录不存在", request, new byte[0], Map.of()
        );
    }
}
