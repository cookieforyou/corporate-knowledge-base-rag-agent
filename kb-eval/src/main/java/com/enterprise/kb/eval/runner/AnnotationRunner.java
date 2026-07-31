package com.enterprise.kb.eval.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Golden Dataset 标注辅助器
 *
 * <p>用法：{@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.annotate-query=增值税发票认证期限}
 *
 * <p>对给定问题跑检索探针 Top-10，输出 chunkId / 得分 / 内容片段，
 * 供人工判定 expectedChunkIds 并写入 golden/*.json（工作流见 golden/README-标注指南.md）。
 */
@Slf4j
@Component
public class AnnotationRunner implements ApplicationRunner {

    private final RetrievalProbe probe;

    public AnnotationRunner(List<RetrievalProbe> probes) {
        this.probe = probes.stream()
            .min(java.util.Comparator.comparingInt(RetrievalProbe::getOrder))
            .orElseThrow();
    }

    @Override
    public void run(ApplicationArguments args) {
        String query = args.getOptionValues("eval.annotate-query") == null ? null
            : args.getOptionValues("eval.annotate-query").stream().findFirst().orElse(null);
        if (query == null || query.isBlank()) return;

        log.info("═══ 标注辅助：query = [{}]，探针 = {} ═══", query, probe.name());
        List<RetrievalProbe.ProbeHit> hits = probe.probe(query, 10);
        for (int i = 0; i < hits.size(); i++) {
            RetrievalProbe.ProbeHit h = hits.get(i);
            log.info("#{} chunkId={} score={} 片段：{}",
                i + 1, h.chunkId(), String.format("%.4f", h.score()),
                h.content() == null ? "" : h.content().replaceAll("\\s+", " ")
                    .substring(0, Math.min(120, h.content().length())));
        }
        log.info("═══ 将相关 chunkId 填入 golden/*.json 的 expectedChunkIds ═══");
    }
}
