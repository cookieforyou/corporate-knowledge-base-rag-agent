package com.enterprise.kb.domain.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 双源一致性守卫（Phase 4 簇⑥ 4.11，Flyway 迁移版本化）。
 *
 * <p>同源双写纪律兜底：{@code schema.sql}（kb-eval Testcontainers init
 * script 专用全量快照）与 Flyway 基线迁移 {@code db/migration/V1__baseline_schema.sql}
 * 的表集必须一致——后续 schema 变更若只改单源（漏同步），本测试红灯。
 * 表级比对（列级漂移由 kb-eval IT 的 ddl-auto=validate 兜底）。
 */
class SchemaDualSourceConsistencyTest {

    private static final Pattern CREATE_TABLE =
        Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)", Pattern.CASE_INSENSITIVE);

    @Test
    void tableSetsOfSchemaSqlAndFlywayBaselineMustMatch() throws IOException {
        Set<String> schemaTables = extractTables("/schema.sql");
        Set<String> baselineTables = extractTables("/db/migration/V1__baseline_schema.sql");

        assertThat(schemaTables)
            .as("schema.sql 表集（10 表全量快照）")
            .hasSize(10);
        assertThat(baselineTables)
            .as("Flyway V1 基线与 schema.sql 表集不一致——schema 变更须双源同步（先 V(N+1) 再快照）")
            .isEqualTo(schemaTables);
    }

    private Set<String> extractTables(String classpathLocation) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(classpathLocation)) {
            assertThat(in).as("资源存在: " + classpathLocation).isNotNull();
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> tables = new TreeSet<>();
            Matcher m = CREATE_TABLE.matcher(ddl);
            while (m.find()) {
                tables.add(m.group(1));
            }
            return tables;
        }
    }
}
