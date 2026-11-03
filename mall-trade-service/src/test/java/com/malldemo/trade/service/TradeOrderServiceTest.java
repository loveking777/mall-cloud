package com.malldemo.trade.service;

import com.malldemo.common.ApiResponse;
import com.malldemo.product.api.dto.ProductInfoDTO;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.trade.client.ProductClient;
import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.constant.InventoryOperationStatus;
import com.malldemo.trade.dto.request.CreateOrderItemRequest;
import com.malldemo.trade.dto.request.CreateOrderRequest;
import com.malldemo.trade.dto.response.OrderCreateResponse;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradeOrderService 单元测试。
 *
 * 用 Mockito 把"商品服务"和"数据库"都替换掉，
 * 只验证下单流程本身的判断逻辑：
 * 幂等、内容冲突、库存不足、部分成功后补偿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeOrderServiceTest {

    private static final long USER_ID = 2L;

    @Mock
    private ProductClient productClient;

    @Mock
    private TradeOrderPersistenceService persistenceService;

    @Mock
    private TradeInventoryOperationPersistenceService inventoryPersistence;

    private TradeOrderService service;

    @BeforeEach
    void setUp() {
        service = new TradeOrderService(
                productClient,
                persistenceService,
                inventoryPersistence
        );
    }

    @Test
    @DisplayName("正常下单：扣减库存、保存明细、返回 CREATED")
    void shouldCreateOrder() {

        when(persistenceService.findByRequestId("req-1"))
                .thenReturn(null);
        when(productClient.getProductInfo(1L))
                .thenReturn(ApiResponse.success(product(1L, "99.90")));
        when(productClient.deductStock(eq(1L), any()))
                .thenReturn(ApiResponse.success(
                        new StockDeductResponse(1L, 2, 18)));

        MallOrder completed = order(
                "ORD-TEST-1",
                OrderStatus.CREATED,
                USER_ID,
                "hash"
        );
        completed.setTotalAmount(new BigDecimal("199.80"));

        when(persistenceService.findByOrderNo(anyString()))
                .thenReturn(completed);
        when(persistenceService.findItems(anyString()))
                .thenReturn(List.of());

        CreateOrderRequest request = request("req-1", item(1L, 2));

        OrderCreateResponse response =
                service.createOrder(USER_ID, request);

        assertEquals("ORD-TEST-1", response.getOrderNo());
        assertEquals("CREATED", response.getStatus());
        assertEquals(new BigDecimal("199.80"), response.getTotalAmount());

        verify(persistenceService).createProcessingOrder(any());
        verify(persistenceService).completeOrder(
                anyString(), any(), anyList());
        verify(inventoryPersistence).markDeductSucceeded(anyString());

        // 成功后不应该触发任何补偿
        verify(productClient, never()).restoreStock(anyLong(), any());
    }

    @Test
    @DisplayName("幂等：同一 requestId 且内容相同，直接返回原订单")
    void shouldReturnExistingOrderForSameRequest() {

        MallOrder existing = order(
                "ORD-EXIST-1",
                OrderStatus.CREATED,
                USER_ID,
                hash(USER_ID, "1:2")
        );

        when(persistenceService.findByRequestId("req-2"))
                .thenReturn(existing);
        when(persistenceService.findItems(anyString()))
                .thenReturn(List.of());

        OrderCreateResponse response = service.createOrder(
                USER_ID,
                request("req-2", item(1L, 2))
        );

        assertEquals("ORD-EXIST-1", response.getOrderNo());

        // 幂等命中时不能再走下单流程
        verify(persistenceService, never()).createProcessingOrder(any());
        verify(productClient, never()).deductStock(anyLong(), any());
    }

    @Test
    @DisplayName("同一 requestId 但内容不同：返回 409")
    void shouldRejectSameRequestWithDifferentContent() {

        MallOrder existing = order(
                "ORD-EXIST-2",
                OrderStatus.CREATED,
                USER_ID,
                hash(USER_ID, "1:2")   // 原来买 2 件
        );

        when(persistenceService.findByRequestId("req-3"))
                .thenReturn(existing);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.createOrder(
                        USER_ID,
                        request("req-3", item(1L, 3))   // 这次买 3 件
                )
        );

        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("库存不足：订单置为 FAILED，且不发起补偿")
    void shouldMarkFailedWhenStockNotEnough() {

        when(persistenceService.findByRequestId("req-4"))
                .thenReturn(null);
        when(productClient.getProductInfo(1L))
                .thenReturn(ApiResponse.success(product(1L, "10.00")));
        when(productClient.deductStock(eq(1L), any()))
                .thenThrow(conflict());

        // 商品服务 409：确定没扣，账本里这条仍是 PENDING
        TradeInventoryOperation op = operation(
                "ORD-TEST-4",
                1L,
                InventoryOperationStatus.PENDING,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findByOrderNo(anyString()))
                .thenReturn(List.of(op));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.createOrder(
                        USER_ID,
                        request("req-4", item(1L, 100))
                )
        );

        assertEquals(409, ex.getStatusCode().value());

        verify(inventoryPersistence)
                .markDeductRejected(anyString(), anyString());
        verify(persistenceService)
                .markStatus(anyString(), eq(OrderStatus.FAILED));

        // PENDING 表示请求没发出去/确定失败，不需要还库存
        verify(productClient, never()).restoreStock(anyLong(), any());
    }

    @Test
    @DisplayName("第二个商品失败：第一个商品已扣的库存要被还回去")
    void shouldCompensateSucceededItems() {

        when(persistenceService.findByRequestId("req-5"))
                .thenReturn(null);
        when(productClient.getProductInfo(anyLong()))
                .thenAnswer(invocation -> ApiResponse.success(
                        product(invocation.getArgument(0), "10.00")
                ));

        // 商品 1 扣成功，商品 2 调用异常（超时，结果未知）
        when(productClient.deductStock(eq(1L), any()))
                .thenReturn(ApiResponse.success(
                        new StockDeductResponse(1L, 1, 19)));
        when(productClient.deductStock(eq(2L), any()))
                .thenThrow(new RuntimeException("read timeout"));

        // 补偿时读到的账本：1 成功，2 结果未知
        TradeInventoryOperation op1 = operation(
                "ORD-TEST-5", 1L,
                InventoryOperationStatus.SUCCEEDED,
                InventoryOperationStatus.NOT_REQUIRED
        );
        TradeInventoryOperation op2 = operation(
                "ORD-TEST-5", 2L,
                InventoryOperationStatus.UNKNOWN,
                InventoryOperationStatus.NOT_REQUIRED
        );

        when(inventoryPersistence.findByOrderNo(anyString()))
                .thenReturn(List.of(op1, op2));

        // 商品 1 恢复成功；商品 2 商品服务说没有这笔扣减（409）
        when(productClient.restoreStock(eq(1L), any()))
                .thenReturn(ApiResponse.success(
                        new StockRestoreResponse(1L, 1, 20)));
        when(productClient.restoreStock(eq(2L), any()))
                .thenThrow(conflict());

        assertThrows(
                ResponseStatusException.class,
                () -> service.createOrder(
                        USER_ID,
                        request("req-5", item(1L, 1), item(2L, 1))
                )
        );

        // 两条都要尝试恢复
        verify(productClient).restoreStock(eq(1L), any());
        verify(productClient).restoreStock(eq(2L), any());

        verify(inventoryPersistence).markRestoreSucceeded(anyString());
        verify(inventoryPersistence)
                .markRestoreNotRequired(anyString(), anyString());
        verify(persistenceService)
                .markStatus(anyString(), eq(OrderStatus.FAILED));
    }

    @Test
    @DisplayName("取消订单：状态改成 CANCELLED 并还库存")
    void shouldCancelAndRestoreStock() {

        MallOrder created = order(
                "ORD-CANCEL-1", OrderStatus.CREATED, USER_ID, "hash");
        MallOrder cancelled = order(
                "ORD-CANCEL-1", OrderStatus.CANCELLED, USER_ID, "hash");

        when(persistenceService.findByOrderNo("ORD-CANCEL-1"))
                .thenReturn(created, cancelled);
        when(persistenceService.cancelIfCreated("ORD-CANCEL-1", USER_ID))
                .thenReturn(true);
        when(persistenceService.findItems(anyString()))
                .thenReturn(List.of());

        TradeInventoryOperation op = operation(
                "ORD-CANCEL-1", 1L,
                InventoryOperationStatus.SUCCEEDED,
                InventoryOperationStatus.NOT_REQUIRED
        );
        when(inventoryPersistence.findByOrderNo("ORD-CANCEL-1"))
                .thenReturn(List.of(op));
        when(productClient.restoreStock(eq(1L), any()))
                .thenReturn(ApiResponse.success(
                        new StockRestoreResponse(1L, 2, 20)));

        OrderCreateResponse response =
                service.cancelOrder(USER_ID, "ORD-CANCEL-1");

        assertEquals("CANCELLED", response.getStatus());
        verify(productClient).restoreStock(eq(1L), any());
        verify(inventoryPersistence).markRestoreSucceeded(anyString());
    }

    @Test
    @DisplayName("取消别人的订单：返回 404")
    void shouldNotCancelOthersOrder() {

        when(persistenceService.findByOrderNo("ORD-OTHER"))
                .thenReturn(order(
                        "ORD-OTHER", OrderStatus.CREATED, 99L, "hash"));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelOrder(USER_ID, "ORD-OTHER")
        );

        assertEquals(404, ex.getStatusCode().value());
        verify(persistenceService, never())
                .cancelIfCreated(anyString(), anyLong());
    }

    @Test
    @DisplayName("取消已失败的订单：409")
    void shouldRejectCancelOfFailedOrder() {

        MallOrder failed = order(
                "ORD-FAILED", OrderStatus.FAILED, USER_ID, "hash");

        when(persistenceService.findByOrderNo("ORD-FAILED"))
                .thenReturn(failed, failed);
        when(persistenceService.cancelIfCreated("ORD-FAILED", USER_ID))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelOrder(USER_ID, "ORD-FAILED")
        );

        assertEquals(409, ex.getStatusCode().value());
    }

    // ------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------

    private static CreateOrderRequest request(
            String requestId,
            CreateOrderItemRequest... items) {

        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(requestId);
        request.setItems(List.of(items));
        return request;
    }

    private static CreateOrderItemRequest item(Long productId, int quantity) {
        CreateOrderItemRequest item = new CreateOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private static ProductInfoDTO product(Long id, String price) {
        ProductInfoDTO dto = new ProductInfoDTO();
        dto.setId(id);
        dto.setName("商品" + id);
        dto.setPrice(new BigDecimal(price));
        dto.setStock(20);
        dto.setStatus(1);
        return dto;
    }

    private static MallOrder order(
            String orderNo,
            int status,
            long userId,
            String requestHash) {

        MallOrder order = new MallOrder();
        order.setOrderNo(orderNo);
        order.setStatus(status);
        order.setUserId(userId);
        order.setRequestHash(requestHash);
        order.setTotalAmount(new BigDecimal("99.90"));
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

    /**
     * 构造一个 feign 409 异常，模拟商品服务返回"库存不足"。
     */
    private static FeignException conflict() {

        Request request = Request.create(
                Request.HttpMethod.POST,
                "http://mall-product-service/internal/products/1/stock/deduct",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8
        );

        return new FeignException.Conflict(
                "库存不足", request, new byte[0], Map.of()
        );
    }

    /**
     * 测试里复算 requestHash，和业务代码保持同一算法。
     */
    private static String hash(long userId, String canonicalItems) {

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest((userId + "|" + canonicalItems)
                            .getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
