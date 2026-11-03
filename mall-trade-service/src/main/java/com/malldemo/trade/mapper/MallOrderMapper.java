package com.malldemo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.malldemo.trade.entity.MallOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MallOrderMapper extends BaseMapper<MallOrder> {

    /**
     * 把"没有正常结束"的订单改成失败状态。
     *
     * 只允许从 PROCESSING(2) 或 COMPENSATION_PENDING(4) 迁移，
     * 防止恢复任务把已经 CREATED(0) 或 CANCELLED(1) 的订单改掉。
     */
    @Update("""
            UPDATE mall_order
            SET status = #{targetStatus},
                update_time = NOW()
            WHERE order_no = #{orderNo}
              AND status IN (2, 4)
            """)
    int updateUnfinishedStatus(
            @Param("orderNo") String orderNo,
            @Param("targetStatus") int targetStatus
    );

    /**
     * 把订单从指定状态原子地改成目标状态。
     *
     * 取消订单用它：
     * WHERE status = 0（CREATED）保证"只有待处理的订单能取消"，
     * 并且 user_id 也进条件，避免越权改别人的订单。
     *
     * 返回 0 表示：订单不存在、不属于该用户、或状态已经变了。
     */
    @Update("""
            UPDATE mall_order
            SET status = #{targetStatus},
                update_time = NOW()
            WHERE order_no = #{orderNo}
              AND user_id = #{userId}
              AND status = #{expectedStatus}
            """)
    int updateStatusIfCurrent(
            @Param("orderNo") String orderNo,
            @Param("userId") Long userId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus
    );
}
