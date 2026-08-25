package com.enterprise.kb.loadtest;

import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * 压测运行参数解析（簇⑥ 批5，v2.59）
 *
 * <p>取值优先级：JVM 系统属性（{@code -Dloadtest.*}）> 环境变量（{@code LOADTEST_*}）> 缺省值。
 * 所有注入规模/阈值均可经此调节，ECS 2 核单机基线下建议按缺省起步，依报告逐步加压。
 *
 * <p><b>JWT 纪律</b>：kb-api 全 /api/** 需 Bearer JWT（Casdoor 签发），压测不自造令牌——
 * 执行前从前端浏览器 localStorage.access_token 取现成 token 注入
 * （{@code -Dloadtest.jwt=...} 或 {@code LOADTEST_JWT}）。token 带 owner claim（租户），
 * 过期即请求 401，长压测前刷新。
 */
public final class LoadTestConfig {

    /** 语料 feeder 文件（kb-eval Golden 干净集按 ID 抽取，生成期已过滤引号/反斜杠/EL 符） */
    public static final String QUERY_FEEDER = "loadtest-queries.json";

    /** SSE 未匹配消息缓冲：await DONE 期间全部 TOKEN 帧入缓冲，长回答可达数千帧 */
    private static final int SSE_UNMATCHED_BUFFER_SIZE = 10_000;

    private LoadTestConfig() {
    }

    // ── 全局 ──

    /** kb-api 基址（机器侧探针场景指向桩时改为桩地址） */
    public static String baseUrl() {
        return resolve("loadtest.baseUrl", "LOADTEST_BASE_URL", "http://localhost:8090");
    }

    /** 压测 JWT（缺失即快速失败，避免整轮压测以 401 空转） */
    public static String jwt() {
        String value = resolve("loadtest.jwt", "LOADTEST_JWT", null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "缺少压测 JWT：请从前端浏览器 localStorage.access_token 获取后经 "
                    + "-Dloadtest.jwt=... 或环境变量 LOADTEST_JWT 注入");
        }
        return value;
    }

    /** 认证协议（所有 kb-api 场景共用）：baseUrl + Bearer 头 + SSE 缓冲扩容 */
    public static HttpProtocolBuilder authenticatedProtocol() {
        return http.baseUrl(baseUrl())
            .header("Authorization", "Bearer " + jwt())
            .sseUnmatchedInboundMessageBufferSize(SSE_UNMATCHED_BUFFER_SIZE);
    }

    /** SSE await 超时（秒）：覆盖真实 LLM 长回答生成时延 */
    public static int awaitSeconds() {
        return intOf("loadtest.await.seconds", "LOADTEST_AWAIT_SECONDS", 120);
    }

    // ── 场景 A：检索真压 ──

    public static int aRate() {
        return intOf("loadtest.a.rate", "LOADTEST_A_RATE", 3);
    }

    public static int aDurationSeconds() {
        return intOf("loadtest.a.duration.seconds", "LOADTEST_A_DURATION_SECONDS", 60);
    }

    /**
     * 4.12 验收线：检索链路 P95（含改写 LLM + 多路召回 + rerank 外部调用）。
     * 簇④ 前双路基线 500；三路融合（向量+BM25+Graph）抬升至 600——图路预算
     * ~100ms 与双路并行（取 max 非求和），+100ms 余量覆盖图路抖动；18 §18.4 同步。
     */
    public static int aP95ThresholdMs() {
        return intOf("loadtest.a.p95.threshold.ms", "LOADTEST_A_P95_THRESHOLD_MS", 600);
    }

    // ── 场景 B：生成桩压 ──

    public static int bUsers() {
        return intOf("loadtest.b.users", "LOADTEST_B_USERS", 50);
    }

    public static int bHoldSeconds() {
        return intOf("loadtest.b.hold.seconds", "LOADTEST_B_HOLD_SECONDS", 120);
    }

    // ── 场景 C：真实 LLM 小样本（计费敏感，显式开启） ──

    public static boolean cEnabled() {
        return boolOf("loadtest.c.enabled", "LOADTEST_C_ENABLED", false);
    }

    public static int cSamples() {
        return intOf("loadtest.c.samples", "LOADTEST_C_SAMPLES", 10);
    }

    /** 4.12 验收线：TTFT P95 < 2s */
    public static int cTtftThresholdMs() {
        return intOf("loadtest.c.ttft.threshold.ms", "LOADTEST_C_TTFT_THRESHOLD_MS", 2000);
    }

    /** 4.12 验收线：TPOT < 100ms */
    public static long cTpotThresholdMs() {
        return longOf("loadtest.c.tpot.threshold.ms", "LOADTEST_C_TPOT_THRESHOLD_MS", 100);
    }

    public static int cTtftTimeoutSeconds() {
        return intOf("loadtest.c.ttft.timeout.seconds", "LOADTEST_C_TTFT_TIMEOUT_SECONDS", 30);
    }

    // ── 场景 D：SSE 长会话稳定 ──

    public static int dUsers() {
        return intOf("loadtest.d.users", "LOADTEST_D_USERS", 20);
    }

    public static int dDurationSeconds() {
        return intOf("loadtest.d.duration.seconds", "LOADTEST_D_DURATION_SECONDS", 180);
    }

    // ── 解析原语 ──

    private static String resolve(String systemProperty, String envName, String defaultValue) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = System.getenv(envName);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return defaultValue;
    }

    private static int intOf(String systemProperty, String envName, int defaultValue) {
        String value = resolve(systemProperty, envName, null);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static long longOf(String systemProperty, String envName, long defaultValue) {
        String value = resolve(systemProperty, envName, null);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    private static boolean boolOf(String systemProperty, String envName, boolean defaultValue) {
        String value = resolve(systemProperty, envName, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }
}
