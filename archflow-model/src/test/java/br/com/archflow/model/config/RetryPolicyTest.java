package br.com.archflow.model.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetryPolicy")
class RetryPolicyTest {

    @Test
    @DisplayName("default constructor preserves all fields")
    void fields() {
        RetryPolicy policy = new RetryPolicy(3, 1000, 2.0, Set.of(IOException.class));
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.delay()).isEqualTo(1000);
        assertThat(policy.multiplier()).isEqualTo(2.0);
        assertThat(policy.retryableExceptions()).containsExactly(IOException.class);
    }

    @Test
    @DisplayName("empty retryableExceptions is valid")
    void emptyExceptions() {
        RetryPolicy policy = new RetryPolicy(1, 100, 1.0, Set.of());
        assertThat(policy.retryableExceptions()).isEmpty();
    }

    @Test
    @DisplayName("equals and hashCode are structural")
    void equalsAndHashCode() {
        RetryPolicy a = new RetryPolicy(3, 1000, 2.0, Set.of());
        RetryPolicy b = new RetryPolicy(3, 1000, 2.0, Set.of());
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("different fields produce inequality")
    void notEqual() {
        RetryPolicy a = new RetryPolicy(3, 1000, 2.0, Set.of());
        RetryPolicy b = new RetryPolicy(5, 1000, 2.0, Set.of());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("null retryableExceptions is handled by the record")
    void nullExceptions() {
        RetryPolicy policy = new RetryPolicy(1, 100, 1.0, null);
        assertThat(policy.retryableExceptions()).isNull();
    }
}
