package com.malldemo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.malldemo.trade.entity.TradeInventoryOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeInventoryOperationMapper
        extends BaseMapper<TradeInventoryOperation> {

    /**
     * 条件更新扣减状态。
     *
     * 只有"还没有确定结果"的记录才允许被改写：
     * SUCCEEDED 和 REJECTED 都是终态，不能被迟到的异常覆盖。
     *
     * 用一条 UPDATE 完成"判断 + 修改"，
     * 避免"先查询再更新"在并发下丢更新。
     */
    @Update("""
            UPDATE trade_inventory_operation
            SET deduct_status = #{targetStatus},
                last_error = #{errorMessage},
                update_time = NOW()
            WHERE deduct_request_id = #{deductRequestId}
              AND deduct_status NOT IN ('SUCCEEDED', 'REJECTED')
            """)
    int updateDeductStatus(
            @Param("deductRequestId") String deductRequestId,
            @Param("targetStatus") String targetStatus,
            @Param("errorMessage") String errorMessage
    );

    /**
     * 条件更新恢复状态。
     *
     * 恢复成功是终态；并且必须先调用过 prepareRestore
     * （restore_request_id 非空）才允许更新恢复状态。
     */
    @Update("""
            UPDATE trade_inventory_operation
            SET restore_status = #{targetStatus},
                last_error = #{errorMessage},
                update_time = NOW()
            WHERE deduct_request_id = #{deductRequestId}
              AND restore_status <> 'SUCCEEDED'
              AND restore_request_id IS NOT NULL
            """)
    int updateRestoreStatus(
            @Param("deductRequestId") String deductRequestId,
            @Param("targetStatus") String targetStatus,
            @Param("errorMessage") String errorMessage
    );

    /**
     * 条件登记"准备恢复"。
     *
     * 只有扣减成功（SUCCEEDED）或结果未知（UNKNOWN）的记录才需要恢复；
     * 同一笔扣减的恢复请求 ID 必须保持稳定。
     */
    @Update("""
            UPDATE trade_inventory_operation
            SET restore_request_id = #{restoreRequestId},
                restore_status = 'PENDING',
                last_error = NULL,
                update_time = NOW()
            WHERE deduct_request_id = #{deductRequestId}
              AND deduct_status IN ('SUCCEEDED', 'UNKNOWN')
              AND restore_status <> 'SUCCEEDED'
              AND (restore_request_id IS NULL
                   OR restore_request_id = #{restoreRequestId})
            """)
    int prepareRestore(
            @Param("deductRequestId") String deductRequestId,
            @Param("restoreRequestId") String restoreRequestId
    );

    /**
     * 找出需要恢复任务处理的行。
     *
     * 覆盖三种情况：
     * 1. 扣减状态长时间停在 PENDING / UNKNOWN（结果不确定）；
     * 2. 扣减成功但还没有恢复成功的（订单失败后没来得及还）；
     * 3. 恢复过程本身失败 / 超时（恢复也是远程调用，也会失败）。
     *
     * 用 update_time 做"冷却时间"：每次重试都会刷新 update_time，
     * 天然形成退避，不会每秒疯狂重试。
     */
    @Select("""
            SELECT *
            FROM trade_inventory_operation
            WHERE update_time < #{staleBefore}
              AND (
                    (deduct_status IN ('PENDING', 'UNKNOWN')
                     AND restore_status <> 'SUCCEEDED')
                 OR restore_status IN ('PENDING', 'FAILED', 'UNKNOWN')
              )
            ORDER BY id
            LIMIT #{limit}
            """)
    List<TradeInventoryOperation> findRecoverable(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit
    );
}
