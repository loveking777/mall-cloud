package com.malldemo.trade.service;

import com.malldemo.common.ApiResponse;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.malldemo.product.api.dto.ProductInfoDTO;
import com.malldemo.product.api.request.StockDeductRequest;
import com.malldemo.product.api.request.StockRestoreRequest;
import com.malldemo.product.api.response.StockDeductResponse;
import com.malldemo.product.api.response.StockRestoreResponse;
import com.malldemo.trade.client.ProductClient;
import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.constant.InventoryOperationStatus;
import com.malldemo.trade.dto.request.CreateOrderItemRequest;
import com.malldemo.trade.dto.request.CreateOrderRequest;
import com.malldemo.trade.dto.response.OrderCreateResponse;
import com.malldemo.trade.dto.response.OrderItemResponse;
import com.malldemo.trade.dto.response.OrderSummaryResponse;
import com.malldemo.trade.entity.MallOrder;
import com.malldemo.trade.entity.OrderItem;
import com.malldemo.trade.entity.TradeInventoryOperation;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TradeOrderService {

    private static final Logger log =
            LoggerFactory.getLogger(TradeOrderService.class);

    /**
     * last_error 字段长度，异常信息需要截断后再落库。
     */
    private static final int MAX_ERROR_LENGTH = 500;

    private final ProductClient productClient;
    private final TradeOrderPersistenceService persistenceService;
    private final TradeInventoryOperationPersistenceService inventoryPersistence;

    public TradeOrderService(
            ProductClient productClient,
            TradeOrderPersistenceService persistenceService,
            TradeInventoryOperationPersistenceService inventoryPersistence) {
        this.productClient = productClient;
        this.persistenceService = persistenceService;
        this.inventoryPersistence = inventoryPersistence;
    }

    /**
     * 创建订单。
     *
     * 本方法不使用 @Transactional 包住整个流程，
     * 因为它会通过 Feign 调用另一个微服务：
     * 远程调用无法被本地事务回滚，长事务还会一直占着数据库连接。
     *
     * 一致性靠三样东西保证：
     * 1. 订单表的 request_id 唯一索引（幂等）
     * 2. 库存操作账本（先记账，再调用，回来盖章）
     * 3. 失败后的补偿（只恢复"可能已经扣减"的记录）
     */
    public OrderCreateResponse createOrder(
            Long userId,
            CreateOrderRequest request) {

        validateRequest(userId, request);

        String requestId = request.getRequestId().trim();
        String requestHash = calculateRequestHash(userId, request);

        // 1. 先检查这次逻辑请求是否已经处理过
        MallOrder existing =
                persistenceService.findByRequestId(requestId);

        if (existing != null) {
            return resolveExistingOrder(existing, requestHash);
        }

        // 2. 生成服务端订单号，并先保存 PROCESSING 记录
        String orderNo =
                "ORD" + UUID.randomUUID().toString().replace("-", "");

        MallOrder order = new MallOrder();
        order.setOrderNo(orderNo);
        order.setRequestId(requestId);
        order.setRequestHash(requestHash);
        // userId 来自网关校验后的 X-User-Id，不再是客户端请求体里的字段
        order.setUserId(userId);
        order.setTotalAmount(BigDecimal.ZERO.setScale(2));
        order.setStatus(OrderStatus.PROCESSING);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        try {
            persistenceService.createProcessingOrder(order);
        } catch (DuplicateKeyException ex) {
            // 并发请求可能同时通过了前面的查询。
            // 数据库唯一索引是最后一道保护。
            MallOrder concurrent =
                    persistenceService.findByRequestId(requestId);

            if (concurrent != null) {
                return resolveExistingOrder(
                        concurrent, requestHash
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "订单请求或订单号重复，请重新发起请求",
                    ex
            );
        }

        // 3. 先在内存里准备好"这次要扣哪些库存"。
        //    注意：真正落库发生在远程调用之前（见步骤 4）。
        List<TradeInventoryOperation> operations =
                prepareOperations(orderNo, request.getItems());

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

        try {
            // 4. 先记账，再调用。
            //    这样即使进程崩溃、调用超时，也能知道"原本打算扣哪件商品、扣多少"。
            inventoryPersistence.createPreparedOperations(
                    orderNo, operations
            );

            for (TradeInventoryOperation operation : operations) {

                // 4.1 查询商品，校验存在、上架、价格合法
                ApiResponse<ProductInfoDTO> productResult =
                        productClient.getProductInfo(
                                operation.getProductId()
                        );

                ProductInfoDTO product =
                        productResult == null
                                ? null
                                : productResult.getData();

                if (product == null) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "商品不存在或商品服务未返回商品数据"
                    );
                }

                if (!Objects.equals(product.getStatus(), 1)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "商品未上架，不能下单"
                    );
                }

                if (product.getPrice() == null
                        || product.getPrice().signum() < 0) {
                    throw new IllegalStateException(
                            "商品价格数据不合法"
                    );
                }

                // 4.2 远程扣减库存。
                //     请求 ID 由订单号和商品 ID 组合，长度小于 64，
                //     对同一个订单重试时必须使用相同的扣减 ID（幂等）。
                StockDeductRequest deductRequest =
                        new StockDeductRequest();

                deductRequest.setRequestId(
                        operation.getDeductRequestId()
                );
                deductRequest.setQuantity(operation.getQuantity());

                try {
                    ApiResponse<StockDeductResponse> deductResult =
                            productClient.deductStock(
                                    operation.getProductId(),
                                    deductRequest
                            );

                    if (deductResult == null
                            || deductResult.getData() == null) {
                        throw new IllegalStateException(
                                "商品服务未返回库存扣减结果"
                        );
                    }

                    // 商品服务明确回复扣减成功，账本盖章
                    inventoryPersistence.markDeductSucceeded(
                            operation.getDeductRequestId()
                    );

                } catch (FeignException ex) {
                    if (ex.status() == 409) {
                        // 明确的业务失败：库存不足、商品未上架。
                        // 该商品确定没有扣减成功，不需要补偿。
                        safeMarkDeductRejected(
                                operation.getDeductRequestId(),
                                ex
                        );
                    } else {
                        // 超时、连接失败、5xx：
                        // 请求可能已经到达商品服务，结果未知。
                        safeMarkDeductUnknown(
                                operation.getDeductRequestId(),
                                ex
                        );
                    }

                    throw ex;

                } catch (RuntimeException ex) {
                    // 本地校验失败（例如没有拿到扣减结果），
                    // 但请求已经发出，同样按"结果未知"处理。
                    safeMarkDeductUnknown(
                            operation.getDeductRequestId(),
                            ex
                    );

                    throw ex;
                }

                // 4.3 使用商品服务返回的真实价格计算金额
                BigDecimal subtotal = product.getPrice()
                        .multiply(
                                BigDecimal.valueOf(
                                        operation.getQuantity()
                                )
                        )
                        .setScale(2, RoundingMode.HALF_UP);

                OrderItem item = new OrderItem();
                item.setOrderNo(orderNo);
                item.setProductId(product.getId());
                item.setProductName(product.getName());
                item.setPrice(product.getPrice());
                item.setQuantity(operation.getQuantity());
                item.setSubtotal(subtotal);
                item.setCreateTime(LocalDateTime.now());

                orderItems.add(item);
                totalAmount = totalAmount.add(subtotal);
            }

            // 5. 所有库存扣减成功后，才保存订单明细并完成订单
            persistenceService.completeOrder(
                    orderNo,
                    totalAmount,
                    orderItems
            );

        } catch (Exception ex) {

            // 6. 任一商品扣减或订单保存失败，
            //    按账本恢复所有"可能已经成功扣减"的商品。
            boolean compensationCompleted =
                    compensateInventory(orderNo);

            int failedStatus = compensationCompleted
                    ? OrderStatus.FAILED
                    : OrderStatus.COMPENSATION_PENDING;

            try {
                persistenceService.markStatus(
                        orderNo,
                        failedStatus
                );
            } catch (Exception statusException) {
                log.error(
                        "订单失败后更新状态失败，orderNo={}",
                        orderNo,
                        statusException
                );

                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "订单处理失败，且订单状态更新未能确认，orderNo="
                                + orderNo,
                        statusException
                );
            }

            throw translateFailure(
                    ex,
                    compensationCompleted,
                    orderNo
            );
        }

        // 7. 只有完成本地订单事务后，才返回成功订单
        MallOrder completed =
                persistenceService.findByOrderNo(orderNo);

        if (completed == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "订单已处理，但未能读取订单结果，orderNo="
                            + orderNo
            );
        }

        return toResponse(completed);
    }

    /**
     * 查询请求对应的订单状态，便于验证失败和补偿结果。
     */
    public OrderCreateResponse getByRequestId(
            Long userId,
            String requestId) {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少用户身份"
            );
        }

        if (requestId == null
                || requestId.isBlank()
                || requestId.trim().length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "requestId 不合法"
            );
        }

        MallOrder order =
                persistenceService.findByRequestId(
                        requestId.trim()
                );

        // 别人的订单一律按"不存在"处理：
        // 避免攻击者通过 403/404 的差异探测某个 requestId 是否存在
        if (order == null
                || !Objects.equals(order.getUserId(), userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到对应订单"
            );
        }

        return toResponse(order);
    }

    /**
     * 我的订单列表（分页）。
     *
     * 只返回概要，不带明细；明细走详情接口，避免 N+1 查询。
     */
    public IPage<OrderSummaryResponse> listMyOrders(
            Long userId,
            long pageNum,
            long pageSize) {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少用户身份"
            );
        }

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

        return persistenceService
                .pageByUserId(userId, pageNum, pageSize)
                .convert(OrderSummaryResponse::from);
    }

    /**
     * 我的订单详情。
     *
     * 只能查询自己的订单：
     * 别人的订单一律返回 404，避免暴露订单是否存在。
     */
    public OrderCreateResponse getMyOrder(
            Long userId,
            String orderNo) {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少用户身份"
            );
        }

        if (orderNo == null
                || orderNo.isBlank()
                || orderNo.trim().length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "订单号不合法"
            );
        }

        MallOrder order =
                persistenceService.findByOrderNo(orderNo.trim());

        if (order == null
                || !Objects.equals(order.getUserId(), userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "订单不存在"
            );
        }

        return toResponse(order);
    }

    /**
     * 取消订单。
     *
     * 步骤：
     * 1. 条件更新状态（CREATED → CANCELLED），同时校验订单归属；
     * 2. 更新成功后，按库存账本把库存还回去；
     * 3. 还库存失败不影响"取消"这个业务结果：
     *    订单已经是 CANCELLED，账本会留下恢复未完成的记录，
     *    由恢复任务继续重试。
     *
     * 幂等：重复取消返回当前订单，不报错、不会重复还库存
     * （商品服务的恢复接口本身按 restoreRequestId 去重）。
     */
    public OrderCreateResponse cancelOrder(
            Long userId,
            String orderNo) {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少用户身份"
            );
        }

        if (orderNo == null
                || orderNo.isBlank()
                || orderNo.trim().length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "订单号不合法"
            );
        }

        String normalizedOrderNo = orderNo.trim();

        // 先查一次：区分"不是你的订单"和"状态不允许"
        MallOrder order =
                persistenceService.findByOrderNo(normalizedOrderNo);

        // 别人的订单一律 404，避免探测订单是否存在
        if (order == null
                || !Objects.equals(order.getUserId(), userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "订单不存在"
            );
        }

        // 条件更新：并发下只有一个请求能把 CREATED 改成 CANCELLED
        boolean cancelled = persistenceService.cancelIfCreated(
                normalizedOrderNo,
                userId
        );

        if (!cancelled) {

            MallOrder current =
                    persistenceService.findByOrderNo(normalizedOrderNo);

            // 已经取消过：返回当前状态，保证接口幂等
            if (current != null
                    && Objects.equals(
                    current.getStatus(),
                    OrderStatus.CANCELLED)) {
                return toResponse(current);
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前订单状态不允许取消"
            );
        }

        // 状态改成功后再还库存：
        // 反过来先还库存再改状态，如果改状态失败，
        // 会出现"库存已还、订单仍有效"的不一致。
        boolean restored = compensateInventory(normalizedOrderNo);

        if (!restored) {
            log.warn(
                    "订单已取消，但库存恢复未全部完成，"
                            + "交给恢复任务继续处理，orderNo={}",
                    normalizedOrderNo
            );
        }

        MallOrder cancelledOrder =
                persistenceService.findByOrderNo(normalizedOrderNo);

        if (cancelledOrder == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "订单已取消，但读取订单失败，orderNo="
                            + normalizedOrderNo
            );
        }

        return toResponse(cancelledOrder);
    }

    /**
     * 把订单明细转成"待执行的库存操作"。
     *
     * 扣减请求 ID = D-订单号-商品ID，
     * 保证同一个订单、同一件商品在重试时使用同一个 ID。
     */
    private List<TradeInventoryOperation> prepareOperations(
            String orderNo,
            List<CreateOrderItemRequest> items) {

        List<TradeInventoryOperation> operations =
                new ArrayList<>(items.size());

        for (CreateOrderItemRequest item : items) {

            TradeInventoryOperation operation =
                    new TradeInventoryOperation();

            operation.setProductId(item.getProductId());
            operation.setQuantity(item.getQuantity());
            operation.setDeductRequestId(
                    "D-" + orderNo + "-" + item.getProductId()
            );

            operations.add(operation);
        }

        return operations;
    }

    /**
     * 需要补偿的记录：
     * - SUCCEEDED：确定扣了，要还
     * - UNKNOWN：可能扣了，必须尝试还
     *
     * PENDING 表示请求还没发出去（本进程内），不需要处理。
     */
    private boolean needsRestore(TradeInventoryOperation operation) {

        String deductStatus = operation.getDeductStatus();

        return InventoryOperationStatus.SUCCEEDED.equals(deductStatus)
                || InventoryOperationStatus.UNKNOWN.equals(deductStatus);
    }

    /**
     * 按账本倒序恢复库存。
     *
     * 恢复同样是幂等的：商品服务用 restoreRequestId 去重，
     * 并对同一笔扣减只允许恢复一次。
     */
    private boolean compensateInventory(String orderNo) {

        List<TradeInventoryOperation> operations;

        try {
            operations = inventoryPersistence.findByOrderNo(orderNo);
        } catch (Exception ex) {
            log.error("读取库存操作账本失败，orderNo={}", orderNo, ex);
            return false;
        }

        boolean allCompleted = true;

        for (int i = operations.size() - 1; i >= 0; i--) {

            TradeInventoryOperation operation = operations.get(i);

            if (!needsRestore(operation)) {
                continue;
            }

            String deductRequestId = operation.getDeductRequestId();
            String restoreRequestId = "R-" + deductRequestId;

            // 先在账本上登记"准备恢复"，再真正调用。
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
                allCompleted = false;
                continue;
            }

            try {
                StockRestoreRequest restoreRequest =
                        new StockRestoreRequest();

                restoreRequest.setRequestId(restoreRequestId);
                restoreRequest.setDeductRequestId(deductRequestId);
                restoreRequest.setQuantity(operation.getQuantity());

                ApiResponse<StockRestoreResponse> result =
                        productClient.restoreStock(
                                operation.getProductId(),
                                restoreRequest
                        );

                if (result == null || result.getData() == null) {
                    safeMarkRestoreUnknown(
                            deductRequestId,
                            "商品服务未返回库存恢复结果"
                    );
                    allCompleted = false;
                } else {
                    inventoryPersistence.markRestoreSucceeded(
                            deductRequestId
                    );
                }

            } catch (FeignException ex) {
                if (ex.status() == 409) {
                    // 商品服务没有找到对应的原始扣减记录：
                    // 说明这次扣减根本没有发生，本来就不需要恢复。
                    try {
                        inventoryPersistence.markRestoreNotRequired(
                                deductRequestId,
                                brief(ex)
                        );
                    } catch (Exception stateException) {
                        log.error(
                                "标记无需恢复失败，deductRequestId={}",
                                deductRequestId,
                                stateException
                        );
                        allCompleted = false;
                    }
                } else {
                    safeMarkRestoreUnknown(
                            deductRequestId,
                            brief(ex)
                    );
                    allCompleted = false;
                }

            } catch (Exception ex) {
                safeMarkRestoreUnknown(
                        deductRequestId,
                        brief(ex)
                );
                allCompleted = false;
            }
        }

        return allCompleted;
    }

    private void safeMarkDeductRejected(
            String deductRequestId,
            Exception ex) {

        try {
            inventoryPersistence.markDeductRejected(
                    deductRequestId,
                    brief(ex)
            );
        } catch (Exception stateException) {
            log.error(
                    "标记扣减被拒绝失败，deductRequestId={}",
                    deductRequestId,
                    stateException
            );
        }
    }

    private void safeMarkDeductUnknown(
            String deductRequestId,
            Exception ex) {

        try {
            inventoryPersistence.markDeductUnknown(
                    deductRequestId,
                    brief(ex)
            );
        } catch (Exception stateException) {
            log.error(
                    "标记扣减结果未知失败，deductRequestId={}",
                    deductRequestId,
                    stateException
            );
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
        } catch (Exception stateException) {
            log.error(
                    "标记恢复结果未知失败，deductRequestId={}",
                    deductRequestId,
                    stateException
            );
        }
    }

    private String brief(Throwable ex) {

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

    private void validateRequest(
            Long userId,
            CreateOrderRequest request) {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少用户身份"
            );
        }

        if (request == null
                || request.getRequestId() == null
                || request.getRequestId().isBlank()
                || request.getRequestId().trim().length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "requestId 不能为空，且长度不能超过 64"
            );
        }

        if (request.getItems() == null
                || request.getItems().isEmpty()
                || request.getItems().size() > 20) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "一次订单必须包含 1 到 20 个商品明细"
            );
        }

        Set<Long> productIds = new HashSet<>();

        for (CreateOrderItemRequest item : request.getItems()) {

            if (item == null
                    || item.getProductId() == null
                    || item.getProductId() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "商品 ID 必须大于 0"
                );
            }

            if (item.getQuantity() == null
                    || item.getQuantity() <= 0
                    || item.getQuantity() > 100) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "商品购买数量必须在 1 到 100 之间"
                );
            }

            if (!productIds.add(item.getProductId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "同一订单不能重复提交相同商品，请先合并数量"
                );
            }
        }
    }

    /**
     * 对用户和商品清单计算稳定摘要。
     * 同样的商品即使顺序不同，摘要也相同。
     */
    private String calculateRequestHash(
            Long userId,
            CreateOrderRequest request) {

        String canonicalItems = request.getItems().stream()
                .sorted(
                        Comparator.comparing(
                                CreateOrderItemRequest::getProductId
                        )
                )
                .map(item -> item.getProductId()
                        + ":" + item.getQuantity())
                .collect(Collectors.joining(","));

        String canonical = userId
                + "|" + canonicalItems;

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            canonical.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "无法计算订单请求摘要",
                    ex
            );
        }
    }

    private OrderCreateResponse resolveExistingOrder(
            MallOrder existing,
            String requestHash) {

        if (!Objects.equals(
                existing.getRequestHash(),
                requestHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "相同 requestId 不能用于不同的下单内容"
            );
        }

        if (Objects.equals(
                existing.getStatus(),
                OrderStatus.CREATED)) {
            // 相同请求重试：返回已有订单，不再次扣库存。
            return toResponse(existing);
        }

        if (Objects.equals(
                existing.getStatus(),
                OrderStatus.PROCESSING)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "订单仍在处理中，请稍后查询"
            );
        }

        if (Objects.equals(
                existing.getStatus(),
                OrderStatus.COMPENSATION_PENDING)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "该订单仍有库存补偿待处理，请先核对库存"
            );
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "该请求此前已失败或订单已取消；不要用原 requestId 重新下单"
        );
    }

    private ResponseStatusException translateFailure(
            Exception ex,
            boolean compensationCompleted,
            String orderNo) {

        if (!compensationCompleted) {
            return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "订单创建失败，库存补偿尚未全部确认，orderNo="
                            + orderNo,
                    ex
            );
        }

        if (ex instanceof ResponseStatusException responseException) {
            return responseException;
        }

        if (ex instanceof FeignException feignException) {

            if (feignException.status() == 404) {
                return new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "商品不存在，订单创建失败",
                        ex
                );
            }

            if (feignException.status() == 409) {
                return new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "商品库存不足或库存操作冲突，订单创建失败",
                        ex
                );
            }

            return new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "商品服务调用失败，订单创建失败",
                    ex
            );
        }

        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "订单创建失败，库存已尝试补偿",
                ex
        );
    }

    private OrderCreateResponse toResponse(MallOrder order) {

        List<OrderItemResponse> items =
                persistenceService.findItems(
                                order.getOrderNo()
                        ).stream()
                        .map(item -> {
                            OrderItemResponse response =
                                    new OrderItemResponse();

                            response.setProductId(
                                    item.getProductId()
                            );
                            response.setProductName(
                                    item.getProductName()
                            );
                            response.setPrice(item.getPrice());
                            response.setQuantity(
                                    item.getQuantity()
                            );
                            response.setSubtotal(
                                    item.getSubtotal()
                            );

                            return response;
                        })
                        .toList();

        OrderCreateResponse response =
                new OrderCreateResponse();

        response.setOrderNo(order.getOrderNo());
        response.setUserId(order.getUserId());
        response.setTotalAmount(order.getTotalAmount());
        response.setStatus(
                OrderStatus.nameOf(order.getStatus())
        );
        response.setItems(items);

        return response;
    }
}
