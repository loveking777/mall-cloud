package com.malldemo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.malldemo.trade.common.OrderStatus;
import com.malldemo.trade.entity.MallOrder;
import com.malldemo.trade.entity.OrderItem;
import com.malldemo.trade.mapper.MallOrderMapper;
import com.malldemo.trade.mapper.OrderItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TradeOrderPersistenceService {

    private final MallOrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public TradeOrderPersistenceService(
            MallOrderMapper orderMapper,
            OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public MallOrder findByRequestId(String requestId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getRequestId, requestId)
        );
    }

    public MallOrder findByOrderNo(String orderNo) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getOrderNo, orderNo)
        );
    }

    public List<OrderItem> findItems(String orderNo) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
                        .orderByAsc(OrderItem::getId)
        );
    }

    /**
     * 分页查询某个用户的订单，按下单时间倒序。
     *
     * 必须带 user_id 条件：
     * 不能让调用方通过分页参数"翻"到别人的订单。
     */
    public IPage<MallOrder> pageByUserId(
            Long userId,
            long pageNum,
            long pageSize) {

        return orderMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getUserId, userId)
                        .orderByDesc(MallOrder::getCreateTime)
        );
    }

    /**
     * 单独提交 PROCESSING 订单记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createProcessingOrder(MallOrder order) {
        int affected = orderMapper.insert(order);

        if (affected != 1) {
            throw new IllegalStateException(
                    "创建订单处理记录失败"
            );
        }
    }

    /**
     * 在同一个本地事务内保存订单明细并更新订单状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeOrder(
            String orderNo,
            BigDecimal totalAmount,
            List<OrderItem> items) {

        MallOrder order = findByOrderNo(orderNo);

        if (order == null) {
            throw new IllegalStateException("订单记录不存在");
        }

        if (!Integer.valueOf(OrderStatus.PROCESSING)
                .equals(order.getStatus())) {
            throw new IllegalStateException(
                    "当前订单状态不允许完成创建"
            );
        }

        for (OrderItem item : items) {
            item.setOrderNo(orderNo);

            int affected = orderItemMapper.insert(item);
            if (affected != 1) {
                throw new IllegalStateException(
                        "保存订单明细失败"
                );
            }
        }

        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatus.CREATED);
        order.setUpdateTime(LocalDateTime.now());

        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException(
                    "更新订单状态失败"
            );
        }
    }

    /**
     * 记录失败或补偿未完成状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markStatus(String orderNo, int status) {
        MallOrder order = findByOrderNo(orderNo);

        if (order == null) {
            throw new IllegalStateException(
                    "找不到需要更新状态的订单"
            );
        }

        order.setStatus(status);
        order.setUpdateTime(LocalDateTime.now());

        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException(
                    "更新订单失败状态失败"
            );
        }
    }

    /**
     * 恢复任务使用：把仍停留在 PROCESSING / COMPENSATION_PENDING 的订单
     * 改成 FAILED。
     *
     * 条件更新，不会误改已经成功的订单。
     *
     * @return true 表示本次确实修改了订单状态
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markUnfinishedFailed(String orderNo) {

        return orderMapper.updateUnfinishedStatus(
                orderNo,
                OrderStatus.FAILED
        ) == 1;
    }

    /**
     * 取消订单：只有 CREATED 状态、且属于当前用户的订单才能被取消。
     *
     * 用一条条件 UPDATE 同时完成三件事：
     * 1. 校验归属（user_id）
     * 2. 校验状态（status = CREATED）
     * 3. 修改状态
     *
     * 并发下两次取消只有一次能成功，天然防重复。
     *
     * @return true 表示本次真的取消了
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelIfCreated(
            String orderNo,
            Long userId) {

        return orderMapper.updateStatusIfCurrent(
                orderNo,
                userId,
                OrderStatus.CREATED,
                OrderStatus.CANCELLED
        ) == 1;
    }
}
