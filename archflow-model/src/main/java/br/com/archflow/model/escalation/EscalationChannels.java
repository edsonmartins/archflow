package br.com.archflow.model.escalation;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide registry of the default {@link EscalationChannel}.
 *
 * <p>A plugin agent cannot depend on Spring directly and is loaded via
 * reflection at flow startup, so it cannot receive an
 * {@code EscalationChannel} through constructor injection. Instead the
 * deployment registers the desired channel here at startup via
 * {@link #setDefault(EscalationChannel)} (e.g. the Linktor integration
 * does this inside its Spring {@code @Configuration}), and agents call
 * {@link #getDefault()} whenever they decide to escalate.</p>
 *
 * <p>Implementation is an {@link AtomicReference} so
 * {@code setDefault} / {@code getDefault} are race-free and safe from
 * multiple startup paths (e.g. test harness + main Spring context).</p>
 */
public final class EscalationChannels {

    private EscalationChannels() {}

    private static final AtomicReference<EscalationChannel> DEFAULT = new AtomicReference<>();

    /** Replaces the current default channel. {@code null} clears it. */
    public static void setDefault(EscalationChannel channel) {
        DEFAULT.set(channel);
    }

    /** @return the current default channel, or {@code null} if none configured */
    public static EscalationChannel getDefault() {
        return DEFAULT.get();
    }

    /**
     * Convenience: tries {@link #getDefault()} and calls
     * {@link EscalationChannel#escalate}. Returns {@code true} if a
     * channel was configured and the call succeeded, {@code false} if
     * no channel is configured. Exceptions during the escalation
     * propagate unchanged so the caller sees real failures.
     */
    public static boolean tryEscalate(EscalationRequest request) {
        EscalationChannel ch = DEFAULT.get();
        if (ch == null) return false;
        ch.escalate(request);
        return true;
    }
}
