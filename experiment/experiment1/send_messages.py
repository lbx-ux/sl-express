"""
实验一 · 数据准备脚本 2/2：向 RabbitMQ 批量发布运单消息
读取 messages.jsonl，经 RabbitMQ Management HTTP API 发布到
交换机 sl.exchange.topic.transportOrder.delayed（vhost=/dispatch，routingKey=JOIN_DISPATCH）

用法：python send_messages.py
前置：generate_messages.py 已产出 messages.jsonl；dispatch 服务已启动（消费消息入 Redis）
"""
import json
import time
import base64
import urllib.request

MQ_API = "http://192.168.150.101:15672/api/exchanges/%2Fdispatch/sl.exchange.topic.transportOrder.delayed/publish"
USER = ("sl", "sl321")
ROUTING_KEY = "JOIN_DISPATCH"
BATCH_PAUSE = 0.05   # 每条间隔 50ms（20/s），5w 条约 42 分钟；dispatch 消费能力 >> 20/s，Redis 队列会持续增长

AUTH = base64.b64encode(f"{USER[0]}:{USER[1]}".encode()).decode()

def publish_one(payload: str) -> bool:
    body = {
        "properties": {"content_type": "application/json", "delivery_mode": 2},
        "routing_key": ROUTING_KEY,
        "payload": payload,
        "payload_encoding": "string",
    }
    req = urllib.request.Request(MQ_API, data=json.dumps(body).encode("utf-8"), method="POST")
    req.add_header("Authorization", f"Basic {AUTH}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read().decode()).get("published", False)
    except Exception as e:
        print(f"  发布失败: {e}")
        return False

def main():
    msgs = [json.loads(l) for l in open("messages.jsonl", encoding="utf-8")]
    total = len(msgs)
    print(f"发布 {total} 条消息（vhost=/dispatch, routingKey={ROUTING_KEY}），间隔 50ms")
    print("⚠️ 确认 dispatch 服务已启动（消费消息合并入 Redis）")
    t0 = time.time()
    ok = fail = 0
    for i, m in enumerate(msgs, 1):
        if publish_one(json.dumps(m)):
            ok += 1
        else:
            fail += 1
        if i % 500 == 0:
            print(f"  {i}/{total}  成功 {ok}  失败 {fail}  速率 {i/(time.time()-t0):.0f}/s")
        time.sleep(BATCH_PAUSE)
    print(f"完成: 成功 {ok}, 失败 {fail}, 总耗时 {(time.time()-t0)/60:.1f} 分钟")

if __name__ == "__main__":
    main()
