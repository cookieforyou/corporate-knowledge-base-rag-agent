package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.config.SmartRoutingConfig;
import com.enterprise.kb.eval.EvalApplication;
import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.config.JudgeModelConfig;
import com.enterprise.kb.eval.runner.AnnotationRunner;
import com.enterprise.kb.eval.runner.EvalRunner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 集成测试专属启动类（簇⑥ D3）——与生产 {@link EvalApplication} 同包扫描范围，
 * 但排除以下组件：
 *
 * <ul>
 *   <li>{@code EvalApplication}——防嵌套 @ComponentScan 重复装配</li>
 *   <li>{@code EvalRunner / AnnotationRunner}——避免 ApplicationReady 触发全量评估</li>
 *   <li>{@code JudgeModelConfig}——judge api-key 空时 Bean 创建阶段即抛异常</li>
 *   <li>{@code SmartRoutingConfig}——其主模型装配（v2.77 双形态：glm/deepseek）
 *       校验真实 api-key；排除后由 {@link com.enterprise.kb.eval.it.config.ItModelConfig}
 *       以桩重建 smartRoutingChatModel（保留真实路由包装器，仅替换底层模型）</li>
 * </ul>
 *
 * <p>模型自动装配经属性门让位：{@code spring.ai.model.chat=none}（生产既有）+
 * {@code spring.ai.model.embedding=none}（IT 注入），OpenAI embedding starter
 * 类级 @ConditionalOnProperty 门控让位（簇③ 实证机制）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = "com.enterprise.kb",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            EvalApplication.class,
            EvalRunner.class,
            AnnotationRunner.class,
            JudgeModelConfig.class,
            SmartRoutingConfig.class
        }))
@EntityScan("com.enterprise.kb.domain.model")
@EnableJpaRepositories("com.enterprise.kb.domain.repository")
@EnableConfigurationProperties(EvalProperties.class)
public class TestEvalApplication {
    // 空体：仅作为 @SpringBootTest(classes=...) 的启动元数据
}
