package com.enterprise.kb.commons.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件词表源（Git Ops 形态）：委派 {@link GuardrailRulesLoader} 静态装载——
 * 结构化文件 ∪ CSV 兼容源双源合并 / 外部 {@code file:} 覆盖（整体顶替语义）/
 * 资源缺失回落内置缺省 / 损坏 fail-fast，既有语义逐字保留。
 *
 * <p>{@code rag.guardrail.rules.source=file}（缺省即本源，matchIfMissing）——
 * 回滚阀门：DB 单轨运营期任何问题回落文件源即恢复 Git Ops 形态。
 * kb-eval 永远走本源（source 缺省 file）实现测量锁版。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.guardrail.rules.source", havingValue = "file", matchIfMissing = true)
public class FileGuardrailRulesSource implements GuardrailRulesSource {

    private final String injectionLocation;
    private final String injectionCsv;
    private final String outputLocation;
    private final String outputCsv;

    public FileGuardrailRulesSource(
            @Value("${rag.guardrail.rules.injection-location:}") String injectionLocation,
            @Value("${rag.guardrail.input.injection-keywords:}") String injectionCsv,
            @Value("${rag.guardrail.rules.output-location:}") String outputLocation,
            @Value("${rag.guardrail.output.blacklist:}") String outputCsv) {
        this.injectionLocation = injectionLocation;
        this.injectionCsv = injectionCsv;
        this.outputLocation = outputLocation;
        this.outputCsv = outputCsv;
    }

    @Override
    public List<GuardrailRule> loadInjectionRules() {
        return GuardrailRulesLoader.loadInjectionRules(injectionLocation, injectionCsv);
    }

    @Override
    public List<GuardrailRule> loadOutputRules() {
        return GuardrailRulesLoader.loadOutputRules(outputLocation, outputCsv);
    }

    @Override
    public String injectionLocation() {
        return injectionLocation;
    }

    @Override
    public String outputLocation() {
        return outputLocation;
    }
}
