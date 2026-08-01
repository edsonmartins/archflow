package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {
    private final DocumentTextExtractorRegistry registry =
            new DocumentTextExtractorRegistry(List.of(
                    new PlainTextDocumentExtractor(),
                    new PdfDocumentExtractor(2),
                    new DocxDocumentExtractor()), 10_000);

    @Test
    void extractsUtf8TextAndMarkdown() {
        assertThat(registry.extract(file("guide.md", "text/markdown"),
                "ação semântica".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("ação semântica");
    }

    @Test
    void extractsPdfText() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument();
             var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Architecture handbook");
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }

        assertThat(registry.extract(file("handbook.pdf", "application/pdf"), bytes))
                .contains("Architecture handbook");
    }

    @Test
    void extractsDocxParagraphs() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Deployment procedure");
            document.createParagraph().createRun().setText("Rollback checklist");
            document.write(output);
            bytes = output.toByteArray();
        }

        assertThat(registry.extract(file("runbook.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                bytes))
                .contains("Deployment procedure", "Rollback checklist");
    }

    @Test
    void rejectsUnsupportedMalformedAndOversizedDocuments() {
        assertThatThrownBy(() -> registry.extract(
                file("image.png", "image/png"), new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");

        assertThatThrownBy(() -> registry.extract(
                file("broken.txt", "text/plain"), new byte[]{(byte) 0xC3, 0x28}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");

        var tinyRegistry = new DocumentTextExtractorRegistry(
                List.of(new PlainTextDocumentExtractor()), 3);
        assertThatThrownBy(() -> tinyRegistry.extract(
                file("large.txt", "text/plain"),
                "four".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 3");
    }

    private static StoredFile file(String name, String contentType) {
        return new StoredFile("file", "tenant", null, name, contentType,
                0, "sha", "key", Instant.now());
    }
}
