package com.enterprise.kb.ai.guardrail;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.guardrail.GuardrailReloadSignal;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 护栏词表热重载协调器（安全簇⑥ F1，设计 12.4 S8 Git Ops 词表运营）
 *
 * <p>双触发通道驱动 {@link GuardrailRulesRegistry#reload()}：
 * <ul>
 *   <li><b>pub/sub 即时信号</b>：Redis 频道 {@value #RELOAD_CHANNEL}（应用内触发方
 *       = CRUD 写后闭环 / 迁移灌库，运营侧 = {@code tools/guardrail/publish_reload_signal.py}
 *       或 redis-cli PUBLISH）——攻击情报响应免重启主通道；Redisson 传输内建断线自动重订阅；
 *       <b>自消息跳板（v2.112）</b>：应用内载荷形如 {@code reason|instanceId}
 *       （{@link GuardrailReloadSignal}），发布实例本地已同步重载 ⇒ 自身订阅回环跳过、
 *       不再二次装载；外部/未知载荷恒触发（判定失败的安全默认是重载）；</li>
 *   <li><b>mtime 轮询回落</b>：仅对 {@code file:} 前缀外部词表源启用（jar 内
 *       classpath 资源 mtime 为构建时刻不可靠，且重部署必经进程重启自然重装载），
 *       覆盖信号丢失/Redis 不可达窗口（pub/sub fire-and-forget 无 backlog）；</li>
 *   <li><b>修订号轮询兜底（v2.113）</b>：{@code db} 源 location 为空串 ⇒ mtime 通道
 *       不适用，丢信号即长期陈旧（直到下次信号或重启）。改用
 *       {@link GuardrailRulesRegistry#revisionChanged()}（db 源 =
 *       {@code count(*):max(updated_at)} 单行聚合）低频比对，变更才全量装载
 *       ——兜底通道与「无冗余装载」两者兼得（本实例自己写库也已刷新标记）。</li>
 * </ul>
 *
 * <p><b>装配边界（三层防护，kb-eval 测量一致性）</b>：
 * {@code @ConditionalOnWebApplication} 结构隔离——kb-eval 为 web-none 跑完即退
 * 进程，天然不装配本协调器（词表启动装载一次，运行期不漂移；兼守 kb-eval 零
 * Redis 依赖纪律）；{@code rag.guardrail.reload.enabled} 为运维回退阀门；
 * RTopic 订阅启动失败 try/catch fail-open（降级纯轮询不击穿启动）。
 *
 * <p><b>fail-keep 语义承接</b>：reload 返回 false（装载失败保旧快照）时只计
 * {@code rag.guardrail.reload.failed}——防线不因词表运营故障降级；下一次文件
 * mtime 变化（运维修复词表）自然重试。
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "rag.guardrail.reload.enabled", havingValue = "true", matchIfMissing = true)
public class GuardrailReloadCoordinator {

    /**
     * 热重载信号频道（应用内经 {@link GuardrailReloadSignal#payload} 带实例标识发布；
     * 运营侧经 publish_reload_signal.py / redis-cli PUBLISH 发布裸载荷——载荷仅用于
     * 自消息判定，不承载其他语义）
     */
    public static final String RELOAD_CHANNEL = "rag:guardrail:reload";

    private final GuardrailRulesRegistry registry;
    private final AiBusinessMetrics metrics;
    private final RedissonClient redissonClient;
    private final long pollIntervalSeconds;

    private final List<WatchedSource> watched = new ArrayList<>();
    /** 修订号兜底通道是否可用（源支持修订号，如 db 源）——启动期判定一次 */
    private boolean revisionWatch;
    private ScheduledExecutorService poller;
    private RTopic topic;
    private int topicListenerId;

    public GuardrailReloadCoordinator(
            GuardrailRulesRegistry registry,
            AiBusinessMetrics metrics,
            RedissonClient redissonClient,
            @Value("${rag.guardrail.reload.poll-interval-seconds:60}") long pollIntervalSeconds) {
        this.registry = registry;
        this.metrics = metrics;
        this.redissonClient = redissonClient;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    @PostConstruct
    void start() {
        // 1. pub/sub 即时信号通道（启动 fail-open：Redis 不可达降级纯轮询，不击穿启动）
        try {
            topic = redissonClient.getTopic(RELOAD_CHANNEL, StringCodec.INSTANCE);
            topicListenerId = topic.addListener(String.class, (channel, message) -> {
                if (GuardrailReloadSignal.isSelf(message)) {
                    // 自消息回环（v2.112）：本实例已在触发点完成同步重载并自行落账，跳过重复装载
                    log.debug("护栏词表重载信号出自本实例（本地已同步生效），跳过重复装载: {}", message);
                    return;
                }
                reload("pub/sub 信号");
            });
        } catch (RuntimeException e) {
            log.warn("护栏词表热重载信号频道订阅失败，降级 mtime 轮询单通道: {}", e.getMessage());
        }
        // 2. file: 源 mtime 轮询回落（classpath 源不轮询——jar 内资源 mtime 不可靠）
        watch(registry.injectionLocation());
        watch(registry.outputLocation());
        // 3. 修订号兜底通道（v2.113：db 源 location 空 ⇒ mtime 不适用，须靠修订号轮询）
        try {
            revisionWatch = registry.currentRevision() != null;
        } catch (RuntimeException e) {
            revisionWatch = false;
            log.warn("护栏词表修订号通道探测失败，降级纯信号/轮询形态: {}", e.getMessage());
        }
        if (!watched.isEmpty() || revisionWatch) {
            poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "guardrail-reload-poller");
                thread.setDaemon(true);
                return thread;
            });
            poller.scheduleWithFixedDelay(this::pollOnce,
                pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        }
        log.info("护栏词表热重载协调器就绪: 频道={} / 轮询={}s / file 源 {} 处 / 修订号兜底={}",
            RELOAD_CHANNEL, pollIntervalSeconds, watched.size(), revisionWatch);
    }

    @PreDestroy
    void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
        if (topic != null) {
            try {
                topic.removeListener(topicListenerId);
            } catch (RuntimeException e) {
                log.warn("护栏词表热重载信号频道退订失败（进程退出，忽略）: {}", e.getMessage());
            }
        }
    }

    /** 登记 file: 前缀词表源（缺失资源记 mtime 0——落盘后轮询即捕获，免重启补文件） */
    private void watch(String location) {
        if (location == null || !location.startsWith("file:")) {
            return;
        }
        Resource resource = new DefaultResourceLoader().getResource(location);
        long mtime = 0L;
        try {
            mtime = resource.exists() ? resource.lastModified() : 0L;
        } catch (IOException e) {
            log.warn("护栏词表轮询源 mtime 读取失败（按 0 登记，变更仍可捕获）: {}", location);
        }
        watched.add(new WatchedSource(location, resource, mtime));
    }

    /**
     * 轮询单趟：任一 file 源 mtime 变化或词表修订号前进即触发重载（先更新标记防
     * 信号/轮询重复触发）；包内可见供单测直驱。
     */
    void pollOnce() {
        for (WatchedSource source : watched) {
            long current;
            try {
                current = source.resource.exists() ? source.resource.lastModified() : 0L;
            } catch (IOException e) {
                continue;
            }
            if (current != source.lastModified) {
                source.lastModified = current;
                reload("mtime 轮询检测到变更: " + source.location);
            }
        }
        if (revisionWatch) {
            // 修订号读取异常已在注册表侧降级为「未变更」（保旧快照，下轮再试）
            if (registry.revisionChanged()) {
                reload("修订号变更（丢信号兜底）");
            }
        }
    }

    /** 触发注册表重载并落成败账；成功后同步全部 file 源 mtime 标记（防轮询二次触发） */
    private void reload(String reason) {
        log.info("护栏词表热重载触发（{}）", reason);
        boolean succeeded = registry.reload();
        metrics.recordGuardrailReload(succeeded);
        if (succeeded) {
            for (WatchedSource source : watched) {
                try {
                    source.lastModified = source.resource.exists() ? source.resource.lastModified() : 0L;
                } catch (IOException e) {
                    // 标记滞后仅致一次无害重载，容忍
                }
            }
        }
    }

    /** 轮询源登记项：location 供日志定位，mtime volatile（轮询线程单写，信号路径只读刷新） */
    private static final class WatchedSource {
        private final String location;
        private final Resource resource;
        private volatile long lastModified;

        private WatchedSource(String location, Resource resource, long lastModified) {
            this.location = location;
            this.resource = resource;
            this.lastModified = lastModified;
        }
    }
}
