package com.enterprise.kb.commons.guardrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护栏词表注册表测试（安全簇⑥ F1）—— 初始装载 / 热重载原子替换 / fail-keep 保旧 /
 * 监听器异常隔离与退订。词面值全部为无语义占位串（第七节敏感词交付纪律）。
 */
class GuardrailRulesRegistryTest {

    private static final String TEST_RULES = "classpath:guardrail-test/test-rules.yml";

    @TempDir
    Path tempDir;

    /** 记录型监听器：双侧快照更新各留痕 */
    private static final class RecordingListener implements GuardrailRulesListener {
        private final List<List<GuardrailRule>> injectionUpdates = new ArrayList<>();
        private final List<List<GuardrailRule>> outputUpdates = new ArrayList<>();

        @Override
        public void onInjectionRulesUpdated(List<GuardrailRule> rules) {
            injectionUpdates.add(rules);
        }

        @Override
        public void onOutputRulesUpdated(List<GuardrailRule> rules) {
            outputUpdates.add(rules);
        }
    }

    private static String rulesYml(String... words) {
        StringBuilder sb = new StringBuilder("rules:\n");
        int index = 0;
        for (String word : words) {
            sb.append("  - id: reload-probe-").append(index++).append('\n')
                .append("    family: UNCLASSIFIED\n")
                .append("    lang: zh\n")
                .append("    type: KEYWORD\n")
                .append("    value: \"")
                .append(Base64.getEncoder().encodeToString(word.getBytes(StandardCharsets.UTF_8)))
                .append("\"\n")
                .append("    action: BLOCK\n")
                .append("    enabled: true\n");
        }
        return sb.toString();
    }

    private Path writeRulesFile(String content) throws IOException {
        Path rulesFile = tempDir.resolve("rules.yml");
        Files.writeString(rulesFile, content);
        return rulesFile;
    }

    @Test
    void initialLoadProvidesBothSnapshots() {
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(TEST_RULES, "", "", "");

        // test-rules.yml 有效词项 3 条（畸形 Base64 词项按 Loader 语义跳过）；
        // 输出侧空配置 → 内置缺省词表
        assertThat(registry.currentInjectionRules()).hasSize(3);
        assertThat(registry.currentOutputRules()).isNotEmpty();
    }

    @Test
    void reloadSwapsSnapshotsAndNotifiesListeners() throws IOException {
        Path rulesFile = writeRulesFile(rulesYml("reload-probe-alpha"));
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(
            "file:" + rulesFile.toAbsolutePath(), "", "", "");
        RecordingListener listener = new RecordingListener();
        registry.subscribe(listener);

        Files.writeString(rulesFile, rulesYml("reload-probe-alpha", "reload-probe-beta"));
        boolean succeeded = registry.reload();

        assertThat(succeeded).isTrue();
        assertThat(registry.currentInjectionRules()).hasSize(2);
        assertThat(listener.injectionUpdates).hasSize(1);
        assertThat(listener.injectionUpdates.get(0)).hasSize(2);
        assertThat(listener.outputUpdates).hasSize(1);
    }

    @Test
    void reloadFailureKeepsOldSnapshotAndSkipsNotification() throws IOException {
        Path rulesFile = writeRulesFile(rulesYml("reload-probe-alpha"));
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(
            "file:" + rulesFile.toAbsolutePath(), "", "", "");
        List<GuardrailRule> snapshotBefore = registry.currentInjectionRules();
        RecordingListener listener = new RecordingListener();
        registry.subscribe(listener);

        Files.writeString(rulesFile, "rules: [unclosed");   // 结构性损坏 → Loader fail-fast
        boolean succeeded = registry.reload();

        assertThat(succeeded).isFalse();
        assertThat(registry.currentInjectionRules()).isSameAs(snapshotBefore);
        assertThat(listener.injectionUpdates).isEmpty();
    }

    @Test
    void listenerExceptionDoesNotBreakRemainingListeners() throws IOException {
        Path rulesFile = writeRulesFile(rulesYml("reload-probe-alpha"));
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(
            "file:" + rulesFile.toAbsolutePath(), "", "", "");
        RecordingListener healthy = new RecordingListener();
        registry.subscribe(new GuardrailRulesListener() {
            @Override
            public void onInjectionRulesUpdated(List<GuardrailRule> rules) {
                throw new IllegalStateException("监听器故障占位");
            }
        });
        registry.subscribe(healthy);

        Files.writeString(rulesFile, rulesYml("reload-probe-alpha", "reload-probe-beta"));

        assertThat(registry.reload()).isTrue();
        assertThat(healthy.injectionUpdates).hasSize(1);
    }

    @Test
    void unsubscribedListenerNotNotified() throws IOException {
        Path rulesFile = writeRulesFile(rulesYml("reload-probe-alpha"));
        GuardrailRulesRegistry registry = new GuardrailRulesRegistry(
            "file:" + rulesFile.toAbsolutePath(), "", "", "");
        RecordingListener listener = new RecordingListener();
        registry.subscribe(listener);
        registry.unsubscribe(listener);

        Files.writeString(rulesFile, rulesYml("reload-probe-alpha", "reload-probe-beta"));

        assertThat(registry.reload()).isTrue();
        assertThat(registry.currentInjectionRules()).hasSize(2);
        assertThat(listener.injectionUpdates).isEmpty();
    }
}
