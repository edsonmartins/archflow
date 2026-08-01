package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class PlainTextDocumentExtractor implements DocumentTextExtractor {
    @Override
    public boolean supports(StoredFile file) {
        String type = normalized(file.contentType());
        String name = normalized(file.originalName());
        return type.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".markdown");
    }

    @Override
    public String extract(StoredFile file, byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("Text document is not valid UTF-8", failure);
        }
    }

    static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
