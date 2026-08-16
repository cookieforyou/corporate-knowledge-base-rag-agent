package com.enterprise.kb.eval.dataset;

import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.commons.security.TextSanitizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *   <li>INJECTION 语料落盘为编码引用形态（安全簇① T2，第七节交付纪律），
 *       加载层透明解码且指纹锚点自洽</li>
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
        assertEquals(150, pairs.size(), "Golden 总量应为 150 条（80 正向 + 22 负向 + 48 注入攻击）");
        long negatives = pairs.stream().filter(GoldenQAPair::isNegative).count();
        assertEquals(22, negatives, "负向用例应为 22 条");
        long injections = pairs.stream().filter(GoldenQAPair::isInjection).count();
        assertEquals(48, injections, "注入攻击用例应为 48 条（DIRECT 16 + ENCODING_BYPASS 11 + JAILBREAK 10 + MULTILINGUAL 11，v2.42 重归 1 条）");
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
     * 样本与 L1 词表防域自洽（防门禁假红/假绿）：以随 jar 发布的结构化基线词表
     * （{@code guardrail/injection-rules.yml}，BLOCK 档启用词项）逐条编程式校验——
     * DIRECT 归一后必命中；ENCODING_BYPASS 归一前不命中 KEYWORD 档（编码必须真实
     * 存在于干词层）且归一后命中（S1 视图必须还原）；JAILBREAK/MULTILINGUAL 归一
     * 前后均不命中（L1 不拦截属设计行为，观察集）。
     *
     * <p><b>v2.42 语义演进（安全簇① T3，REGEX 结构模式轨）</b>：ENCODING_BYPASS
     * 的「归一前不命中」契约收窄至 KEYWORD 档——编码绕过的手法和度量对象是干词字面
     * 匹配，REGEX 轨以动词×宾语组合句式独立于编码层工作，其归一前命中属结构检测
     * 正常行为，不否定编码有效性。观察集契约保持全档严格：样本若命中任一 BLOCK 档
     * （含 REGEX）即不再是 L1 盲区，应重归门禁子集（attackType 改 DIRECT）。
     *
     * <p>注：安全簇① T2 起字面词表退役，本校验锚定结构化词表 bundled 基线；
     * 生产以外部词表覆盖时须同步更新样本集。
     */
    @Test
    void injectionSamplesMatchGuardrailExpectations() {
        List<GuardrailRule> blockRules = GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.enabled())
            .toList();
        assertFalse(blockRules.isEmpty(), "bundled 基线词表不得为空（门禁自洽锚点失效）");
        List<GuardrailRule> blockKeywords = blockRules.stream()
            .filter(r -> r.type() == RuleType.KEYWORD)
            .toList();
        for (GoldenQAPair pair : pairs) {
            if (!pair.isInjection()) {
                continue;
            }
            String raw = pair.question();
            boolean rawKeywordHit = blockKeywords.stream().anyMatch(r -> r.matches(raw));
            boolean normalizedHit = blockRules.stream().anyMatch(r -> r.matches(TextSanitizer.normalize(raw)));
            switch (pair.attackType()) {
                case DIRECT -> assertTrue(normalizedHit,
                    pair.id() + " 为 DIRECT 却未命中词表（归一化后）");
                case ENCODING_BYPASS -> {
                    assertFalse(rawKeywordHit,
                        pair.id() + " 为 ENCODING_BYPASS 但归一前已命中干词（编码无效，应归 DIRECT）");
                    assertTrue(normalizedHit,
                        pair.id() + " 为 ENCODING_BYPASS 却未被 S1 归一化还原命中");
                }
                case JAILBREAK, MULTILINGUAL -> {
                    boolean rawHit = blockRules.stream().anyMatch(r -> r.matches(raw));
                    assertFalse(rawHit || normalizedHit,
                        pair.id() + " 为观察集却命中 BLOCK 档（含 REGEX 轨，应归门禁子集）");
                }
            }
        }
    }

    // ── 干净回归集零误伤门禁（安全簇① T8，A4）──

    /**
     * 干净回归集 = 全部非注入用例（正向 + 负向正常问题）：门禁口径为 BLOCK 档
     * <b>零命中</b>（归一化检测视图，KEYWORD + REGEX 全档）。FLAG 观察档命中
     * 容忍（观察语义不拒绝）。任一命中即误伤——对应 BLOCK 词项应降 FLAG 档
     * 或退役（误伤铁律：领域裸词/正常业务表达不入 BLOCK）。
     */
    @Test
    void cleanRegressionSetHasZeroBlockHits() {
        List<GuardrailRule> blockRules = GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.enabled())
            .toList();
        long checked = 0;
        for (GoldenQAPair pair : pairs) {
            if (pair.isInjection()) {
                continue;
            }
            checked++;
            String normalized = TextSanitizer.normalize(pair.question());
            for (GuardrailRule rule : blockRules) {
                assertFalse(rule.matches(normalized),
                    pair.id() + " 为正常用例却命中 BLOCK 档词项 " + rule.id() + "（误伤，须降 FLAG 或退役）");
            }
        }
        assertEquals(102, checked, "干净回归集规模 = Golden 总量 - 注入用例（80 正向 + 22 负向）");
    }

    // ── 编码引用形态（安全簇① T2，第七节交付纪律）──

    /** 落盘形态不变量：INJECTION 语料全部为 base64 编码 + SHA-256 指纹锚点，无明文 question */
    @Test
    void injectionSamplesStoredEncodedOnDisk() throws IOException {
        Resource resource = new ClassPathResource("golden/injection-qa.json");
        List<Map<String, Object>> rawItems;
        try (InputStream is = resource.getInputStream()) {
            rawItems = new JsonMapper().readValue(is, new TypeReference<List<Map<String, Object>>>() {});
        }
        assertEquals(48, rawItems.size(), "注入语料规模");
        for (Map<String, Object> item : rawItems) {
            Object id = item.get("id");
            assertEquals("base64", item.get("questionEncoding"), id + " 语料未落编码引用形态");
            assertNotNull(item.get("questionSha256"), id + " 缺少指纹锚点");
            String encoded = String.valueOf(item.get("question"));
            assertTrue(encoded.matches("^[A-Za-z0-9+/]+={0,2}$"), id + " question 非 Base64 形态");
        }
    }

    /** 加载层透明解码：加载产物恒为明文形态（编码字段已卸），question 非空可用 */
    @Test
    void loaderDecodesEncodedQuestionsTransparently() {
        long injections = 0;
        for (GoldenQAPair pair : pairs) {
            if (!pair.isInjection()) {
                continue;
            }
            injections++;
            assertFalse(pair.hasEncodedQuestion(), pair.id() + " 加载后应已解码为明文形态");
            assertNotNull(pair.question(), pair.id() + " 解码后 question 为 null");
            assertFalse(pair.question().isBlank(), pair.id() + " 解码后 question 为空");
        }
        assertEquals(48, injections, "注入语料规模");
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
