package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答案人审表渲染单测（16 章 v2.94）——CA/HR 破线归因人审材料的过滤面：
 * 正向干净域全量落表（NOT_SUPPORTED 索引置顶）+ 负向/注入例不落表
 *（内容盲纪律域外样本：拒答形态与攻击域字面不进人审产物）。
 */
class EvalRunnerAnswerSheetTest {

    private static EvalResult result(String id, QACategory category, String answer, String citationVerdict) {
        GoldenQAPair pair = new GoldenQAPair(id, category, "问句-" + id, null, null, null, null, null, null, null);
        return new EvalResult(pair, List.of(), answer, 1.0, 0.5, 0.8,
            Double.NaN, Double.NaN, Double.NaN, 4.0, 3.5, null, null, "理由", null, null,
            null, 4.5, citationVerdict, 1.0, 0.02, null, null);
    }

    private static EvalReport reportOf(List<EvalResult> results) {
        return new EvalReport("hybrid", results.size(), results.size(), results.size(), 0,
            0.9, 0.8, 0.7, 0, Double.NaN, Double.NaN, Double.NaN,
            4.2, 3.9, Double.NaN, 0, Double.NaN, 0, Double.NaN,
            Map.of(), results, EvalReport.Phase5Metrics.EMPTY);
    }

    /** 正向域全量落表 + NOT_SUPPORTED 索引置顶（抽审入口） */
    @Test
    void answerSheetDumpsCleanDomainWithNotSupportedIndex() {
        String sheet = EvalRunner.renderAnswerSheet(reportOf(List.of(
            result("f-01", QACategory.FACTOID, "回答甲[ref-1]", "SUPPORTED"),
            result("f-02", QACategory.FACTOID, "回答乙[ref-2]", "NOT_SUPPORTED"))));

        assertThat(sheet)
            .contains("# 答案人审表")
            .contains("## f-01 · FACTOID")
            .contains("回答甲[ref-1]")
            .contains("## f-02 · FACTOID")
            .contains("回答乙[ref-2]")
            .contains("CA=NOT_SUPPORTED");
        // 索引行只列 NOT_SUPPORTED 例
        int idxStart = sheet.indexOf("## NOT_SUPPORTED 索引");
        int idxEnd = sheet.indexOf("## f-01");
        String indexLine = sheet.substring(idxStart, idxEnd);
        assertThat(indexLine).contains("f-02").doesNotContain("f-01");
    }

    /** 内容盲纪律补充形态：负向例（拒答形态）与注入例（攻击域）不落表 */
    @Test
    void answerSheetExcludesNegativeAndInjectionDomain() {
        GoldenQAPair injPair = new GoldenQAPair("inj-01", QACategory.INJECTION, "样本",
            null, null, null, null, AttackType.JAILBREAK, null, null);
        EvalResult injection = new EvalResult(injPair, List.of(), "注入例回答-EXCL-INJ", Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, null,
            null, null, null, EvalResult.INJECTION_NOT_BLOCKED, EvalResult.INJECTION_NOT_BLOCKED,
            "SUSPECT", null, null, null, null, null, null);

        String sheet = EvalRunner.renderAnswerSheet(reportOf(List.of(
            result("f-01", QACategory.FACTOID, "正向回答[ref-1]", "SUPPORTED"),
            result("neg-01", QACategory.NEGATIVE, "负向例回答-EXCL-NEG", null),
            injection)));

        assertThat(sheet)
            .contains("正向回答[ref-1]")
            .doesNotContain("EXCL-NEG")
            .doesNotContain("EXCL-INJ")
            .doesNotContain("neg-01")
            .doesNotContain("inj-01");
    }

    /** 空答案例（judge 失败/检索空）不落表；全空正向域索引行显式「（无）」 */
    @Test
    void answerSheetSkipsBlankAnswersAndHandlesEmptyIndex() {
        String sheet = EvalRunner.renderAnswerSheet(reportOf(List.of(
            result("f-01", QACategory.FACTOID, null, null))));

        assertThat(sheet)
            .contains("（无）")
            .doesNotContain("## f-01 ·");
    }

    /** 开关缺省关（显式 opt-in，缺省行为零变化） */
    @Test
    void dumpAnswersDefaultsToFalse() {
        assertThat(new EvalProperties().isDumpAnswers()).isFalse();
    }
}
