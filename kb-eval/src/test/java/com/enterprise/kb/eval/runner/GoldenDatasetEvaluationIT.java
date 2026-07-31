package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.EvalApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Golden Dataset 全量评估集成测试
 *
 * <p>默认 @Disabled：依赖 ECS 基础设施（向量库/PG/ES）+ DEEPSEEK_API_KEY + DASHSCOPE_API_KEY，
 * 不作为常驻 CI 单测运行。需要时移除 @Disabled，或直接用 CI 门禁入口：
 * {@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci}
 */
@Slf4j
@Disabled("依赖 ECS 基础设施与 API Keys；运行方式见 golden/README-标注指南.md")
@SpringBootTest(classes = EvalApplication.class)
class GoldenDatasetEvaluationIT {

    @Autowired
    private EvalRunner evalRunner;

    @Test
    void runFullEvaluationAndPrintReport() {
        EvalReport report = evalRunner.runFullEval();
        log.info("\n{}", report.summary());
    }
}
