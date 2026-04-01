package br.com.archflow.agent.pattern;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Reflection memory for the Reflexion pattern.
 *
 * <p>Stores verbal reflections about what went wrong in previous attempts,
 * used as additional context in subsequent retries. Maintains a bounded
 * window of the most recent reflections.
 */
public class ReflectionMemory {

    private final int maxReflections;
    private final Deque<Reflection> reflections;
    private final Object lock = new Object();
    private int totalAttempts = 0;

    public ReflectionMemory(int maxReflections) {
        this.maxReflections = maxReflections;
        this.reflections = new ConcurrentLinkedDeque<>();
    }

    public ReflectionMemory() {
        this(3);
    }

    public void addReflection(String attempt, String outcome, String reflection) {
        synchronized (lock) {
            if (reflections.size() >= maxReflections) {
                reflections.pollFirst();
            }
            reflections.addLast(new Reflection(
                    ++totalAttempts, attempt, outcome, reflection, Instant.now()));
        }
    }

    public List<Reflection> getReflections() {
        synchronized (lock) {
            return List.copyOf(reflections);
        }
    }

    public String formatAsContext() {
        synchronized (lock) {
            if (reflections.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("Previous reflections:\n");
            for (Reflection r : reflections) {
                sb.append(String.format("- Attempt %d: %s → %s\n  Reflection: %s\n",
                        r.attemptNumber(), r.attempt(), r.outcome(), r.reflection()));
            }
            return sb.toString();
        }
    }

    public int size() { return reflections.size(); }
    public void clear() { synchronized (lock) { reflections.clear(); totalAttempts = 0; } }
    public boolean isEmpty() { return reflections.isEmpty(); }

    public record Reflection(int attemptNumber, String attempt, String outcome, String reflection, Instant timestamp) {}
}
