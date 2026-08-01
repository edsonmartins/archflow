package br.com.archflow.api.knowledge;

import br.com.archflow.api.storage.StoredFile;

/** Extracts plain text from one or more safe, explicitly supported document formats. */
public interface DocumentTextExtractor {
    boolean supports(StoredFile file);
    String extract(StoredFile file, byte[] content);
}
