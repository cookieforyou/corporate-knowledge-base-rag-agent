package com.enterprise.kb.ai.advisor;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 任务边界 Advisor（簇⑤ 收官注记① 三轮，11 章 v2.108）—— Order 420，
 * Memory(400) 之后 ToolCallingAdvisor(1000) 之前，消息层结构分隔注记。
 *
 * <p><b>治理对象</b>：编排链多轮会话跨任务历史污染——同会话连发独立新任务时，
 * 历史轮次随 Memory 注入后主 Agent 呈「会话=任务清单累积」心智（新消息被理解
 * 为追加第二项任务，旧任务全套重做）。system prompt 层三轮纪律（否定式禁令 →
 * 程序式两分支 → 末尾重申，v2.106-v2.107）实测均被无视——消息层历史惯性压过
 * system 层静态纪律（v2.105「prompt 纪律是概率性约束」同源结论）。
 *
 * <p><b>机制</b>：历史在场（UserMessage 数 &gt; 1）时在最后一条用户消息前插入
 * SystemMessage 分隔注记（历史完结声明 + 当前轮次边界）——注意力位置从 system
 * 层移至消息层紧贴当前任务（热修五「停止指令入 SearchOutcome 载荷」同款位置
 * 治理逻辑）；首轮（无历史）零注入零变化。
 *
 * <p><b>记忆零污染</b>（源码核验 Spring AI 2.0.1 MessageChatMemoryAdvisor）：
 * user 消息写入发生于其 before 阶段（本 advisor 之前，取原始形态）、assistant
 * 消息写入取自 response——本 advisor 的消息注入不进记忆回写，逐轮独立幂等。
 *
 * <p>仅编排链挂载（编排多轮任务场景专属）；无历史时透传，对他链/首轮零影响。
 */
public class TaskBoundaryAdvisor implements CallAdvisor, StreamAdvisor {

    /** 分隔注记（结构信号优先于禁令措辞）：历史完结 + 当前轮次边界双职能 */
    static final String BOUNDARY_NOTE =
        "──── 历史轮次到此结束：其中所有任务均已交付完结 ────"
            + "当前轮次：仅处理下一条用户消息所述任务；"
            + "历史内容仅当该消息明确引用时（如「刚才那份报告」「再补充一节」）使用。";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(applyBoundary(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(applyBoundary(request));
    }

    /**
     * 历史在场（UserMessage 数 > 1）时在最后一条用户消息前插入分隔注记；
     * 首轮（单 UserMessage）透传零变化。注记为 SystemMessage 类型——不会被
     * Memory 的 {@code getLastUserOrToolResponseMessage} 选中写入记忆。
     */
    static ChatClientRequest applyBoundary(ChatClientRequest request) {
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        int lastUserIdx = -1;
        int userCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                userCount++;
                lastUserIdx = i;
            }
        }
        if (userCount <= 1) {
            return request;
        }
        messages.add(lastUserIdx, new SystemMessage(BOUNDARY_NOTE));
        return request.mutate()
            .prompt(request.prompt().mutate().messages(messages).build())
            .build();
    }

    @Override
    public String getName() {
        return "TaskBoundaryAdvisor";
    }

    @Override
    public int getOrder() {
        return 420;
    }
}
