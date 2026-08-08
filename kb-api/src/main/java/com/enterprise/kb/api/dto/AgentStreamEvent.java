package com.enterprise.kb.api.dto;

import java.util.List;
import java.util.Map;

/**
 * SSE 流式对话事件类型（设计文档 11.3，任务 2.12）
 *
 * <p>协议兼容策略（11.3 v2 注「与 Phase 1 兼容」）：TOKEN/ERROR 保持 Phase 1
 * 线形（无名 message 事件 + 原数据形状 {"token":...} / {"error":...}）；
 * TRACE 为**新增命名事件**（event: TRACE），旧前端忽略，簇 D 前端切换
 * EventSource 命名监听后消费溯源。TOOL_CALL 事件随 2.13 工具调用落地时追加。
 *
 * <p><b>DONE 帧协议修订（3.17，v2.14）</b>：DONE 由字面量 "[DONE]" 演进为
 * JSON 载荷 {"messageId":..., "traceId":...}——终帧天然携带本轮句柄：
 * messageId 定位归档消息（反馈 API 外键），traceId 关联审计行（反馈回填）。
 * 唯一消费方为自家前端，同批改造；错误路径不发 DONE（无归档无可评价对象）。
 */
public sealed interface AgentStreamEvent {

    /** 增量 token（无名事件，data 形状与 Phase 1 一致） */
    record TokenEvent(String token) implements AgentStreamEvent {}

    /** 流式失败（无名事件，{"error": msg} 与 Phase 1 一致） */
    record ErrorEvent(String error) implements AgentStreamEvent {}

    /** 流结束（无名事件，3.17 起 JSON 载荷）：本轮助手消息 ID + 请求级 traceId */
    record DoneEvent(String messageId, String traceId) implements AgentStreamEvent {}

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
     * scores 按路透传（bm25_score/bm25_rank、vector_rank、fusion_score、rerank_score/rerank_rank）。
     * docId 为「查看原文」数据通道（3.15）：前端凭 docId+chunkId 经
     * GET /documents/{docId}/chunks（租户隔离）按需拉全文，不经 SSE 帧下传。
     */
    record ChunkTrace(String chunkId, String docId, String fileName, Integer pageNum,
                      Map<String, Object> scores, String snippet) {}
}
