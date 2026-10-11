package com.enterprise.kb.api.config;

import com.enterprise.kb.commons.constant.Constants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 护栏链装配契约守卫（12.11 v2.110）——<b>L2 的前提是 L1 同链，L2 不能孤立存在</b>。
 *
 * <p><b>为什么需要这条断言</b>：L2（{@code SemanticInjectionAdvisor}，order 320）的触发判定把
 * 「命中 KEYWORD 干词」当作「L1 已观察」处理并据此**压制触发**，同时假定「BLOCK 档命中不可达」——
 * 两条推论都以 L1（{@code InputSanitizeAdvisor}，order 300）在同一条链上先执行为前提。若 L2 孤立
 * 装配，一个 BLOCK 档词项命中会：① 无人拒答（没有 L1）；② 压制 L2 触发（命中干词）⇒
 * <b>BLOCK 档载荷静默通过</b>（既不拒答也不二判，且无任何计数痕迹）。该拓扑此前只写在注释里、
 * 无测试守护，故本测试把「注释里的假设」升格为「装配契约」。
 *
 * <p><b>两条判据</b>：
 * <ol>
 *   <li><b>同链</b>：每处 {@code defaultAdvisors(...)} 的**组装切片**（方法体内自其所在花括号块起
 *       至该调用处，再拼上该调用自身的实参）若含 {@code semanticInjectionAdvisor}，必须同时含
 *       {@code inputSanitizeAdvisor} 且声明在前。签名刻意排除在切片外——「形参收了 L1 却没挂进链」
 *       正是要抓的缺陷；内联实参与「先建 {@code List<Advisor>} 再传入」两种链形态均覆盖；
 *       不追溯经私有辅助方法间接拼链的形态，故另钉链数下限做覆盖告警；</li>
 *   <li><b>位序</b>：{@code ChainOrder.INPUT_SANITIZE < ChainOrder.SEMANTIC_INJECTION}，且两个
 *       Advisor 的 {@code getOrder()} 必须委派给该常量（单一事实源，防手写字面量漂移）。</li>
 * </ol>
 *
 * <p>扫描面 = 各模块 {@code src/main/java} 的链定义（源码级，形态同 kb-etl 语料漂移守卫的相对路径）；
 * 模块目录缺位（单独签出）时整类跳过，不误报。
 */
class GuardrailChainAssemblyTest {

    /** 链定义所在模块的源码根（相对 kb-api 模块目录） */
    private static final List<Path> SOURCE_ROOTS = List.of(
        Path.of("..", "kb-ai-core", "src", "main", "java"),
        Path.of("..", "kb-ai-agent", "src", "main", "java"),
        Path.of("..", "kb-api", "src", "main", "java"),
        Path.of("..", "kb-eval", "src", "main", "java"));

    private static final String L1_PARAM = "inputSanitizeAdvisor";
    private static final String L2_PARAM = "semanticInjectionAdvisor";

    /** 当前 L2 链数（ragAgent / toolAgent / orchestrator / evalGuardrailL2）——数量下降说明扫描面失效 */
    private static final int EXPECTED_L2_CHAINS = 4;

    /** 链组装切片：label = 所属方法名，composition = 链成员可见文本 */
    private record ChainSlice(String label, String composition) {}

    @Test
    void everyChainWithL2_alsoCarriesL1() throws IOException {
        assumeTrue(SOURCE_ROOTS.stream().allMatch(Files::isDirectory), "模块源码目录不在相对位置，跳过装配契约守卫");

        List<String> chainsWithL2 = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            for (Path file : javaFiles(root)) {
                for (ChainSlice slice : chainSlices(Files.readString(file, StandardCharsets.UTF_8))) {
                    if (!slice.composition().contains(L2_PARAM)) {
                        continue;
                    }
                    chainsWithL2.add(file.getFileName() + "#" + slice.label());
                    int l1 = slice.composition().indexOf(L1_PARAM);
                    int l2 = slice.composition().indexOf(L2_PARAM);
                    if (l1 < 0) {
                        violations.add(file.getFileName() + "#" + slice.label()
                            + "：带 L2 但链内无 L1（BLOCK 档命中将静默通过）");
                    } else if (l1 > l2) {
                        violations.add(file.getFileName() + "#" + slice.label()
                            + "：L1 挂在 L2 之后（应先 L1 后 L2）");
                    }
                }
            }
        }

        assertThat(chainsWithL2)
            .as("带 L2 的链数不得低于 %d——数量下降说明链定义改经间接拼装或搬离扫描面，守卫已失效，请同步本测试",
                EXPECTED_L2_CHAINS)
            .hasSizeGreaterThanOrEqualTo(EXPECTED_L2_CHAINS);
        assertThat(violations)
            .as("护栏链装配契约破坏：L2(SemanticInjectionAdvisor) 的前提是同链存在 L1(InputSanitizeAdvisor)"
                + "——否则 BLOCK 档命中会因「命中干词 ⇒ 压制 L2 触发」而静默通过（12.11 v2.110）")
            .isEmpty();
    }

    @Test
    void l1OrderConstant_precedesL2_andIsTheSingleSource() throws IOException {
        assertThat(Constants.ChainOrder.INPUT_SANITIZE)
            .as("L1 必须排在 L2 之前——L2 的「BLOCK 命中不可达」与「干词压制」两条推论均依赖此位序")
            .isLessThan(Constants.ChainOrder.SEMANTIC_INJECTION);

        Path advisorDir = Path.of("..", "kb-ai-core", "src", "main", "java", "com", "enterprise", "kb", "ai", "advisor");
        assumeTrue(Files.isDirectory(advisorDir), "advisor 源码目录不在相对位置，跳过常量委派核验");
        assertThat(Files.readString(advisorDir.resolve("InputSanitizeAdvisor.java"), StandardCharsets.UTF_8))
            .as("L1 的 getOrder() 必须委派 ChainOrder 常量（防手写字面量与常量漂移）")
            .contains("Constants.ChainOrder.INPUT_SANITIZE");
        assertThat(Files.readString(advisorDir.resolve("SemanticInjectionAdvisor.java"), StandardCharsets.UTF_8))
            .as("L2 的 getOrder() 必须委派 ChainOrder 常量（防手写字面量与常量漂移）")
            .contains("Constants.ChainOrder.SEMANTIC_INJECTION");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /** 提取全部 {@code defaultAdvisors(...)} 的组装切片（见类注判据 1） */
    private static List<ChainSlice> chainSlices(String source) {
        List<ChainSlice> slices = new ArrayList<>();
        Deque<Integer> bodyStarts = new ArrayDeque<>();
        Deque<String> labels = new ArrayDeque<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int call = source.indexOf("defaultAdvisors(", cursor);
            int open = source.indexOf('{', cursor);
            int close = source.indexOf('}', cursor);
            int next = minPositive(call, open, close);
            if (next < 0) {
                break;
            }
            if (next == open) {
                bodyStarts.push(open + 1);
                labels.push(methodLabelBefore(source, open));
            } else if (next == close) {
                if (!bodyStarts.isEmpty()) {
                    bodyStarts.pop();
                    labels.pop();
                }
            } else if (!bodyStarts.isEmpty()) {
                String composition = source.substring(bodyStarts.peek(), call)
                    + argumentText(source, call + "defaultAdvisors".length());
                slices.add(new ChainSlice(labels.peek(), composition));
            }
            cursor = next + 1;
        }
        return slices;
    }

    /** 括号配平取实参文本（覆盖嵌套泛型与多行实参） */
    private static String argumentText(String source, int openParen) {
        int depth = 0;
        for (int i = openParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(openParen + 1, i);
                }
            }
        }
        return source.substring(0, 0);
    }

    /** 花括号所属方法名：向左配平找到签名左括号，再取其前面的标识符（跨行签名同样适用） */
    private static String methodLabelBefore(String source, int braceIndex) {
        int depth = 0;
        for (int i = braceIndex - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    int end = i;
                    int start = end;
                    while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
                        start--;
                    }
                    return start == end ? "?" : source.substring(start, end);
                }
            }
        }
        return "?";
    }

    private static int minPositive(int... values) {
        int best = -1;
        for (int value : values) {
            if (value >= 0 && (best < 0 || value < best)) {
                best = value;
            }
        }
        return best;
    }
}
