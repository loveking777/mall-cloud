package com.malldemo.trade.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    /**
     * 同一次下单重试时必须保持不变。
     *
     * 它是幂等键：同一个 requestId 重复提交，只会生成一笔订单。
     */
    private String requestId;

    /**
     * 注意：这里没有 userId。
     *
     * 当前用户 identity 由网关校验 JWT 后通过 X-User-Id 请求头传入，
     * 客户端无法伪造（网关注入前会先删除同名请求头）。
     */
    private List<CreateOrderItemRequest> items;
}
