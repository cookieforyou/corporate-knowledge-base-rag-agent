package com.enterprise.kb.etl.pipeline.graph;

import java.util.List;

/**
 * 知识图谱结构化抽取输出（簇④ 5.1，{@code .entity()} 映射目标）。
 *
 * <p>纯 record 无注解——字段名即 JSON 契约（结构化输出转换器按名映射），
 * 规避 Jackson 3 命名空间注解面（坑位⑬ 同源纪律：不引注解即无命名空间风险）。
 *
 * @param entities  抽取实体（可空列表 = 目标片段无明确实体）
 * @param relations 抽取关系（源/目标以实体名为引用，由服务侧解析为实体 ID）
 */
public record ExtractionResult(
    List<EntityExtraction> entities,
    List<RelationExtraction> relations) {

    /**
     * @param name        实体规范全称
     * @param type        实体类型（PERSON/ORG/PRODUCT/CONCEPT/LOCATION/TECH/EVENT/OTHER）
     * @param description 一句话描述（≤100 字，嵌入语料——须携带可区分信息）
     */
    public record EntityExtraction(String name, String type, String description) {
    }

    /**
     * @param sourceName   源实体名（须在本轮实体集内，越集关系由服务侧丢弃）
     * @param targetName   目标实体名
     * @param relationType 关系类型标识（大写英文短词）
     * @param description  关系描述（≤50 字）
     */
    public record RelationExtraction(String sourceName, String targetName,
                                     String relationType, String description) {
    }
}
