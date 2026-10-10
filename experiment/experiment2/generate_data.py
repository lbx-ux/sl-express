"""
实验二：Neo4j vs MySQL 路线查询对比 —— 数据生成脚本
生成三份文件（都在本脚本所在目录）：
  1. bench_data.cypher  Neo4j 灌图数据（AGENCY/TLT/OLT 三类节点 + IN_LINE/OUT_LINE 成对线路）
  2. bench_data.sql     MySQL 建库建表 + 同一份线路数据 + B+Tree 索引
  3. od_pairs.csv       100 对起终点（BFS 校验可达，深度 2-6 跳），JMeter CSV 参数用

固定随机种子，重跑结果完全一致。
压测数据标识：bid ∈ [9100001, 9100252]，name 以 BENCH- 开头，与真实数据隔离，测完可一键清理。
"""
import os
import random
from collections import deque

# ============ 可调参数：数据规模（差异不明显时调大重跑） ============
OLT_NUM = 8        # 一级转运中心
TLT_NUM = 40       # 二级转运中心
AGENCY_NUM = 152   # 网点（营业部）
CROSS_OLT_LINES = 16  # 一级转运中心之间的干线数
SEED = 42

BID_START = 9100001
LINE_NAME = "BENCH-LINE"

random.seed(SEED)
HERE = os.path.dirname(os.path.abspath(__file__))

# ============ 1. 生成节点 ============
nodes = []          # (bid, label, name)
bid = BID_START
for i in range(OLT_NUM):
    nodes.append((bid, "OLT", f"BENCH-OLT-{i:03d}")); bid += 1
for i in range(TLT_NUM):
    nodes.append((bid, "TLT", f"BENCH-TLT-{i:03d}")); bid += 1
for i in range(AGENCY_NUM):
    nodes.append((bid, "AGENCY", f"BENCH-AGY-{i:03d}")); bid += 1

BID_END = bid - 1
olt_bids = [n[0] for n in nodes if n[1] == "OLT"]
tlt_bids = [n[0] for n in nodes if n[1] == "TLT"]
agy_bids = [n[0] for n in nodes if n[1] == "AGENCY"]

# ============ 2. 生成线路拓扑（有向边集合，灌库时成对建关系/两行记录） ============
edges = set()

def add_edge(a, b):
    """加一条 a->b 的有向边；灌库时会自动成对（a->b 与 b->a）"""
    if a != b:
        edges.add((a, b))

# 2.1 网点 -> 归属二级转运中心（每个网点归属一个 TLT，再加 1-2 个邻近 TLT 兜底）
# 层级间双向加边：真实线路是成对创建的（IN_LINE/OUT_LINE），快递上下行都要走
tlt_neighbors = {}
for i, agy in enumerate(agy_bids):
    home = tlt_bids[i % TLT_NUM]
    add_edge(agy, home); add_edge(home, agy)
    tlt_neighbors.setdefault(home, []).append(agy)
    # 20% 网点额外连一个随机 TLT，制造多路径
    if random.random() < 0.2:
        other = random.choice(tlt_bids)
        if other != home:
            add_edge(agy, other); add_edge(other, agy)

# 2.2 二级转运中心 -> 归属一级转运中心（每个 TLT 归属一个 OLT + 1 个随机备份）
tlt_home = {}
for i, tlt in enumerate(tlt_bids):
    home = olt_bids[i % OLT_NUM]
    add_edge(tlt, home); add_edge(home, tlt)
    tlt_home[tlt] = home
    if random.random() < 0.3:
        other = random.choice(olt_bids)
        if other != home:
            add_edge(tlt, other); add_edge(other, tlt)

# 2.3 一级转运中心之间干线（保证全国连通）
for i in range(CROSS_OLT_LINES):
    a, b = random.sample(olt_bids, 2)
    add_edge(a, b)
    add_edge(b, a)  # 干线双向直连，避免绕行整圈

print(f"节点: OLT={OLT_NUM} TLT={TLT_NUM} AGENCY={AGENCY_NUM} 共 {len(nodes)}")
print(f"有向边: {len(edges)}（灌库后线路记录数 = 2 倍关系数）")

# ============ 3. BFS 校验可达性 ============
adj = {}
for a, b in edges:
    adj.setdefault(a, []).append(b)

def bfs_min_hops(s, e, max_depth=8):
    if s == e:
        return 0
    q, seen = deque([(s, 0)]), {s}
    while q:
        cur, d = q.popleft()
        if d >= max_depth:
            continue
        for nb in adj.get(cur, []):
            if nb == e:
                return d + 1
            if nb not in seen:
                seen.add(nb)
                q.append((nb, d + 1))
    return None

# ============ 4. 生成 100 对可达 O-D（深度 2-6，保证有中转，对图查询才有意义） ============
pairs = []
random.seed(SEED + 1)
attempts = 0
while len(pairs) < 100 and attempts < 50000:
    attempts += 1
    s, e = random.choice(agy_bids), random.choice(agy_bids)
    if s == e or (s, e) in [(p[0], p[1]) for p in pairs]:
        continue
    h = bfs_min_hops(s, e)
    if h is not None and 2 <= h <= 6:
        pairs.append((s, e, h))

if len(pairs) < 100:
    raise SystemExit(f"可达 O-D 不足 100 对（仅 {len(pairs)}），请调大 CROSS_OLT_LINES 或节点数")
hop_dist = {}
for _, _, h in pairs:
    hop_dist[h] = hop_dist.get(h, 0) + 1
print(f"O-D 对: 100（跳数分布 {dict(sorted(hop_dist.items()))}）")

# ============ 5. 写 Neo4j cypher 文件 ============
# 预生成每条有向边的 cost/distance，保证 Neo4j 关系与 MySQL 行逐条数值一致
cost_map = {e: (random.randint(100, 500), random.randint(50, 800)) for e in edges}
# 物理链路 = edges 中 a<b 的规范方向：一条线路记录 = 1 IN_LINE(a→b) + 1 OUT_LINE(b→a)
# 与项目 TransportLineRepositoryImpl#create() 的成对创建逻辑一致（每链路共 2 个关系）
links = sorted(e for e in edges if e[0] < e[1])

cy = []
cy.append("// ==== 实验二压测数据：节点（AGENCY/TLT/OLT，bid 9100001-9100252，name BENCH- 前缀）====")
for b, label, name in nodes:
    cy.append(f"CREATE (n:{label} {{bid: {b}, name: '{name}', status: true}});")
cy.append("")
cy.append(f"// ==== 线路（{len(links)} 条线路记录，成对创建 IN_LINE/OUT_LINE，与项目 create() 逻辑一致）====")
for a, b in links:
    cost_ab, dist_ab = cost_map[(a, b)]
    cost_ba, dist_ba = cost_map[(b, a)]
    cy.append(
        f"MATCH (m {{bid: {a}}}), (n {{bid: {b}}}) "
        f"CREATE (m)-[:IN_LINE {{cost: {cost_ab}, number: '{LINE_NAME}', type: 1, name: '{LINE_NAME}', "
        f"distance: {dist_ab}, time: {dist_ab}, extra: '', startOrganId: {a}, endOrganId: {b}, created: 0, updated: 0}}]->(n), "
        f"(m)<-[:OUT_LINE {{cost: {cost_ba}, number: '{LINE_NAME}', type: 1, name: '{LINE_NAME}', "
        f"distance: {dist_ba}, time: {dist_ba}, extra: '', startOrganId: {b}, endOrganId: {a}, created: 0, updated: 0}}]-(n);"
    )
cy.append("")
cy.append("// ==== 验证（执行后应与脚本输出一致）====")
cy.append(f"// MATCH (n) WHERE n.name STARTS WITH 'BENCH-' RETURN count(n);  // 期望 {len(nodes)}")
cy.append(f"// MATCH ()-[r]->() WHERE r.name = '{LINE_NAME}' RETURN count(r);  // 期望 {len(edges)}（= MySQL sl_bench_line 行数）")

with open(os.path.join(HERE, "bench_data.cypher"), "w", encoding="utf-8") as f:
    f.write("\n".join(cy) + "\n")

# ============ 6. 写 MySQL sql 文件 ============
sq = []
sq.append("-- 实验二压测数据：MySQL 侧（库 sl_benchmark，测完 DROP DATABASE 即清理干净）")
sq.append("DROP DATABASE IF EXISTS sl_benchmark;")
sq.append("CREATE DATABASE sl_benchmark DEFAULT CHARSET utf8mb4;")
sq.append("USE sl_benchmark;")
sq.append("""
-- 机构表（对齐 Neo4j 节点：bid/name/type/status）
CREATE TABLE sl_bench_agency (
  bid BIGINT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  type TINYINT NOT NULL,          -- 1网点 2二级转运 3一级转运
  status TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB;

-- 线路表（对齐 Neo4j 关系：每条有向边一行，数据与图一致）
CREATE TABLE sl_bench_line (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  start_organ_id BIGINT NOT NULL,
  end_organ_id BIGINT NOT NULL,
  cost INT NOT NULL,
  distance INT NOT NULL,
  KEY idx_start (start_organ_id),
  KEY idx_end (end_organ_id)
) ENGINE=InnoDB;
""")
type_map = {"AGENCY": 1, "TLT": 2, "OLT": 3}
sq.append("INSERT INTO sl_bench_agency (bid, name, type, status) VALUES")
sq.append(",\n".join(f"({b}, '{n}', {type_map[l]}, 1)" for b, l, n in nodes) + ";")
sq.append("")
sq.append("INSERT INTO sl_bench_line (start_organ_id, end_organ_id, cost, distance) VALUES")
sq.append(",\n".join(f"({a}, {b}, {cost_map[(a, b)][0]}, {cost_map[(a, b)][1]})" for a, b in sorted(edges)) + ";")
sq.append("")
sq.append("-- 验证：两行应分别等于脚本输出的节点数与有向边数")
sq.append(f"-- SELECT COUNT(*) FROM sl_bench_agency;  -- 期望 {len(nodes)}")
sq.append(f"-- SELECT COUNT(*) FROM sl_bench_line;    -- 期望 {len(edges)}")

with open(os.path.join(HERE, "bench_data.sql"), "w", encoding="utf-8") as f:
    f.write("\n".join(sq) + "\n")

# ============ 7. 写 O-D pairs CSV（JMeter CSV Data Set：列 startId,endId,hops） ============
with open(os.path.join(HERE, "od_pairs.csv"), "w", encoding="utf-8") as f:
    for s, e, h in pairs:
        f.write(f"{s},{e},{h}\n")

print(f"\n已生成 3 个文件到 {HERE}:")
for fn in ["bench_data.cypher", "bench_data.sql", "od_pairs.csv"]:
    p = os.path.join(HERE, fn)
    print(f"  {fn}  ({os.path.getsize(p)} bytes)")
print("\n下一步：按方案文档 Step 2/3 分别灌入 Neo4j 和 MySQL")
