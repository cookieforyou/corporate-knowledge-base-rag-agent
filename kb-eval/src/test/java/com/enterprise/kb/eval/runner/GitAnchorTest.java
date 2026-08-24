package com.enterprise.kb.eval.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行锚点解析单测（簇② 5.9 批3）——git 工作区解析 + 非 git 降级
 */
class GitAnchorTest {

    @Test
    void resolveInGitRepoFindsCommitAndTimestamp() {
        // surefire 在 kb-eval 模块目录运行，位于项目 git 工作区内
        GitAnchor anchor = GitAnchor.resolve(Path.of("."));

        assertThat(anchor.resolved()).isTrue();
        assertThat(anchor.commit()).matches("[0-9a-f]{40}");
        assertThat(anchor.commitShort()).hasSize(10);
        assertThat(anchor.commitTime()).isNotBlank().doesNotContain(GitAnchor.UNKNOWN);
        assertThat(anchor.runAt()).isNotBlank();
    }

    @Test
    void resolveOutsideGitRepoFallsBackToUnknown(@TempDir Path tempDir) {
        GitAnchor anchor = GitAnchor.resolve(tempDir);

        assertThat(anchor.resolved()).isFalse();
        assertThat(anchor.commit()).isEqualTo(GitAnchor.UNKNOWN);
        assertThat(anchor.commitShort()).isEqualTo(GitAnchor.UNKNOWN);
        assertThat(anchor.runAt()).isNotBlank();
    }

    @Test
    void shortHashIsPrefixOfFullCommit() {
        GitAnchor anchor = GitAnchor.resolve(Path.of("."));

        assertThat(anchor.resolved()).isTrue();
        assertThat(anchor.commit()).startsWith(anchor.commitShort());
    }
}
