package com.malldemo.trade.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderCreateResponse {

    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    private String status;

    private List<OrderItemResponse> items;
}