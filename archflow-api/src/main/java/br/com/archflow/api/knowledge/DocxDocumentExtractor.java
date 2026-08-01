package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;

public class DocxDocumentExtractor implements DocumentTextExtractor {
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public boolean supports(StoredFile file) {
        return DOCX.equalsIgnoreCase(file.contentType())
                || PlainTextDocumentExtractor.normalized(file.originalName()).endsWith(".docx");
    }

    @Override
    public String extract(StoredFile file, byte[] content) {
        try (var input = new ByteArrayInputStream(content);
             var document = new XWPFDocument(input);
             var extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Could not extract text from DOCX", failure);
        }
    }
}
