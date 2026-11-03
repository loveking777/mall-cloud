# mall-cloud 接口测试指南

> 配套文件：`api-tests.http`（用 IDEA 打开，点 ▶ 直接运行）
> 网关地址：`http://localhost:8080`

## 1. 启动清单

| 服务 | 端口 | 环境变量 | 说明 |
|---|---|---|---|
| MySQL | 3306 | — | `mall_db`、`trade_db` |
| Nacos | 8848 | — | 服务注册与发现 |
| Redis | 6379 | — | 商品缓存 |
| mall-product-service | 8081 | — | 先启动 |
| mall-user-service | 8083 | `MALL_JWT_SECRET_BASE64` | |
| mall-trade-service | 8082 | — | 依赖商品服务 |
| mall-gateway | 8080 | `MALL_JWT_SECRET_BASE64` | 与用户服务同一密钥 |

密钥示例（两个服务必须一致）：

```
MALL_JWT_SECRET_BASE64=9meH+JQDBLGMcp2vxYvWTfG5srF+SZ5dHZBRzrGlJBM=
```

## 2. 测试顺序

```
注册 → 登录（拿 token）→ 浏览商品 → 下单 → 重复下单 → 查列表/详情
     → 取消 → 重复取消 → 越权查询 → 库存不足 → 参数边界
```

## 3. 场景与预期

| # | 场景 | 请求 | 预期 |
|---|---|---|---|
| 1 | 注册 | `POST /user/register` | 201；重复注册 409 |
| 2 | 登录 | `POST /user/login` | 200，返回 `accessToken` |
| 3 | 密码错误 | `POST /user/login` | 401，统一提示"用户名或密码错误" |
| 4 | 公开浏览商品 | `GET /product` | 200，无需 token |
| 5 | 未登录下单 | `POST /orders` | 401 |
| 6 | 正常下单 | `POST /orders` + token | 201，`status=CREATED` |
| 7 | 幂等 | 同 requestId 再下单 | 201，**同一 orderNo**，库存不变 |
| 8 | 内容冲突 | 同 requestId 改数量 | 409 |
| 9 | 我的订单 | `GET /orders?pageNum=1&pageSize=10` | 200，只含自己的订单 |
| 10 | 订单详情 | `GET /orders/{orderNo}` | 200 |
| 11 | 取消 | `PUT /orders/{orderNo}/cancel` | 200，`status=CANCELLED`，库存恢复 |
| 12 | 重复取消 | 同上再调一次 | 200，仍 `CANCELLED` |
| 13 | 越权查询 | bob 查 alice 的订单 | 404（不是 403） |
| 14 | 库存不足 | 买 100 件（库存 20） | 409，订单 FAILED |
| 15 | 数量超限 | 买 999 件 | 400（参数校验） |
| 16 | 商品不存在 | productId=999999 | 404 |
| 17 | 缺 requestId | 请求体不传 | 400 |
| 18 | 重复商品 | 同一单两个相同 productId | 400 |

## 4. 数据库对账 SQL

测试时建议开一个 MySQL 会话，随时对照：

```sql
-- 订单主表
SELECT order_no, user_id, status, total_amount, request_id, create_time
FROM trade_db.mall_order
ORDER BY id DESC LIMIT 10;

-- 库存操作账本（关键！）
SELECT order_no, product_id, quantity,
       deduct_status, restore_status, LEFT(last_error, 60) AS err
FROM trade_db.trade_inventory_operation
ORDER BY id DESC LIMIT 10;

-- 商品库存
SELECT id, name, stock, status FROM mall_db.product;
```

**状态对照：**

| 表 | 值 | 含义 |
|---|---|---|
| mall_order.status | 0 | CREATED（下单成功） |
| | 1 | CANCELLED（已取消） |
| | 2 | PROCESSING（处理中，正常不会久留） |
| | 3 | FAILED（失败） |
| | 4 | COMPENSATION_PENDING（补偿未完成） |
| 账本 deduct_status | PENDING | 已记账，未确认结果 |
| | SUCCEEDED | 确定扣减成功 |
| | REJECTED | 确定没扣（库存不足等） |
| | UNKNOWN | 可能扣了（超时） |
| 账本 restore_status | NOT_REQUIRED | 无需恢复 |
| | PENDING / SUCCEEDED / FAILED / UNKNOWN | 恢复过程状态 |

## 5. 恢复任务验证（手工制造卡单）

1. 插入一条 10 分钟前的"卡住"记录：

```sql
INSERT INTO trade_db.mall_order
(order_no, request_id, request_hash, user_id, total_amount, status, create_time, update_time)
VALUES ('ORCTEST0001','recover-test-1', REPEAT('a',64), 2, 0.00, 2,
        NOW() - INTERVAL 10 MINUTE, NOW() - INTERVAL 10 MINUTE);

INSERT INTO trade_db.trade_inventory_operation
(order_no, product_id, quantity, deduct_request_id, deduct_status, restore_status, create_time, update_time)
VALUES ('ORCTEST0001', 1, 5, 'D-ORCTEST0001-1', 'PENDING', 'NOT_REQUIRED',
        NOW() - INTERVAL 10 MINUTE, NOW() - INTERVAL 10 MINUTE);
```

2. 等 60~90 秒（恢复任务默认每 60 秒跑一次）。

3. 预期：

```sql
SELECT status FROM trade_db.mall_order WHERE order_no='ORCTEST0001';           -- 3 (FAILED)
SELECT deduct_status, restore_status FROM trade_db.trade_inventory_operation
 WHERE order_no='ORCTEST0001';                                                 -- REJECTED / NOT_REQUIRED
```

4. 清理：

```sql
DELETE FROM trade_db.trade_inventory_operation WHERE order_no='ORCTEST0001';
DELETE FROM trade_db.mall_order WHERE order_no='ORCTEST0001';
```

## 6. 重置演示数据（可重复测试）

```sql
UPDATE mall_db.product SET stock = 20, status = 1 WHERE id = 1;

DELETE FROM trade_db.trade_inventory_operation;
DELETE FROM trade_db.order_item;
DELETE FROM trade_db.mall_order;
```

> 注意：`mall_user` 里的账号可以保留；重复注册会返回 409，不影响后续登录测试。

## 7. 常见问题排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 所有请求 401 | 网关和用户服务密钥不一致 / 没设环境变量 | 两个 Run Configuration 的 `MALL_JWT_SECRET_BASE64` 是否相同 |
| 404 且是网关返回 | Nacos 没注册上 / 服务没起 | Nacos 控制台看 `mall-product-service` 等实例；服务日志看 `register finished` |
| 502 / 调用商品服务失败 | 商品服务没起或注册失败 | 直连 `http://localhost:8081/product` 试一下 |
| 下单 500 且库存没变 | 商品服务连不上数据库 / Redis | 看商品服务日志 |
| 商品分页每次查库 | Redis 没起或缓存配置问题 | `GET /actuator/caches`（商品服务暴露了 caches） |
| 启动报"未配置 JWT 密钥" | 环境变量没设 | 按第 1 节设置后重启 |
| 端口占用 | 上一次没关干净 | `Get-NetTCPConnection -LocalPort 8080 -State Listen` 找到 PID 后结束 |

## 8. 关于直连服务端口

`api-tests.http` 里 5.8 演示了直连 `8082` 并手工传 `X-User-Id` 也能下单成功——这不是 bug，而是**部署边界**问题：网关只保护经过它的流量。

生产做法：
- 服务端口只监听内网 / 容器网络，不对外暴露；
- 或者服务间加内部签名（mTLS、内部 token）。

面试被问到"网关鉴权就够了吗"，这是标准答案。
