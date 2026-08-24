package com.enterprise.kb.ai.cache;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import java.nio.charset.StandardCharsets;

/**
 * 语义缓存文档编解码器（Phase 5 簇③ 5.6）。
 *
 * <p>Redis 8 内建搜索引擎的 VECTOR 字段要求 HASH 字段为原始字节
 * （FLOAT32 小端序），而条目的问句/回答/溯源载荷为 UTF-8 文本——
 * 单一文档混合二进制与文本字段。既有编解码器无一适用（实证核验，
 * 源码级）：{@code StringCodec} 编码器对入参执行 {@code toString()}，
 * byte[] 会写成对象散列字面量；{@code ByteArrayCodec} 强制转换
 * {@code (byte[]) in}，String 键/值会抛 ClassCastException。
 *
 * <p>本编解码器语义钉死：编码侧 byte[] 原样透传、其余（String 等）
 * UTF-8 编码；解码侧一律还原为 UTF-8 字符串（向量字节读回场景不存在——
 * 向量只在索引与查询时使用，命中回放只消费文本字段）。
 */
public class CacheDocCodec extends BaseCodec {

    public static final CacheDocCodec INSTANCE = new CacheDocCodec();

    private final Encoder encoder = in -> {
        byte[] raw = (in instanceof byte[] bytes)
                ? bytes
                : String.valueOf(in).getBytes(StandardCharsets.UTF_8);
        return Unpooled.wrappedBuffer(raw);
    };

    private final Decoder<Object> decoder = (ByteBuf buf, State state) -> {
        String value = buf.toString(StandardCharsets.UTF_8);
        buf.readerIndex(buf.readerIndex() + buf.readableBytes());
        return value;
    };

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }
}
