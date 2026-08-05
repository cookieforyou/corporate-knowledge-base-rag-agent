package com.enterprise.kb.api.dto;

import java.util.List;
import java.util.Map;

/**
 * SSE 流式对话事件类型（设计文档 11.3，任务 2.12）
 *
 * <p>协议兼容策略（11.3 v2 注「与 Phase 1 兼容」）：TOKEN/ERROR/DONE 保持 Phase 1
 * 线形（无名 message 事件 + 原数据形状 {"token":...} / {"error":...} / [DONE]，
 * 旧前端行解析器零改动可用）；TRACE 为**新增命名事件**（event: TRACE），
 * 旧前端忽略，簇 D 前端切换 EventSource 命名监听后消费溯源。
 * TOOL_CALL 事件随 2.13 工具调用落地时追加。
 */
public sealed interface AgentStreamEvent {

    /** 增量 token（无名事件，data 形状与 Phase 1 一致） */
    record TokenEvent(String token) implements AgentStreamEvent {}

    /** 流式失败（无名事件，{"error": msg} 与 Phase 1 一致） */
    record ErrorEvent(String error) implements AgentStreamEvent {}

    /**
     * 检索溯源（命名事件 TRACE，流末推送）：双路原始命中 + final 最终注入序列。
     * final 序列下标与回答中 [ref-N] 标注一一对应（11.1.2），前端溯源卡片按此渲染。
     */
    record TraceEvent(List<SourceTrace> sources) implements AgentStreamEvent {}

    /**
     * 工具调用状态（命名事件 TOOL_CALL，流末先于 TRACE 推送，任务 3.4 复审要素③）：
     * 写工具挂起时 status=PENDING_APPROVAL 携带 approvalId，前端弹确认卡片；
     * 用户确认后经 /chat 请求体 approvedToolCallId 回传触发真正执行（EXECUTED）。
     */
    record ToolCallEvent(List<ToolCallInfo> toolCalls) implements AgentStreamEvent {}

    /** 工具调用投影：与 RetrievalContext.ToolCall 同形（SSE 载荷独立 record，与 ChunkTrace 投影同策） */
    record ToolCallInfo(String toolName, String status, String approvalId, String summary) {}

    /** 单路 trace：source ∈ {vector, bm25, final}；latencyMs 该路耗时（10.8 时延观测） */
    record SourceTrace(String source, List<ChunkTrace> chunks, Long latencyMs) {}

    /**
     * Chunk 轻量投影（不序列化全文，控制 SSE 帧体积）：
     * scores 按路透传（bm25_score/bm25_rank、vector_rank、fusion_score、rerank_score/rerank_rank）
     */
    record ChunkTrace(String chunkId, String fileName, Integer pageNum,
                      Map<String, Object> scores, String snippet) {}
}
