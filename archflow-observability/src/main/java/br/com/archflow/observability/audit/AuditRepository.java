package br.com.archflow.observability.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and retrieving audit events.
 *
 * <p>Implementations can store audit events in various backends:
 * <ul>
 *   <li>Relational database (JDBC/JPA)</li>
 *   <li>NoSQL database (MongoDB, Cassandra)</li>
 *   <li>Search engine (Elasticsearch)</li>
 *   <li>Log aggregation system (Splunk, ELK)</li>
 * </ul>
 *
 * <p>Default implementations are provided for JDBC and JPA.
 */
public interface AuditRepository {

    /**
     * Saves an audit event.
     *
     * @param event The audit event to save
     */
    void save(AuditEvent event);

    /**
     * Saves multiple audit events in batch.
     *
     * @param events The audit events to save
     */
    void saveAll(List<AuditEvent> events);

    /**
     * Finds an audit event by its ID.
     *
     * @param id The event ID
     * @return The audit event, if found
     */
    Optional<AuditEvent> findById(String id);

    /**
     * Finds audit events by user ID.
     *
     * @param userId The user ID
     * @param limit Maximum number of results
     * @return List of audit events
     */
    List<AuditEvent> findByUserId(String userId, int limit);

    /**
     * Finds audit events by action.
     *
     * @param action The audit action
     * @param limit Maximum number of results
     * @return List of audit events
     */
    List<AuditEvent> findByAction(AuditAction action, int limit);

    /**
     * Finds audit events by resource type and ID.
     *
     * @param resourceType The resource type
     * @param resourceId The resource ID
     * @param limit Maximum number of results
     * @return List of audit events
     */
    List<AuditEvent> findByResource(String resourceType, String resourceId, int limit);

    /**
     * Finds audit events within a time range.
     *
     * @param start The start time (inclusive)
     * @param end The end time (exclusive)
     * @param limit Maximum number of results
     * @return List of audit events
     */
    List<AuditEvent> findByTimeRange(Instant start, Instant end, int limit);

    /**
     * Finds audit events by multiple criteria.
     *
     * @param query The audit query
     * @return List of audit events
     */
    List<AuditEvent> query(AuditQuery query);

    /**
     * Counts audit events matching criteria.
     *
     * @param query The audit query
     * @return The count
     */
    long count(AuditQuery query);

    /**
     * Deletes audit events older than the specified timestamp.
     *
     * @param before Delete events before this time
     * @return The number of events deleted
     */
    long deleteOlderThan(Instant before);

    /**
     * Query builder for audit events.
     */
    class AuditQuery {
        private String userId;
        private AuditAction action;
        private String resourceType;
        private String resourceId;
        private Boolean success;
        private Instant startTime;
        private Instant endTime;
        private String ipAddress;
        private int limit = 100;
        private int offset = 0;
        private String sortBy = "timestamp";
        private boolean sortDescending = true;

        public static AuditQuery builder() {
            return new AuditQuery();
        }

        public AuditQuery userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AuditQuery action(AuditAction action) {
            this.action = action;
            return this;
        }

        public AuditQuery resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public AuditQuery resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public AuditQuery success(boolean success) {
            this.success = success;
            return this;
        }

        public AuditQuery startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public AuditQuery endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public AuditQuery ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public AuditQuery limit(int limit) {
            this.limit = limit;
            return this;
        }

        public AuditQuery offset(int offset) {
            this.offset = offset;
            return this;
        }

        public AuditQuery sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        public AuditQuery sortDescending(boolean sortDescending) {
            this.sortDescending = sortDescending;
            return this;
        }

        // Getters
        public String getUserId() { return userId; }
        public AuditAction getAction() { return action; }
        public String getResourceType() { return resourceType; }
        public String getResourceId() { return resourceId; }
        public Boolean getSuccess() { return success; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public String getIpAddress() { return ipAddress; }
        public int getLimit() { return limit; }
        public int getOffset() { return offset; }
        public String getSortBy() { return sortBy; }
        public boolean isSortDescending() { return sortDescending; }
    }
}
