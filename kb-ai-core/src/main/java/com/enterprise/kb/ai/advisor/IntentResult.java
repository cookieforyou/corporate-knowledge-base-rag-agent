package com.enterprise.kb.ai.advisor;

/**
 * 意图分类器结构化输出（5.4 收窄版，QueryRoutingAdvisor 消费）
 *
 * <p>经 {@code ChatClient.call().entity(IntentResult.class)} 解析——
 * jsonschema-module-jackson（父 POM 锁定 5.0.0）由 record 生成 JSON Schema
 * 注入分类 prompt。intent 用 String 承接而非 enum：模型输出变体
 * （大小写/空白）由消费方 equalsIgnoreCase 容错，避免反序列化硬失败。
 *
 * @param intent         KNOWLEDGE（知识问答，走完整检索）| CHITCHAT（闲聊/元问题，免检索直答）
 * @param rewrittenQuery KNOWLEDGE 路径的检索用 query：含指代/省略时为消解后的完整查询，
 *                       否则为当前消息原文；CHITCHAT 路径为 null
 */
public record IntentResult(String intent, String rewrittenQuery) {

    public static final String INTENT_KNOWLEDGE = "KNOWLEDGE";
    public static final String INTENT_CHITCHAT = "CHITCHAT";
}
