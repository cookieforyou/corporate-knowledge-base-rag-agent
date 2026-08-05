package com.enterprise.kb.commons.exception;

/**
 * Token 预算耗尽异常（任务 3.8，设计文档 12.3）
 *
 * <p>租户日 Token 预算用尽时由 TokenBudgetAdvisor 抛出。GlobalExceptionHandler
 * 将 errorCode {@code TOKEN_BUDGET_EXCEEDED} 映射为 HTTP 429（配额类拒绝，
 * 与 RATE_LIMITED 同族）。
 *
 * <p>v2.6 实现期修正：草图中本类为 BusinessException.java 同文件包私有类，
 * 跨模块（kb-ai-core）不可用，按草图注记拆分独立文件并公开。
 */
public class TokenBudgetExceededException extends BusinessException {

    public TokenBudgetExceededException(String message) {
        super("TOKEN_BUDGET_EXCEEDED", message);
    }
}
