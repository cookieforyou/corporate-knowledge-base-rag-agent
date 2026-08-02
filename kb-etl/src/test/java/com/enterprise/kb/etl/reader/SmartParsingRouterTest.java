package com.enterprise.kb.etl.reader;

import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.infrastructure.parsing.ParsingProperties;
import com.enterprise.kb.infrastructure.parsing.ParsingResult;
import com.enterprise.kb.infrastructure.parsing.ParsingServiceClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SmartParsingRouter 单测（2.1）：路由决策 + 降级容错
 */
class SmartParsingRouterTest {

    private ParsingProperties props(boolean deepByDefault) {
        ParsingProperties p = new ParsingProperties();
        p.setDeepByDefault(deepByDefault);
        return p;
    }

    private ParsingServiceClient stub(String name, ParsingResult result) {
        return new ParsingServiceClient() {
            @Override
            public ParsingResult parse(byte[] content, String fileName) {
                return result;
            }

            @Override
            public String providerName() {
                return name;
            }
        };
    }

    private ParsingServiceClient failing(String name) {
        return new ParsingServiceClient() {
            @Override
            public ParsingResult parse(byte[] content, String fileName) {
                throw new ParsingException(name + " 不可用");
            }

            @Override
            public String providerName() {
                return name;
            }
        };
    }

    private byte[] emptyPdfBytes() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            pdf.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void nonPdf_routesToNative() {
        var router = new SmartParsingRouter(props(false), List.of());
        byte[] md = "# 标题\n\n纯文本 Markdown 内容。".getBytes(StandardCharsets.UTF_8);

        var outcome = router.read(md, "notes.md", null);

        assertThat(outcome.route()).isEqualTo(ParseRoute.NATIVE);
        assertThat(outcome.documents()).isNotEmpty();
    }

    @Test
    void blankPdf_densityBelowThreshold_routesToOcr() throws Exception {
        var router = new SmartParsingRouter(props(false),
            List.of(stub("qwen-ocr", new ParsingResult("OCR 识别文本", 0, 0, 1))));

        var outcome = router.read(emptyPdfBytes(), "scan.pdf", null);

        assertThat(outcome.route()).isEqualTo(ParseRoute.OCR);
        assertThat(outcome.documents().get(0).getText()).isEqualTo("OCR 识别文本");
    }

    @Test
    void deepByDefault_routesToDeep_withPageMetadata() throws Exception {
        var router = new SmartParsingRouter(props(true),
            List.of(stub("docmind", new ParsingResult("## 正文\n<table><tr><td>表格</td></tr></table>", 1, 0, 3))));

        // deepByDefault 时不触发密度探测分支（此处用非 PDF 规避探测；PDF+deep 见下）
        var outcome = new SmartParsingRouter(props(true),
            List.of(stub("docmind", new ParsingResult("正文", 0, 0, 2))))
            .read("text".getBytes(StandardCharsets.UTF_8), "x.txt", null);
        assertThat(outcome.route()).isEqualTo(ParseRoute.NATIVE);   // 非 PDF 始终 NATIVE

        var pdfOutcome = router.read(emptyPdfBytes(), "complex.pdf", null);
        assertThat(pdfOutcome.route()).isEqualTo(ParseRoute.DEEP);
        Document doc = pdfOutcome.documents().get(0);
        assertThat(doc.getMetadata().get("page_count")).isEqualTo(3);
        assertThat(doc.getMetadata().get("table_count")).isEqualTo(1);
    }

    @Test
    void forcedRoute_overridesAutoDecision() {
        var router = new SmartParsingRouter(props(false),
            List.of(stub("docmind", new ParsingResult("深度解析结果", 0, 0, 1))));
        byte[] md = "普通文本".getBytes(StandardCharsets.UTF_8);

        var outcome = router.read(md, "notes.md", ParseRoute.DEEP);

        assertThat(outcome.route()).isEqualTo(ParseRoute.DEEP);
        assertThat(outcome.documents().get(0).getText()).isEqualTo("深度解析结果");
    }

    @Test
    void autoRoute_deepFailure_fallsBackToNative() throws Exception {
        var router = new SmartParsingRouter(props(true), List.of(failing("docmind")));

        var outcome = router.read(emptyPdfBytes(), "complex.pdf", null);

        assertThat(outcome.route()).isEqualTo(ParseRoute.NATIVE);   // 回落 Tika
        assertThat(outcome.documents()).isNotEmpty();
    }

    @Test
    void forcedRoute_failure_propagates() {
        var router = new SmartParsingRouter(props(false), List.of(failing("docmind")));
        byte[] md = "普通文本".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> router.read(md, "notes.md", ParseRoute.DEEP))
            .isInstanceOf(ParsingServiceClient.ParsingException.class)
            .hasMessageContaining("docmind");
    }
}
