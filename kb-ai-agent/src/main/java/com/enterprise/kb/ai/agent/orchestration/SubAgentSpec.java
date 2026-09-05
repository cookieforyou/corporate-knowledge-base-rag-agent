package com.enterprise.kb.ai.agent.orchestration;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * 子代理静态描述子（簇⑤ 5.3，设计文档 §11.5.5）
 *
 * <p>Orchestrator-Workers 收窄骨架：主 Agent 仅持 {@link TaskTool} 委派工具，
 * 每个子代理由本描述子定义——独立 system prompt / 工具集 / 模型 / 超时，
 * 委派时在隔离上下文中执行（不挂主会话记忆，看不到主对话历史）。
 *
 * <p><b>真实工具挂接契约</b>：新增能力 = 注册一条新 Spec（主 Agent 系统提示的
 * 子代理清单经 {@link SubAgentRegistry#renderRoster()} 自动纳入，链路零改动）；
 * 已有子代理换真实实现 = 替换 toolObjects 实现（@Tool 契约不变即零改动）。
 * 升级路径（跨层 HITL / usage 聚合计账 / 并发委派）见 §11.5.5 契约文档。
 *
 * <p><b>递归物理防护</b>：toolObjects 静态定义且不得包含 {@link TaskTool}——
 * 子代理无法再委派，层级恒为两层。
 */
public record SubAgentSpec(String name, String description, String systemPrompt,
                           List<Object> toolObjects, ChatModel chatModel, int timeoutSeconds) {

    public SubAgentSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("子代理 name 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("子代理 description 不能为空（主 Agent 选择依据）");
        }
        toolObjects = toolObjects == null ? List.of() : List.copyOf(toolObjects);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("子代理 timeoutSeconds 必须 > 0: " + name);
        }
    }
}
