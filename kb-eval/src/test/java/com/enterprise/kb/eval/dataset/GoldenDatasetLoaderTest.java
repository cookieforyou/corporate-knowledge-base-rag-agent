package com.enterprise.kb.eval.dataset;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(102, pairs.size(), "Golden 总量应为 102 条（80 正向 + 22 负向）");
        long negatives = pairs.stream().filter(GoldenQAPair::isNegative).count();
        assertEquals(22, negatives, "负向用例应为 22 条");
        assertTrue(negatives * 5 >= pairs.size(), "负向占比须 ≥ 20%（16.1 分布目标）");
    }

    @Test
    void everyPositiveCaseCarriesBothAnchorLayers() {
        for (GoldenQAPair pair : pairs) {
            if (pair.isNegative()) {
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
}
