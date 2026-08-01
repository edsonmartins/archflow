package br.com.archflow.api.jobs;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable registry that rejects duplicate job type ownership. */
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(Collection<JobHandler> handlers) {
        var indexed = new LinkedHashMap<String, JobHandler>();
        for (JobHandler handler : handlers) {
            JobHandler duplicate = indexed.putIfAbsent(handler.type(), handler);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate job handler type: " + handler.type());
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public JobHandler get(String type) {
        return handlers.get(type);
    }

    public Set<String> types() {
        return handlers.keySet();
    }
}
