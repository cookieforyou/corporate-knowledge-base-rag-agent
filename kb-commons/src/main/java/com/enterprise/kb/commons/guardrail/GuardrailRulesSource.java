package com.enterprise.kb.commons.guardrail;

import java.util.List;

/**
 * 护栏词表源 SPI（v2.53 词表双形态：file Git Ops / db PG 单轨，设计 12.7 词表工程）——
 * {@link GuardrailRulesRegistry} 的装载抽象，实现按
 * {@code rag.guardrail.rules.source} 条件装配择一：
 * <ul>
 *   <li>{@code file}（缺省）→ {@link FileGuardrailRulesSource}：结构化文件 ∪ CSV
 *       兼容源双源合并（Git Ops 形态，kb-eval 锁版走此源——测量一致性，
 *       eval 运行期词表不随 DB 运营漂移）；</li>
 *   <li>{@code db} → kb-domain {@code DbGuardrailRulesSource}：PG 表唯一事实源
 *       （Plan C 修订形态：DB 单轨 + git 导出存档，v2.52 钉死复审推荐）。</li>
 * </ul>
 *
 * <p>装载失败语义由实现方决定（file 源：缺失回落 / 损坏 fail-fast；
 * db 源：异常上抛），Registry 统一以 fail-keep 承接热重载失败。
 */
public interface GuardrailRulesSource {

    /** 装载注入侧词表全量（运行时形态：KEYWORD 已小写化 / REGEX 已编译）。 */
    List<GuardrailRule> loadInjectionRules();

    /** 装载输出侧词表全量。 */
    List<GuardrailRule> loadOutputRules();

    /** 源标识（运营端点 ReloadResult 回显用）：file | db。 */
    default String sourceName() {
        return "file";
    }

    /**
     * 注入侧词表源位置（重载协调器 mtime 轮询判定 {@code file:} 源用）；
     * 非文件源返回空串 → mtime 通道不适用（DB 源经 pub/sub、运营端点与修订号轮询触发）。
     */
    default String injectionLocation() {
        return "";
    }

    /** 输出侧词表源位置；语义同 {@link #injectionLocation()}。 */
    default String outputLocation() {
        return "";
    }

    /**
     * 词表修订号（v2.113 丢信号兜底轮询，G5 修正为内容指纹）：内容变更即应变化的廉价标识
     * （db 源 = {@code count(*):内容指纹}；时间戳型对直改库无感）。<b>缺省 null = 该源不支持修订号</b>
     * ——file 源由 mtime 轮询覆盖，不重复实现；null 时协调器不启用修订轮询。
     */
    default String revision() {
        return null;
    }
}
