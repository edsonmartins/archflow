package br.com.archflow.model.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Helpers de hashing compartilhados — fonte única para o padrão
 * SHA-256 → Base64 usado em chaves de cache, blacklist de tokens etc.
 * (antes duplicado em cada módulo).
 */
public final class Hashing {

    private Hashing() {
    }

    /** SHA-256 do valor, codificado em Base64 padrão. */
    public static String sha256Base64(String value) {
        return Base64.getEncoder().encodeToString(sha256(value));
    }

    /**
     * SHA-256 do valor em Base64 URL-safe sem padding, truncado a
     * {@code length} caracteres — útil para compor chaves opacas sem expor
     * o valor original.
     */
    public static String sha256Base64Url(String value, int length) {
        String full = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(value));
        return length < full.length() ? full.substring(0, length) : full;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
