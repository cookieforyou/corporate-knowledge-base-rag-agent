package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估机读快照单测（簇② 5.9 批3）——聚合/逐例投影 + 内容盲纪律 + 指纹确定性
 */
class EvalSnapshotTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static EvalResult result(String id, String question, String answer) {
        GoldenQAPair pair = new GoldenQAPair(id, QACategory.FACTOID, question, null, null, null, null, null, null, null);
        return new EvalResult(pair, List.of(), answer, 1.0, 0.5, 0.8,
            Double.NaN, Double.NaN, Double.NaN, 4.0, 3.5, null, null, "理由", null, null,
            null, 4.5, "SUPPORTED", 1.0, 0.02, null, null);
    }

    private static EvalReport reportOf(List<EvalResult> results) {
        return new EvalReport("hybrid", results.size(), results.size(), results.size(), 0,
            0.9, 0.8, 0.7, 0, Double.NaN, Double.NaN, Double.NaN,
            4.2, 3.9, Double.NaN, 0, Double.NaN, 0, Double.NaN,
            Map.of(), results, EvalReport.Phase5Metrics.EMPTY);
    }

    @Test
    void snapshotProjectsAnchorConfigAggregatesAndCases() {
        EvalProperties props = new EvalProperties();
        props.setRunLabel("baseline");
        GitAnchor anchor = new GitAnchor("a".repeat(40), "a".repeat(10), "2026-08-24T00:00:00+08:00", false,
            "2026-08-24T01:00:00Z");

        EvalSnapshot snapshot = EvalSnapshot.from(
            reportOf(List.of(result("f-01", "问题一", "回答一"))), props, anchor);

        assertThat(snapshot.anchor()).isEqualTo(anchor);
        assertThat(snapshot.runConfig().probe()).isEqualTo("hybrid");
        assertThat(snapshot.runConfig().judgeModel()).isEqualTo("qwen3.7-plus");
        assertThat(snapshot.aggregates().avgFaithfulness()).isEqualTo(4.2);
        assertThat(snapshot.aggregates().phase5()).isEqualTo(EvalReport.Phase5Metrics.EMPTY);
        assertThat(snapshot.cases()).hasSize(1);
        EvalSnapshot.CaseScores c = snapshot.cases().get(0);
        assertThat(c.id()).isEqualTo("f-01");
        assertThat(c.category()).isEqualTo("FACTOID");
        assertThat(c.faithfulness()).isEqualTo(4.0);
        assertThat(c.citationVerdict()).isEqualTo("SUPPORTED");
        assertThat(c.answerSha256()).isEqualTo(EvalSnapshot.sha256("回答一"));
    }

    /** L2 原始裁决投影（簇② 批5 路径 a）：verdict 枚举内容盲，机读快照直读 */
    @Test
    void snapshotProjectsL2RawVerdict() {
        GoldenQAPair pair = new GoldenQAPair("inj-jailbreak-15", QACategory.INJECTION, "样本",
            null, null, null, null, AttackType.JAILBREAK, null, null);
        EvalResult injection = new EvalResult(pair, List.of(), null, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, null, null, null, null, null,
            EvalResult.INJECTION_NOT_BLOCKED, EvalResult.INJECTION_NOT_BLOCKED,
            "SUSPECT", null, null, null, null, null, null);

        EvalSnapshot snapshot = EvalSnapshot.from(reportOf(List.of(injection)), new EvalProperties(),
            new GitAnchor("a".repeat(40), "a".repeat(10), "2026-08-28T00:00:00+08:00", false,
                "2026-08-28T01:00:00Z"));

        assertThat(snapshot.cases()).hasSize(1);
        assertThat(snapshot.cases().get(0).l2RawVerdict()).isEqualTo("SUSPECT");
    }

    /** 内容盲纪律（敏感词红线）：快照 JSON 不携带问句/回答正文——注入样本字面不落产物 */
    @Test
    void snapshotJsonIsContentBlind() {
        EvalSnapshot snapshot = EvalSnapshot.from(
            reportOf(List.of(result("f-01", "机密问题正文-UNIQUE-QUESTION", "回答正文-UNIQUE-ANSWER"))),
            new EvalProperties(),
            new GitAnchor(GitAnchor.UNKNOWN, GitAnchor.UNKNOWN, GitAnchor.UNKNOWN, false, "t"));

        String json = jsonMapper.writeValueAsString(snapshot);

        assertThat(json)
            .doesNotContain("UNIQUE-QUESTION")
            .doesNotContain("UNIQUE-ANSWER")
            .contains("f-01")                                  // ID 保留
            .contains(EvalSnapshot.sha256("回答正文-UNIQUE-ANSWER")); // 指纹替代正文
    }

    @Test
    void sha256IsDeterministicAndNullSafe() {
        assertThat(EvalSnapshot.sha256("同一答案")).isEqualTo(EvalSnapshot.sha256("同一答案"));
        assertThat(EvalSnapshot.sha256("答案甲")).isNotEqualTo(EvalSnapshot.sha256("答案乙"));
        assertThat(EvalSnapshot.sha256(null)).isNull();
        assertThat(EvalSnapshot.sha256("x")).matches("[0-9a-f]{64}");
    }

    /** NaN 往返：检索侧无标注指标为 NaN，序列化→反序列化后仍为 NaN（diff 按样本缺失跳过） */
    @Test
    void snapshotRoundTripsNaNThroughJson() {
        EvalSnapshot snapshot = EvalSnapshot.from(
            reportOf(List.of(result("f-01", "问题", "回答"))),
            new EvalProperties(),
            new GitAnchor(GitAnchor.UNKNOWN, GitAnchor.UNKNOWN, GitAnchor.UNKNOWN, false, "t"));

        String json = jsonMapper.writeValueAsString(snapshot);
        EvalSnapshot parsed = jsonMapper.readValue(json, EvalSnapshot.class);

        assertThat(parsed.cases().get(0).docRecall()).isNaN();
        assertThat(parsed.aggregates().avgDocRecall()).isNaN();
        assertThat(parsed.cases().get(0).faithfulness()).isEqualTo(4.0);
    }
}
