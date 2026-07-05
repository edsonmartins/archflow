package br.com.archflow.brainsentry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

    @Test
    @DisplayName("stays closed under the failure threshold")
    void staysClosedUnderThreshold() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, Duration.ofSeconds(30));

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("opens after consecutive failures and rejects locally")
    void opensAfterThreshold() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, Duration.ofMinutes(5));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.getRejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("success resets the consecutive failure count")
    void successResetsCount() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, Duration.ofMinutes(5));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("after the open window, one probe is allowed (half-open)")
    void halfOpenAllowsOneProbe() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("test", 1, Duration.ofMillis(50));

        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();

        Thread.sleep(80);

        assertThat(breaker.allowRequest()).isTrue();   // probe
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.allowRequest()).isFalse();  // only one probe in flight
    }

    @Test
    @DisplayName("successful probe closes the circuit; failed probe reopens it")
    void probeOutcomeDecides() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("test", 1, Duration.ofMillis(50));

        breaker.recordFailure();
        Thread.sleep(80);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordSuccess();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        breaker.recordFailure();
        Thread.sleep(80);
        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
