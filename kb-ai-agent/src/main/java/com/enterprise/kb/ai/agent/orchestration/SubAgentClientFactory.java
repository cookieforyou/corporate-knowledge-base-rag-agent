package com.enterprise.kb.ai.agent.orchestration;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 子代理 ChatClient 工厂（簇⑤ 5.3）——轻链构建策略，TaskTool 经此获取子客户端
 *
 * <p>生产实现（OrchestratorChatClientConfig）：按 Spec.name 缓存 +
 * {@code ChatClient.builder(spec.chatModel(), observationRegistry, null, null)}——
 * 不挂 Memory（上下文隔离）/ Audit（委派记录经主请求审计行）/ 配额（主请求已计账，
 * 防双计）；身份安全不在此层——由 TaskTool 经子调用 toolContext 下传。
 *
 * <p>接口化的目的：TaskTool 的路由/超时/失败语义单测可注入 mock 工厂，
 * 不依赖真实 ChatModel 装配。
 */
@FunctionalInterface
public interface SubAgentClientFactory {

    ChatClient create(SubAgentSpec spec);
}
