package com.enterprise.kb.eval.dataset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Golden Dataset 加载器 —— 扫描 classpath:golden/*.json（.example 文件不匹配，天然排除）
 *
 * <p>数据集版本化管理在 Git 中（kb-eval/src/main/resources/golden/），
 * 标注工作流见同目录 README-标注指南.md。
 *
 * <p><b>双形态兼容（安全簇① T2）</b>：敏感样本（injection-qa.json）的 question
 * 以引用形态存储——{@code questionEncoding=base64} + {@code questionSha256} 指纹锚点；
 * 本加载器统一解码为运行时明文（其余消费方无感），解码后校验指纹，腐化即 fail-fast。
 * 明文样本（过渡期/非敏感语料）不带编码字段，原样通过。解码错误消息只含样本 id
 * （第七节敏感词交付纪律：内容不外显）。
 */
@Slf4j
@Component
public class GoldenDatasetLoader {

    private static final String ENCODING_BASE64 = "base64";

    private final JsonMapper jsonMapper;

    public GoldenDatasetLoader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<GoldenQAPair> loadAll() {
        List<GoldenQAPair> all = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:golden/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    List<GoldenQAPair> pairs = jsonMapper.readValue(is, new TypeReference<List<GoldenQAPair>>() {});
                    all.addAll(pairs.stream().map(this::decodeIfEncoded).toList());
                    log.info("加载 Golden Dataset: {} → {} 条", resource.getFilename(), pairs.size());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Golden Dataset 加载失败", e);
        }
        log.info("Golden Dataset 合计 {} 条", all.size());
        return all;
    }

    /**
     * 引用形态样本解码：base64 question 还原明文 + SHA-256 锚点完整性校验；
     * 明文样本原样返回。解码产物不再携带编码字段（下游恒见明文形态）。
     */
    private GoldenQAPair decodeIfEncoded(GoldenQAPair pair) {
        if (!pair.hasEncodedQuestion()) {
            return pair;
        }
        if (!ENCODING_BASE64.equalsIgnoreCase(pair.questionEncoding())) {
            throw new IllegalStateException(
                "Golden 样本 " + pair.id() + " 携带未知 questionEncoding（语料结构损坏）");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(pair.question()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "Golden 样本 " + pair.id() + " question Base64 解码失败（语料结构损坏）", e);
        }
        String anchor = pair.questionSha256();
        if (anchor != null && !anchor.isBlank() && !sha256(decoded).equals(anchor)) {
            throw new IllegalStateException(
                "Golden 样本 " + pair.id() + " question 指纹锚点校验失败（语料内容腐化）");
        }
        return new GoldenQAPair(pair.id(), pair.category(), decoded, pair.expectedKeywords(),
            pair.expectedAnswer(), pair.expectedChunkIds(), pair.expectedDocs(), pair.attackType(),
            null, null);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
