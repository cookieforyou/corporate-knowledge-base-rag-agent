package com.enterprise.kb.eval.dataset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Golden Dataset 加载器 —— 扫描 classpath:golden/*.json（.example 文件不匹配，天然排除）
 *
 * <p>数据集版本化管理在 Git 中（kb-eval/src/main/resources/golden/），
 * 标注工作流见同目录 README-标注指南.md。
 */
@Slf4j
@Component
public class GoldenDatasetLoader {

    private final JsonMapper jsonMapper;

    public GoldenDatasetLoader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<GoldenQAPair> loadAll() {
        List<GoldenQAPair> all = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:golden/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    List<GoldenQAPair> pairs = jsonMapper.readValue(is, new TypeReference<List<GoldenQAPair>>() {});
                    all.addAll(pairs);
                    log.info("加载 Golden Dataset: {} → {} 条", resource.getFilename(), pairs.size());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Golden Dataset 加载失败", e);
        }
        log.info("Golden Dataset 合计 {} 条", all.size());
        return all;
    }
}
