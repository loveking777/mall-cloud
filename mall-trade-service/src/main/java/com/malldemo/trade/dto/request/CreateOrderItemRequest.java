package com.malldemo.trade.dto.request;

import lombok.Data;

@Data
public class CreateOrderItemRequest {

    private Long productId;

    private Integer quantity;
}