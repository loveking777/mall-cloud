-- ============================================================
-- mall-cloud 数据库初始化脚本
--
-- 包含两个库：
--   mall_db  : 用户 + 商品 + 库存流水
--   trade_db : 订单 + 订单明细 + 库存操作账本
--
-- 设计说明：
-- 1. 两个库分开，对应"每个服务只访问自己的数据"；
-- 2. 所有唯一索引都是幂等的基础，不要删；
-- 3. 统一 charset=utf8mb4 / collation=utf8mb4_0900_ai_ci，
--    避免跨表 JOIN 时出现 Illegal mix of collations；
-- 4. trade_db.mall_order.request_id / request_hash 设为 NOT NULL：
--    唯一索引对 NULL 不生效，允许 NULL 会让幂等出现漏洞。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `mall_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `trade_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- ============================================================
-- mall_db：用户
-- ============================================================

USE `mall_db`;

CREATE TABLE IF NOT EXISTS `mall_user`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`      varchar(50)  NOT NULL COMMENT '用户名',
    `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 密码哈希值',
    `nickname`      varchar(50) DEFAULT NULL COMMENT '昵称',
    `status`        tinyint      NOT NULL DEFAULT '1' COMMENT '1=正常，0=禁用',
    `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 防止并发注册出现同名用户
    UNIQUE KEY `uk_mall_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商城用户表';

-- ============================================================
-- mall_db：商品
-- ============================================================

CREATE TABLE IF NOT EXISTS `product`
(
    `id`          bigint         NOT NULL AUTO_INCREMENT,
    `name`        varchar(100)   NOT NULL,
    `description` varchar(500) DEFAULT NULL,
    `price`       decimal(10, 2) NOT NULL,
    `stock`       int            NOT NULL DEFAULT '0',
    `status`      tinyint        NOT NULL DEFAULT '1' COMMENT '1=上架，0=下架',
    `create_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品表';

-- ============================================================
-- mall_db：库存操作流水（商品服务侧的幂等表）
-- ============================================================

CREATE TABLE IF NOT EXISTS `product_stock_operation`
(
    `id`                   bigint      NOT NULL AUTO_INCREMENT,
    `request_id`           varchar(64) NOT NULL COMMENT '本次库存操作的唯一请求ID',
    `reference_request_id` varchar(64) DEFAULT NULL COMMENT '恢复操作对应的原始扣减请求ID',
    `operation_type`       varchar(16) NOT NULL DEFAULT 'DEDUCT' COMMENT 'DEDUCT / RESTORE',
    `product_id`           bigint      NOT NULL,
    `quantity`             int         NOT NULL,
    `remaining_stock`      int         NOT NULL COMMENT '操作后的剩余库存',
    `create_time`          datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 同一个请求ID只能执行一次：重复请求直接返回第一次的结果
    UNIQUE KEY `uk_request_id` (`request_id`),
    -- 同一笔扣减只能被恢复一次
    UNIQUE KEY `uk_stock_restore_reference` (`reference_request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='库存操作流水表';

-- ============================================================
-- trade_db：订单
-- ============================================================

USE `trade_db`;

CREATE TABLE IF NOT EXISTS `mall_order`
(
    `id`           bigint         NOT NULL AUTO_INCREMENT,
    `order_no`     varchar(64)    NOT NULL,
    `request_id`   varchar(64)    NOT NULL COMMENT '客户端幂等键',
    `request_hash` char(64)       NOT NULL COMMENT '请求内容摘要，用于判断同一 requestId 是否同一份内容',
    `user_id`      bigint         NOT NULL,
    `total_amount` decimal(12, 2) NOT NULL,
    `status`       tinyint        NOT NULL DEFAULT '0' COMMENT '0=CREATED,1=CANCELLED,2=PROCESSING,3=FAILED,4=COMPENSATION_PENDING',
    `create_time`  datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 幂等核心：同一个 requestId 只能生成一笔订单
    UNIQUE KEY `uk_order_request_id` (`request_id`),
    KEY `idx_user_create_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单表';

-- ============================================================
-- trade_db：订单明细（商品名称/价格为下单时快照）
-- ============================================================

CREATE TABLE IF NOT EXISTS `order_item`
(
    `id`           bigint         NOT NULL AUTO_INCREMENT,
    `order_no`     varchar(64)    NOT NULL,
    `product_id`   bigint         NOT NULL,
    `product_name` varchar(255)   NOT NULL,
    `price`        decimal(12, 2) NOT NULL,
    `quantity`     int            NOT NULL,
    `subtotal`     decimal(12, 2) NOT NULL,
    `create_time`  datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_product_id` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单明细表';

-- ============================================================
-- trade_db：库存操作账本（交易服务侧）
--
-- 与商品服务的 product_stock_operation 是两层记录：
--   trade_inventory_operation  记录"我这笔订单打算扣什么、结果如何"
--   product_stock_operation    记录"商品服务实际执行了什么"
-- 两者通过 deduct_request_id 关联。
-- ============================================================

CREATE TABLE IF NOT EXISTS `trade_inventory_operation`
(
    `id`                 bigint      NOT NULL AUTO_INCREMENT,
    `order_no`           varchar(64) NOT NULL,
    `product_id`         bigint      NOT NULL,
    `quantity`           int         NOT NULL,
    `deduct_request_id`  varchar(64) NOT NULL COMMENT '扣减库存的请求ID',
    `deduct_status`      varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCEEDED/REJECTED/UNKNOWN',
    `restore_request_id` varchar(64) DEFAULT NULL COMMENT '恢复库存的请求ID',
    `restore_status`     varchar(16) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'NOT_REQUIRED/PENDING/SUCCEEDED/FAILED/UNKNOWN',
    `last_error`         varchar(500) DEFAULT NULL COMMENT '最近一次错误信息',
    `create_time`        datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 同一个扣减请求只能有一条账本记录
    UNIQUE KEY `uk_trade_inventory_deduct_request_id` (`deduct_request_id`),
    -- 同一订单同一商品只允许一条记录
    UNIQUE KEY `uk_trade_inventory_order_product` (`order_no`, `product_id`),
    -- 恢复任务按"状态 + 更新时间"扫描待处理记录
    KEY `idx_trade_inventory_recovery` (`deduct_status`, `restore_status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='库存操作账本';
