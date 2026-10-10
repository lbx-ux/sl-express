# 压测方案 · 实验三：Leaf 号段模式发号性能 + DB 故障注入

> 目标：验证美团 Leaf 号段模式（双 buffer）的性能水位与容错能力，支撑简历「Leaf 号段模式生成运单号」的量化表述。
> 简历落点：「实测 9808 QPS、TP99 86ms、10w 取号零重复；DB 宕机注入停库 60s 发号零中断，恢复后服务自愈」。
> 结果文档：`测试结果\实验三.md`

---

## 一、实验设计

三个子实验逐级递进：

| # | 子实验 | 回答的问题 | 工具 |
|---|---|---|---|
| 3a | 发号吞吐压测 | 号段模式的 QPS / RT 水位 | JMeter（HTTP） |
| 3b | 唯一性校验 | 并发发号是否零重复 | Python 采集脚本（50 线程 × 10w id） |
| 3c | DB 故障注入 | `docker stop mysql` 后发号是否中断、持续多久、恢复后是否自愈 | Bash 计时脚本 |

**被测对象**：虚拟机 Leaf 服务 `http://192.168.150.101:28838`（docker 容器 `meituan-leaf`，监控页 `/cache`）。

**直压 Leaf HTTP 接口而非项目 IdService 封装**：IdService 本身是一次 HTTP 转发（`/api/segment/get/{biz}`），直压 Leaf 即代表真实链路上限；封装层的 10s readTimeout 是客户端参数，不影响服务端水位。

## 二、环境清单

| 组件 | 要求 |
|---|---|
| Leaf 服务 | 虚拟机 28838 端口（容器 `meituan-leaf`） |
| MySQL | 虚拟机 `sl_leaf` 库 `leaf_alloc` 表（容器名 `mysql`） |
| JMeter | 5.6.3，纯 HTTP 压测无需额外 jar |
| biz_tag | 压测专用 `bench_stress`（step=10000 起步），不污染 `transport_order` |

**目录约定**：脚本与产物在 `5.系统测试\experiment3\`（`bench-leaf.jmx` / `collect_ids.py` / `failover_test.sh`）。

**为什么专用 biz_tag**：压测消耗的号段与真实运单号彻底隔离，step 可独立调参；测后 `DELETE FROM leaf_alloc WHERE biz_tag='bench_stress'` 即完成清理。

## 三、执行步骤

### Step 0：前置检查

```bash
curl http://192.168.150.101:28838/api/segment/get/bench_stress   # 返回纯文本数字即通
```

虚拟机 MySQL 建压测 tag：

```sql
USE sl_leaf;
INSERT INTO leaf_alloc (biz_tag, max_id, step, description)
VALUES ('bench_stress', 1, 10000, '压测专用，测完删除');
```

step=10000 使 10w 压测仅触发 10 次 DB 领段——压测打在内存发号逻辑上，符合号段模式的真实工作状态。

### Step 1（3a）：发号吞吐压测

- JMeter：`bench-leaf.jmx`，GET `/api/segment/get/bench_stress`，200 并发 / 10s ramp / 预热 60s 丢弃 / 正式 300s
- 断言：响应匹配 `^\d+$`（纯数字），拦截 Leaf 异常时的非数字返回
- 读聚合报告：Samples、TP99、中位数、异常%、吞吐量
- 若 200 并发出错：阶梯降档（100 → 50）定位服务拐点

### Step 2（3b）：唯一性校验

```bash
python collect_ids.py
```

50 线程并发取号 10w 个，自动判重并输出 collected/unique/duplicates。可与 JMeter 同时运行，验证**混合压力下**的号段原子性（更贴近真实场景）。

### Step 3（3c）：DB 故障注入

`failover_test.sh` 自动完成全流程：预热（双 buffer 就绪，`/cache` 确认 nextIdReady=true）→ `docker stop mysql` 计时 → 每 50ms 取号一次并打时间戳 → 60s 后 `docker start` → 汇总「停库时刻 / 持续发号时长 / 期间发号个数 / 自愈时长」四元组 + 期间 id 判重。要求虚拟机 ssh 免密。

### Step 4（选做）：step 与容错时长的定量关系

将 step 改回 100（`UPDATE leaf_alloc SET step=100 WHERE biz_tag='bench_stress'`）并重启 Leaf（热改对已加载 buffer 不生效，容器 `meituan-leaf` 需 restart），重复 Step 3——对比两种 step 下的持续发号时长，实测验证「step 决定 DB 故障时的缓冲窗口」。

### Step 5：结果填写

| 指标 | 数值 |
|---|---|
| 3a 吞吐 / TP99 / 异常% | |
| 3a 服务拐点（如有降档） | |
| 3b 采集数 / 唯一数 / 重复数 | |
| 3c 持续发号时长 / 期间发号数 / 自愈时长 | |

**表述模板**：

- 完整版：「实测发号 X QPS、TP99 X ms、Xw 并发取号零重复；注入 DB 宕机故障，发号零中断持续 X 秒、恢复后 Y 秒自愈——以故障注入验证高可用设计」
- 面试延伸：自测数据 + 官方数据（4C8G 内网 RPC 近 5w QPS / TP999 1ms）双层印证，注意说明环境口径差异（虚拟机 + HTTP vs 生产硬件 + RPC）

### Step 6：清理

```sql
DELETE FROM leaf_alloc WHERE biz_tag = 'bench_stress';
```

- `transport_order` 号段被消耗无影响（运单号语义只需不重复，号段空洞无副作用）
- 确认 mysql 容器 Up、Leaf `/api/segment/get/transport_order` 正常返回

## 四、面试防守问答

1. **号段模式为什么快？** 取号命中内存双 buffer，DB 仅在号段消耗至临界点时被后台线程异步访问；同步号段模式才是每发一号碰一次 DB。
2. **双 buffer 切换时机？** 当前号段消耗至剩余 10% 时异步预加载下一号段，耗尽瞬间无缝切换（`/cache` 监控页 nextIdReady 字段可观测）。
3. **重启会怎样？** 当前号段作废，浪费量 = 实例数 × step；故 step 需权衡「故障缓冲时长」与「重启浪费」，不能盲目调大。
4. **为什么不用雪花/UUID？** 雪花 64bit 超出 SL+13 位格式约束且有时钟回拨风险；UUID 无序引发 InnoDB 页分裂且 36 字符过长。号段 = 趋势递增 + 长度可控 + 实现简单，代价是依赖 DB（被双 buffer 解耦）。
5. **多实例会重号吗？** 不会——号段分配依赖 DB 行锁（`UPDATE max_id = max_id + step`），各实例领取不重叠号段。
6. **IdService 10s readTimeout 有风险吗？** 正常取号 TP99 个位数 ms，超时余量充足；DB 故障且双 buffer 耗尽的窗口期会抛 SLException，由业务侧 MQ 重试兜底。
