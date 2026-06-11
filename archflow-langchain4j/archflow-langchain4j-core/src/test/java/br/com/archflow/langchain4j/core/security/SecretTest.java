package br.com.archflow.langchain4j.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Secret + ConfigSecrets")
class SecretTest {

    @Test
    @DisplayName("toString nunca expõe o valor")
    void toStringRedacts() {
        Secret secret = Secret.of("sk-supersecret-123");
        assertThat(secret.toString()).isEqualTo("Secret[***]");
        assertThat(secret.toString()).doesNotContain("supersecret");
    }

    @Test
    @DisplayName("reveal devolve o valor real")
    void revealReturnsValue() {
        assertThat(Secret.of("sk-abc").reveal()).isEqualTo("sk-abc");
    }

    @Test
    @DisplayName("fromConfig lê a chave; null se ausente ou em branco")
    void fromConfig() {
        Map<String, Object> config = Map.of("api.key", "sk-xyz");
        assertThat(Secret.fromConfig(config, "api.key").reveal()).isEqualTo("sk-xyz");
        assertThat(Secret.fromConfig(config, "missing")).isNull();
        assertThat(Secret.fromConfig(Map.of("api.key", "  "), "api.key")).isNull();
    }

    @Test
    @DisplayName("equals em tempo constante; hashCode não depende do valor")
    void equalsAndHashCode() {
        assertThat(Secret.of("same")).isEqualTo(Secret.of("same"));
        assertThat(Secret.of("a")).isNotEqualTo(Secret.of("b"));
        // hashCode constante — não vaza o valor em estruturas/dumps
        assertThat(Secret.of("a").hashCode()).isEqualTo(Secret.of("b").hashCode());
    }

    @Test
    @DisplayName("destroy zera o valor; reveal depois falha")
    void destroy() {
        Secret secret = Secret.of("sk-abc");
        secret.destroy();
        assertThatThrownBy(secret::reveal).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("redactForLogging mascara chaves sensíveis e Secret, mantém o resto")
    void redactForLogging() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("api.key", "sk-supersecret");
        config.put("password", "hunter2");
        config.put("accessToken", "tok-123");
        config.put("model", "gpt-4o-mini");
        config.put("temperature", 0.7);
        config.put("wrapped", Secret.of("revealed"));

        Map<String, Object> redacted = ConfigSecrets.redactForLogging(config);

        assertThat(redacted.get("api.key")).isEqualTo("***");
        assertThat(redacted.get("password")).isEqualTo("***");
        assertThat(redacted.get("accessToken")).isEqualTo("***");
        assertThat(redacted.get("wrapped")).isEqualTo("***");
        assertThat(redacted.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(redacted.get("temperature")).isEqualTo(0.7);
        // original intacto
        assertThat(config.get("api.key")).isEqualTo("sk-supersecret");
        // o toString do mapa redatado não vaza segredo
        assertThat(redacted.toString()).doesNotContain("supersecret").doesNotContain("hunter2");
    }

    @Test
    @DisplayName("isSensitive reconhece marcadores comuns")
    void isSensitive() {
        assertThat(ConfigSecrets.isSensitive("api.key")).isTrue();
        assertThat(ConfigSecrets.isSensitive("PASSWORD")).isTrue();
        assertThat(ConfigSecrets.isSensitive("accessToken")).isTrue();
        assertThat(ConfigSecrets.isSensitive("model")).isFalse();
        assertThat(ConfigSecrets.isSensitive(null)).isFalse();
    }
}
