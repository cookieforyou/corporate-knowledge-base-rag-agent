# frontend — 企业知识库 RAG Agent 工作台前端

> Vue 3.5 + TypeScript 5.6 + Element Plus + Pinia + Vite 6。六个页面：Login / Chat（溯源对话）/ Documents / Debug（检索调试台）/ Chunks（观测台）/ Admin（运维中心五 Tab）。
>
> 后端契约见 [API 文档](../docs/delivery/API文档.md)；设计见 [11 章](../docs/project-implement/11-Agent对话链路.md) / [12.12](../docs/project-implement/12-安全护栏体系.md)。

## 1. 快速开始

```bash
cd frontend
npm install
cp .env.example .env      # 填 Casdoor 地址/clientId；BACKEND_URL 指向后端
npm run dev               # Vite dev server: http://localhost:5173
```

- 后端本地约定端口 **8090**（`SERVER_PORT=8090`；不设时后端缺省 8080）。
- `npm run build` = `vue-tsc && vite build`，产物 `dist/`；`npm run preview` 本地预览。

## 2. 环境变量

| 变量 | 作用 | 说明 |
|---|---|---|
| `VITE_AUTH_URL` | Casdoor 地址 | **构建期内联**；换机构建必须携带，缺失时登录必败且构建不报错 |
| `VITE_AUTH_CLIENT_ID` | OAuth2 client id | 同上 |
| `BACKEND_URL` | Vite dev `server.proxy` 的 `/api`、`/ws` 目标 | 仅开发期；生产由宿主 nginx 同源反代，产物走相对路径 |

> `.env` 不入 git；生产前端产物不读运行时 env（源码核验：axios `'/api/v1'`、SSE 相对路径、WS 同源）。

## 3. 页面与权限

| 路由 | 页面 | 能力 | 权限 |
|---|---|---|---|
| `/login` | Login | Casdoor OAuth2/PKCE 登录 | 公开 |
| `/chat` | Chat | 三模式（rag/tool/agent）+ 多轮会话 + SSE 实时 token/溯源/委派/审批卡片 + 历史恢复 + 反馈 | 全员 |
| `/documents` | Documents | 上传/列表/详情/chunks/删除/重解析/替换 | 上传与读全员；删除/重解析/替换租户管理员 |
| `/debug` | Debug | 直调检索链，三路得分/降级/耗时 | 全员 |
| `/chunks` | Chunks | 文档 Chunk 观测、原文查看、软删/恢复/编辑 | 读全员；写租户管理员 |
| `/admin` | Admin | 仪表盘 / Chunk 运维 / 日志查询 / Bad Case / 护栏词表 | 仪表盘全员；前四 Tab 租户管理员；第五 Tab 系统超管 |

**三层角色**：`isAdmin`（租户管理员 → `ROLE_ADMIN`）/ `owner=built-in` 超管（追加 `ROLE_SUPER_ADMIN`）。前端 `auth` store 解析 JWT payload 暴露 `isAdmin`/`isSuperAdmin`，仅用于收纳；**后端才是边界**（`/api/v1/admin/**` filter-chain + 治理写 `@PreAuthorize`）。

## 4. 对话协议与渲染

- **发送**：`POST /api/v1/chat/stream`（SSE），body `{query, sessionId?, mode, approvedToolCallId?}`。
- **帧族**：无名 `TOKEN` / `ERROR` / `DONE`（JSON 载荷含 messageId/traceId）+ 命名 `TRACE`（溯源）/ `TOOL_CALL`（工具/委派/审批，实时 + 流末兜底）/ `REPLACE`（输出护栏追回整段替换）/ `PROGRESS`（三链路进度 + 心跳）。
- **渲染**：`marked` + `DOMPurify`；`[ref-N]` 先替换为透明占位 token，整体 sanitize 后换回徽标（保证内联不割裂）；溯源面板/委派卡片/审批卡片随消息历史回显。
- **历史**：`GET /api/v1/sessions` + `/{id}/messages`；会话归属 `mode` 恢复对应 Tab；`citations`/`toolCalls` 来自归档。
- **反馈**：`POST /api/v1/feedback`（messageId upsert，可改评）。

## 5. 目录结构

```
frontend/
├── index.html
├── vite.config.ts          # /api /ws dev proxy（BACKEND_URL）
├── .env.example
└── src/
    ├── main.ts / App.vue
    ├── api/index.ts        # axios 封装 + 全部端点/类型
    ├── router/index.ts     # 路由 + 登录守卫
    ├── stores/
    │   ├── auth.ts         # token / isAdmin / isSuperAdmin
    │   └── chat.ts         # 会话、消息、SSE 状态、模式
    ├── views/              # Login/Chat/Documents/Debug/Chunks/Admin/Layout
    ├── components/         # SessionList / SourceDialog / ToolCallCard
    ├── composables/markdown.ts
    └── styles/theme.css    # 主题与 chip 色板
```

## 6. 生产部署

1. `npm run build` 出 `dist/`；
2. 托管到宿主 nginx（`infra/nginx/frontend.conf`，root 指向部署目录），`/api`、`/ws` 同源反代到 `127.0.0.1:8090`；
3. Casdoor Redirect URLs 注册前端 origin `/login`；
4. CORS/WS 白名单含生产 origin（同源 POST 也带 Origin 头）。

详见 [生产部署实操手册](../docs/delivery/生产部署实操手册.md) §7 与 [17 章](../docs/project-implement/17-部署与运维.md) §17.4。

## 7. 注意事项

- 对话窗**不提供上传入口**（上传唯一收敛于文档管理页，v2.13 定案）。
- Admin 五 Tab 懒加载：首次进入才拉数；受限 Tab 非超管不渲染。
- 不要把前端隐藏当权限边界；所有治理写以后端 403/400 为准。
- SSE 长连接经 nginx 时需关闭 `proxy_buffering` 并放宽读超时（配置已在 `frontend.conf`）。
