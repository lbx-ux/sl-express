# sl-express · 神领物流系统（微服务实现）

> 基于《神领物流》课程项目**独立实现**的物流平台，覆盖物流全链路业务：
> **下单 → 生成运单 → 智能调度 → 干线运输 → 末端派件**。
> 从 0 搭建十余个微服务，代码按功能逐步提交，完整记录开发轨迹。

## 业务全景

```mermaid
flowchart LR
    A[用户下单] --> B[订单服务]
    B --> C[运单服务<br/>Leaf 号段生成运单号]
    C --> D[调度中心<br/>消息驱动·纯计算]
    D --> E[取派件任务<br/>分配快递员]
    E --> F[运输任务<br/>xxl-job 分片调度]
    F --> G[干线运输<br/>Neo4j 路线规划]
    G --> H[末端派件<br/>签收/拒收]
    H --> I[物流追踪<br/>多级缓存]
```

## 技术栈

| 分层 | 技术 |
|---|---|
| 微服务框架 | Spring Boot · Spring Cloud Alibaba（Nacos / Gateway / OpenFeign） |
| 存储 | MySQL（MyBatis-Plus）· Redis · **Neo4j**（路线图数据）· **MongoDB**（地理围栏/轨迹） |
| 消息 | RabbitMQ（可靠投递 + 死信/延迟队列 + 消费幂等） |
| 调度 | **xxl-job**（分片广播）· **美团 Leaf**（号段模式分布式 ID） |
| 分布式 | Redisson 分布式锁 · Seata |
| 认证 | JWT 双 token + Redis，网关统一鉴权，四端差异化权限 |
| 可观测 | SkyWalking 链路追踪 · GrayLog 日志集中检索 |
| 工程化 | Git · Maven 私服 · Jenkins CI/CD · Docker/docker-compose |

## 核心亮点

### 1. 消息驱动的智能调度中心
不落库的纯计算服务：消费订单消息 → 计算所属网点与快递员 → 发消息生成任务。按排班与**历史任务量均衡分配**快递员，避免忙闲不均；运单合并后推入 Redis List，供调度器批量装载。

### 2. Neo4j 路线规划
运输网络建模为图：**机构（网点）为节点、线路为边**，Cypher 深度查询自动计算多级中转路线。机构/线路数据经 MQ 从权限中台异步同步，保证图数据与关系型数据最终一致。

### 3. 运输任务调度（xxl-job 分片 + 递归装载）
xxl-job **分片广播**多节点并行调度，Redisson 互斥锁防止重复执行；按「起点-终点」维度分组装载运单，递归实现**载重/体积双约束**的装车分配，生成运输任务与司机作业单。

### 4. 分布式 ID（Leaf 号段模式）
双 buffer 预加载号段：当前号段消耗至 10% 时异步加载下一号段，DB 短暂不可用期间仍可持续发号；相比雪花算法无时钟回拨问题，相比自增 ID 无单点瓶颈。

### 5. 运费计算（责任链模式）
责任链逐级匹配运费模板（同城/省内/跨省 × 经济/特快），新增计费规则零侵入扩展，替代多层 if-else。

### 6. 多级缓存与三空治理
Caffeine（进程内）+ Redis 二级缓存；空值缓存防穿透、互斥锁防击穿、随机 TTL + 逻辑过期防雪崩。

### 7. MongoDB 地理围栏
2dsphere 空间索引存储快递员/机构作业范围多边形，`$geoWithin` 毫秒级判定「坐标 → 所属网点」，支撑下单自动分单。

### 8. 可靠消息与幂等
本地消息表 + Confirm/ACK 全链路可靠投递；消费端基于**业务状态机幂等去重**，保障订单 → 运单 → 任务全链路数据最终一致。

## 模块划分

| 模块 | 职责 | 核心技术 |
|---|---|---|
| `sl-express-gateway` | 统一网关：路由、认证鉴权 | Gateway + JWT |
| `sl-express-ms-web-manager` | 管理后台聚合服务 | Spring Boot |
| `sl-express-ms-web-customer` | 用户端（小程序） | 微信登录 |
| `sl-express-ms-web-courier` | 快递员端 | - |
| `sl-express-ms-web-driver` | 司机端 | - |
| `sl-express-ms-carriage` | 运费计算 | 责任链模式 |
| `sl-express-ms-transport` | 路线规划、线路管理 | Neo4j |
| `sl-express-ms-service-scope` | 作业范围（地理围栏） | MongoDB 2dsphere |
| `sl-express-ms-dispatch` | 智能调度中心 | RabbitMQ 纯计算 |
| `sl-express-ms-work` | 取派件/运输任务 | xxl-job + Redisson |
| `sl-express-ms-transport-info` | 物流追踪 | Caffeine + Redis 多级缓存 |
| `sl-express-ms-base` | 基础数据（车辆/车次/排班） | MySQL |
| `sl-express-ms-sms` | 短信服务 | 阿里云 SMS |

## 快速开始

```bash
# 1. 启动中间件环境（MySQL/Redis/RabbitMQ/Nacos/Neo4j/MongoDB/xxl-job 等）
docker-compose up -d

# 2. 配置本机 hosts（域名统一访问中间件）
# 3. 按 nacos 配置中心 → 各微服务 顺序启动
# 4. 访问管理后台：http://admin.sl-express.com
```


## 声明

本项目基于黑马程序员《神领物流》课程独立实现，仅用于个人学习与技术交流，不用于任何商业用途。
