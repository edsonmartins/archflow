package br.com.archflow.engine.lifecycle;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide registry of extra {@link FlowLifecycleListener}s that
 * deployments want attached to every flow engine they create.
 *
 * <p>Exists because listeners often come from Spring beans while the
 * {@code FlowEngine} itself is created by code that isn't Spring-aware
 * (e.g. {@code ArchFlowAgent}). Integrations register here at startup
 * and the engine pulls the list into a
 * {@link CompositeFlowLifecycleListener} at construction time.</p>
 *
 * <p>Backed by a {@link CopyOnWriteArrayList} so registration/unregistration
 * is safe to do concurrently with engine instantiation.</p>
 */
public final class FlowLifecycleListeners {

    private FlowLifecycleListeners() {}

    private static final CopyOnWriteArrayList<FlowLifecycleListener> REGISTRY = new CopyOnWriteArrayList<>();

    public static void register(FlowLifecycleListener listener) {
        if (listener == null || listener == FlowLifecycleListener.NO_OP) return;
        REGISTRY.addIfAbsent(listener);
    }

    public static void unregister(FlowLifecycleListener listener) {
        REGISTRY.remove(listener);
    }

    /** Snapshot of currently-registered listeners; never returns {@code null}. */
    public static java.util.List<FlowLifecycleListener> snapshot() {
        return new java.util.ArrayList<>(REGISTRY);
    }
}
