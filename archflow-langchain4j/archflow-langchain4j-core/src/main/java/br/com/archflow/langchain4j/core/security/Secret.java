package br.com.archflow.langchain4j.core.security;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Valor sensível (API key, senha, token) que não vaza em logs.
 *
 * <p>{@link #toString()} sempre redige (nunca expõe o valor), {@link #equals}
 * usa comparação em tempo constante, e o valor real só sai por
 * {@link #reveal()} — chamado no ponto de uso (ao construir o cliente do
 * provider), não armazenado em logs/strings.
 *
 * <p>O segredo é mantido como {@code char[]} e pode ser apagado da memória
 * com {@link #destroy()} quando o adapter é encerrado.
 */
public final class Secret {

    private final char[] value;
    private boolean destroyed;

    private Secret(char[] value) {
        this.value = value;
    }

    /** Cria um Secret a partir de uma String (copiando os caracteres). */
    public static Secret of(String value) {
        Objects.requireNonNull(value, "value");
        return new Secret(value.toCharArray());
    }

    /** Cria um Secret assumindo posse do array (não copia). */
    public static Secret ofChars(char[] value) {
        return new Secret(Objects.requireNonNull(value, "value").clone());
    }

    /**
     * Lê uma chave de um mapa de configuração como Secret; {@code null} se
     * ausente ou em branco.
     */
    public static Secret fromConfig(java.util.Map<String, Object> config, String key) {
        Object raw = config == null ? null : config.get(key);
        if (raw == null) {
            return null;
        }
        String s = raw.toString();
        return s.isBlank() ? null : Secret.of(s);
    }

    /** Retorna o valor real. Use somente no ponto de consumo. */
    public String reveal() {
        ensureNotDestroyed();
        return new String(value);
    }

    /** {@code true} se o segredo está vazio. */
    public boolean isBlank() {
        ensureNotDestroyed();
        return value.length == 0;
    }

    /** Zera o array em memória; chamadas a {@link #reveal()} depois falham. */
    public void destroy() {
        Arrays.fill(value, '\0');
        destroyed = true;
    }

    private void ensureNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("Secret has been destroyed");
        }
    }

    @Override
    public String toString() {
        return "Secret[***]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Secret other) || destroyed || other.destroyed) return false;
        // tempo constante: não vaza o tamanho do prefixo que casa
        return MessageDigest.isEqual(toBytes(value), toBytes(other.value));
    }

    @Override
    public int hashCode() {
        // Constante: o hashCode não deve depender do valor (evita exposição
        // indireta em coleções/dumps); a igualdade real fica no equals.
        return Secret.class.hashCode();
    }

    private static byte[] toBytes(char[] chars) {
        byte[] bytes = new byte[chars.length * 2];
        for (int i = 0; i < chars.length; i++) {
            bytes[i * 2] = (byte) (chars[i] >> 8);
            bytes[i * 2 + 1] = (byte) chars[i];
        }
        return bytes;
    }
}
