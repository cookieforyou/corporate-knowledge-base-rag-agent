-- ============================================
-- PostgreSQL 18 DDL — 企业知识库 RAG Agent
-- 与 JPA Entity（ddl-auto=validate）配套使用
-- ============================================

-- 0. pgvector 扩展（须超级用户权限；存量库已装则 IF NOT EXISTS 空操作。
--    2026-08-13 簇⑥ D3：收入文件使 DDL 自包含——Testcontainers init script
--    以 postgres 超级用户直接执行本文件建全套 schema）
CREATE EXTENSION IF NOT EXISTS vector;

-- 1. 文档主表
CREATE TABLE IF NOT EXISTS kb_document (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(36) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    original_name   VARCHAR(500),
    type            VARCHAR(20) NOT NULL,
    size            BIGINT,
    oss_path        VARCHAR(500),
    status          VARCHAR(20) DEFAULT 'UPLOADING',
    parse_route     VARCHAR(20),
    page_count      INT,
    table_count     INT,
    image_count     INT,
    chunk_count     INT,
    error_message   TEXT,
    -- 簇⑥ C1 增量重入库：版本号（首次入库 1，每次重入库成功 +1）
    -- 存量库升级 DDL：
    --   ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;
    version         INT NOT NULL DEFAULT 1,
    created_by      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tenant_status ON kb_document (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_created_at ON kb_document (created_at);

-- 2. 章节表
CREATE TABLE IF NOT EXISTS kb_section (
    id              VARCHAR(36) PRIMARY KEY,
    doc_id          VARCHAR(36) NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    parent_id       VARCHAR(36),
    title           VARCHAR(500),
    level           INT DEFAULT 1,
    order_index     INT,
    page_start      INT,
    page_end        INT
);
CREATE INDEX IF NOT EXISTS idx_doc_section ON kb_section (doc_id, order_index);

-- 3. 切分块表（核心业务表）
CREATE TABLE IF NOT EXISTS kb_chunk (
    id              VARCHAR(36) PRIMARY KEY,
    doc_id          VARCHAR(36) NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    section_id      VARCHAR(36),
    chunk_index     INT NOT NULL,
    content         TEXT NOT NULL,
    original_content TEXT,
    page_num        INT,
    token_count     INT,
    metadata        JSONB DEFAULT '{}',
    chunk_type      VARCHAR(20) DEFAULT 'TEXT',
    vector_id       VARCHAR(100),
    is_deleted      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_doc_chunk ON kb_chunk (doc_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_vector_id ON kb_chunk (vector_id);
CREATE INDEX IF NOT EXISTS idx_chunk_type ON kb_chunk (chunk_type);
CREATE INDEX IF NOT EXISTS idx_ft_content ON kb_chunk USING GIN (to_tsvector('simple', content));

-- 4. 会话表
CREATE TABLE IF NOT EXISTS kb_session (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(36) NOT NULL,
    user_id         VARCHAR(50) NOT NULL,
    title           VARCHAR(255),
    knowledge_base  VARCHAR(100),
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    message_count   INT DEFAULT 0,
    total_tokens    BIGINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_session ON kb_session (user_id, updated_at DESC);

-- 5. 消息表
CREATE TABLE IF NOT EXISTS kb_message (
    id              VARCHAR(36) PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL REFERENCES kb_session(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT,
    citations       JSONB,
    token_usage     JSONB,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_session_msg ON kb_message (session_id, created_at);

-- 6. 审计日志表
-- v2.10 扩展（3.12 审计落地，双链路时代补列）：mode/status/error_code/tool_calls
-- v2.34 扩展（Phase 4 簇④ 4.7 Bad Case 闭环）：root_cause 根因标注
-- v2.43 扩展（安全簇① T7 FLAG 观察语义）：guardrail_flags 观察标记
-- 存量库升级 DDL：
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS mode VARCHAR(10);
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS status VARCHAR(20);
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS error_code VARCHAR(50);
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS tool_calls JSONB;
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS root_cause VARCHAR(20);
--   ALTER TABLE kb_audit_log ADD COLUMN IF NOT EXISTS guardrail_flags VARCHAR(255);
--   CREATE INDEX IF NOT EXISTS idx_audit_tenant_created ON kb_audit_log (tenant_id, created_at DESC);
CREATE TABLE IF NOT EXISTS kb_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(100),
    session_id      VARCHAR(36),
    user_id         VARCHAR(50),
    tenant_id       VARCHAR(36),
    mode            VARCHAR(10),
    query_text      TEXT NOT NULL,
    rewritten_query TEXT,
    retrieval_type  VARCHAR(30),
    retrieved_chunks JSONB,
    reranked_chunks  JSONB,
    final_answer    TEXT,
    tool_calls      JSONB,
    model_name      VARCHAR(100),
    latency_ms      INT,
    token_usage     JSONB,
    status          VARCHAR(20),
    error_code      VARCHAR(50),
    feedback        VARCHAR(10),
    root_cause      VARCHAR(20),
    guardrail_flags VARCHAR(255),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_trace ON kb_audit_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_audit_session ON kb_audit_log (session_id);
CREATE INDEX IF NOT EXISTS idx_audit_user ON kb_audit_log (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON kb_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_created ON kb_audit_log (tenant_id, created_at DESC);

-- 7. 用户反馈表
CREATE TABLE IF NOT EXISTS kb_feedback (
    id              VARCHAR(36) PRIMARY KEY,
    message_id      VARCHAR(36) NOT NULL REFERENCES kb_message(id),
    audit_log_id    BIGINT REFERENCES kb_audit_log(id),
    user_id         VARCHAR(50),
    rating          VARCHAR(10) NOT NULL,
    expected_answer TEXT,
    feedback_tags   JSONB,
    resolved        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 8. Prompt 模板表
CREATE TABLE IF NOT EXISTS kb_prompt_template (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    version         VARCHAR(20) NOT NULL,
    template_text   TEXT NOT NULL,
    variables       JSONB,
    status          VARCHAR(20) DEFAULT 'DRAFT',
    ab_test_group   VARCHAR(20),
    created_by      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_name_version UNIQUE (name, version)
);

-- ============================================
-- 9. pgvector 向量表（需先以 superuser 执行: CREATE EXTENSION IF NOT EXISTS vector）
--    之后以应用用户执行本 DDL 创建表与索引
-- ============================================
CREATE TABLE IF NOT EXISTS kb_embeddings (
    id          VARCHAR(36) PRIMARY KEY,
    embedding   vector(1024),
    content     TEXT,
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_embedding_hnsw
    ON kb_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_emb_metadata
    ON kb_embeddings USING GIN (metadata);

-- ============================================
-- 10. 护栏词项表（v2.53 词表 DB 单轨，设计 12.7 词表工程）
--     Plan C 修订形态唯一事实源（v2.52 钉死复审推荐：DB 单轨 + git 导出存档）；
--     value_b64 恒为逐条 Base64 编码态（第七节敏感词交付纪律条 2）；
--     去重键 (side, type, fingerprint) 对齐 import_words.py 幂等口径。
-- ============================================
CREATE TABLE IF NOT EXISTS kb_guardrail_rule (
    id          VARCHAR(50) PRIMARY KEY,
    side        VARCHAR(10) NOT NULL,
    family      VARCHAR(40) NOT NULL,
    lang        VARCHAR(10) NOT NULL DEFAULT '',
    type        VARCHAR(10) NOT NULL,
    value_b64   TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    action      VARCHAR(10) NOT NULL DEFAULT 'FLAG',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    origin      VARCHAR(20) NOT NULL DEFAULT 'API',
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gr_dedup UNIQUE (side, type, fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_gr_side_family ON kb_guardrail_rule (side, family);
