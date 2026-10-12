package com.enterprise.kb.commons.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表修订号语义测试（v2.113 丢信号兜底轮询）：装载前取号（不吞装载窗口内提交的写入）、
 * 不支持的源恒不报变更、读号失败降级不击穿重载。
 */
class GuardrailRulesRegistryRevisionTest {

    /** 可编程修订号源：规则恒空，revision 由测试推进；onLoad 模拟「装载窗口内他实例提交」 */
    private static final class StubSource implements GuardrailRulesSource {

        private String revision;
        private Runnable onLoad = () -> { };

        private StubSource(String revision) {
            this.revision = revision;
        }

        @Override
        public List<GuardrailRule> loadInjectionRules() {
            onLoad.run();
            return List.of();
        }

        @Override
        public List<GuardrailRule> loadOutputRules() {
            return List.of();
        }

        @Override
        public String sourceName() {
            return "stub";
        }

        @Override
        public String revision() {
            return revision;
        }
    }

    /** 修订号读取异常源：规则可装载，仅修订号不可知（如 DB 瞬时抖动） */
    private static final class BrokenRevisionSource implements GuardrailRulesSource {

        @Override
        public List<GuardrailRule> loadInjectionRules() {
            return List.of();
        }

        @Override
        public List<GuardrailRule> loadOutputRules() {
            return List.of();
        }

        @Override
        public String revision() {
            throw new IllegalStateException("revision unavailable");
        }
    }

    @Test
    void revisionAdvanceIsDetectedThenClearedByReload() {
        StubSource source = new StubSource("1:x");
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(source);

        assertThat(registry.currentRevision()).isEqualTo("1:x");
        assertThat(registry.revisionChanged()).isFalse();

        source.revision = "2:y";
        assertThat(registry.revisionChanged()).isTrue();

        assertThat(registry.reload()).isTrue();
        assertThat(registry.revisionChanged()).isFalse();   // 装载后标记刷新，不重复触发
    }

    @Test
    void revisionAdvancingDuringLoadIsNotSwallowed() {
        StubSource source = new StubSource("1:x");
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(source);

        // 装载窗口内他实例提交：修订号在装载之后才前进 —— 装载前取号语义保证不被标记为已装载
        source.onLoad = () -> source.revision = "2:y";
        assertThat(registry.reload()).isTrue();

        assertThat(registry.revisionChanged()).isTrue();    // 下轮兜底轮询仍会重载（不漏更）
        source.onLoad = () -> { };
        registry.reload();
        assertThat(registry.revisionChanged()).isFalse();
    }

    @Test
    void sourceWithoutRevisionSupportNeverReportsChange() {
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(new StubSource(null));

        assertThat(registry.currentRevision()).isNull();
        assertThat(registry.revisionChanged()).isFalse();
    }

    @Test
    void revisionReadFailureDegradesToUnknownAndReloadStillWorks() {
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(new BrokenRevisionSource());

        assertThat(registry.currentRevision()).isNull();       // 未知，协调器据此不启用修订轮询
        assertThat(registry.revisionChanged()).isFalse();      // 不需重载（无判据）
        assertThat(registry.reload()).isTrue();                // 规则装载不受修订号故障影响
    }
}
