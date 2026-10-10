"""
实验四：MongoDB 地理围栏压测数据生成
生成 10w 条作业范围多边形（type=9，bid 9200001+，与真实数据隔离）：
  - 聚集在武汉/成都/北京三个城市圈（模拟真实快递员分布密度）
  - 每个多边形为 5-8 顶点的随机凸包近似（小型作业范围，0.01-0.05 经纬度跨度）
  - 生成坐标查询 CSV：1000 个随机点（按城市圈分布），供 JMeter 参数化

输出（本脚本目录）：
  bench_polygons.jsonl   MongoDB mongoimport 直接可灌
  od_points.csv          JMeter CSV：longitude,latitude
"""
import json
import math
import os
import random

TOTAL = 100_000
POINT_SAMPLES = 1_000
BID_START = 9_200_001
TYPE_BENCH = 2   # 与真实快递员同类型(枚举只认1/2, 查询参数必须合法); 隔离靠 bid>=9200001

# 城市圈: (中心经度, 中心纬度, 权重)
HUBS = [
    (114.305, 30.593, 0.4),   # 武汉
    (104.065, 30.657, 0.35),  # 成都
    (116.407, 39.904, 0.25),  # 北京
]

random.seed(42)
HERE = os.path.dirname(os.path.abspath(__file__))

def pick_hub():
    r = random.random()
    acc = 0
    for lng, lat, w in HUBS:
        acc += w
        if r <= acc:
            return lng, lat
    return HUBS[0][0], HUBS[0][1]

def make_polygon(center_lng, center_lat):
    """严格凸正 n 边形（顶点按极角均分、固定半径）：数学上保证简单环不自交，2dsphere 合法。
    v1 版随机顶点半径会产生自交环（Edges cross），被 MongoDB 拒绝写入。"""
    n = random.randint(5, 8)
    radius = random.uniform(0.01, 0.05)
    start = random.uniform(0, 2 * math.pi)
    coords = []
    for k in range(n):
        a = start + k * 2 * math.pi / n
        coords.append([round(center_lng + radius * math.cos(a), 6),
                       round(center_lat + radius * math.sin(a), 6)])
    coords.append(coords[0])  # GeoJSON polygon 首尾闭合
    return [coords]

def main():
    poly_file = os.path.join(HERE, "bench_polygons.jsonl")
    csv_file = os.path.join(HERE, "od_points.csv")

    with open(poly_file, "w") as f:
        for i in range(TOTAL):
            lng, lat = pick_hub()
            doc = {
                "bid": BID_START + i,
                "type": TYPE_BENCH,
                "polygon": {"type": "Polygon", "coordinates": make_polygon(lng, lat)},
            }
            f.write(json.dumps(doc) + "\n")

    with open(csv_file, "w") as f:
        for _ in range(POINT_SAMPLES):
            # 查询点：以多边形聚集区为中心 ±0.08 度撒点，保证相当比例落在多边形内
            lng, lat = pick_hub()
            f.write(f"{round(lng + random.uniform(-0.08, 0.08), 6)},"
                    f"{round(lat + random.uniform(-0.08, 0.08), 6)}\n")

    print(f"已生成 {TOTAL} 条多边形 → {poly_file}")
    print(f"已生成 {POINT_SAMPLES} 个查询坐标 → {csv_file}")
    print("下一步：按方案 Step 2 灌入 MongoDB")

if __name__ == "__main__":
    main()
