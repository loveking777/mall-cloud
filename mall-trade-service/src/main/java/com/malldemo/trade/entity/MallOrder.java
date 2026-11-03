package com.malldemo.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("mall_order")
public class MallOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String requestId;

    private String requestHash;

    private Long userId;

    private BigDecimal totalAmount;

    /**
     * 0=CREATED
     * 1=CANCELLED
     * 2=PROCESSING
     * 3=FAILED
     * 4=COMPENSATION_PENDING
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}