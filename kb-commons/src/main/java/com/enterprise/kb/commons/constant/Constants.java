package com.enterprise.kb.commons.constant;

/**
 * 全局常量定义
 */
public final class Constants {

    private Constants() {}

    /** 默认分页大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大分页大小 */
    public static final int MAX_PAGE_SIZE = 100;

    // 注：检索调优参数（topK / RRF_K / 召回倍数 / 相似度阈值 / 单路超时）已于簇① A3
    // 收编为 rag.retrieval.* 配置组（kb-ai-core RetrievalProperties），不再以常量硬编码。
}
