package com.enterprise.kb.ai.config;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * 请求上下文传播执行器（2.12 热修）—— 包装虚拟线程执行器
 *
 * <p>问题：RetrievalAugmentationAdvisor 经 taskExecutor 在虚拟线程上执行检索任务，
 * 而 {@link RequestContextHolder} 是 ThreadLocal——工作线程拿不到请求属性，
 * 导致请求级 RetrievalContext（租户过滤、溯源 trace）在流式/并行检索路径静默降级。
 *
 * <p>机制：任务提交时（请求线程）捕获 RequestAttributes，任务执行时（工作线程）
 * 重绑、执行后清理。同一 RequestAttributes 实例在工作线程只读消费（检索组件仅读取
 * 请求作用域 Bean），与请求线程的写入天然隔离于不同生命周期阶段，安全。
 * 非 Web 环境（kb-eval）attrs 为 null，任务原样执行。
 */
public class ContextPropagatingTaskExecutor implements AsyncTaskExecutor {

    private final AsyncTaskExecutor delegate;

    public ContextPropagatingTaskExecutor(AsyncTaskExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(wrap(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    private Runnable wrap(Runnable task) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return task;
        }
        return () -> {
            RequestContextHolder.setRequestAttributes(attributes);
            try {
                task.run();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return task;
        }
        return () -> {
            RequestContextHolder.setRequestAttributes(attributes);
            try {
                return task.call();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
    }
}
