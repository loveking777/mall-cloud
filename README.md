# mall-cloud 商城微服务项目

[![CI](https://github.com/loveking777/mall-cloud/actions/workflows/ci.yml/badge.svg)](https://github.com/loveking777/mall-cloud/actions/workflows/ci.yml)

一个基于 Spring Cloud Alibaba 的商城后端，覆盖 **用户认证、商品管理、下单、订单查询、取消订单、库存扣减与恢复** 的完整闭环。

项目最初是单体应用（`mall-demo`），后按业务域拆分为微服务。本文档描述**当前实际实现**，包括已知限制和后续计划。

---

## 1. 架构

```
                       客户端
                         │
                 ┌───────▼────────┐
                 │  mall-gateway   │  8080   路由 + JWT 校验 + 注入 X-User-Id
                 └──┬─────┬─────┬──┘
        /user/**    │     │ /product/**   │ /orders/**
              ┌─────▼┐  ┌─▼─────────┐  ┌─▼──────────┐
              │ user │  │ product   │  │   trade    │
              │ 8083 │  │ 8081      │  │   8082     │
              └───┬──┘  └─┬─────────┘  └─┬──────────┘
                  │       │  ▲            │
              mall_db  mall_db│ OpenFeign  trade_db
                        Redis │ /internal/**  + 定时恢复任务
```

| 服务 | 端口 | 职责 | 数据库 |
|---|---|---|---|
| mall-gateway | 8080 | 统一入口、JWT 校验、身份注入、路由 | — |
| mall-user-service | 8083 | 注册、登录、签发 JWT | mall_db（mall_user） |
| mall-product-service | 8081 | 商品 CRUD、分页缓存、库存扣减/恢复 | mall_db（product、product_stock_operation） |
| mall-trade-service | 8082 | 下单、订单查询、取消、库存账本、补偿恢复 | trade_db（mall_order、order_item、trade_inventory_operation） |

**服务之间不共享表**：交易服务只能通过 OpenFeign 调用商品服务的 `/internal/**` 接口，不能直接查商品表。

---

## 2. 技术栈

| 类别 | 选型 |
|---|---|
| JDK | Java 17 |
| 框架 | Spring Boot 4.0.9 |
| 微服务 | Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0 |
| 网关 | Spring Cloud Gateway 5.0.4（WebFlux） |
| 服务发现 | Nacos |
| 远程调用 | OpenFeign + Spring Cloud LoadBalancer |
| 持久层 | MyBatis-Plus 3.5.17 |
| 数据库 | MySQL 8 |
| 缓存 | Redis（Spring Cache） |
| 鉴权 | JWT（JJWT 0.13.0）+ BCrypt |
| 构建 | Maven 多模块 |

> 注意：Boot 4 / Cloud 2025 的依赖坐标和旧教程不同，例如
> `spring-boot-starter-webmvc`（不是 `spring-boot-starter-web`）、
> `spring-cloud-starter-gateway-server-webflux`（不是 `spring-cloud-starter-gateway`）。

---

## 3. 目录结构

```
mall-cloud
├── pom.xml                      父工程：统一版本与模块
├── mall-common/                 统一响应 ApiResponse、BusinessException、全局异常处理
├── mall-product-api/            服务间契约：商品/库存的 DTO 与请求响应体
├── mall-user-service/           用户服务
├── mall-product-service/        商品与库存服务
├── mall-trade-service/          订单与库存账本服务
├── mall-gateway/                网关
├── db/
│   ├── schema.sql               建库建表脚本（含全部唯一索引）
│   └── seed.sql                 演示商品数据
├── docker/
│   └── Dockerfile               通用应用镜像（通过 JAR_FILE 参数复用）
├── docker-compose.yml           MySQL / Redis / Nacos + 可选的应用服务
├── .env.example                 JWT 密钥示例
└── docs/
    ├── api-tests.http           IDEA HTTP Client 测试集（可点着跑）
    └── api-test-guide.md        测试场景、状态码、SQL 对账、排错
```

---

## 4. 数据库设计

### mall_db（用户 + 商品）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `mall_user` | id, username, password_hash, nickname, status | `uk_mall_user_username` |
| `product` | id, name, description, price, stock, status | status：1 上架 / 0 下架 |
| `product_stock_operation` | request_id, product_id, quantity, remaining_stock, operation_type, reference_request_id | 库存操作流水，`uk_request_id`、`uk_stock_restore_reference` |

### trade_db（订单）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `mall_order` | order_no, request_id, request_hash, user_id, total_amount, status | `uk_order_no`、`uk_order_request_id`、`idx_user_create_time` |
| `order_item` | order_no, product_id, product_name, price, quantity, subtotal | 下单时的商品快照 |
| `trade_inventory_operation` | order_no, product_id, quantity, deduct_request_id, deduct_status, restore_request_id, restore_status, last_error | 库存操作账本，`uk_trade_inventory_deduct_request_id` 等 |

### 唯一索引为什么重要

幂等最终依赖数据库唯一索引。应用层的"先查再插"在并发下一定会漏，只有唯一约束能保证"同一请求只成功一次"：

- `trade_inventory_operation.deduct_request_id`：同一个扣减请求只执行一次；
- `mall_order.request_id`：同一个下单请求只生成一笔订单。

### 订单状态

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | CREATED | 下单成功 |
| 1 | CANCELLED | 已取消 |
| 2 | PROCESSING | 处理中（正常只会短暂存在） |
| 3 | FAILED | 创建失败 |
| 4 | COMPENSATION_PENDING | 失败且库存补偿未完成 |

### 库存账本状态

| 字段 | 值 | 含义 |
|---|---|---|
| deduct_status | PENDING | 已记账，尚未确认扣减结果 |
| | SUCCEEDED | 确定扣减成功 |
| | REJECTED | 确定没有扣减（库存不足、商品下架） |
| | UNKNOWN | 可能扣了（超时/5xx，结果不确定） |
| restore_status | NOT_REQUIRED | 不需要恢复 |
| | PENDING / SUCCEEDED / FAILED / UNKNOWN | 恢复过程状态 |

---

## 5. 核心设计

### 5.1 防超卖：一条 SQL 完成判断与扣减

```sql
UPDATE product
SET stock = stock - #{quantity}
WHERE id = #{productId}
  AND status = 1
  AND stock >= #{quantity}
```

受影响的不是 1 行，就说明库存不足或商品不可售。把"判断"和"扣减"放进同一条语句，由数据库行锁保证原子性，而不是先 `select` 再 `update`（那样一定会超卖）。

### 5.2 幂等：三层保护

1. **业务层**：`mall_order.request_id` 唯一索引 + `request_hash` 校验内容是否一致；
2. **请求 ID 确定性生成**：扣减 ID 固定为 `D-{orderNo}-{productId}`，重试时天然复用；
3. **流水表**：`product_stock_operation.request_id` 唯一，重复扣减直接返回第一次结果。

### 5.3 跨服务一致性：本地事务 + 库存账本 + 补偿 + 定时恢复

下单流程**不用一个大事务包住远程调用**（远程调用无法回滚，长事务还会占着数据库连接），而是：

```
1. 校验参数、计算 requestHash
2. 查 request_id，命中直接返回（幂等）
3. 落 PROCESSING 订单（占住 requestId）
4. 落库存账本：每条明细一条 PENDING 记录   ← 先记账，再调用
5. 逐条：查商品 → 扣库存 → 按结果盖章
      成功   → SUCCEEDED
      409    → REJECTED
      超时   → UNKNOWN
6. 全部成功：本地事务写订单明细 + 状态改 CREATED
7. 任一步失败：按账本倒序补偿（只恢复 SUCCEEDED / UNKNOWN）
      全部还清 → FAILED；没还清 → COMPENSATION_PENDING
```

**为什么先记账**：如果先调用再记账，一旦超时，连"原本要扣哪件、扣多少"都不知道，事后无法对账。

**定时恢复任务**（`TradeInventoryRecoveryService`）兜底：

- 每 60 秒扫描一次 `update_time` 超过 2 分钟、且未收敛的记录；
- `PENDING` 超时 → 视为 `UNKNOWN`；
- 恢复失败/超时 → 保留状态，下一轮重试（复用同一个 restore 请求 ID，幂等）；
- 全部收敛后，把卡住的 `PROCESSING / COMPENSATION_PENDING` 订单改成 `FAILED`；
- **订单已是 CREATED 的直接跳过，绝不补偿成功订单**。

### 5.4 鉴权：网关统一校验

- 网关校验 `Authorization: Bearer <token>`，验签通过后向下游注入 `X-User-Id`、`X-Username`；
- **注入前先删除客户端自带的同名请求头**，防止伪造；
- 公开接口：`POST /user/register`、`POST /user/login`、`GET /product/**`；
- 交易服务从 `X-User-Id` 取当前用户，查询/取消都校验订单归属，别人的订单统一返回 404（避免探测订单是否存在）。

### 5.5 缓存

商品详情、商品分页走 Redis 缓存，TTL 1 分钟；写操作与库存变更时失效。

### 5.6 统一响应与异常

所有服务返回同一结构：

```json
{ "code": 200, "message": "success", "data": {} }
```

`BusinessException` + `GlobalExceptionHandler` 放在 `mall-common`，三个 Web 服务通过 `scanBasePackages` 引入；兜底处理会识别 Spring 的 `ErrorResponse`，保留 404/400/405 的原始语义，而不是一律 500。

---

## 6. 快速开始

### 6.1 前置条件

- JDK 17、Maven 3.9+
- MySQL 8（需提前建好 `mall_db`、`trade_db` 和上述表）
- Redis
- Nacos（默认 `localhost:8848`）

### 6.2 配置 JWT 密钥

用户服务签发、网关校验，**两者必须使用同一个密钥**（Base64，解码后至少 32 字节）：

```powershell
$b = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

在用户服务和网关的运行环境里设置：

```
MALL_JWT_SECRET_BASE64=<上一步生成的值>
```

### 6.3 构建与启动

```bash
mvn -DskipTests package
```

启动顺序（IDEA 里直接运行各启动类也可以）：

```
mall-product-service  →  mall-user-service  →  mall-trade-service  →  mall-gateway
```

### 6.4 冒烟测试

```bash
# 注册
curl -X POST http://localhost:8080/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123","nickname":"Alice"}'

# 登录（拿到 accessToken）
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'

# 浏览商品（公开）
curl http://localhost:8080/product

# 下单
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"demo-001","items":[{"productId":1,"quantity":2}]}'
```

完整测试集见 [`docs/api-tests.http`](docs/api-tests.http)（IDEA 里可直接运行），场景说明见 [`docs/api-test-guide.md`](docs/api-test-guide.md)。

### 6.5 用 Docker 启动（推荐）

建库建表脚本在 `db/schema.sql`，演示数据在 `db/seed.sql`，容器编排在 `docker-compose.yml`。

**只启动基础设施**（应用仍在 IDEA 里跑，便于调试）：

```bash
docker compose up -d
```

MySQL 首次启动会自动执行 `schema.sql` + `seed.sql`。注意：初始化脚本**只在数据卷为空时执行**；如果表没建成，先 `docker compose down -v` 再启动。

**连应用服务一起构建启动**：

```bash
mvn -DskipTests package                 # 先在宿主机打包
docker compose --profile app up -d --build
```

四个服务通过环境变量覆盖配置（容器内用服务名访问 MySQL/Nacos/Redis）：

| 变量 | 作用 |
|---|---|
| `SPRING_DATASOURCE_URL` | 数据库地址 |
| `SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR` | 注册中心地址 |
| `SPRING_DATA_REDIS_HOST` | Redis 地址 |
| `MALL_JWT_SECRET_BASE64` | 用户服务与网关共享的 JWT 密钥 |

本地开发可以直接用 compose 里的默认密钥；生产请复制 `.env.example` 为 `.env` 并替换成随机值。

> 验证记录（2026-11-03）：使用 `docker compose --profile app up -d --build` 实际启动过完整栈——四个服务全部注册到容器内 Nacos，网关冒烟通过（公开商品 4 件、登录、下单 201、同 requestId 幂等、取消后库存回到 20、账本 `SUCCEEDED/SUCCEEDED`）。
> 为避开本机已占用的 3306/6379/8848/8080，验证时通过环境变量改用了 13306/16379/18848/18080。

---

## 7. 接口清单

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/user/register` | 公开 | 注册 |
| POST | `/user/login` | 公开 | 登录，返回 JWT |
| GET | `/product` | 公开 | 商品列表 |
| GET | `/product/page` | 公开 | 分页（pageNum/pageSize/keyword/status） |
| GET | `/product/{id}` | 公开 | 商品详情 |
| POST | `/product` | 登录 | 新增商品 |
| PUT | `/product/{id}` | 登录 | 修改商品 |
| DELETE | `/product/{id}` | 登录 | 删除商品 |
| POST | `/orders` | 登录 | 下单（幂等键 requestId） |
| GET | `/orders` | 登录 | 我的订单（分页） |
| GET | `/orders/{orderNo}` | 登录 | 订单详情 |
| GET | `/orders/by-request/{requestId}` | 登录 | 按幂等键查询 |
| PUT | `/orders/{orderNo}/cancel` | 登录 | 取消订单（幂等，恢复库存） |

内部接口（只在服务间调用，网关不路由）：`/internal/products/**`、`/internal/health`。

---

## 8. 测试

```bash
mvn -pl mall-trade-service test
```

当前 12 个单元测试，覆盖：

- 下单成功 / 幂等重复 / 同 requestId 内容冲突；
- 库存不足不补偿 / 部分成功后补偿；
- 取消订单 / 越权取消 / 取消失败订单；
- 恢复任务：PENDING 收敛、UNKNOWN 恢复、成功订单不补偿、恢复失败不误判。

测试用 Mockito 隔离了商品服务和数据库，毫秒级完成，适合本地随时跑。

### 并发压测

100 个并发下单请求抢 10 件库存（本地服务，全部通过交易服务直接打）：

| 指标 | 结果 |
|---|---|
| 总耗时 | 0.42 s |
| 成功（HTTP 201） | 10 |
| 失败（HTTP 409，库存不足） | 90 |
| 最终库存 | 0（无负数） |
| 库存账本 | SUCCEEDED 10 / REJECTED 90 |
| 订单状态 | CREATED 10 / FAILED 90 |

这验证了"判断与扣减在同一条 SQL"确实不会超卖：成功的数量恰好等于库存，多出来的请求全部被数据库行锁挡在 `stock >= ?` 条件之外。

---

## 9. 已知限制

| 限制 | 说明 | 改进方向 |
|---|---|---|
| 网关不是唯一防线 | 直连服务端口并手工传 `X-User-Id` 仍可伪造身份 | 服务端口只监听内网 / 容器网络，或加服务间内部签名 |
| 恢复任务是单机语义 | 多实例会重复执行（靠幂等接口兜底） | 引入 ShedLock 或分布式锁选主 |
| 商品更新会覆盖库存 | `PUT /product/{id}` 带 stock，可能与下单扣减互相覆盖 | 库存单独接口 / 乐观锁 `@Version` |
| 补偿判定依赖 409 | 商品服务没有业务错误码，"409 = 没扣"是当前约定 | 给 `ApiResponse` 加错误码细化 |
| 无支付 | 订单状态没有"已支付" | 接入支付回调后扩展状态机 |
| 无购物车 | 下单直接传商品明细 | 可后续补充（表放 trade_db） |
| 无集成测试 | 并发、唯一索引、真实事务未自动化 | Testcontainers + 并发测试 |
| Docker 仅单机验证 | 已在 Docker Desktop 上跑通完整栈，未验证多机/生产网络 | 生产需补镜像仓库、资源限制、健康检查 |
| 交易服务缺校验实现 | 启动有 `NoProviderFoundException` 警告 | 加 `spring-boot-starter-validation` |

---

## 10. 设计取舍（常被问到的几个问题）

**为什么不用 Seata？**
远程调用没法被本地事务回滚，AT 模式需要全局锁、链路复杂。这个场景用"本地事务 + 唯一索引幂等 + 账本 + 补偿重试"更轻，每一步都能解释清楚。真要上分布式事务，也应该是业务量证明补偿不够用之后。

**为什么先落账本再调用？**
为了让"不确定"变成可对账。先调用再记账，超时后连操作对象都不知道；先记账，最坏情况是"记了但没扣"，可以查询或恢复来判定。

**为什么补偿只处理 SUCCEEDED / UNKNOWN？**
REJECTED 表示商品服务明确拒绝，确定没扣；PENDING 表示请求还没发出（本进程内）。只有"确定扣了"和"可能扣了"才需要还。

**为什么订单明细要存商品名和价格？**
订单是历史凭证。商品改名、调价后，历史订单不能跟着变，所以下单时做快照。

**为什么越权查询返回 404 而不是 403？**
403 会泄露"这个订单存在但不属于你"。统一 404 让攻击者无法枚举订单号。

---

## 11. 后续计划

1. 集成测试与 CI（GitHub Actions：编译 + 单测 + compose 冒烟）；
2. 集成测试与并发压测（验证唯一索引与防超卖）；
3. 购物车、订单超时自动取消（MQ 延迟消息）；
4. 可观测性：日志 traceId、Micrometer 指标。
