package com.enterprise.kb.commons.security.pii;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * PII 识别器注册表（安全簇③ C2，设计 12 章 PII 识别器注册表）——对齐 Presidio
 * AnalyzerEngine 语义：持有启用识别器的有序集合，统一提供掩码/检测入口。
 *
 * <p><b>单一实现源纪律的承载体</b>（替代 TextSanitizer 静态掩码）：对话链
 * （InputSanitizeAdvisor）、ETL 入库（SanitizingTransformer）、审计脱敏
 * （AuditTraceAdvisor/McpAuditRecorder/AgentController）全部消费 Spring 上下文
 * 内同一注册表 Bean（kb-commons {@code PiiConfiguration} 装配），类型扩容与
 * 开关变更单点生效，双链护栏永不漂移。
 *
 * <p><b>顺序语义</b>：识别器按注册顺序依次应用（PHONE → ID_CARD → EMAIL →
 * BANK_CARD → LANDLINE → LICENSE_PLATE → IPV4）——既有三类顺序逐字保留
 * （零行为漂移），C1 新增四类按交叠消解需要排后（18 位纯数字串先落身份证，
 * 19 位串身份证模式不可达由银行卡 Luhn 判定）。顺序即优先级仲裁，确定性形态
 * 无需运行期仲裁（confidence 为元数据预留）。
 *
 * <p><b>掩码幂等</b>：各识别器掩码字面不含可再匹配结构（数字类掩码无数字、
 * 邮箱掩码无词字符、车牌掩码保留前缀 + 星号尾），重复掩码结果不动。
 */
public final class PiiRecognizerRegistry {

    private final List<PiiRecognizer> recognizers;

    public PiiRecognizerRegistry(List<PiiRecognizer> recognizers) {
        this.recognizers = List.copyOf(recognizers);
    }

    /** 缺省全集（七类确定性识别器全启用）：测试与非 Spring 消费侧的同基线入口 */
    public static PiiRecognizerRegistry defaults() {
        return new PiiRecognizerRegistry(List.of(
            new PhonePiiRecognizer(),
            new IdCardPiiRecognizer(),
            new EmailPiiRecognizer(),
            new BankCardPiiRecognizer(),
            new LandlinePiiRecognizer(),
            new LicensePlatePiiRecognizer(),
            new Ipv4PiiRecognizer()));
    }

    /** 启用识别器类型清单（注册顺序） */
    public List<PiiType> enabledTypes() {
        return recognizers.stream().map(PiiRecognizer::type).toList();
    }

    /**
     * PII 掩码（幂等，null 安全）：识别器按注册顺序依次应用。
     * 对话链保护模型上下文与记忆；ETL 落库前三存储面同规则消毒；审计旁路脱敏。
     */
    public String mask(String text) {
        return maskWithReport(text).text();
    }

    /**
     * 掩码 + 命中类型报告（对话链消费形态）：每识别器先 detect 判定命中、
     * 命中方施加掩码——检测与掩码同一模式两次执行的开销在 L1 正则量级可忽略，
     * 换取接口面纯净（识别器不暴露可变状态）。
     */
    public PiiMaskResult maskWithReport(String text) {
        if (text == null) {
            return new PiiMaskResult(null, Set.of());
        }
        String current = text;
        Set<PiiType> hits = EnumSet.noneOf(PiiType.class);
        for (PiiRecognizer recognizer : recognizers) {
            if (recognizer.detect(current).isEmpty()) {
                continue;
            }
            hits.add(recognizer.type());
            current = recognizer.mask(current);
        }
        return new PiiMaskResult(current,
            hits.isEmpty() ? Set.of() : Collections.unmodifiableSet(hits));
    }

    /**
     * 检测视图（只识别不掩码）：输出侧 PII 回显探测消费（观察起步不替换），
     * 返回全部启用识别器的命中（按识别器顺序拼接，各识别器内按出现顺序）。
     */
    public List<PiiHit> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiHit> all = new ArrayList<>();
        for (PiiRecognizer recognizer : recognizers) {
            all.addAll(recognizer.detect(text));
        }
        return List.copyOf(all);
    }
}
