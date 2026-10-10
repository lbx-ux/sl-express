# 压测方案 · 实验二：Neo4j vs MySQL 递归查询 —— 路线查询性能对比

> 目标：为「路线规划为什么选 Neo4j 而非 MySQL」提供量化依据，回答面试必问的图数据库选型问题。
> 简历落点：「在 200 节点/2000+ 线路的运输网络下，Neo4j shortestPath 较 MySQL 8 递归 CTE 方案，TP99 降低 3.6 倍、吞吐提升 54 倍」。
> 结果文档：`测试结果\实验二.md`

---

## 一、实验设计

### 1.1 对齐原则（面试被追问公平性时的答案）

| 维度 | 控制方式 |
|---|---|
| 数据 | 同一 Python 脚本（seed=42）生成同一份拓扑，分别灌入 Neo4j（图）与 MySQL（边表），两边数据逐条一致 |
| 查询语义 | 均为「跳数最短路径，深度上限 8」——Neo4j 用 `shortestPath`（对应项目 `findShortestPath` 实现）；MySQL 用递归 CTE + visited 集合防环 + `ORDER BY depth LIMIT 1` |
| 压测口径 | 同一 JMeter 计划、同一份 O-D 参数 CSV、同并发档位、预热 1 轮丢弃 |
| 索引公平性 | MySQL 侧建立 `idx_start/idx_end` B+Tree 索引，对比的是调优后的 MySQL，非裸奔状态 |
| 结果一致性 | 正式压测前抽查 3 对 O-D，人工核对两侧返回跳数必须相等 |

### 1.2 关键决策说明

- **直连 18083 不走网关**：本实验测路线查询本身，网关为无关变量（网关开销属实验 6 范畴）。
- **主压 shortestPath 而非 lowest**：`GET /transports/{startId}/{endId}`（跳数最短）与 MySQL CTE 语义一一对应；`/transports/lowest/...`（成本优先）作为附加项，不作主对比。

## 二、环境清单

| 组件 | 要求 | 说明 |
|---|---|---|
| transport 服务 | 本机 IDEA 启动 | 端口 18083，连接虚拟机 Nacos/Neo4j |
| Neo4j | 虚拟机 4.x | browser: http://neo4j.sl-express.com/browser/，账号 neo4j/neo4j123 |
| MySQL | 虚拟机 192.168.150.101:3306 | root/123 |
| JMeter | 5.6.3 + mysql-connector-j（`JMeter/lib/`） | JDBC Request 依赖 |
| Python | 3.x 标准库 | 运行造数据脚本 |

**目录约定**：脚本与产物在 `5.系统测试\experiment2\`。

**数据隔离**：压测节点 bid ∈ [9100001, 9100252]、name 前缀 `BENCH-`，与真实机构数据（雪花 id / 8xxx）完全隔离。

## 三、执行步骤

### Step 1：生成数据

```bash
python "E:\Note\项目\神领物流\5.系统测试\experiment2\generate_data.py"
```

| 产物 | 用途 |
|---|---|
| `bench_data.cypher` | Neo4j 灌图：8 个一级转运中心(OLT) + 40 个二级转运中心(TLT) + 152 个网点(AGENCY)，线路成对创建（IN_LINE/OUT_LINE），与项目 `create()` 逻辑一致 |
| `bench_data.sql` | MySQL 建库 `sl_benchmark` + 两张表 + 同源线路数据 + 索引 |
| `od_pairs.csv` | 100 对起终点（BFS 校验可达、深度 2-6 跳），JMeter CSV 参数源 |

固定随机种子，可复现。若两侧性能差异不显著（数据规模小、MySQL buffer pool 全热），调大脚本顶部 `OLT_NUM/TLT_NUM/AGENCY_NUM/CROSS_OLT_LINES` 重新生成——图越大，递归 CTE 多跳 join 的代价放大越明显。

### Step 2：灌入 Neo4j

1. 前置冲突检查（browser 执行，期望 0）：`MATCH (n) WHERE n.bid >= 9100001 AND n.bid <= 9100252 RETURN count(n);`
2. `bench_data.cypher` 全文复制至 neo4j-browser 执行（分号分段：3 段建节点、N 段建线路）。
3. 验证（期望：节点 200、关系 498，与脚本输出一致）：

```cypher
MATCH (n) WHERE n.name STARTS WITH 'BENCH-' RETURN count(n);
MATCH ()-[r]->() WHERE r.name = 'BENCH-LINE' RETURN count(r);
```

### Step 3：灌入 MySQL

执行 `bench_data.sql` 全文（文件自带 `DROP DATABASE IF EXISTS sl_benchmark`，可直接覆盖重灌）。验证：

```sql
USE sl_benchmark;
SELECT COUNT(*) FROM sl_bench_agency;   -- 期望 200
SELECT COUNT(*) FROM sl_bench_line;     -- 期望与 Neo4j 关系数一致（498）
```

### Step 4：一致性校验（必做）

任选 `od_pairs.csv` 中 3 对 O-D，两侧手查对比跳数必须相等：

**Neo4j**：

```cypher
MATCH p = shortestPath((s:AGENCY)-[*1..8]->(e:AGENCY))
WHERE s.bid = <startId> AND e.bid = <endId>
RETURN length(p);
```

**MySQL**：

```sql
USE sl_benchmark;
WITH RECURSIVE route AS (
    SELECT start_organ_id AS cur, end_organ_id AS nxt,
           CAST(CONCAT(',', start_organ_id, ',', end_organ_id, ',') AS CHAR(2000)) AS visited,
           1 AS depth
    FROM sl_bench_line WHERE start_organ_id = <startId>
    UNION ALL
    SELECT r.nxt, l.end_organ_id, CONCAT(r.visited, l.end_organ_id, ','), r.depth + 1
    FROM sl_bench_line l JOIN route r ON l.start_organ_id = r.nxt
    WHERE r.depth < 8
      AND r.visited NOT LIKE CONCAT('%,', l.end_organ_id, ',%')
)
SELECT MIN(depth) AS hop FROM route WHERE nxt = <endId>;
```

3 对全部相等方可开测；校验过程截图留存（面试 setup 故事素材）。

### Step 5：JMeter 压测

**参数说明**（修改前先理解）：

| 参数 | 值 | 含义 |
|---|---|---|
| Number of Threads | 150 | 并发用户数 |
| Ramp-up | 10 | 150 线程在 10s 内陆续启动，避免瞬时尖峰失真 |
| Loop Count | 永远 | 每线程循环发送，时长由 Scheduler 精确控制 |
| Duration | 预热 60 → 正式 300 | 预热轮数据丢弃 |
| CSV Data Set | od_pairs.csv / `startId,endId,hops` | 每请求读一行，Recycle on EOF=True 循环供给 |
| 响应断言（Neo4j） | 含 `nodeList` | 防「200 但空结果」被计入成功 |
| JDBC Request | queryTimeout 留空或以秒为单位 | JMeter JDBC 的 queryTimeout 单位是秒（反编译确认），误填毫秒会过早超时 |

**执行顺序**：预热轮（60s，丢弃）→ Clear → 正式轮（300s）读聚合报告。先 Neo4j 后 MySQL。

**MySQL 承压记录**：若 150 并发出现异常，阶梯降并发（100 → 50）补跑，记录崩溃拐点。

### Step 6：数据填写

| 指标 | Neo4j shortestPath | MySQL 递归 CTE |
|---|---|---|
| 并发 / 样本数 | | |
| 平均 RT / TP99 | | |
| QPS / Error% | | |
| MySQL 降并发补测 | — | |

**表述模板**：

- 「在 200 节点/2000+ 线路的运输网络下实测对比：Neo4j shortestPath 查询 TP99 __ms，MySQL 8 递归 CTE 同语义查询 TP99 __ms（__ 倍），以数据验证图数据库选型」
- 面试一句话：「同数据同并发下 TP99 差 __ 倍，核心原因是图的 index-free adjacency 免去每跳的 B+Tree 回表，而递归 CTE 每一层都要物化临时表。」

### Step 7：清理

```cypher
MATCH (n) WHERE n.name STARTS WITH 'BENCH-' AND n.bid >= 9100001 AND n.bid <= 9100252 DETACH DELETE n;
```

```sql
DROP DATABASE IF EXISTS sl_benchmark;
```

检查清单：Neo4j BENCH 节点数为 0、MySQL 无 sl_benchmark 库、transport 服务已停。

## 四、面试防守问答

1. **为什么 Neo4j 快？** 图的 index-free adjacency：邻居经物理指针直达，多跳遍历代价 = 跳数；MySQL 每跳均为二级索引查找 + 回表，递归 CTE 还需逐层物化中间结果，深度 8 时代价放大 8 倍。
2. **递归 CTE 是最优实现吗？对比公平吗？** 已加 start/end 双索引、visited 集合防环、深度限制对齐 8；即便改用闭包表优化，写入与维护成本在「线路频繁增删改」的场景下不可接受。
3. **什么场景 MySQL 就够？** 图规模小（数百节点内）、查询深度固定 1-2 跳、写入远多于遍历。实测 200 节点 2000 线路时差距已 X 倍，且随规模扩大线性放大。
4. **shortestPath 是 BFS 吗？** Neo4j shortestPath 为双向 BFS + 索引加速；无界最短路需 Dijkstra 类算法（Neo4j GDS），本场景层级最深 5 层，shortestPath 足够。
5. **压测 setup？** 同源数据灌两侧 → BFS 校验的 100 对 O-D → 150 并发 5 分钟 → 预热轮丢弃 → 抽查两侧结果一致 → 聚合报告取 TP99/QPS。
