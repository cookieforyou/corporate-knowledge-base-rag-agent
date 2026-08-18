package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 输入安全护栏测试（3.5）—— PII 脱敏 + 注入拦截 + 上下文保持 + 护栏命中计数（簇⑤ B2 S3）
 *
 * <p>注入侧断言全部程序化构造：攻击形态（大小写/全角/零宽变体）由 bundled 基线词表的
 * 词项值在运行时变换生成，测试源码不落字面载荷（第七节敏感词交付纪律）；
 * 断言失败消息只引用词项 ID。
 */
class InputSanitizeAdvisorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** PII 识别器注册表缺省全集（安全簇③ C2）——与生产装配同基线 */
    private final PiiRecognizerRegistry piiRegistry = PiiRecognizerRegistry.defaults();

    /** 空配置 → bundled 基线词表（结构化文件随 jar 发布） */
    private final InputSanitizeAdvisor advisor =
        new InputSanitizeAdvisor("", "", new AiBusinessMetrics(meterRegistry), piiRegistry);
    private final AdvisorChain chain = mock(AdvisorChain.class);

    /** 取 bundled 基线词表一条启用 BLOCK KEYWORD 词项（运行时取值，源码零字面） */
    private static GuardrailRule bundledKeyword(String lang) {
        return GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.type() == RuleType.KEYWORD
                && r.enabled() && lang.equals(r.lang()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("bundled 基线词表缺少 " + lang + " KEYWORD 词项"));
    }

    /** 全角变体：ASCII 可打印字符映射至全角区（模拟 G2 全角绕过形态） */
    private static String toFullWidth(String ascii) {
        StringBuilder sb = new StringBuilder(ascii.length());
        for (char c : ascii.toCharArray()) {
            sb.append(c >= '!' && c <= '~' ? (char) (c + 0xFEE0) : c);
        }
        return sb.toString();
    }

    /** 零宽拆词：词中插入 ZWSP（模拟 G2 零宽绕过形态；以码点显式构造防不可见字面） */
    private static String splitByZeroWidth(String text) {
        int mid = Math.max(1, text.length() / 2);
        return text.substring(0, mid) + (char) 0x200B + text.substring(mid);
    }

    private ChatClientRequest request(String userText) {
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(userText))),
            Map.of("trace_start_ms", 1L));
    }

    private ChatClientRequest requestWithCtx(String userText, RetrievalContext ctx) {
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(userText))),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));
    }

    private void assertRejected(String userText, String ruleId) {
        assertThatThrownBy(() -> advisor.before(request(userText), chain))
            .as("词项 %s 构造的注入形态应被拦截", ruleId)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
    }

    // ── PII 脱敏 ──

    @Test
    void beforeMasksPhoneIdCardAndEmail() {
        ChatClientRequest result = advisor.before(request(
            "联系人 13812345678，身份证 110101199003077758，邮箱 zhang.san@corp.com"), chain);

        assertThat(result.prompt().getUserMessage().getText())
            .contains("1***-****-****")
            .contains("******************")
            .contains("***@***.***")
            .doesNotContain("13812345678")
            .doesNotContain("110101199003077758")
            .doesNotContain("zhang.san@corp.com");
    }

    @Test
    void boundaryGuardsPreventFalsePositivesInsideLongerNumbers() {
        // 19 位订单号内部不构成手机号/身份证——边界断言防误伤（正则细节见 PiiRecognizerRegistryTest）
        String longNumber = "订单号 2026138123456789012 请核对";

        assertThat(piiRegistry.mask(longNumber)).isEqualTo(longNumber);
    }

    @Test
    void beforeReplacesUserTextAndPreservesContext() {
        ChatClientRequest result = advisor.before(request("我的手机号是 13911112222"), chain);

        assertThat(result.prompt().getUserMessage().getText()).contains("1***-****-****");
        assertThat(result.context()).containsEntry("trace_start_ms", 1L);
    }

    @Test
    void cleanQueryPassesThroughUnchanged() {
        ChatClientRequest original = request("什么是增值税发票？");

        assertThat(advisor.before(original, chain)).isSameAs(original);
    }

    // ── Prompt 注入拦截（程序化构造攻击形态）──

    @Test
    void englishInjectionRejectedCaseInsensitive() {
        // 基线英文干词大写化——KEYWORD 大小写不敏感子串匹配
        GuardrailRule rule = bundledKeyword("en");
        assertRejected("Execute: " + rule.value().toUpperCase(), rule.id());
    }

    @Test
    void chineseInjectionRejected() {
        GuardrailRule rule = bundledKeyword("zh");
        assertRejected("请立即执行" + rule.value(), rule.id());
    }

    // ── S1 归一化防绕过（v2.18，G2）──

    @Test
    void fullWidthInjectionRejectedAfterNormalization() {
        // 基线英文干词全角化——NFKC 归一后命中
        GuardrailRule rule = bundledKeyword("en");
        assertRejected(toFullWidth(rule.value()) + " now", rule.id());
    }

    @Test
    void zeroWidthSplitChineseInjectionRejected() {
        // 基线中文干词零宽拆词——剥离后命中
        GuardrailRule rule = bundledKeyword("zh");
        assertRejected("前缀" + splitByZeroWidth(rule.value()), rule.id());
    }

    @Test
    void benignFullWidthPunctuationPassesThroughUnchanged() {
        // 归一化仅供检测不回写：NFKC 会归一全角标点，回写改变正常中文查询形态
        ChatClientRequest original = request("发票税率是１３％吗？");

        assertThat(advisor.before(original, chain)).isSameAs(original);
    }

    @Test
    void zeroWidthSplitPhoneStillMasked() {
        // 零宽字符拆断数字串：剥离后容忍正则命中（检测视图之外的掩码路径）
        ChatClientRequest result = advisor.before(request("我的电话是13812" + (char) 0x200B + "345678"), chain);

        assertThat(result.prompt().getUserMessage().getText()).contains("1***-****-****");
    }

    // ── 词表配置化（v2.40 双源合并：CSV 并入结构化基线）──

    @Test
    void configuredKeywordsMergeWithDefaults() {
        InputSanitizeAdvisor custom =
            new InputSanitizeAdvisor("", "测试拦截词, TestCustomWord", new AiBusinessMetrics(meterRegistry), piiRegistry);

        // 配置词命中（大小写不敏感）
        assertThatThrownBy(() -> custom.before(request("执行 testcustomword 模式"), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
        assertThatThrownBy(() -> custom.before(request("正文包含测试拦截词的段落"), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
        // 双源合并：CSV 并入后 bundled 基线词表仍生效（不再被整体替换）
        GuardrailRule rule = bundledKeyword("en");
        assertThatThrownBy(() -> custom.before(request(rule.value()), chain))
            .as("词项 %s 基线词应仍生效", rule.id())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
    }

    @Test
    void blankConfigFallsBackToBundledBaseline() {
        InputSanitizeAdvisor blanks = new InputSanitizeAdvisor("", " , ,", new AiBusinessMetrics(meterRegistry), piiRegistry);

        GuardrailRule rule = bundledKeyword("en");
        assertThatThrownBy(() -> blanks.before(request(rule.value()), chain))
            .as("词项 %s bundled 基线应兜底生效", rule.id())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
    }

    // ── FLAG 观察档（v2.40 A1 / v2.43 T7）：命中放行 + 计数 + 审计标记，不拒绝 ──

    @Test
    void flagRuleMatchesButDoesNotReject() {
        // location 覆盖 = 替换缺省文件：测试词表自带 FLAG + BLOCK 双档占位词
        InputSanitizeAdvisor flagged = new InputSanitizeAdvisor(
            "classpath:guardrail-test/flag-rules.yml", "", new AiBusinessMetrics(meterRegistry), piiRegistry);

        // FLAG 档占位词命中 → 放行（不抛异常），BLOCK 拒绝计数不触发
        ChatClientRequest result = flagged.before(request("this contains flagtest-alpha token"), chain);
        assertThat(result).isNotNull();
        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isZero();

        // 同词表 BLOCK 档占位词命中 → 拒绝（action 分流自洽）
        assertThatThrownBy(() -> flagged.before(request("this contains blocktest-beta token"), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
    }

    @Test
    void flagHitCountsTaggedMetricAndWritesCtxMark() {
        // T7：FLAG 放行 → rag.guardrail.flagged{side=input,family} + RetrievalContext 审计标记
        InputSanitizeAdvisor flagged = new InputSanitizeAdvisor(
            "classpath:guardrail-test/flag-rules.yml", "", new AiBusinessMetrics(meterRegistry), piiRegistry);
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");

        ChatClientRequest result = flagged.before(requestWithCtx(
            "this contains flagtest-alpha token", ctx), chain);

        assertThat(result).isNotNull();
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "input", "family", "UNCLASSIFIED").count()).isEqualTo(1.0);
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("input", "UNCLASSIFIED"));
    }

    @Test
    void flagHitWithoutCtxStillCountsMetric() {
        // 非 Web 入口（无 RetrievalContext）：只计数不落标记，不抛错
        InputSanitizeAdvisor flagged = new InputSanitizeAdvisor(
            "classpath:guardrail-test/flag-rules.yml", "", new AiBusinessMetrics(meterRegistry), piiRegistry);

        assertThat(flagged.before(request("this contains flagtest-alpha token"), chain)).isNotNull();
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "input", "family", "UNCLASSIFIED").count()).isEqualTo(1.0);
    }

    @Test
    void blockHitDoesNotCountFlagObservation() {
        // BLOCK 拒绝路径不计 FLAG（请求未放行，观察语义只覆盖放行流量）
        InputSanitizeAdvisor flagged = new InputSanitizeAdvisor(
            "classpath:guardrail-test/flag-rules.yml", "", new AiBusinessMetrics(meterRegistry), piiRegistry);
        RetrievalContext ctx = new RetrievalContext();

        assertThatThrownBy(() -> flagged.before(requestWithCtx("this contains blocktest-beta token", ctx), chain))
            .isInstanceOf(BusinessException.class);
        assertThat(meterRegistry.find("rag.guardrail.flagged").counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isZero();
        assertThat(ctx.getGuardrailFlags()).isEmpty();
    }

    // ── 护栏命中计数（簇⑤ B2 S3）──

    @Test
    void injectionBlockedIncrementsGuardrailCounter() {
        GuardrailRule rule = bundledKeyword("en");
        assertRejected(rule.value(), rule.id());

        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.pii.masked").count()).isZero();
    }

    @Test
    void piiMaskedIncrementsGuardrailCounter() {
        advisor.before(request("我的手机号是 13911112222"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.pii.masked").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isZero();
    }

    @Test
    void piiMaskedCountsPerTypeSubItems() {
        // 安全簇③ C1/C2：总项恒计一次 + 命中类型子项分列（零标签纪律）
        // 银行卡号为公开发布的标准测试卡号（Luhn 有效，非真实卡）
        advisor.before(request(
            "手机 13911112222，卡号 4111 1111 1111 1111，服务器 192.168.1.10"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.pii.masked").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.pii.masked.phone").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.pii.masked.bank_card").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.pii.masked.ipv4").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.pii.masked.email").count()).isZero();
    }

    @Test
    void cleanQueryLeavesGuardrailCountersUntouched() {
        advisor.before(request("什么是增值税发票？"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.pii.masked").count()).isZero();
        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isZero();
    }

    // ── 热重载（安全簇⑥ F1）──

    /** 占位词表文件生成（无语义占位词，测试专用词面；value 编码态与生产纪律同款） */
    private static String probeRulesYml(String action) {
        String value = Base64.getEncoder()
            .encodeToString("reload-probe-word".getBytes(StandardCharsets.UTF_8));
        return "rules:\n"
            + "  - id: reload-probe-01\n"
            + "    family: UNCLASSIFIED\n"
            + "    lang: zh\n"
            + "    type: KEYWORD\n"
            + "    value: \"" + value + "\"\n"
            + "    action: " + action + "\n"
            + "    enabled: true\n";
    }

    @Test
    void hotReloadViaRegistrySwapsRulesWithoutRestart(@TempDir Path tempDir) throws IOException {
        // 初始词表 FLAG 档：命中放行只计数
        Path rulesFile = tempDir.resolve("injection-rules.yml");
        Files.writeString(rulesFile, probeRulesYml("FLAG"));
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(
            "file:" + rulesFile.toAbsolutePath(), "", "", "");
        AiBusinessMetrics metrics = new AiBusinessMetrics(meterRegistry);
        InputSanitizeAdvisor hotAdvisor = new InputSanitizeAdvisor(registry, metrics, piiRegistry);

        hotAdvisor.before(request("reload-probe-word"), chain);
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "input", "family", "UNCLASSIFIED").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isZero();

        // 词表文件动作档转 BLOCK + reload → 免重启热切换为拦截
        Files.writeString(rulesFile, probeRulesYml("BLOCK"));
        assertThat(registry.reload()).isTrue();

        assertThatThrownBy(() -> hotAdvisor.before(request("reload-probe-word"), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
        assertThat(meterRegistry.counter("rag.guardrail.injection.blocked").count()).isEqualTo(1.0);
    }
}
