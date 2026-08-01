package br.com.archflow.api.knowledge;

public interface KnowledgeEmbeddingModel {
    float[] embed(String text);
    default java.util.List<float[]> embedAll(java.util.List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
    int dimension();
}
