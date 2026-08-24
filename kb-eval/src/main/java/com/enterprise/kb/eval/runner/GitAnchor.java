package com.enterprise.kb.eval.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * 运行锚点（簇② 5.9 批3，16 章 §16.5）：git 提交哈希 + 提交时间 + 工作区脏标记 + 运行时刻。
 *
 * <p>A/B 双跑差异报表的可比性前提——报告读数必须能回溯到产出它的代码形态：
 * Prompt Git Ops（4.8）下 prompt 版本即 git 版本，锚点是两跑之间「单变量」
 * 假设的核验依据。工作区脏（未提交改动）时哈希不能完全代表运行代码，显式标记。
 *
 * <p>非 git 工作区 / git 不可用时降级为 {@link #UNKNOWN}（resolved()=false）——
 * 锚点缺失只削弱可追溯性，不阻断评估。
 */
public record GitAnchor(String commit, String commitShort, String commitTime, boolean dirty, String runAt) {

    /** 解析失败占位（非 git 工作区 / git 不可用） */
    public static final String UNKNOWN = "UNKNOWN";

    public static GitAnchor resolve() {
        return resolve(Path.of("."));
    }

    public static GitAnchor resolve(Path workDir) {
        String runAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String commit = exec(workDir, "git", "rev-parse", "HEAD");
        if (commit == null) {
            return new GitAnchor(UNKNOWN, UNKNOWN, UNKNOWN, false, runAt);
        }
        String commitTime = exec(workDir, "git", "log", "-1", "--format=%cI");
        String porcelain = exec(workDir, "git", "status", "--porcelain");
        boolean dirty = porcelain != null && !porcelain.isBlank();
        String shortHash = commit.length() > 10 ? commit.substring(0, 10) : commit;
        return new GitAnchor(commit, shortHash, commitTime == null ? UNKNOWN : commitTime, dirty, runAt);
    }

    /** git 是否解析成功（失败 = 非 git 工作区或 git 不可用） */
    public boolean resolved() {
        return !UNKNOWN.equals(commit);
    }

    private static String exec(Path workDir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }
}
