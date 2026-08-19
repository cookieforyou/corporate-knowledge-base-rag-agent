package com.enterprise.kb.admin.dto;

/**
 * 词表热更新触发回执（v2.53 词表 DB 单轨，POST /reload）。
 *
 * @param source         当前词表源标识（file | db）
 * @param reloaded       本次重载是否成功替换快照（false = fail-keep 保旧）
 * @param injectionCount 当前注入侧快照条数
 * @param outputCount    当前输出侧快照条数
 */
public record ReloadResult(
    String source,
    boolean reloaded,
    int injectionCount,
    int outputCount
) {
}
