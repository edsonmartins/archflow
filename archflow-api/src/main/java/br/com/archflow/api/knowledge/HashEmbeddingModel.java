package br.com.archflow.api.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Dependency-free feature hashing model for dev/test; replaceable by a provider bean. */
public class HashEmbeddingModel implements KnowledgeEmbeddingModel {
    private final int dimension;

    public HashEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    public float[] embed(String text) {
        float[] vector = new float[dimension];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) continue;
            byte[] hash;
            try {
                hash = MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            int slot = Math.floorMod(java.nio.ByteBuffer.wrap(hash).getInt(), dimension);
            vector[slot] += (hash[4] & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm > 0) {
            float divisor = (float) Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= divisor;
        }
        return vector;
    }

    public int dimension() {
        return dimension;
    }
}
