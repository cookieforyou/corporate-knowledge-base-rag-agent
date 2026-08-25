package com.enterprise.kb.infrastructure.graph;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * 图谱确定性 ID 派生（Phase 5 簇④）。
 *
 * <p>实体 ID = nameUUID v3（租户 × 规范化名称 × 类型）——同名同类型实体跨文档/跨次
 * 抽取收敛为同一节点（合并语义），租户入 ID 物理隔离（跨租户同名不同节点）。
 * 与 {@code kb_chunk.id} 的确定性派生同思路（入库幂等收敛先例）。
 */
public final class GraphIds {

    private GraphIds() {
    }

    /** 实体规范化名称：去首尾空白 + 压缩内部连续空白 + 统一小写（大小写异写收敛同节点） */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 实体确定性 ID（租户域内唯一；不同租户同名实体 ID 不同） */
    public static String entityId(String tenantId, String name, String type) {
        String key = tenantId + "|" + normalizeName(name) + "|" + normalizeName(type);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
