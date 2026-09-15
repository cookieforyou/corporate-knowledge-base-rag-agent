"""A2A 端点官方 client 冒烟（Phase5簇⑥ 批2 DT2 步骤 9，a2a-sdk 1.1.2 实测 API）

用途：标准 A2A Client 视角的端到端回归冒烟——拉卡（带 Bearer）→ SendMessage
同步问答 → 断言 TASK_STATE_COMPLETED / contextId 回显 / [ref-N] 锚定。
服务端为自研 v1.0 协议层（A2aController），本脚本即互操作性对照样例。

运行（依赖 a2a-sdk>=1.0、httpx；a2a-sdk 1.1.2 为 protobuf-first 形态实测）：
  pip install a2a-sdk
  export CHAT_JWT='<前端登录的 JWT>'
  python3 a2a_client_check.py            # 可选 CHAT_BASE_URL，缺省 http://localhost:8090
"""
import asyncio, os, sys, uuid, httpx
from a2a.client import ClientConfig, ClientFactory
from a2a.types.a2a_pb2 import Message, Part, Role, SendMessageRequest, TaskState

BASE_URL = os.environ.get("CHAT_BASE_URL", "http://localhost:8090")
QUESTION = "DDD 战术设计是怎样的"    # 换任一知识库内问题


async def main():
  token = os.environ.get("CHAT_JWT", "").strip()
  if not token:
    sys.exit("缺少 CHAT_JWT（前端登录后取 Authorization Bearer 值）")
  # Bearer 经共享 httpx_client 注入：拉卡与发消息同源带认证
  async with httpx.AsyncClient(
      headers={"Authorization": f"Bearer {token}"},
      timeout=180,                # GLM 思考形态 10-30s，默认 5s 必超时
  ) as hc:
    factory = ClientFactory(ClientConfig(
        httpx_client=hc,
        streaming=False,                     # 服务端 capabilities.streaming=false
        accepted_output_modes=["text/plain"],
    ))
    # 拉卡（resolver 复用同 client，带 Bearer）+ 建 client（端点取 Card 声明 url；
    # factory 自动注 A2A-Version: 1.0 头，setdefault 不覆盖上方 Authorization）
    client = await factory.create_from_url(BASE_URL)
    print("拉卡成功（无 401）→ client 建立")

    msg = Message(
        message_id="m-" + uuid.uuid4().hex[:8],
        role=Role.ROLE_USER,
        parts=[Part(text=QUESTION)],
    )
    async for resp in client.send_message(SendMessageRequest(message=msg)):
      if not resp.HasField("task"):
        continue
      t = resp.task
      print("state     =", TaskState.Name(t.status.state))   # 预期 TASK_STATE_COMPLETED
      print("contextId =", t.context_id)                     # 预期非空回显
      for a in t.artifacts:
        for p in a.parts:
          print("answer    =", p.text[:200], "…")            # 预期含 [ref-N]


if __name__ == "__main__":
  asyncio.run(main())
