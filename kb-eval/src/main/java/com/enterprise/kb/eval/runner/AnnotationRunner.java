package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Golden Dataset 标注辅助器
 *
 * <p>用法：{@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.annotate-query=增值税发票认证期限}
 *
 * <p>对给定问题跑检索探针 Top-10，输出 chunkId / 得分 / 内容片段，
 * 供人工判定 expectedChunkIds 并写入 golden/*.json（工作流见 golden/README-标注指南.md）。
 *
 * <p>全量重标注表（簇④ A4 修复，2026-08-12）：
 * {@code -Dspring-boot.run.arguments=--eval.annotate-all} ——对全部正向用例跑
 * 探针 Top-8，落盘 target/golden-reannotate-sheet.md，供一次性回填
 * expectedChunkIds（确定性 ID）与 expectedDocs（文档级兜底）。适用于 chunk ID
 * 从随机 UUID 迁到确定性 ID 后的整体重标注。
 */
@Slf4j
@Component
public class AnnotationRunner implements ApplicationRunner {

    private final RetrievalProbe probe;
    private final GoldenDatasetLoader datasetLoader;

    public AnnotationRunner(List<RetrievalProbe> probes, GoldenDatasetLoader datasetLoader) {
        this.probe = probes.stream()
            .min(java.util.Comparator.comparingInt(RetrievalProbe::getOrder))
            .orElseThrow();
        this.datasetLoader = datasetLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("eval.annotate-all")) {
            writeReannotateSheet();
            return;
        }
        String query = args.getOptionValues("eval.annotate-query") == null ? null
            : args.getOptionValues("eval.annotate-query").stream().findFirst().orElse(null);
        if (query == null || query.isBlank()) return;

        log.info("═══ 标注辅助：query = [{}]，探针 = {} ═══", query, probe.name());
        List<RetrievalProbe.ProbeHit> hits = probe.probe(query, 10);
        for (int i = 0; i < hits.size(); i++) {
            RetrievalProbe.ProbeHit h = hits.get(i);
            log.info("#{} chunkId={} file={} score={} 片段：{}",
                i + 1, h.chunkId(), h.fileName(), String.format("%.4f", h.score()), snippet(h.content(), 120));
        }
        log.info("═══ 将相关 chunkId 填入 golden/*.json 的 expectedChunkIds ═══");
    }

    /**
     * 全量重标注表：逐用例输出 Top-8 候选（chunkId/文件名/片段），人工圈定后回填。
     *
     * <p>负向用例跳过（无检索期望）。候选含旧 expectedChunkIds 供对照。
     */
    private void writeReannotateSheet() {
        List<GoldenQAPair> pairs = datasetLoader.loadAll().stream()
            .filter(p -> !p.isNegative()).toList();
        StringBuilder sb = new StringBuilder("""
            # Golden Dataset 重标注表（簇④ A4 修复）

            chunk ID 已从随机 UUID 迁为确定性 ID（文档名+序号+原文，重入库不变）。
            旧 expectedChunkIds 全部失效，请逐用例从 Top-8 候选中圈定真正包含答案的
            chunk，回填 golden/*.json：
            - expectedChunkIds：圈定的 chunkId 列表（确定性 ID，重入库后仍有效）
            - expectedDocs：圈定 chunk 的文件名（file_name 列），文档级兜底指标用

            判定口径：候选 chunk 内容**能回答该问题**即圈（不限 rank）。

            """);
        int count = 0;
        for (GoldenQAPair pair : pairs) {
            count++;
            sb.append(String.format("---%n## %s（%s）%n%n**问题**：%s%n%n",
                pair.id(), pair.category(), pair.question()));
            sb.append(String.format("旧 expectedChunkIds（失效）：`%s`%n%n",
                pair.expectedChunkIds() == null ? "无" : String.join(", ", pair.expectedChunkIds())));
            List<RetrievalProbe.ProbeHit> hits;
            try {
                hits = probe.probe(pair.question(), 8);
            } catch (Exception e) {
                sb.append(String.format("探针失败：%s%n", e.getMessage()));
                continue;
            }
            if (hits.isEmpty()) {
                sb.append("（无命中）\n");
                continue;
            }
            sb.append("| rank | chunkId | 文件 | score | 片段 |\n|---|---|---|---|---|\n");
            for (int i = 0; i < hits.size(); i++) {
                RetrievalProbe.ProbeHit h = hits.get(i);
                sb.append(String.format("| %d | `%s` | %s | %.4f | %s |%n",
                    i + 1, h.chunkId(), h.fileName() == null ? "—" : h.fileName(),
                    h.score(), snippet(h.content(), 100)));
            }
            if (count % 10 == 0) {
                log.info("重标注表进度：{}/{}", count, pairs.size());
            }
        }

        try {
            Path out = Path.of("target/golden-reannotate-sheet.md");
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, sb.toString());
            log.info("═══ 重标注表已落盘：{}（共 {} 条正向用例）═══", out.toAbsolutePath(), count);
        } catch (Exception e) {
            throw new IllegalStateException("重标注表落盘失败", e);
        }
    }

    private static String snippet(String content, int max) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").replace("|", "/");
        return flat.substring(0, Math.min(max, flat.length()));
    }
}
