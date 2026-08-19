package com.enterprise.kb.commons.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 护栏词表注册表（安全簇⑥ F1，设计 12.4 S8 词表动态运营 / 12.7 词表工程）
 *
 * <p>全上下文<b>单一词表持有者</b>：注入/输出双侧 volatile 快照，启动经
 * {@link GuardrailRulesSource} SPI 装载（v2.53 起源按
 * {@code rag.guardrail.rules.source} 条件装配择一：file 源双源合并 Git Ops 形态 /
 * db 源 PG 唯一事实源——Plan C 修订形态 DB 单轨），热重载时
 * 先全量重载、成功后<b>双侧原子替换</b>并经 {@link GuardrailRulesListener}
 * 推送各消费方；替换前各消费方持有的旧快照为不可变 List，in-flight 匹配
 * 天然安全（无锁无竞态）。
 *
 * <p><b>fail-keep 纪律</b>：reload 任一侧装载失败（安全配置损坏 fail-fast 上抛
 * 形态）→ 保旧快照不替换、warn 记录、返回 false——词表运营故障不得击穿既有
 * 防线（与 file 源「资源缺失回落内置缺省」的分层语义互补：缺失有回落，损坏不替换）。
 *
 * <p>kb-commons 第二个 Spring 装配（继安全簇③ PII 识别器注册表后，同款
 * 跨模块共享 Bean 形态）：kb-ai-core / kb-etl / kb-admin 消费方经依赖方向
 * 自然可达；kb-eval 独立上下文同样装配本 Bean 但重载协调器经
 * {@code rag.guardrail.reload.enabled=false} 条件装配关闭（测量一致性——
 * eval 运行期词表不漂移），且 kb-eval 恒走 file 源（source 缺省 file）锁版。
 */
@Slf4j
@Component
public class GuardrailRulesRegistry {

    private final GuardrailRulesSource source;

    private volatile List<GuardrailRule> injectionRules;
    private volatile List<GuardrailRule> outputRules;

    private final List<GuardrailRulesListener> listeners = new CopyOnWriteArrayList<>();

    /** 装配构造器：源由 {@code rag.guardrail.rules.source} 条件装配择一注入。 */
    @Autowired
    public GuardrailRulesRegistry(GuardrailRulesSource source) {
        this.source = source;
        this.injectionRules = source.loadInjectionRules();
        this.outputRules = source.loadOutputRules();
        log.info("护栏词表注册表装载（源 {}）: 注入侧 {} 条 / 输出侧 {} 条（热重载就绪）",
            source.getClass().getSimpleName(), injectionRules.size(), outputRules.size());
    }

    /**
     * 测试构造器（无 Spring 上下文直装）：包装 file 源，
     * 既有单测（Registry / AdminService / ReloadCoordinator）构造签名零破坏。
     */
    public GuardrailRulesRegistry(String injectionLocation, String injectionCsv,
                                  String outputLocation, String outputCsv) {
        this(new FileGuardrailRulesSource(injectionLocation, injectionCsv, outputLocation, outputCsv));
    }

    /** 注入侧当前快照（不可变 List，volatile 读即最新） */
    public List<GuardrailRule> currentInjectionRules() {
        return injectionRules;
    }

    /** 输出侧当前快照（不可变 List，volatile 读即最新） */
    public List<GuardrailRule> currentOutputRules() {
        return outputRules;
    }

    /** 注入侧词表源位置（重载协调器 mtime 轮询判定 file: 源用；db 源返回空串） */
    public String injectionLocation() {
        return source.injectionLocation();
    }

    /** 输出侧词表源位置（重载协调器 mtime 轮询判定 file: 源用；db 源返回空串） */
    public String outputLocation() {
        return source.outputLocation();
    }

    /** 词表源标识（file | db）——运营端点 ReloadResult 回显用 */
    public String sourceName() {
        return source.sourceName();
    }

    public void subscribe(GuardrailRulesListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(GuardrailRulesListener listener) {
        listeners.remove(listener);
    }

    /**
     * 热重载：双侧全量重载成功后原子替换 + 推送监听器；任一失败保旧快照（fail-keep）。
     * synchronized 防 pub/sub 信号与 mtime 轮询并发双触发重复装载。
     *
     * @return 替换成功 true；装载失败保旧 false
     */
    public synchronized boolean reload() {
        List<GuardrailRule> freshInjection;
        List<GuardrailRule> freshOutput;
        try {
            freshInjection = source.loadInjectionRules();
            freshOutput = source.loadOutputRules();
        } catch (RuntimeException e) {
            log.warn("护栏词表热重载失败，保持旧快照（注入侧 {} 条 / 输出侧 {} 条）: {}",
                injectionRules.size(), outputRules.size(), e.getMessage());
            return false;
        }
        this.injectionRules = freshInjection;
        this.outputRules = freshOutput;
        log.info("护栏词表热重载成功: 注入侧 {} 条 / 输出侧 {} 条", freshInjection.size(), freshOutput.size());
        for (GuardrailRulesListener listener : listeners) {
            try {
                listener.onInjectionRulesUpdated(freshInjection);
                listener.onOutputRulesUpdated(freshOutput);
            } catch (RuntimeException e) {
                log.warn("护栏词表热重载监听器回调异常（继续通知后续监听器）: {}", e.getMessage());
            }
        }
        return true;
    }
}
