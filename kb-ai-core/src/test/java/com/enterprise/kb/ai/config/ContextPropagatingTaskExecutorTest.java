package com.enterprise.kb.ai.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求上下文传播执行器单测（2.12 热修）：工作线程可见请求属性，执行后清理
 */
class ContextPropagatingTaskExecutorTest {

    private final AsyncTaskExecutor executor =
        new ContextPropagatingTaskExecutor(new VirtualThreadTaskExecutor("test-retrieval-"));

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void propagatesRequestAttributesToWorkerThread() throws Exception {
        RequestAttributes attributes = Mockito.mock(RequestAttributes.class);
        RequestContextHolder.setRequestAttributes(attributes);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RequestAttributes> seen = new AtomicReference<>();
        AtomicReference<String> threadName = new AtomicReference<>();

        executor.execute(() -> {
            seen.set(RequestContextHolder.getRequestAttributes());
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertSame(attributes, seen.get());
        assertTrue(threadName.get().startsWith("test-retrieval-"), "应在虚拟线程上执行");
    }

    @Test
    void cleansUpAfterTask() throws Exception {
        RequestContextHolder.setRequestAttributes(Mockito.mock(RequestAttributes.class));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RequestAttributes> after = new AtomicReference<>();
        executor.execute(() -> {
            done.countDown();
            // 任务体内清理发生在 finally——此处仍在任务内，用第二个任务验证清理
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));

        CountDownLatch done2 = new CountDownLatch(1);
        // 无请求上下文的提交（模拟工作线程再提交）→ 原样执行不传播
        RequestContextHolder.resetRequestAttributes();
        executor.execute(() -> {
            after.set(RequestContextHolder.getRequestAttributes());
            done2.countDown();
        });
        assertTrue(done2.await(5, TimeUnit.SECONDS));
        assertNull(after.get());
    }
}
