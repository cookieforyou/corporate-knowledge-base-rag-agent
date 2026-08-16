package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.security.TextSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 输入安全护栏（设计文档 12.1，任务 3.5）—— 归一化 + PII 脱敏 + Prompt 注入检测
 *
 * <p>Order 300：先于记忆 Advisor(400) 执行 before()——脱敏后的文本才进记忆仓储，
 * 避免 PII 落库（11.2 链序表注）。
 *
 * <p><b>v2.4 实现期修正</b>（12 章草稿两处失效 API）：① BaseAdvisor 是接口，
 * 草稿 {@code extends} 改 {@code implements}；② {@code request.userText()} 不存在
 * （ChatClientRequest 为 record，仅 prompt()/context()），用户文本经
 * {@link Prompt#augmentUserMessage(String)} 替换末条用户消息后重建请求。
 *
 * <p><b>v2.18 修正（簇② B1，S1 输入归一化）</b>：注入检测前先经
 * {@link TextSanitizer#normalize} 构造归一化检测视图（NFKC + 零宽剥离 +
 * 空白折叠），堵 G2 编码绕过（全角字符/零宽拆词/空白拆词）——视图仅供检测
 * 不回写（NFKC 归一全角标点，回写会改变正常中文查询形态）；PII 掩码落原文
 * （零宽剥离 + 容忍空格/连字符的正则）。PII 正则与注入词表收编
 * {@link TextSanitizer}（kb-commons），与 ETL 入库消毒（S4）同源。
 *
 * <p>L1 形态（12.1.1 三层防御第一层）：归一化 + 正则快筛。数字类模式加边界断言
 * 并容忍数字间空格/连字符（防长数字串内部误匹配、防拆词绕过）。语义化/多语言
 * 注入的 L2（LLM 辅助判定）与 L3（专用分类器）为升级路线。
 *
 * <p><b>v2.24 修正（簇⑤ B2，S3 护栏可观测）</b>：命中事件接
 * {@link AiBusinessMetrics} 计数——注入拦截 {@code rag.guardrail.injection.blocked}
 * （抛异常前）、PII 掩码 {@code rag.guardrail.pii.masked}（非拒绝型干预，
 * 只记事实不落原文）；拒绝型拦截的审计行经 AuditTraceAdvisor REJECTED 三态
 * 既有通道落 kb_audit_log，本 Advisor 不重复落库。
 *
 * <p><b>v2.40 修正（安全簇① A1/T2，词表结构化）</b>：注入词表由单行 CSV 升级为结构化
 * 词表——经 {@link GuardrailRulesLoader#loadInjectionRules} 双源合并装载
 * {@link GuardrailRule}（族系 / 动作 / KEYWORD+REGEX 双型；内置基线词项 T2 迁入
 * 结构化文件，字面硬编码退役）。命中按 {@code action} 分流：
 * BLOCK 拒绝（语义不变）、FLAG 观察档放行只计数。
 * 词项 value 编码态存储、加载层解码（第七节敏感词交付纪律条 2）。
 *
 * <p><b>v2.43 修正（安全簇① T7，FLAG 观察语义）</b>：FLAG 档命中放行 + 计数
 * {@code rag.guardrail.flagged}（side=input + family 低基数标签）+ 经参数链
 * {@link RetrievalContext.FlagMark} 写审计标记（AuditTraceAdvisor 落
 * kb_audit_log.guardrail_flags）——词表变更流程「新增 → FLAG 观察 → 零误伤确认
 * 转 BLOCK」的可查面（指标 + 审计）。BLOCK 拒绝路径不计 FLAG（请求未放行）。
 */
@Slf4j
@Component
public class InputSanitizeAdvisor implements BaseAdvisor {

    /** 生效结构化词表：双源合并（结构化文件 ∪ CSV 兼容），action 分流 */
    private final List<GuardrailRule> injectionRules;

    /** 护栏命中计数（簇⑤ B2 S3）——注入拦截/PII 掩码事件入 Prometheus */
    private final AiBusinessMetrics metrics;

    public InputSanitizeAdvisor(
            @Value("${rag.guardrail.rules.injection-location:}") String rulesLocation,
            @Value("${rag.guardrail.input.injection-keywords:}") String keywordsCsv,
            AiBusinessMetrics metrics) {
        this.injectionRules = GuardrailRulesLoader.loadInjectionRules(rulesLocation, keywordsCsv);
        this.metrics = metrics;
        long blocks = injectionRules.stream().filter(r -> r.action() == RuleAction.BLOCK).count();
        log.info("注入检测词表加载: {} 条（BLOCK {} / FLAG {}）",
            injectionRules.size(), blocks, injectionRules.size() - blocks);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        String userText = prompt.getUserMessage() != null ? prompt.getUserMessage().getText() : null;
        if (userText == null || userText.isEmpty()) {
            return request;
        }

        // 1. S1 归一化检测视图：NFKC + 零宽剥离 + 空白折叠（堵 G2 编码绕过）。
        //    仅用于检测不回写——NFKC 会归一全角标点，回写改变正常中文查询形态
        String detectionView = TextSanitizer.normalize(userText);

        // 2. Prompt 注入检测——结构化词表匹配，按 action 分流（v2.40）：
        //    BLOCK 命中即拒（同步 400 / 流式 ERROR 事件）；FLAG 观察档放行只计数
        List<GuardrailRule> matched = TextSanitizer.matchRules(detectionView, injectionRules);
        boolean blocked = matched.stream().anyMatch(r -> r.action() == RuleAction.BLOCK);
        if (blocked) {
            metrics.recordInjectionBlocked();
            log.warn("检测到 Prompt 注入攻击，请求已拦截（命中 {} 条规则）", matched.size());
            throw new BusinessException("PROMPT_INJECTION", "检测到 Prompt 注入攻击，请求已被拦截");
        }
        if (!matched.isEmpty()) {
            // FLAG 观察档（T7）：放行 + 计数 rag.guardrail.flagged + 审计标记（不拒绝）
            recordFlagObservation(request, matched);
        }

        // 3. PII 脱敏（幂等：掩码形态不会被二次匹配）：先剥零宽防数字串被拆断，
        //    掩码落原文（容忍空格/连字符的正则覆盖拆词形态）
        String sanitized = TextSanitizer.maskPii(TextSanitizer.stripInvisible(userText));
        if (sanitized.equals(userText)) {
            return request;
        }
        // S3 可观测：掩码属非拒绝型干预（请求照常放行），只记事实不落原文
        metrics.recordPiiMasked();
        log.info("PII 掩码触发，用户输入已脱敏后进入后续链路");
        Prompt mutated = prompt.augmentUserMessage(sanitized);
        return new ChatClientRequest(mutated, request.context());
    }

    /**
     * FLAG 观察档记录（安全簇① T7）：命中族系去重后逐一计数
     * {@code rag.guardrail.flagged}（side=input + family）并经参数链写
     * {@link RetrievalContext.FlagMark} 审计标记（非 Web 入口无 ctx 时只计数）。
     * 只记事实（族系名 + 计数），不回显命中词值。
     */
    private void recordFlagObservation(ChatClientRequest request, List<GuardrailRule> matched) {
        RetrievalContext ctx = request.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext rc
            ? rc : null;
        List<String> families = matched.stream()
            .map(r -> new RetrievalContext.FlagMark(AiBusinessMetrics.SIDE_INPUT, r.family()).family())
            .distinct()
            .toList();
        for (String family : families) {
            metrics.recordFlagged(AiBusinessMetrics.SIDE_INPUT, family);
            if (ctx != null) {
                ctx.addGuardrailFlag(new RetrievalContext.FlagMark(AiBusinessMetrics.SIDE_INPUT, family));
            }
        }
        log.info("注入词表 FLAG 观察档命中 {} 条，放行（族系 {}）", matched.size(), families);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 300;
    }
}
