package com.enterprise.kb.eval.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation Attribution 确定性阶段（簇② 5.8，16 章 §16.2 三步验证前两步）
 *
 * <p>三步验证：① 引用发出（回答含 [ref-N] 标注）→ ② 编号可解析（N ∈ [1, 上下文条数]）
 * → ③ 来源支撑（Judge 判定，{@link JudgePrompts#CITATION_ATTRIBUTION}）。
 * 本类承担①②——确定性零 LLM；①未发出或②存在越界/失配时判负，免③的 Judge 调用。
 *
 * <p>编号契约与被测链路一致：[ref-N] 仅阿拉伯数字（GROUNDING_PROMPT 规则 2），
 * 圈号 ①②③ 等形态非合法引用锚点，不匹配。
 */
public final class CitationMetrics {

    private CitationMetrics() {}

    /** [ref-N] 标注提取：N = 阿拉伯数字（与被测链路的引用锚点契约一致） */
    private static final Pattern REF_PATTERN = Pattern.compile("\\[ref-(\\d+)]");

    /** Citation Attribution 判定结果（确定性阶段 + Judge 阶段的合成语义） */
    public static final String VERDICT_SUPPORTED = "SUPPORTED";
    public static final String VERDICT_NOT_SUPPORTED = "NOT_SUPPORTED";
    /** 第一步未通过：回答未发出任何 [ref-N] 引用标注 */
    public static final String VERDICT_NO_CITATION = "NO_CITATION";

    /** 提取回答中的全部引用编号（按出现顺序，含重复引用） */
    public static List<Integer> extractRefs(String answer) {
        List<Integer> refs = new ArrayList<>();
        if (answer == null || answer.isEmpty()) {
            return refs;
        }
        Matcher m = REF_PATTERN.matcher(answer);
        while (m.find()) {
            try {
                refs.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长数字串越界：按不可解析丢弃（与 N 越界同语义）
            }
        }
        return refs;
    }

    /**
     * 第二步可解析率：引用编号落在 [1, contextSize] 内的比例。
     * 无引用标注（第一步未通过）返回 NaN，聚合按「非 NaN 才计入」跳过。
     */
    public static double resolvableRate(List<Integer> refs, int contextSize) {
        if (refs.isEmpty()) {
            return Double.NaN;
        }
        long resolvable = refs.stream().filter(n -> n >= 1 && n <= contextSize).count();
        return (double) resolvable / refs.size();
    }
}
