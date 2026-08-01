package br.com.archflow.api.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Deterministic character chunker that prefers paragraph/line boundaries. */
public class RecursiveTextChunker {
    public List<Slice> split(String text, int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Expected size > overlap >= 0");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<Slice> slices = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + size);
            int end = boundary(normalized, start, hardEnd);
            if (end <= start) end = hardEnd;
            String content = normalized.substring(start, end).trim();
            if (!content.isEmpty()) slices.add(new Slice(content, start, end));
            if (end == normalized.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return slices;
    }

    private int boundary(String text, int start, int hardEnd) {
        if (hardEnd == text.length()) return hardEnd;
        int minimum = start + ((hardEnd - start) / 2);
        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph >= minimum) return paragraph + 2;
        int line = text.lastIndexOf('\n', hardEnd);
        if (line >= minimum) return line + 1;
        int space = text.lastIndexOf(' ', hardEnd);
        return space >= minimum ? space + 1 : hardEnd;
    }

    public record Slice(String content, int startOffset, int endOffset) {}
}
