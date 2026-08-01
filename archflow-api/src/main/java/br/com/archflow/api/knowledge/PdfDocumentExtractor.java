package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class PdfDocumentExtractor implements DocumentTextExtractor {
    private final int maxPages;

    public PdfDocumentExtractor(int maxPages) {
        if (maxPages <= 0) throw new IllegalArgumentException("maxPages must be positive");
        this.maxPages = maxPages;
    }

    @Override
    public boolean supports(StoredFile file) {
        return "application/pdf".equalsIgnoreCase(file.contentType())
                || PlainTextDocumentExtractor.normalized(file.originalName()).endsWith(".pdf");
    }

    @Override
    public String extract(StoredFile file, byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Encrypted PDF documents are not supported");
            }
            if (document.getNumberOfPages() > maxPages) {
                throw new IllegalArgumentException(
                        "PDF exceeds the limit of " + maxPages + " pages");
            }
            return new PDFTextStripper().getText(document);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Could not extract text from PDF", failure);
        }
    }
}
