package com.malldemo.trade.controller;

import com.malldemo.common.ApiResponse;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.malldemo.trade.dto.request.CreateOrderRequest;
import com.malldemo.trade.dto.response.OrderCreateResponse;
import com.malldemo.trade.dto.response.OrderSummaryResponse;
import com.malldemo.trade.service.TradeOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class TradeOrderController {

    /**
     * 网关校验 JWT 后写入的用户 ID 请求头。
     *
     * 直连 8082 时也可以手工传这个头（本地调试），
     * 但生产环境只应通过网关访问，不能把服务端口暴露出去。
     */
    private static final String USER_ID_HEADER = "X-User-Id";

    private final TradeOrderService tradeOrderService;

    public TradeOrderController(
            TradeOrderService tradeOrderService) {
        this.tradeOrderService = tradeOrderService;
    }

    /**
     * 创建订单。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderCreateResponse> createOrder(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @RequestBody CreateOrderRequest request) {

        return ApiResponse.success(
                tradeOrderService.createOrder(userId, request)
        );
    }

    /**
     * 我的订单列表（分页）。
     */
    @GetMapping
    public ApiResponse<IPage<OrderSummaryResponse>> listMyOrders(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {

        return ApiResponse.success(
                tradeOrderService.listMyOrders(
                        userId, pageNum, pageSize
                )
        );
    }

    /**
     * 我的订单详情。
     */
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderCreateResponse> getMyOrder(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @PathVariable String orderNo) {

        return ApiResponse.success(
                tradeOrderService.getMyOrder(userId, orderNo)
        );
    }

    /**
     * 取消订单。
     *
     * 只有 CREATED 状态的订单可以取消；
     * 重复取消会返回当前订单（幂等）。
     */
    @PutMapping("/{orderNo}/cancel")
    public ApiResponse<OrderCreateResponse> cancelOrder(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @PathVariable String orderNo) {

        return ApiResponse.success(
                tradeOrderService.cancelOrder(userId, orderNo)
        );
    }

    /**
     * 通过幂等请求 ID 查询订单状态。
     *
     * 只能查自己的订单：userId 必须匹配，否则返回 404。
     */
    @GetMapping("/by-request/{requestId}")
    public ApiResponse<OrderCreateResponse> getByRequestId(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @PathVariable String requestId) {

        return ApiResponse.success(
                tradeOrderService.getByRequestId(userId, requestId)
        );
    }
}
