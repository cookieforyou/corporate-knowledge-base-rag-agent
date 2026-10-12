package com.enterprise.kb.commons.guardrail;

import java.util.UUID;

/**
 * 词表热重载信号载荷契约（v2.112 自跳板，设计 12.7 词表工程）
 *
 * <p><b>问题</b>：应用内触发方（CRUD 写后闭环 / 迁移灌库）在本地
 * {@link GuardrailRulesRegistry#reload()} 同步生效后向 {@code rag:guardrail:reload}
 * 广播集群信号；Redis pub/sub 的扇出集合<b>包含发布者自身的订阅连接</b>，
 * 于是一次触发发生<b>两次全量装载</b>——第二次纯冗余（重载为状态式，快照恒取自
 * 事实源，结果相同），且把「本地重载」与「信号重载」的记账/日志搅在一起
 * （本地重载不落指标、回环那次落指标 ⇒ 指标与真实重载脱钩）。
 *
 * <p><b>治法</b>：内部发布者的载荷统一携带本 JVM 实例标识
 * （{@code reason|instanceId}），订阅侧凭 {@link #isSelf(String)} 跳过自消息——
 * 发布实例已完成本地同步重载，无需二次装载；他实例照旧重载。
 *
 * <p><b>兼容与纪律</b>：外部通道（{@code tools/guardrail/publish_reload_signal.py}
 * / redis-cli）不带本标识，一律视为外部信号<b>恒触发重载</b>；载荷判定失败
 * （null / 旧格式 / 任意文本）同理<b>不跳过</b>——安全默认是「重载」而非「忽略」。
 * 载荷仅用于身份判定，不承载其他语义（触发原因仅供日志，不做分支）。
 */
public final class GuardrailReloadSignal {

    /** 分隔符：{@code reason|instanceId}（reason 不含该字符） */
    private static final String SEPARATOR = "|";

    /** 本 JVM 实例标识（一次进程一个；容器形态 1 进程 = 1 应用实例） */
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private GuardrailReloadSignal() {
    }

    /** 构造本实例的广播载荷；{@code reason} 仅供对端日志定位（不做分支）。 */
    public static String payload(String reason) {
        return reason + SEPARATOR + INSTANCE_ID;
    }

    /**
     * 消息是否出自本实例（发布实例已完成本地同步重载 ⇒ 跳过回环的重复装载）。
     * null / 外部载荷 / 旧格式一律返回 false（→ 正常触发重载）。
     */
    public static boolean isSelf(String message) {
        return message != null && message.endsWith(SEPARATOR + INSTANCE_ID);
    }

    /** 本实例标识（日志与单测断言用） */
    public static String instanceId() {
        return INSTANCE_ID;
    }
}
