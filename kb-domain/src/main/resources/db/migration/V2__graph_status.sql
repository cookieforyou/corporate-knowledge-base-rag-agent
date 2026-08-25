-- V2: kb_document 图谱构建状态（Phase 5 簇④ GraphRAG）
-- 抽取是 ETL 成功后的异步旁路管道，独立状态机追踪每文档图覆盖形态；
-- 缺省 PENDING = 存量文档待回填（回填任务按 PENDING/FAILED 选目标）。
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS graph_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS graph_updated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_doc_graph_status ON kb_document (graph_status);
