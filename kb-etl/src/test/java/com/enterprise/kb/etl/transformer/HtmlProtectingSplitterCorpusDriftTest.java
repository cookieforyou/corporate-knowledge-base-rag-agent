package com.enterprise.kb.etl.transformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实语料切分漂移守卫（9.2 v2.24，修复批3 沉淀）——{@code docs/corpus/*.md} 逐篇校验两条不变量：
 * <ol>
 *   <li><b>分发计数</b>：chunk 总数与 TABLE chunk 数与本表基线一致——三路分发（快速/行扫描/AST）
 *       哪条路径被选中发生变化，计数会立刻反映出来；</li>
 *   <li><b>标题来源纯净</b>：每个 chunk 的 heading_path 逐段必须能在文档自身的 Markdown 标题行里找到
 *       ——围栏代码块或 HTML 注释内的伪标题一旦泄漏进 heading_path（修复批1/批3 的两类缺陷），此处立刻暴露。</li>
 * </ol>
 *
 * <p><b>基线变更纪律</b>：语料新增/改写或切分行为有意变更时，本测试会失败并提示——
 * 确认 diff 符合预期后再更新 {@link #BASELINE}（勿为过测试而放宽不变量）。
 * 语料目录不在相对位置（模块单独签出等）时整类跳过，不误报。
 */
class HtmlProtectingSplitterCorpusDriftTest {

    /** 基线（2026-10-04 修复批1+批2+批3 后实测）：语料文件名 → {chunk 总数, TABLE chunk 数} */
    private static final Map<String, int[]> BASELINE = Map.of(
        "Kubernetes集群运维规范.md", new int[] {8, 0},
        "企业信息安全与数据保护管理办法.md", new int[] {28, 1},
        "增值税发票管理实务手册.md", new int[] {7, 0},
        "智能硬件产品规格目录.md", new int[] {8, 0},
        "阿里云文档解析（大模型版）介绍.md", new int[] {19, 0},
        "领域驱动设计（DDD）全面深度解析.md", new int[] {71, 0});

    /** 与切分器同源的 ATX 标题口径（此处独立复刻，避免因被测实现漂移而失去判据） */
    private static final Pattern ATX = Pattern.compile("^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$");

    private static final Path CORPUS_DIR = Path.of("..", "docs", "corpus");

    private final HtmlProtectingSplitter splitter = new HtmlProtectingSplitter();

    @Test
    void corpusChunking_matchesBaseline_andHeadingPathsDeriveFromRealHeadings() throws IOException {
        assumeTrue(Files.isDirectory(CORPUS_DIR), "语料目录不在相对位置，跳过语料漂移守卫");

        for (Map.Entry<String, int[]> entry : BASELINE.entrySet()) {
            Path file = CORPUS_DIR.resolve(entry.getKey());
            assertThat(file)
                .as("语料基线引用的文件缺失：%s（基线变更请同步更新 BASELINE）", entry.getKey())
                .exists();
            String text = Files.readString(file);
            List<Document> chunks = splitter.apply(List.of(new Document(text)));

            long tableChunks = chunks.stream()
                .filter(c -> "TABLE".equals(c.getMetadata().get("chunk_type"))).count();
            assertThat(chunks)
                .as("%s：chunk 总数偏离基线（切分分发路径或判据变更——确认后更新 BASELINE）", entry.getKey())
                .hasSize(entry.getValue()[0]);
            assertThat(tableChunks)
                .as("%s：TABLE chunk 数偏离基线（表格保护判据变更）", entry.getKey())
                .isEqualTo(entry.getValue()[1]);

            Set<String> realHeadings = markdownHeadings(text);
            for (Document chunk : chunks) {
                Object headingPath = chunk.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY);
                if (!(headingPath instanceof String path) || path.isBlank()) {
                    continue;
                }
                for (String segment : path.split(" > ")) {
                    assertThat(realHeadings)
                        .as("%s：heading_path 段「%s」不是文档中的真实 Markdown 标题（围栏/注释内伪标题泄漏？）",
                            entry.getKey(), segment)
                        .contains(segment);
                }
            }
        }
    }

    private static Set<String> markdownHeadings(String text) {
        Set<String> headings = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            Matcher m = ATX.matcher(line.stripTrailing());
            if (m.matches()) {
                headings.add(m.group(2).replace('\u00a0', ' ').strip());
            }
        }
        return headings;
    }
}
