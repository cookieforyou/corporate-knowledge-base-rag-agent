package com.enterprise.kb.eval.it.stub;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性 EmbeddingModel 桩（簇⑥ D3）——hashing trick 词袋向量（1024 维，L2 归一化）。
 *
 * <p><b>语义近似性</b>（区别于纯哈希桩）：向量由 token 分桶累加而成——共享 token
 * 的文本余弦相似度 &gt; 0，检索阈值过滤、TopK 排序、租户 FilterExpression 组合
 * 均有真实行为可测；无任何共享 token 的文本正交（相似度 0），空证据拒答路径可触发。
 *
 * <p><b>确定性</b>：同文本必同向量（String.hashCode 为规范算法跨 JVM 稳定）——
 * 蓝绿重入库同 ID 幂等覆写、跨用例可比性的前提。
 *
 * <p>分词：CJK 按单字、拉丁/数字按小写词——中文语料按字共享即足量（测试语料
 * 刻意高重叠构造，配合 IT 基线 similarity-threshold=0.1）。
 */
public class StubEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(embedText(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] embedText(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) && cp < 0x2E80) {
                word.appendCodePoint(Character.toLowerCase(cp));
            } else {
                flushWord(word, vector);
                if (cp >= 0x2E80 && !Character.isWhitespace(cp) && !isPunctuation(cp)) {
                    addToken(new String(Character.toChars(cp)), vector);
                }
            }
        }
        flushWord(word, vector);
        normalize(vector);
        return vector;
    }

    private static void flushWord(StringBuilder word, float[] vector) {
        if (word.length() > 0) {
            addToken(word.toString(), vector);
            word.setLength(0);
        }
    }

    private static void addToken(String token, float[] vector) {
        int bucket = Math.floorMod(token.hashCode(), DIMENSIONS);
        vector[bucket] += 1.0f;
    }

    private static boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.OTHER_PUNCTUATION || type == Character.START_PUNCTUATION
            || type == Character.END_PUNCTUATION || type == Character.CONNECTOR_PUNCTUATION
            || type == Character.DASH_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
            || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static void normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
        }
    }
}
