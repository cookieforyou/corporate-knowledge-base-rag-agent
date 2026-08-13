package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 集成测试容器基类（簇⑥ D3）——三容器共享单例，整个 IT 套件只启动一次：
 *
 * <ul>
 *   <li><b>PG</b>：pgvector/pgvector:pg17，init script 直接复用 kb-domain 的
 *       {@code schema.sql}（classpath，经 kb-ai-core 传递依赖可见）——超级用户执行
 *       CREATE EXTENSION vector + 8 业务表 + kb_embeddings，测试 schema 与生产
 *       DDL 单一来源零漂移；Hibernate ddl-auto=validate 校验 Entity↔DDL 一致</li>
 *   <li><b>Redis</b>：redis-stack-server（RedisJSON + RediSearch）——ChatMemory
 *       仓储 JSON.SET/FT.CREATE 硬依赖模块能力</li>
 *   <li><b>MinIO</b>：文档生命周期 IT（reparse/replace 蓝绿）的原件存储</li>
 * </ul>
 *
 * <p>ES/外部 AI 端点全部指向不可达地址走既有降级路径（ES 双写吞异常、BM25 路
 * 超时降级空、rerank 空 endpoint fusion_score 截断）——检索为纯向量路单路形态。
 */
@SpringBootTest(classes = TestEvalApplication.class)
public abstract class AbstractAdvisorChainIT {

    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17"))
        .withUsername("postgres")   // 超级用户：init script 需 CREATE EXTENSION vector
        .withPassword("kb_it_pw")
        .withDatabaseName("kb_it")
        .withInitScript("schema.sql");

    /**
     * 容器 Redis 密码——必须非空：Redisson 4.6.1 把空串密码视为「有密码」发送
     * AUTH，无密码 redis 拒连（ERR AUTH，表象为 Unable to connect，2026-08-13
     * 对照实验实证）。与生产形态对齐（ECS redis 持真实密码）。
     */
    static final String REDIS_PASSWORD = "kb_it_pw";

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.4.0-v0"))
        // 镜像 entrypoint 经 REDIS_ARGS 追加 redis-server 参数（withCommand 会整体
        // 顶替 CMD=/entrypoint.sh 致 "server" 找不到可执行文件）
        .withEnv("REDIS_ARGS", "--requirepass " + REDIS_PASSWORD)
        .withExposedPorts(6379)
        // 就绪等待：TCP 监听早于命令可执行，Redisson 启动期建连须等真实就绪
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z"))
        .withCommand("server /data")
        .withEnv("MINIO_ROOT_USER", "minioadmin")
        .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    // 静态块显式启动（@Testcontainers 只在标注类生效，基类静态块保证跨类共享单例）
    static {
        try {
            PG.start();
            REDIS.start();
            MINIO.start();
        } catch (Exception e) {
            ExceptionInInitializerError err = new ExceptionInInitializerError(
                "Docker 不可用，集成测试无法执行——请启动 Docker Desktop 后重试；" +
                "CI 无 Docker 环境请加 -DskipITs 跳过。Root cause: " + e.getMessage());
            err.initCause(e);   // 保留原始异常链（镜像拉取/等待策略/init script 细节）
            throw err;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        // ── 数据源（Testcontainers PG）──
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);

        // ── Redis（Redisson + Jedis 会话记忆共享同一连接参数，REDIS_DB 必须 0）──
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        r.add("spring.data.redis.database", () -> "0");

        // ── MinIO（生命周期 IT）──
        r.add("minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        r.add("minio.access-key", () -> "minioadmin");
        r.add("minio.secret-key", () -> "minioadmin");
        r.add("minio.bucket", () -> "kb-documents");

        // ── 向量库：切 pgvector（避 Milvus gRPC 建连）──
        r.add("kb.vector-store.provider", () -> "pgvector");
        r.add("kb.vector-store.pgvector.initialize-schema", () -> "true");

        // ── 模型桩锚点：chat 生产已 none；embedding 让位 OpenAI starter ──
        r.add("spring.ai.model.chat", () -> "none");
        r.add("spring.ai.model.embedding", () -> "none");
        r.add("spring.ai.mcp.client.enabled", () -> "false");
        r.add("rag.routing.fallback.enabled", () -> "false");   // 单模型形态，备用 Bean 不装配

        // ── 意图路由：L1 正则 + L2 桩分类（responseRouter 返回 IntentResult JSON）──
        r.add("rag.routing.intent.enabled", () -> "true");

        // ── 重排：空 endpoint 触发降级（fusion_score 截断）──
        r.add("rag.rerank.endpoint", () -> "");

        // ── 会话记忆：redis-stack 支持 FT.CREATE（kb-eval yml 的 false 覆盖回 true）──
        r.add("spring.ai.chat.memory.redis.initialize-schema", () -> "true");

        // ── 检索形态：ES 不可达 → BM25 路降级空，纯向量单路 ──
        r.add("spring.elasticsearch.uris", () -> "http://localhost:1");
        // 桩向量为 hashing trick 相似度（非真实语义），阈值下调保高重叠语料可召回；
        // 空证据用例以零共享词正交向量触发（相似度 0）
        r.add("rag.retrieval.similarity-threshold", () -> "0.1");

        // ── 护栏测试词表 ──
        r.add("rag.guardrail.output.blacklist", () -> "竞品Alpha,违禁词Beta");

        // ── ETL：NATIVE 解析（Tika 离线）+ 关闭 Contextual 增强（消除 LLM 依赖）──
        r.add("kb.parsing.deep-by-default", () -> "false");
        r.add("kb.etl.contextual.enabled", () -> "false");
    }

    // ── 用例共用 helper ──

    protected static RetrievalContext ctx(String tenantId, String userId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        ctx.setUserId(userId);
        return ctx;
    }

    protected static String sessionId() {
        return UUID.randomUUID().toString();
    }

    /** 向量库种子文档（租户 + 软删标记元数据，FilterExpression 消费） */
    protected static Document doc(String id, String text, String tenantId) {
        return Document.builder().id(id).text(text)
            .metadata(Map.of(
                "tenant_id", tenantId,
                "is_deleted", false,
                "chunk_type", "TEXT",
                "doc_id", "doc-" + id))
            .build();
    }

    /**
     * L2 意图分类桩路由：分类调用（user 文本含「意图分类器」标记）返回
     * IntentResult JSON（KNOWLEDGE + 原消息改写，复刻生产分类器契约）；
     * 其余调用返回 answer——消除分类失败 fail-open 引发的二次改写 LLM 调用。
     */
    protected static Function<String, String> knowledgeRouter(String answer) {
        return knowledgeRouter(() -> answer);
    }

    /** 动态答案变体：路由时经 Supplier 读取（用例内改 defaultAnswer 的场景） */
    protected static Function<String, String> knowledgeRouter(java.util.function.Supplier<String> answerSupplier) {
        return userText -> {
            if (userText != null && userText.contains("意图分类器")) {
                int idx = userText.lastIndexOf("【当前用户消息】");
                String current = idx >= 0
                    ? userText.substring(idx + "【当前用户消息】".length()).trim()
                    : "";
                // 只取首行——结构化输出的格式说明可能追加在同一 user 消息尾部
                int nl = current.indexOf('\n');
                if (nl > 0) {
                    current = current.substring(0, nl).trim();
                }
                return "{\"intent\":\"KNOWLEDGE\",\"rewrittenQuery\":\"" + current + "\"}";
            }
            return answerSupplier.get();
        };
    }
}
