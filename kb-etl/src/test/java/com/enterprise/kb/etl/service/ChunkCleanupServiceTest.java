package com.enterprise.kb.etl.service;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * ChunkCleanupService 单测（簇⑥ C1）：三库级联共享组件——
 * PG 事实源必须项 + 向量/ES 尽力而为；软删管道三存储面联动。
 */
class ChunkCleanupServiceTest {

    private KbChunkRepository chunkRepository;
    private VectorStore vectorStore;
    private EsIndexWriter esIndexWriter;
    private ChunkCleanupService service;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KbChunkRepository.class);
        vectorStore = mock(VectorStore.class);
        esIndexWriter = mock(EsIndexWriter.class);
        service = new ChunkCleanupService(chunkRepository, vectorStore, esIndexWriter);
    }

    @Test
    void physicalDeleteByDocIdSweepsEsOrphans() {
        service.physicalDelete("doc-1", List.of("c-1", "c-2"), true);
        verify(chunkRepository).deleteAllById(List.of("c-1", "c-2"));
        verify(vectorStore).delete(List.of("c-1", "c-2"));
        verify(esIndexWriter).deleteByDocId("doc-1");       // 文档级扫尾形态
        verify(esIndexWriter, never()).deleteByChunkIds(anyList());
    }

    @Test
    void physicalDeleteByChunkIdsPreciseForBlueGreenDiff() {
        service.physicalDelete("doc-1", List.of("c-stale"), false);
        verify(esIndexWriter).deleteByChunkIds(List.of("c-stale"));  // 精确删，不误伤存活 chunk
        verify(esIndexWriter, never()).deleteByDocId(anyString());
    }

    @Test
    void physicalDeleteEmptyChunksStillSweepsEsWhenByDocId() {
        service.physicalDelete("doc-1", List.of(), true);
        verify(chunkRepository, never()).deleteAllById(anyList());
        verify(esIndexWriter).deleteByDocId("doc-1");
    }

    @Test
    void vectorFailureDoesNotBlockEsCleanup() {
        doThrow(new RuntimeException("Milvus 不可达")).when(vectorStore).delete(anyList());
        assertThatCode(() -> service.physicalDelete("doc-1", List.of("c-1"), false))
            .doesNotThrowAnyException();
        verify(esIndexWriter).deleteByChunkIds(List.of("c-1"));  // 向量失败不阻断 ES
    }

    @Test
    void softDeleteSetsFlagAndSyncsAllStores() {
        KbChunk chunk = new KbChunk();
        chunk.setId("c-1");
        chunk.setDocId("doc-1");
        when(chunkRepository.findById("c-1")).thenReturn(Optional.of(chunk));

        KbChunk result = service.softDelete("c-1");

        assertThat(result).isNotNull();
        assertThat(result.getIsDeleted()).isTrue();
        verify(chunkRepository).save(chunk);
        verify(esIndexWriter).markDeleted("c-1");
        verify(vectorStore).delete(List.of("c-1"));   // 向量库无软删形态，物理删
    }

    @Test
    void softDeleteMissingChunkReturnsNullWithoutSideEffects() {
        when(chunkRepository.findById("c-gone")).thenReturn(Optional.empty());
        assertThat(service.softDelete("c-gone")).isNull();
        verifyNoInteractions(esIndexWriter, vectorStore);
    }

    @Test
    void softDeleteVectorFailureDoesNotPropagate() {
        KbChunk chunk = new KbChunk();
        chunk.setId("c-1");
        when(chunkRepository.findById("c-1")).thenReturn(Optional.of(chunk));
        doThrow(new RuntimeException("向量库故障")).when(vectorStore).delete(anyList());
        assertThatCode(() -> service.softDelete("c-1")).doesNotThrowAnyException();
        assertThat(chunk.getIsDeleted()).isTrue();   // PG 事实源已落
    }
}
