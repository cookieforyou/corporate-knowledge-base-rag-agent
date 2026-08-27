package com.enterprise.kb.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.enterprise.kb.eval.config.EvalProperties;

/**
 * kb-eval 独立启动入口 —— Phase 2.16 最小评估基线
 *
 * <p>运行模式：
 * <ul>
 *   <li>CI 门禁：{@code EVAL_CI_ENABLED=true mvn spring-boot:run -pl kb-eval}
 *       —— 评估低于阈值则进程非零退出（EvalFailedException）</li>
 *   <li>标注辅助：{@code --eval.annotate-query="你的问题"} 输出候选 chunkId 供人工标注</li>
 * </ul>
 *
 * <p>非 Web 应用（web-application-type=none）：评估跑完即退出，
 * 成功 exit 0 / 门禁失败 exit 非 0，天然适配 CI。
 */
@SpringBootApplication(scanBasePackages = "com.enterprise.kb")
@EntityScan("com.enterprise.kb.domain.model")
@EnableJpaRepositories("com.enterprise.kb.domain.repository")
@EnableConfigurationProperties(EvalProperties.class)
public class EvalApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(EvalApplication.class, args);
        // 非 Web 应用跑完即退：基础设施客户端（Milvus gRPC / Redisson Netty 等）持有非守护线程，
        // 不显式退出则 JVM 永远挂起。SpringApplication.exit 先关闭上下文释放连接，
        // System.exit 兜底强退；门禁失败（EvalFailedException）在 run() 内抛出，进程已非零退出。
        System.exit(SpringApplication.exit(ctx));
    }
}
