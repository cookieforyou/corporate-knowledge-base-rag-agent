package com.enterprise.kb.eval.metric;

import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Judge 结构化输出容错转换器（簇② md1-final 判读落地，16 章 v2.87）。
 *
 * <p>背景：Judge 模型结构化输出偶发畸形（md1-final 实测 4/267 ≈ 1.5%，
 * judgeModel=qwen3.8-flash；qwen3.7-plus κ3 轮同款 2 例）——两种形态：① 键值错位（字符串误入对象位，Jackson 绑定异常）；
 * ② 裸 token 前缀（如以 reason: 开头的非 JSON 文本）。既有路径经
 * {@code .entity(Class)} 直接剔除该用例，致分区分母逐轮漂移、门禁读数不可复现
 * （MULTI_HOP 30→27 等）。
 *
 * <p>容错策略（不静默给分纪律）：首次解析失败或 score 缺位时，逐个截取文本中的
 * 平衡 JSON 对象（字符串感知配对，覆盖围栏/前缀噪声包裹形态）重解析；全部失败
 * 上抛，走既有「评估失败」剔除路径。经 {@code .entity(StructuredOutputConverter)}
 * 重载接入，格式注入（getFormat）与原 {@code .entity(Class)} 完全同源。
 */
public class TolerantJudgeScoreConverter extends BeanOutputConverter<JudgePrompts.JudgeScore> {

    public TolerantJudgeScoreConverter() {
        super(JudgePrompts.JudgeScore.class);
    }

    @Override
    public JudgePrompts.JudgeScore convert(String text) {
        RuntimeException firstFailure = null;
        try {
            JudgePrompts.JudgeScore js = super.convert(text);
            if (js != null && js.score() != null) {
                return js;
            }
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }
        int from = 0;
        while (from < text.length()) {
            String candidate = nextBalancedObject(text, from);
            if (candidate == null) {
                break;
            }
            from = text.indexOf('{', from) + candidate.length();
            try {
                JudgePrompts.JudgeScore js = super.convert(candidate);
                if (js != null && js.score() != null) {
                    return js;
                }
            } catch (RuntimeException ignored) {
                // 尝试下一个平衡对象
            }
        }
        throw new IllegalStateException("judge 输出不可解析: "
            + (text == null ? "null" : abbreviate(text)), firstFailure);
    }

    /**
     * 自 from 起截取首个平衡 JSON 对象（字符串感知：跳过双引号内的括号与转义），
     * 不平衡（截断输出）返回 null。
     */
    private static String nextBalancedObject(String text, int from) {
        int start = text.indexOf('{', from);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
