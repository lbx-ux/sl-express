#!/bin/bash
# 实验三 3c：Leaf DB 故障注入测试
# 用法（Git Bash）：
#   1. 先把下方 MYSQL_CONTAINER 改成实际容器名（虚拟机里 docker ps | grep mysql）
#   2. 确认虚拟机可 ssh 免密：ssh root@192.168.150.101（或改用 VMware 手动配合，见方案文档）
#   3. bash failover_test.sh
#
# 输出：failover_result.txt（四元组：停库时刻/持续发号时长/期间发号个数/自愈时长）
#       failover_ids.txt（故障期间取到的 id，供判重）

LEAF_HOST="192.168.150.101"
LEAF_PORT="28838"
BIZ="bench_stress"
VM_HOST="root@192.168.150.101"
MYSQL_CONTAINER="mysql"          # ←←← 改成 Step 0 查到的实际容器名
WARMUP_SECONDS=30                # 预热时长（让双 buffer 就绪）
STOP_SECONDS=60                  # 停库时长

LEAF_URL="http://${LEAF_HOST}:${LEAF_PORT}/api/segment/get/${BIZ}"

echo "== 0. 前置检查 =="
first_id=$(curl -s -m 5 "$LEAF_URL")
if ! [[ "$first_id" =~ ^[0-9]+$ ]]; then
  echo "❌ Leaf 不可达或返回异常: '$first_id'"; exit 1
fi
echo "Leaf 正常，样例 id: $first_id"

echo "== 1. 预热 ${WARMUP_SECONDS}s（把双 buffer 跑热）=="
end=$((SECONDS + WARMUP_SECONDS))
while [ $SECONDS -lt $end ]; do curl -s -m 5 "$LEAF_URL" > /dev/null; done
echo "预热完成，监控页当前状态："
curl -s -m 5 "http://${LEAF_HOST}:${LEAF_PORT}/cache" | grep -oE "<td>[^<]+</td>" | sed 's/<[^>]*>//g' | head -10

echo "== 2. 启动持续取号（后台），随后立即停库 =="
: > failover_ids.txt
: > failover_log.txt
# 后台取号循环：成功→id 写入文件并打时间戳；失败→记录错误和时间戳，继续尝试（观察自愈）
(
  while true; do
    ts=$(date +%s%3N)
    id=$(curl -s -m 3 "$LEAF_URL")
    if [[ "$id" =~ ^[0-9]+$ ]]; then
      echo "$ts OK $id" >> failover_log.txt
      echo "$id" >> failover_ids.txt
    else
      echo "$ts ERR $id" >> failover_log.txt
    fi
    sleep 0.05   # ~20 次/秒，避免把单核虚拟机打满影响停库操作
  done
) &
FETCHER_PID=$!
sleep 2   # 让取号循环先跑起来

echo "-- T0: 停库 $(date '+%H:%M:%S.%3N') --"
T0=$(date +%s%3N)
ssh -o BatchMode=yes "$VM_HOST" "docker stop ${MYSQL_CONTAINER}" && echo "mysql 已停止"

echo "-- 等待 ${STOP_SECONDS}s（观察持续发号）--"
sleep "$STOP_SECONDS"

echo "-- 恢复: 重启 mysql --"
ssh -o BatchMode=yes "$VM_HOST" "docker start ${MYSQL_CONTAINER}"
sleep 8   # 等 MySQL 就绪

# 再观察 30s 自愈情况
sleep 30
kill $FETCHER_PID 2>/dev/null
wait $FETCHER_PID 2>/dev/null

echo "== 3. 汇总 =="
python - << 'PYEOF'
import datetime

T0 = None
lines = open('failover_log.txt').read().splitlines()
# T0 = 停库时刻：日志里第一个 ERR 之前的最后一个 OK 时间点附近；用脚本里的时间戳更准——这里以第一个 ERR 为界
first_err_ts = next((int(l.split()[0]) for l in lines if l.split()[1] == 'ERR'), None)
ok_ts = [int(l.split()[0]) for l in lines if l.split()[1] == 'OK']
ids = [l.split()[2] for l in lines if l.split()[1] == 'OK']

if first_err_ts:
    # 持续发号 = T0 到第一个 ERR 之间（用相对差值近似，误差 <100ms）
    print(f"OK 请求数: {len(ids)}, ERR 请求数: {sum(1 for l in lines if l.split()[1]=='ERR')}")
    print(f"首个失败距离压测开始: {(first_err_ts - min(ok_ts))/1000:.1f}s（含停库前 2s 预跑）")
    # 恢复判定：最后一个 ERR 之后的第一个 OK
    last_err_ts = max((int(l.split()[0]) for l in lines if l.split()[1] == 'ERR'), default=None)
    recover = [ts for ts in ok_ts if last_err_ts and ts > last_err_ts]
    if last_err_ts and recover:
        print(f"恢复自愈: 最后失败后 {(recover[0]-last_err_ts)/1000:.1f}s 内恢复发号")
    else:
        print("⚠️ 未观察到自愈（可能停库时长内号段耗尽后未恢复，检查 mysql 状态）")
    print(f"判重: collected={len(ids)}, unique={len(set(ids))}, duplicates={len(ids)-len(set(ids))}")
    open('failover_result.txt', 'w').write(
        f"期间发号个数: {len(ids)}\n唯一 id: {len(set(ids))}\n"
        f"持续发号时长: 参考『首个失败距离压测开始』\n"
        f"明细见 failover_log.txt\n")
else:
    print(f"✅ 全程 {len(lines)} 次请求零失败——DB 停机期间发号完全无感（双 buffer 未耗尽）")
    print(f"期间发号: {len(ids)} 个, 判重: unique={len(set(ids))}")
PYEOF

echo "明细: failover_log.txt / failover_ids.txt / failover_result.txt"
echo "测完记得: DELETE FROM leaf_alloc WHERE biz_tag='bench_stress'; 并确认 mysql 容器运行中"
