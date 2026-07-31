package com.enterprise.kb.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * kb_chunks 索引初始化器 —— 启动时幂等创建（存在即跳过）
 *
 * <p>mapping 定义见 classpath:es/kb_chunks-index.json（ik 双模式分词，
 * 前置 E1：服务端 ES 已安装与版本对应的 analysis-ik 插件）。
 *
 * <p>初始化失败不阻断应用启动（ES 为向量库的从属副本，第十四章索引重建任务可兜底），
 * 仅记录错误日志——但混合检索（Phase 2.6+）将不可用，需及时处理。
 */
@Slf4j
@Component
public class EsIndexInitializer {

    private final ElasticsearchClient esClient;

    public EsIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
        try {
            boolean exists = esClient.indices()
                .exists(e -> e.index(EsChunkDoc.INDEX)).value();
            if (exists) {
                log.info("ES 索引 [{}] 已存在，跳过创建", EsChunkDoc.INDEX);
                return;
            }
            try (InputStream is = new ClassPathResource("es/kb_chunks-index.json").getInputStream()) {
                esClient.indices().create(c -> c.index(EsChunkDoc.INDEX).withJson(is));
                log.info("ES 索引 [{}] 创建成功（ik_max_word 索引 / ik_smart 检索）", EsChunkDoc.INDEX);
            }
        } catch (Exception e) {
            log.error("ES 索引 [{}] 初始化失败（ik 插件是否已装？ES 是否可达？）——混合检索将不可用: {}",
                EsChunkDoc.INDEX, e.getMessage(), e);
        }
    }
}
