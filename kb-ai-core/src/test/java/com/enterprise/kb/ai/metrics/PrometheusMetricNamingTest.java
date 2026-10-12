package com.enterprise.kb.ai.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusNamingConvention;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标导出名守卫（v2.114 实证）：Micrometer 的 Prometheus 命名约定会<b>剥离保留后缀</b>
 * ——`rag.guardrail.rule.created` 实测导出为 `rag_guardrail_rule`（scrape 期成
 * `rag_guardrail_rule_total`，与「规则总数」语义撞名且与文档名不符）。本测试把
 * 「声明名 → 导出名」映射钉死在测试里，防后人再引入保留后缀末段。
 *
 * <p>约定内的两种变换予以放行：counter 末段 {@code total} 归一为 {@code _total}；
 * timer 追加 {@code _seconds}（两者均为标准形态，生产实测已证）。
 */
class PrometheusMetricNamingTest {

    /** Prometheus 保留后缀（出现在末段即被剥离/改写）：created 为本次实证踩中项 */
    private static final Set<String> RESERVED_TAIL_SEGMENTS =
        Set.of("created", "bucket", "sum", "count", "info");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiBusinessMetrics metrics = new AiBusinessMetrics(meterRegistry);
    private final PrometheusNamingConvention convention = new PrometheusNamingConvention();

    /**
     * 声明名 → 约定内应有的导出基名（不含 scrape 期追加的 _total）。
     * 放行三类标准形态：counter 末段 total 归一为 _total、timer 追加 _seconds、
     * 带 baseUnit 的仪表（如 Micrometer 客户端分位 gauge 继承 Timer 的 seconds）。
     */
    private static String expectedExportedBase(Meter.Id id) {
        String dotted = id.getName().replace('.', '_').replace('-', '_');
        String base = switch (id.getType()) {
            case COUNTER -> dotted.endsWith("_total")
                ? dotted.substring(0, dotted.length() - "_total".length())
                : dotted;
            case TIMER -> dotted.endsWith("_seconds") ? dotted : dotted + "_seconds";
            default -> dotted;
        };
        String unit = id.getBaseUnit();
        if (unit != null && !unit.isBlank() && !base.endsWith("_" + unit)) {
            base = base + "_" + unit;
        }
        return base;
    }

    @Test
    void businessMetricsRegistersMetersForAudit() {
        assertThat(metrics).isNotNull();
        assertThat(meterRegistry.getMeters()).isNotEmpty();
    }

    @Test
    void noMeterNameEndsWithPrometheusReservedSegment() {
        for (Meter meter : meterRegistry.getMeters()) {
            String name = meter.getId().getName();
            String tail = name.substring(name.lastIndexOf('.') + 1);
            assertThat(RESERVED_TAIL_SEGMENTS)
                .as("指标 %s 末段 %s 属 Prometheus 保留后缀——导出名会被蚕食或撞名", name, tail)
                .doesNotContain(tail);
        }
    }

    @Test
    void everyMeterNameExportsWithoutDroppedSegments() {
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            assertThat(convention.name(id.getName(), id.getType(), id.getBaseUnit()))
                .as("指标 %s 的导出名发生段丢失", id.getName())
                .isEqualTo(expectedExportedBase(id));
        }
    }

    /** 同源名带不同标签注册（如分位 phi / 族系 family）属正常形态；此处只查「异源撞名」 */
    @Test
    void exportedNamesAreUniqueAcrossFamily() {
        Map<String, String> owners = new LinkedHashMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            String exported = convention.name(id.getName(), id.getType(), id.getBaseUnit());
            String owner = owners.putIfAbsent(exported, id.getName());
            assertThat(owner == null || owner.equals(id.getName()))
                .as("导出名 %s 被 %s 与 %s 同时占用（导出后撞名）", exported, owner, id.getName())
                .isTrue();
        }
    }

    /** 护栏词表运营族：三名同构导出（v2.114 改名后一一对应） */
    @Test
    void guardrailRuleOpsFamilyKeepsSymmetricExportedNames() {
        assertThat(convention.name("rag.guardrail.rule.create", Meter.Type.COUNTER, null))
            .isEqualTo("rag_guardrail_rule_create");
        assertThat(convention.name("rag.guardrail.rule.updated", Meter.Type.COUNTER, null))
            .isEqualTo("rag_guardrail_rule_updated");
        assertThat(convention.name("rag.guardrail.rule.deleted", Meter.Type.COUNTER, null))
            .isEqualTo("rag_guardrail_rule_deleted");
        // 旧声明的现实：保留后缀被蚕食（留作回归钉子，防再改回）
        assertThat(convention.name("rag.guardrail.rule.created", Meter.Type.COUNTER, null))
            .isEqualTo("rag_guardrail_rule");
    }
}
