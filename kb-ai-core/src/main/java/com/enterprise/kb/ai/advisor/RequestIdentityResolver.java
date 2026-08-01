package com.enterprise.kb.ai.advisor;

/**
 * 当前请求身份解析（2.11）—— kb-ai-core 对 kb-api JWT 层的解耦抽象
 *
 * <p>依赖方向约束（kb-ai-core ← kb-api）决定本模块不可引用 kb-api 的 JwtUtils；
 * kb-api 提供基于 JWT claims 的实现（owner→tenantId、sub→userId）。
 * 无实现的运行环境（如 kb-eval 非 Web 评估进程）经 ObjectProvider 判空降级：
 * 不填充租户上下文，检索不做租户隔离（评估期可接受，生产链路由 SecurityConfig 保证）。
 */
public interface RequestIdentityResolver {

    /** 当前租户 ID（JWT owner claim） */
    String getTenantId();

    /** 当前用户 ID（JWT sub claim） */
    String getUserId();
}
