-- ============================================
-- PostgreSQL 18 DDL — 企业知识库 RAG Agent
-- 与 JPA Entity（ddl-auto=validate）配套使用
-- ============================================

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
CREATE TABLE IF NOT EXISTS kb_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(100),
    session_id      VARCHAR(36),
    user_id         VARCHAR(50),
    tenant_id       VARCHAR(36),
    query_text      TEXT NOT NULL,
    rewritten_query TEXT,
    retrieval_type  VARCHAR(30),
    retrieved_chunks JSONB,
    reranked_chunks  JSONB,
    final_answer    TEXT,
    model_name      VARCHAR(100),
    latency_ms      INT,
    token_usage     JSONB,
    feedback        VARCHAR(10),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_trace ON kb_audit_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_audit_session ON kb_audit_log (session_id);
CREATE INDEX IF NOT EXISTS idx_audit_user ON kb_audit_log (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON kb_audit_log (created_at DESC);

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
