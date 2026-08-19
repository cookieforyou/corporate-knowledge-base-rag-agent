package com.enterprise.kb.admin.dto;

/**
 * 词项写操作回执（v2.53 词表 DB 单轨）——DB 写入与热重载状态分离表达：
 * DB 为唯一事实源，写成功即受理；{@code reloaded=false} 表示本地热重载
 * fail-keep 保旧快照（词表装载异常），运营经手动重载端点重试。
 *
 * @param rule     写后词项编辑视图
 * @param reloaded 写后本地热重载是否成功替换快照
 */
public record GuardrailRuleMutationResult(
    GuardrailRuleEditView rule,
    boolean reloaded
) {
}
