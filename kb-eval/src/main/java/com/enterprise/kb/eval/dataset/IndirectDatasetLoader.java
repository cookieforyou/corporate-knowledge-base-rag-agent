package com.enterprise.kb.eval.dataset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 间接注入毒化语料加载器（安全簇④ D3，设计 §12.8）
 *
 * <p>语料落 {@code classpath:indirect/}独立目录——<b>不入 golden/</b>：
 * GoldenDatasetLoader 扫 golden/*.json 按 GoldenQAPair 解析，毒化语料的
 * question 是正常触发问句，混入会污染 Golden 主数据集（结构性隔离）。
 *
 * <p>编码引用形态沿 injection-qa.json 纪律（簇① T2）：document 字段 Base64
 * 编码态 + SHA-256 指纹锚点，本加载器统一解码为运行时明文（解码产物不携带
 * 编码字段），指纹校验腐化即 fail-fast；解码错误消息只含用例 id（§7 纪律：
 * 内容不外显）。文件缺失/空数组合法（语料经带外通道注入前的缺省形态）。
 */
@Slf4j
@Component
public class IndirectDatasetLoader {

    private static final String ENCODING_BASE64 = "base64";

    private final JsonMapper jsonMapper;
    private final String location;

    public IndirectDatasetLoader(
            JsonMapper jsonMapper,
            @Value("${eval.indirect.corpus-location:classpath:indirect/indirect-qa.json}") String location) {
        this.jsonMapper = jsonMapper;
        this.location = location;
    }

    /** 加载全部毒化语料（解码后明文态）；文件缺失返回空列表（缺省合法形态） */
    public List<IndirectQAPair> loadAll() {
        List<IndirectQAPair> all = new ArrayList<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(location);
        } catch (Exception e) {
            throw new IllegalStateException("间接注入语料位置解析失败: " + location, e);
        }
        for (Resource resource : resources) {
            if (!resource.exists()) {
                continue;
            }
            try (InputStream is = resource.getInputStream()) {
                List<IndirectQAPair> pairs =
                    jsonMapper.readValue(is, new TypeReference<List<IndirectQAPair>>() {});
                all.addAll(pairs.stream().map(this::decodeIfEncoded).toList());
                log.info("加载间接注入语料: {} → {} 条", resource.getFilename(), pairs.size());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("间接注入语料加载失败: " + location, e);
            }
        }
        return all;
    }

    /**
     * 引用形态解码：base64 document 还原明文 + SHA-256 锚点完整性校验；
     * 解码产物不再携带编码字段（下游恒见明文形态，内容仅供探针程序化消费）。
     */
    private IndirectQAPair decodeIfEncoded(IndirectQAPair pair) {
        if (!pair.hasEncodedDocument()) {
            return pair;
        }
        if (!ENCODING_BASE64.equalsIgnoreCase(pair.documentEncoding())) {
            throw new IllegalStateException(
                "间接注入用例 " + pair.id() + " 携带未知 documentEncoding（语料结构损坏）");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(pair.document()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "间接注入用例 " + pair.id() + " document Base64 解码失败（语料结构损坏）", e);
        }
        String anchor = pair.documentSha256();
        if (anchor != null && !anchor.isBlank() && !sha256(decoded).equals(anchor)) {
            throw new IllegalStateException(
                "间接注入用例 " + pair.id() + " document 指纹锚点校验失败（语料内容腐化）");
        }
        return new IndirectQAPair(pair.id(), pair.fileName(), pair.question(),
            pair.judgeCriteria(), decoded, null, null);
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
