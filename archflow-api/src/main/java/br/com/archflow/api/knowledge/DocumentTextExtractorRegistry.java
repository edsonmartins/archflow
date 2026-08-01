package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;

import java.util.List;

public class DocumentTextExtractorRegistry {
    private final List<DocumentTextExtractor> extractors;
    private final int maxCharacters;

    public DocumentTextExtractorRegistry(List<DocumentTextExtractor> extractors,
                                         int maxCharacters) {
        if (extractors == null || extractors.isEmpty()) {
            throw new IllegalArgumentException("At least one document extractor is required");
        }
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        this.extractors = List.copyOf(extractors);
        this.maxCharacters = maxCharacters;
    }

    public String extract(StoredFile file, byte[] content) {
        DocumentTextExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(file))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported document type: " + file.contentType()));
        String text = extractor.extract(file, content);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Document contains no text");
        }
        if (text.length() > maxCharacters) {
            throw new IllegalArgumentException(
                    "Extracted document exceeds " + maxCharacters + " characters");
        }
        return text;
    }
}
