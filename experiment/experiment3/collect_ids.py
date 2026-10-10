"""
实验三 3b：Leaf 并发取号唯一性校验
50 线程并发取号，收集 10w 个 id，结束后判重。

用法（先确认 bench_stress 已创建，step>=10000）：
    python collect_ids.py

输出：
    ids_collected.txt   采集到的全部 id（留档）
    控制台               collected / unique / duplicates 结论
"""
import http.client
import threading
import time
from collections import Counter

HOST, PORT = "192.168.150.101", 28838
BIZ = "bench_stress"
TARGET = 100_000        # 目标采集数
THREADS = 50            # 并发线程数
TIMEOUT = 10            # 单次请求超时（秒）

ids = []
lock = threading.Lock()
errors = []

def worker(total_per_thread: int):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
    local = []
    for _ in range(total_per_thread):
        try:
            conn.request("GET", f"/api/segment/get/{BIZ}")
            resp = conn.getresponse()
            body = resp.read().decode().strip()
            if resp.status == 200 and body.isdigit():
                local.append(body)
            else:
                errors.append(f"HTTP {resp.status}: {body[:50]}")
        except Exception as e:
            errors.append(f"{type(e).__name__}: {e}")
            try:
                conn.close()
            except Exception:
                pass
            conn = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
    conn.close()
    with lock:
        ids.extend(local)

def main():
    per_thread = TARGET // THREADS
    print(f"目标 {TARGET} 个 id，{THREADS} 线程 × {per_thread} 次/线程，biz_tag={BIZ}")
    t0 = time.time()
    threads = [threading.Thread(target=worker, args=(per_thread,)) for _ in range(THREADS)]
    for t in threads: t.start()
    for t in threads: t.join()
    elapsed = time.time() - t0

    dup = Counter(ids)
    duplicates = {k: v for k, v in dup.items() if v > 1}
    unique = len(dup)

    with open("ids_collected.txt", "w") as f:
        f.write("\n".join(ids))

    print(f"\n===== 唯一性校验结果 =====")
    print(f"耗时: {elapsed:.1f}s（采集吞吐 {len(ids)/elapsed:.0f} id/s）")
    print(f"collected={len(ids)}, unique={unique}, duplicates={len(duplicates)}")
    if errors:
        print(f"请求错误 {len(errors)} 个（样例: {errors[:3]}）")
    if duplicates:
        print(f"❌ 发现重复！样例: {list(duplicates.items())[:5]}")
    elif len(ids) == TARGET:
        print("✅ 零重复 —— 简历可写「10w 并发取号零重复」")
    # 客户端吞吐仅参考：多线程 + 每线程一连接，瓶颈在客户端侧
    print("\n留档: ids_collected.txt")

if __name__ == "__main__":
    main()
