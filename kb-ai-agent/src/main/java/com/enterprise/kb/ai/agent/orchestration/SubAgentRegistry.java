package com.enterprise.kb.ai.agent.orchestration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 子代理注册表（簇⑤ 5.3）——Orchestrator-Workers 的 Workers 面
 *
 * <p>静态注册（装配期定死，运行期不可变）：{@code rag.orchestrator.enabled=true}
 * 时由 OrchestratorChatClientConfig 装配初始集；主 Agent 系统提示的子代理清单
 * 经 {@link #renderRoster()} 渲染注入（{@code %s} 占位，见 PromptTemplates）。
 *
 * <p>线程安全说明：LinkedHashMap 仅装配期写入、运行期只读（条件装配下
 * Bean 初始化完成即冻结；委派并发只读无争用）。
 */
public class SubAgentRegistry {

    private final Map<String, SubAgentSpec> specs = new LinkedHashMap<>();

    public SubAgentRegistry(Iterable<SubAgentSpec> initial) {
        initial.forEach(this::register);
    }

    public void register(SubAgentSpec spec) {
        specs.put(spec.name(), spec);
    }

    /** 按名查找（未命名返回 null，由 TaskTool 转错误文本供主 Agent 纠正重试） */
    public SubAgentSpec find(String name) {
        return specs.get(name);
    }

    public Collection<SubAgentSpec> all() {
        return specs.values();
    }

    /** 主 Agent 系统提示的子代理清单（name — 职责，逐行） */
    public String renderRoster() {
        return specs.values().stream()
            .map(spec -> "- " + spec.name() + " — " + spec.description())
            .collect(Collectors.joining("\n"));
    }

    /** 可用子代理名列表（未知名委派的错误提示用） */
    public String renderNames() {
        return String.join(", ", specs.keySet());
    }
}
