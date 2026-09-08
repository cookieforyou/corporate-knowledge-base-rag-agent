-- V3: kb_session 会话链路归属（簇⑥ E2E 补强四：会话回显恢复对应链路 tab）
-- mode = rag|tool|agent，归档首建写入（首轮归属，已存在不覆写）；
-- 存量会话 NULL → 前端降级不切 tab。链路逐轮事实源仍是 kb_audit_log.mode（审计视角）。
ALTER TABLE kb_session ADD COLUMN IF NOT EXISTS mode VARCHAR(10);
