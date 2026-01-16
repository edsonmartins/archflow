package br.com.archflow.observability.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of AuditRepository for testing and development.
 *
 * <p><b>Warning:</b> This implementation stores events in memory only.
 * Events will be lost on application restart. Use a persistent implementation
 * for production.</p>
 *
 * <p>Thread-safe for concurrent access.</p>
 */
public class InMemoryAuditRepository implements AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditRepository.class);

    private final Map<String, AuditEvent> eventsById = new ConcurrentHashMap<>();
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final int maxEvents;

    /**
     * Creates a new in-memory repository with unlimited capacity.
     */
    public InMemoryAuditRepository() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a new in-memory repository with a maximum number of events.
     *
     * @param maxEvents Maximum number of events to keep in memory
     */
    public InMemoryAuditRepository(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    @Override
    public void save(AuditEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }

        eventsById.put(event.getId(), event);
        events.add(event);

        // Enforce max limit
        while (events.size() > maxEvents) {
            AuditEvent removed = events.remove(0);
            eventsById.remove(removed.getId());
        }

        log.debug("Saved audit event: {}", event.getId());
    }

    @Override
    public void saveAll(List<AuditEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("events cannot be null");
        }
        for (AuditEvent event : events) {
            save(event);
        }
    }

    @Override
    public Optional<AuditEvent> findById(String id) {
        return Optional.ofNullable(eventsById.get(id));
    }

    @Override
    public List<AuditEvent> findByUserId(String userId, int limit) {
        return events.stream()
                .filter(e -> userId.equals(e.getUserId()))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> findByAction(AuditAction action, int limit) {
        return events.stream()
                .filter(e -> action.equals(e.getAction()))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> findByResource(String resourceType, String resourceId, int limit) {
        return events.stream()
                .filter(e -> resourceType.equals(e.getResourceType())
                        && resourceId.equals(e.getResourceId()))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> findByTimeRange(Instant start, Instant end, int limit) {
        return events.stream()
                .filter(e -> !e.getTimestamp().isBefore(start)
                        && e.getTimestamp().isBefore(end))
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditEvent> query(AuditQuery query) {
        return events.stream()
                .filter(e -> matchesQuery(e, query))
                .sorted((a, b) -> {
                    int cmp = 0;
                    switch (query.getSortBy()) {
                        case "timestamp":
                            cmp = a.getTimestamp().compareTo(b.getTimestamp());
                            break;
                        case "action":
                            cmp = a.getAction().compareTo(b.getAction());
                            break;
                        case "userId":
                            cmp = compareNulls(a.getUserId(), b.getUserId());
                            break;
                        default:
                            cmp = 0;
                    }
                    return query.isSortDescending() ? -cmp : cmp;
                })
                .skip(query.getOffset())
                .limit(query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE)
                .collect(Collectors.toList());
    }

    @Override
    public long count(AuditQuery query) {
        return events.stream()
                .filter(e -> matchesQuery(e, query))
                .count();
    }

    @Override
    public long deleteOlderThan(Instant before) {
        long beforeCount = events.size();
        events.removeIf(e -> e.getTimestamp().isBefore(before));
        eventsById.entrySet().removeIf(entry -> entry.getValue().getTimestamp().isBefore(before));
        return beforeCount - events.size();
    }

    /**
     * Gets the total number of events stored.
     */
    public int size() {
        return events.size();
    }

    /**
     * Clears all stored events.
     */
    public void clear() {
        events.clear();
        eventsById.clear();
    }

    private boolean matchesQuery(AuditEvent event, AuditQuery query) {
        if (query.getUserId() != null && !query.getUserId().equals(event.getUserId())) {
            return false;
        }
        if (query.getAction() != null && !query.getAction().equals(event.getAction())) {
            return false;
        }
        if (query.getResourceType() != null && !query.getResourceType().equals(event.getResourceType())) {
            return false;
        }
        if (query.getResourceId() != null && !query.getResourceId().equals(event.getResourceId())) {
            return false;
        }
        if (query.getSuccess() != null && !query.getSuccess().equals(event.isSuccess())) {
            return false;
        }
        if (query.getStartTime() != null && event.getTimestamp().isBefore(query.getStartTime())) {
            return false;
        }
        if (query.getEndTime() != null && !event.getTimestamp().isBefore(query.getEndTime())) {
            return false;
        }
        if (query.getIpAddress() != null && !query.getIpAddress().equals(event.getIpAddress())) {
            return false;
        }
        return true;
    }

    private int compareNulls(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
}
