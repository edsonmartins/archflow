package br.com.archflow.agent.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Implementação in-memory da {@link AgentInvocationQueue}.
 *
 * <p>Usa uma {@link LinkedBlockingQueue} com capacidade configurável.
 * Rejeita requisições que excedam a profundidade máxima de recursão.
 */
public class InMemoryAgentInvocationQueue implements AgentInvocationQueue {
    private static final Logger logger = Logger.getLogger(InMemoryAgentInvocationQueue.class.getName());
    private static final int DEFAULT_MAX_RECURSION_DEPTH = 10;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    private final LinkedBlockingQueue<InvocationRequest> queue;
    private final int maxRecursionDepth;

    public InMemoryAgentInvocationQueue() {
        this(DEFAULT_MAX_RECURSION_DEPTH, DEFAULT_QUEUE_CAPACITY);
    }

    public InMemoryAgentInvocationQueue(int maxRecursionDepth, int queueCapacity) {
        this.maxRecursionDepth = maxRecursionDepth;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    @Override
    public void submit(InvocationRequest request) {
        if (request.recursionDepth() > maxRecursionDepth) {
            throw new MaxRecursionDepthException(request.recursionDepth(), maxRecursionDepth);
        }

        boolean added = queue.offer(request);
        if (!added) {
            throw new RuntimeException("Invocation queue is full (capacity=" + queue.remainingCapacity() + ")");
        }

        logger.info(() -> String.format(
                "Invocation queued: tenant=%s, agent=%s, depth=%d, requestId=%s",
                request.tenantId(), request.agentId(), request.recursionDepth(), request.requestId()));
    }

    @Override
    public Optional<InvocationRequest> poll() {
        return Optional.ofNullable(queue.poll());
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    @Override
    public List<InvocationRequest> pending() {
        return new ArrayList<>(queue);
    }
}
