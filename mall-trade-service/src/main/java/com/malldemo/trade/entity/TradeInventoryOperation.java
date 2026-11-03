package com.malldemo.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trade_inventory_operation")
public class TradeInventoryOperation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属订单号
     */
    private String orderNo;

    /**
     * 商品 ID
     */
    private Long productId;

    /**
     * 操作数量
     */
    private Integer quantity;

    /**
     * 库存扣减操作请求 ID
     */
    private String deductRequestId;

    /**
     * 扣减状态：
     * PENDING、SUCCEEDED、REJECTED、UNKNOWN
     */
    private String deductStatus;

    /**
     * 库存恢复操作请求 ID
     */
    private String restoreRequestId;

    /**
     * 恢复状态：
     * NOT_REQUIRED、PENDING、SUCCEEDED、FAILED、UNKNOWN
     */
    private String restoreStatus;

    /**
     * 最近一次错误信息
     */
    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}