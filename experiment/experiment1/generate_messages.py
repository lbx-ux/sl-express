"""
实验一 · 数据准备脚本 1/2：生成 5w 条运单消息（含 Leaf 真实运单号）
产出：messages.jsonl（每行一条 JSON 消息，send_messages.py 消费）

线路：Neo4j 现有 6 对 AGENCY→OLT 真实线路（cost>0 保证可达）
运单号：调用 Leaf /api/segment/get/transport_order（真实运单号格式 SL+13位）
"""
import json
import random
import time
import urllib.request

LEAF_URL = "http://192.168.150.101:28838/api/segment/get/transport_order"
TOTAL = 50_000
BATCH = 1000

# 6 对真实线路（Neo4j IN_LINE 查询结果，cost>0 可达）
ROUTES = [
    (1024771466287232801, 1024706903290237921),  # 金牛区营业部 → 成都转运中心
    (1024771753995515873, 1024706903290237921),  # 青羊区营业部 → 成都转运中心
    (1024772301733870785, 1024772115791985729),  # 碑林区营业部 → 西安转运中心
    (1024772425923018049, 1024772115791985729),  # 长安区营业部 → 西安转运中心
    (1024980800111765185, 1024980728632436289),  # 昌平区营业部 → 北京转运中心
    (1024980833938827105, 1024980728632436289),  # 顺义区营业部 → 北京转运中心
]

random.seed(42)

def leaf_ids(n):
    """从 Leaf 批量取 n 个运单号（内部串行 HTTP，1w 次 ~20s）"""
    ids = []
    for _ in range(n):
        with urllib.request.urlopen(LEAF_URL, timeout=5) as r:
            body = r.read().decode().strip()
        ids.append("SL" + body.zfill(13) if len(body) < 13 else "SL" + body)
    return ids

def main():
    print(f"生成 {TOTAL} 条运单消息（运单号从 Leaf 现取，约 2-3 分钟）...")
    t0 = time.time()
    with open("messages.jsonl", "w") as f:
        sent = 0
        while sent < TOTAL:
            n = min(BATCH, TOTAL - sent)
            ids = leaf_ids(n)
            for tid in ids:
                start, end = random.choice(ROUTES)
                msg = {
                    "transportOrderId": tid,
                    "currentAgencyId": start,
                    "nextAgencyId": end,
                    "totalWeight": round(random.uniform(0.5, 8.0), 1),
                    "totalVolume": round(random.uniform(0.2, 4.0), 2),
                    "created": int(time.time() * 1000),
                }
                f.write(json.dumps(msg) + "\n")
            sent += n
            print(f"  {sent}/{TOTAL}")
    print(f"完成，耗时 {time.time()-t0:.0f}s → messages.jsonl")

if __name__ == "__main__":
    main()
