# 压测方案 · 实验四：MongoDB 2dsphere 索引对比 —— 地理围栏查询性能

> 目标：为「地理围栏为什么用 2dsphere 索引」提供量化依据，验证空间索引对 10w 级多边形查询的必要性。
> 简历落点：「实测 10w+ 范围数据下，2dsphere 索引将查询 TP99 从 13s 降至 605ms（21 倍）、吞吐提升 100 倍」。
> 结果文档：`测试结果\实验四.md`

---

## 一、实验设计

| 维度 | 设计 |
|---|---|
| 被测接口 | `GET /scopes/location?type=2&longitude=&latitude=`（service-scope 服务，本机 18089；源码为 `Criteria.where("polygon").intersects(point)` → `$geoIntersects`，实体 `@Document("sl_service_scope")` 内嵌 `GeoJsonPolygon`） |
| 数据 | 100,000 条压测多边形（type=2、bid≥9200001、面状分布武汉/成都/北京城市圈 ±0.28 度、凸正多边形半径 0.005-0.015 度）+ 1000 个查询坐标 |
| 对比 | 同接口、同数据、同 150 并发档位：**有 2dsphere 索引（polygon）vs drop 后**各压一轮；无索引轮崩溃则降档补测 |
| 工具 | JMeter（HTTP）+ 虚拟机 mongo shell（灌数/删索引/清理） |

### 关键设计决策

- **压 intersects 而非 $geoWithin**：忠于项目真实实现——压测对象必须与简历描述、线上行为是同一个接口（点在多边形边界上也命中，语义更严谨）。
- **type=2 + bid 段隔离**：压测数据与真实快递员同类型（服务端 `ServiceTypeEnum` 仅认 1/2，非法 code 经 `codeOf` 返回 null 引发 NPE），隔离依靠 bid ≥ 9200001；无索引轮查询时 10w 条全部参与几何扫描（对比所需的最大压力），有索引轮靠 2dsphere 裁剪。
- **面状分布 + 限制命中数**：多边形若扎堆，单点可命中数万个（14MB 响应）直接打爆服务；v3 数据单点命中 18~58 个（响应 ~27KB），贴近真实快递员分布密度。

## 二、环境清单

| 组件 | 要求 |
|---|---|
| service-scope 服务 | IDEA 启动（端口 18089，连虚拟机 Nacos/MongoDB） |
| MongoDB | 虚拟机容器 `mongodb`，`sl_scope.sl_service_scope` 集合，legacy `mongo` shell |
| 造数据脚本 | `experiment4\generate_polygons.py`（seed=42 可复现） |
| JMeter | `experiment4\bench-scope.jmx`（150 线程 + CSV 坐标参数化） |

**前置自检**（服务启动后）：

```bash
curl -s "http://127.0.0.1:18089/scopes/location?type=2&longitude=114.35&latitude=30.63"
```

返回 JSON 数组（空数组亦可，不允许 500/NPE）。

## 三、执行步骤

### Step 1：生成数据

```bash
python "E:\Note\项目\神领物流\5.系统测试\experiment4\generate_polygons.py"
```

| 产物 | 用途 |
|---|---|
| `bench_polygons.jsonl` | 10w 条多边形文档（mongoimport 格式） |
| `od_points.csv` | 1000 个查询坐标（城市圈 ±0.15 度撒点，命中 18~58 个多边形） |

### Step 2：灌入 MongoDB

文件经 FinalShell 文件面板上传至虚拟机 `/tmp/`（保持原名 `bench_polygons.jsonl`），虚拟机终端执行：

```bash
docker cp /tmp/bench_polygons.jsonl mongodb:/tmp/polygons.jsonl
docker exec mongodb mongoimport -u sl -p 123321 --authenticationDatabase admin -d sl_scope -c sl_service_scope --file /tmp/polygons.jsonl
```

验证：

```bash
docker exec mongodb mongo -u sl -p 123321 --authenticationDatabase admin --quiet --eval 'db.getSiblingDB("sl_scope").sl_service_scope.countDocuments({bid:{$gte:9200001}})'
# 期望 100000
```

### Step 3：有索引轮压测

IDEA 启动 service-scope → curl 自检 → `bench-scope.jmx` 预热 60s（丢弃）→ Clear → 正式 300s → 记录聚合报告（Samples / 平均 RT / TP99 / 吞吐 / 异常%）。

### Step 4：drop 索引对照轮

```bash
docker exec mongodb mongo -u sl -p 123321 --authenticationDatabase admin --quiet --eval 'db.getSiblingDB("sl_scope").sl_service_scope.dropIndex("polygon")'
```

JMeter 同参数压 **120s**（无索引下 300s 意义不大），头 20 秒观察：异常率快速攀升则立即停止并降档（50 线程）补跑稳定档数据。**压完立即重建索引**：

```bash
docker exec mongodb mongo -u sl -p 123321 --authenticationDatabase admin --quiet --eval 'db.getSiblingDB("sl_scope").sl_service_scope.createIndex({polygon:"2dsphere"})'
```

### Step 5：数据填写

| 指标 | 有 2dsphere 索引 | 无索引（稳定档） | 无索引（崩溃档） |
|---|---|---|---|
| 并发 / 样本数 | | | |
| 平均 RT / TP99 | | | |
| QPS / 异常% | | | |

**表述模板**：

- 「实测 10w+ 范围数据下索引将查询 TP99 从 __ms 降至 __ms（__ 倍），无索引高并发下触发慢请求雪崩——以对比压测验证空间索引的必要性」
- 面试延伸：崩溃曲线（先正常后超时批量失败）本身是慢请求堆积的教科书案例

### Step 6：清理

```bash
docker exec mongodb mongo -u sl -p 123321 --authenticationDatabase admin --quiet --eval 'db.getSiblingDB("sl_scope").sl_service_scope.deleteMany({bid:{$gte:9200001}})'
docker exec mongodb mongo -u sl -p 123321 --authenticationDatabase admin --quiet --eval 'db.getSiblingDB("sl_scope").sl_service_scope.countDocuments({})'
```

- 清理后确认文档数恢复至真实数据量、2dsphere 索引存在（`getIndexes()` 复查）
- **注意**：实验期间的 deleteMany 按 bid 段清理，波及范围仅限压测数据；但历史事故中真实数据曾被误删，测后需在管理后台核实真实作业范围数据完整性

## 四、面试防守问答

1. **为什么这么快？** 2dsphere 底层 B+Tree 组织 GeoJSON 空间键，查询先按空间范围裁剪候选集，再做精确几何计算；无索引 = 10w 多边形逐个做点面相交运算（CPU 密集），慢请求堆积引发雪崩。
2. **每请求返回 ~40 个多边形、27KB，序列化不是瓶颈？** 是开销的一部分，但两组对比口径一致（同数据同接口），不影响索引结论；业务上快递员选取本就需要返回多个候选范围交调度中心均衡分配。
3. **MySQL 不行吗？** 8.0 InnoDB 支持 SPATIAL INDEX，但作业范围是结构不规则的多边形文档，MongoDB 文档模型天然贴合；且与 Neo4j 实验同一逻辑——选型靠数据说话。
4. **为什么不用 Redis GEO？** Redis GEO 仅支持点（GEOADD），不支持多边形包含判断。
5. **造数据为什么用凸正多边形？** 随机顶点半径的多边形实测 13.9% 自交（边交叉），2dsphere 拒绝非法环；极角均分 + 固定半径的凸多边形在数学上保证简单环，10w 条 0 失败。
