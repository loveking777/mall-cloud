package com.malldemo.trade.dto.response;

import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.entity.MallOrder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表项：只给概要，不带商品明细。
 *
 * 列表页通常几十条，如果每条都去查 order_item，
 * 就是典型的 N+1 查询。明细留给详情接口。
 */
@Data
public class OrderSummaryResponse {

    private String orderNo;

    private BigDecimal totalAmount;

    /**
     * 状态的字符串表示：CREATED / CANCELLED / PROCESSING / FAILED / COMPENSATION_PENDING
     */
    private String status;

    private LocalDateTime createTime;

    public OrderSummaryResponse(
            String orderNo,
            BigDecimal totalAmount,
            String status,
            LocalDateTime createTime
    ) {
        this.orderNo = orderNo;
        this.totalAmount = totalAmount;
        this.status = status;
        this.createTime = createTime;
    }

    public static OrderSummaryResponse from(MallOrder order) {
        return new OrderSummaryResponse(
                order.getOrderNo(),
                order.getTotalAmount(),
                OrderStatus.nameOf(order.getStatus()),
                order.getCreateTime()
        );
    }
}
