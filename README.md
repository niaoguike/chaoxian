# 潮限 · 潮玩限量发售与秒杀平台

基于黑马点评课程代码改造的 Spring Boot 后端。保留上游 `com.hmdp` 包名与门店/优惠券模型，对应展示页、限量发售活动与订单。尚未提供独立的潮玩商品前端，不能将其描述为已上线的完整电商产品。

## 已实现的核心链路

| 简历模块 | 实现入口 | 行为 |
| --- | --- | --- |
| JWT + Redis 鉴权 | JwtTokens、RefreshTokenInterceptor、LoginInterceptor | 校验签名/绝对过期、Redis 会话滑动过期、ThreadLocal 清理、退出删除会话 |
| Lua 秒杀 | seckill.lua、OrderMessaging | 活动时间、库存、一人一单与事务标记原子写入 |
| 异步削峰与可靠投递 | OrderMessaging | RocketMQ 半消息、本地预占、事务回查；消费失败重试，成功落库才确认 |
| 并发控制 | VoucherOrderServiceImpl、chaoxian-migration.sql | Redisson 看门狗锁、MySQL 条件扣库存、用户活动唯一索引、事务落单 |
| 支付与关单 | OrderLifecycle | 支付和超时关单竞争同一待支付状态，10 分钟延迟消息触发关单，定时扫描补漏 |
| 库存回补 | order_compensation、OrderLifecycle | 数据库关单/回库存/补偿记录同事务；Redis Lua 幂等回补，扫描重试 |
| 缓存 | CacheClient | 空值缓存、随机 TTL、Redisson 互斥双检、逻辑过期和有界异步重建 |

RocketMQ 事务协议依据[官方事务消息说明](https://rocketmq.apache.org/docs/4.x/producer/06message5/)。回查通过 Redis 持久化标记判断，不依赖进程内 Map；回查先写 ABORT 屏障，防止迟到的本地回调继续预占。

## 本地运行

建议 JDK 17、Maven 3.8+，准备 MySQL 8、Redis 6+、RocketMQ 4.9.8 NameServer/Broker。Broker 开启事务检查并创建 `chaoxian-orders`、`chaoxian-close` Topic，后者使用标准延时级别 14（10 分钟）。应用与 Redis/MySQL 使用北京时间，MySQL 连接当前库名为 hmdp。

1. 创建数据库 `hmdp`，依次导入 `src/main/resources/db/schema.sql` 和 `chaoxian-migration.sql`。不要在已有重复订单的数据集上直接添加唯一索引。
2. 设置环境变量 `MYSQL_PASSWORD`、`JWT_SECRET`（至少 32 字节随机密钥）；可设置 `MYSQL_USER`、`REDIS_HOST`、`REDIS_PASSWORD`、`ROCKETMQ_NAMESERVER`。不要将真实密钥提交到仓库。
3. 执行 `mvn spring-boot:run`。默认端口 8081。演示支付需显式开启 `demo` profile，生产环境不要启用。
4. `POST /user/code?phone=...` 获取验证码（当前为教学用日志短信模拟器）；`POST /user/login` 提交 `phone`、`code`。真实短信服务尚未接入。验证码限流 60 秒且一次性消费。
5. 后续请求携带 `Authorization: Bearer <token>`。`POST /voucher-order/seckill/{活动ID}` 返回受理订单号；通过 `GET /voucher-order/{订单号}` 查询本人落库结果。
6. `POST /demo/payment/{订单号}` 仅在 demo profile 下模拟支付确认。实际支付必须由验签后的支付服务回调调用 `OrderLifecycle.paid`，本项目没有接入支付宝/微信。

活动创建沿用原 `/voucher/seckill` 接口，并写入 Redis 库存及起止时间。仅对新建活动启用新的 Lua 协议，已有活动需重新预热。公开部署前需要为活动管理/上传/门店写接口接入管理员权限，目前仅校验已登录。

## 一致性边界和运维

- Redis 必须启用持久化和可靠备份。预占结果/回补幂等标记不可随意清空。本实现使用单主 Redis 多键 Lua，不支持直接切换 Redis Cluster；单主异步复制故障切换可能丢失预占记录，数据库条件更新和唯一约束仍保护数据库库存与一人一单，异常预占需要对账。
- RocketMQ 消费重试耗尽会进入死信队列。监控 `%DLQ%chaoxian-order-consumer`，修复原因后按原订单号重放，不要生成新订单号；同时监控长时间未落库的 COMMIT 事务标记与未投递补偿记录。
- 支付/关单状态竞争由 SQL CAS 决定；支付先成功则关单不回补，关单先成功则支付返回失败。真实支付渠道还需要迟到支付退款流程。
- 已取消订单也保留“一人一单”资格记录；同一用户不能再次购买同一活动。Redis 回补标记不设置短 TTL，避免迟到重试重复增加库存。
- 当前提供并发正确性机制，未完成 Redis Sentinel、MySQL 主从和 RocketMQ 集群部署验证，不能据此声称基础设施已达到高可用。
- 缓存失效在数据库提交后执行；删除失败需重试或等待缓存 TTL。逻辑过期模式仅应用于允许返回旧数据的热点读场景。

## 验证

`mvn -Dtest=ChaoxianUnitTest test` 验证 JWT、鉴权边界、支付状态条件、关单幂等及回补记录。新 JDK 运行旧版 Mockito 时可增加 `-Dnet.bytebuddy.experimental=true`，建议使用 JDK 17。

本次实际环境为 IDEA 自带 JDK，6 项单元测试通过；Docker 引擎未启动，尚未完成 MySQL/Redis/RocketMQ 联调、故障注入和吞吐测试。**2500 TPS 是简历目标，当前没有测量证据，不作为已达成指标。**

压测应预热活动与独立用户 token，记录机器配置、并发数、成功受理/真实落库 TPS、P95/P99、失败率、库存与重复订单数，再据实更新简历。

## 来源

基础代码来自[黑马点评课程示例](https://gitee.com/huyi612/hm-dianping)，保留原作者源码注释。此仓库为教学改造，发布不代表对上游代码权利作额外授权；使用前请核对上游许可。
