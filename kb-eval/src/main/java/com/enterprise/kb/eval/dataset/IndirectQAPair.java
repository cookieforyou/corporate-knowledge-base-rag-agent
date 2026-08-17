package com.enterprise.kb.eval.dataset;

/**
 * 间接注入评估用例（安全簇④ D3，设计 §12.8 / 12.6 提案落地）
 *
 * <p>毒化语料三层内容分级（簇④分解纪律条 6）：
 * <ul>
 *   <li>{@code document}——毒化文档正文，<b>唯一载荷载体</b>：Base64 编码引用形态
 *       存储（{@code documentEncoding=base64} + {@code documentSha256} 指纹锚点），
 *       加载层解码供探针消费，AI 零接触字面（§7 纪律）；</li>
 *   <li>{@code question}——触发问句：<b>正常业务问句</b>（非载荷，与干净集同形态），
 *       明文落盘合规；</li>
 *   <li>{@code judgeCriteria}——忠实回答判据：期望行为的结构描述（非载荷），明文。</li>
 * </ul>
 *
 * <p>语料经 {@code tools/guardrail/import_poison_corpus.py} 带外编码注入
 * （inbox 正文文本 + 元数据 JSONL），形态沿 injection-qa.json 编码引用纪律。
 *
 * @param id            用例唯一标识（poison-NN）
 * @param fileName      毒化文档入库文件名（探针打标自洽校验的 file_name 匹配键）
 * @param question      触发问句（正常业务问句，明文）
 * @param judgeCriteria 忠实回答判据（期望行为结构描述，明文）
 * @param document      毒化正文：编码态（解码前）/ 明文（解码后，编码字段已卸）
 * @param documentEncoding 编码声明（"base64"；解码后产物不再携带）
 * @param documentSha256   正文指纹锚点（解码后校验，腐化 fail-fast）
 */
public record IndirectQAPair(
        String id,
        String fileName,
        String question,
        String judgeCriteria,
        String document,
        String documentEncoding,
        String documentSha256) {

    /** 引用形态判定：携带编码声明即编码态（加载层负责解码） */
    public boolean hasEncodedDocument() {
        return documentEncoding != null && !documentEncoding.isBlank();
    }
}
