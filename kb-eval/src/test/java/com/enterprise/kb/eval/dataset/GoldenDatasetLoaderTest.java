package com.enterprise.kb.eval.dataset;

import com.enterprise.kb.commons.security.TextSanitizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden Dataset 在库文件回归测试（簇④ A4 重标注后新增）
 *
 * <p>不依赖任何基础设施：直接以裸 {@link JsonMapper} 驱动 {@link GoldenDatasetLoader}，
 * 加载 classpath:golden/*.json 在库文件并校验结构不变量：
 * <ul>
 *   <li>总量与负向占比达标（16.1：50+ 条、负向 ≥ 20%）</li>
 *   <li>正向用例必须同时携带 chunk 级与文档级检索锚点（重标注后语料保证）</li>
 *   <li>chunk 锚点为确定性 ID（UUID v3 形态，9.3 v2.22）——拦截误粘贴的随机 v4 ID</li>
 *   <li>用例 id 全局唯一</li>
 *   <li>INJECTION 用例携带合法 attackType、无检索锚点，且样本与 L1 词表防域
 *       自洽（簇⑤ B2 S6）——防假红/假绿</li>
 * </ul>
 */
class GoldenDatasetLoaderTest {

    /** UUID v3（nameUUIDFromBytes 产物）：version 位固定为 3 */
    private static final Pattern DETERMINISTIC_ID =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static List<GoldenQAPair> pairs;

    @BeforeAll
    static void loadDataset() {
        pairs = new GoldenDatasetLoader(new JsonMapper()).loadAll();
    }

    @Test
    void loadsFullDatasetWithNegativeShare() {
        assertEquals(146, pairs.size(), "Golden 总量应为 146 条（80 正向 + 22 负向 + 44 注入攻击）");
        long negatives = pairs.stream().filter(GoldenQAPair::isNegative).count();
        assertEquals(22, negatives, "负向用例应为 22 条");
        long injections = pairs.stream().filter(GoldenQAPair::isInjection).count();
        assertEquals(44, injections, "注入攻击用例应为 44 条（四类各 11）");
        // 负向占比以问答质量用例为分母（INJECTION 是安全测试集，非问答负向集）
        long nonInjection = pairs.size() - injections;
        assertTrue(negatives * 5 >= nonInjection, "负向占比须 ≥ 20%（16.1 分布目标）");
    }

    @Test
    void everyPositiveCaseCarriesBothAnchorLayers() {
        for (GoldenQAPair pair : pairs) {
            if (pair.isNegative() || pair.isInjection()) {
                continue;
            }
            assertTrue(pair.hasRetrievalExpectation(),
                pair.id() + " 缺少 expectedChunkIds（chunk 级锚点必填）");
            assertTrue(pair.hasDocExpectation(),
                pair.id() + " 缺少 expectedDocs（文档级兜底锚点必填）");
        }
    }

    @Test
    void chunkAnchorsAreDeterministicUuidV3() {
        for (GoldenQAPair pair : pairs) {
            if (!pair.hasRetrievalExpectation()) {
                continue;
            }
            for (String chunkId : pair.expectedChunkIds()) {
                assertTrue(DETERMINISTIC_ID.matcher(chunkId).matches(),
                    pair.id() + " 的 chunk 锚点 " + chunkId + " 不是确定性 UUID v3（疑似随机 ID 残留）");
            }
        }
    }

    @Test
    void caseIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (GoldenQAPair pair : pairs) {
            assertTrue(ids.add(pair.id()), "用例 id 重复: " + pair.id());
        }
    }

    @Test
    void negativeCasesCarryNoRetrievalAnchors() {
        for (GoldenQAPair pair : pairs) {
            if (!pair.isNegative()) {
                continue;
            }
            assertTrue(!pair.hasRetrievalExpectation() && !pair.hasDocExpectation(),
                pair.id() + " 为负向用例却携带了检索锚点");
        }
    }

    // ── INJECTION 用例结构（簇⑤ B2 S6）──

    @Test
    void injectionCasesCarryValidAttackType() {
        for (GoldenQAPair pair : pairs) {
            if (!pair.isInjection()) {
                continue;
            }
            assertNotNull(pair.attackType(), pair.id() + " 缺少 attackType");
        }
        for (AttackType type : AttackType.values()) {
            long n = pairs.stream()
                .filter(p -> p.isInjection() && p.attackType() == type).count();
            assertTrue(n >= 10, type + " 样本须 ≥ 10 条（实际 " + n + "）");
        }
    }

    @Test
    void injectionCasesCarryNoRetrievalAnchors() {
        for (GoldenQAPair pair : pairs) {
            if (!pair.isInjection()) {
                continue;
            }
            assertTrue(!pair.hasRetrievalExpectation() && !pair.hasDocExpectation(),
                pair.id() + " 为注入用例却携带了检索锚点");
        }
    }

    /**
     * 样本与 L1 词表防域自洽（防门禁假红/假绿）：以 {@link TextSanitizer} 默认词表
     * 逐条编程式校验——DIRECT 归一后必命中干词；ENCODING_BYPASS 归一前不命中
     * （编码必须真实存在）且归一后命中（S1 视图必须还原）；JAILBREAK/MULTILINGUAL
     * 归一前后均不命中（L1 不拦截属设计行为，观察集）。
     * 注：校验锚定默认词表；生产以 rag.guardrail.input.injection-keywords 覆盖
     * 词表时须同步更新样本集。
     */
    @Test
    void injectionSamplesMatchGuardrailExpectations() {
        List<String> keywords = TextSanitizer.loadInjectionKeywords("");
        for (GoldenQAPair pair : pairs) {
            if (!pair.isInjection()) {
                continue;
            }
            String raw = pair.question();
            boolean rawHit = TextSanitizer.containsInjectionKeyword(raw, keywords);
            boolean normalizedHit = TextSanitizer.containsInjectionKeyword(
                TextSanitizer.normalize(raw), keywords);
            switch (pair.attackType()) {
                case DIRECT -> assertTrue(normalizedHit,
                    pair.id() + " 为 DIRECT 却未命中词表干词（归一化后）");
                case ENCODING_BYPASS -> {
                    assertFalse(rawHit,
                        pair.id() + " 为 ENCODING_BYPASS 但归一前已命中（编码无效，应归 DIRECT）");
                    assertTrue(normalizedHit,
                        pair.id() + " 为 ENCODING_BYPASS 却未被 S1 归一化还原命中");
                }
                case JAILBREAK, MULTILINGUAL -> assertFalse(rawHit || normalizedHit,
                    pair.id() + " 为观察集却命中词表干词（应归门禁子集）");
            }
        }
    }

    @Test
    void injectionGateSubsetIsDirectAndEncodingBypass() {
        long gate = pairs.stream().filter(GoldenQAPair::isInjectionGateSubset).count();
        long directAndEncoding = pairs.stream()
            .filter(p -> p.isInjection()
                && (p.attackType() == AttackType.DIRECT || p.attackType() == AttackType.ENCODING_BYPASS))
            .count();
        assertEquals(directAndEncoding, gate, "门禁子集 = DIRECT + ENCODING_BYPASS");
        assertTrue(gate >= 20, "门禁子集样本须 ≥ 20 条（实际 " + gate + "）");
    }
}
