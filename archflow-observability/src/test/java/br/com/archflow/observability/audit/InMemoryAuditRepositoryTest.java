package br.com.archflow.observability.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryAuditRepository")
class InMemoryAuditRepositoryTest {

    private InMemoryAuditRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditRepository();
    }

    private AuditEvent createEvent(AuditAction action, String userId) {
        return AuditEvent.builder()
                .action(action)
                .userId(userId)
                .build();
    }

    private AuditEvent createEvent(AuditAction action, String userId, String resourceType, String resourceId) {
        return AuditEvent.builder()
                .action(action)
                .userId(userId)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .build();
    }

    private AuditEvent createEventWithTimestamp(AuditAction action, String userId, Instant timestamp) {
        return AuditEvent.builder()
                .action(action)
                .userId(userId)
                .timestamp(timestamp)
                .build();
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("should save and retrieve event")
        void shouldSaveAndRetrieveEvent() {
            // Arrange
            AuditEvent event = createEvent(AuditAction.CREATE, "user-1");

            // Act
            repository.save(event);

            // Assert
            assertThat(repository.size()).isEqualTo(1);
            assertThat(repository.findById(event.getId())).isPresent();
        }

        @Test
        @DisplayName("should throw when event is null")
        void shouldThrowWhenEventIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> repository.save(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("event cannot be null");
        }

        @Test
        @DisplayName("should save multiple events")
        void shouldSaveMultipleEvents() {
            // Arrange
            AuditEvent event1 = createEvent(AuditAction.CREATE, "user-1");
            AuditEvent event2 = createEvent(AuditAction.UPDATE, "user-2");
            AuditEvent event3 = createEvent(AuditAction.DELETE, "user-3");

            // Act
            repository.save(event1);
            repository.save(event2);
            repository.save(event3);

            // Assert
            assertThat(repository.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("saveAll()")
    class SaveAll {

        @Test
        @DisplayName("should save all events in batch")
        void shouldSaveAllEventsInBatch() {
            // Arrange
            List<AuditEvent> events = List.of(
                    createEvent(AuditAction.CREATE, "user-1"),
                    createEvent(AuditAction.UPDATE, "user-2"),
                    createEvent(AuditAction.DELETE, "user-3")
            );

            // Act
            repository.saveAll(events);

            // Assert
            assertThat(repository.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("should throw when events list is null")
        void shouldThrowWhenEventsListIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> repository.saveAll(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("events cannot be null");
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("should find existing event by id")
        void shouldFindExistingEventById() {
            // Arrange
            AuditEvent event = createEvent(AuditAction.CREATE, "user-1");
            repository.save(event);

            // Act
            Optional<AuditEvent> result = repository.findById(event.getId());

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(event.getId());
            assertThat(result.get().getAction()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("should return empty for non-existing id")
        void shouldReturnEmptyForNonExistingId() {
            // Act
            Optional<AuditEvent> result = repository.findById("non-existing-id");

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByUserId()")
    class FindByUserId {

        @Test
        @DisplayName("should find events by user id")
        void shouldFindEventsByUserId() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-1"));
            repository.save(createEvent(AuditAction.DELETE, "user-2"));

            // Act
            List<AuditEvent> result = repository.findByUserId("user-1", 10);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).allMatch(e -> "user-1".equals(e.getUserId()));
        }

        @Test
        @DisplayName("should respect limit parameter")
        void shouldRespectLimitParameter() {
            // Arrange
            for (int i = 0; i < 10; i++) {
                repository.save(createEvent(AuditAction.CREATE, "user-1"));
            }

            // Act
            List<AuditEvent> result = repository.findByUserId("user-1", 3);

            // Assert
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("should return empty list when no events match")
        void shouldReturnEmptyListWhenNoEventsMatch() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));

            // Act
            List<AuditEvent> result = repository.findByUserId("user-999", 10);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByAction()")
    class FindByAction {

        @Test
        @DisplayName("should find events by action")
        void shouldFindEventsByAction() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.CREATE, "user-2"));
            repository.save(createEvent(AuditAction.DELETE, "user-3"));

            // Act
            List<AuditEvent> result = repository.findByAction(AuditAction.CREATE, 10);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).allMatch(e -> AuditAction.CREATE.equals(e.getAction()));
        }

        @Test
        @DisplayName("should respect limit parameter")
        void shouldRespectLimitParameter() {
            // Arrange
            for (int i = 0; i < 10; i++) {
                repository.save(createEvent(AuditAction.CREATE, "user-" + i));
            }

            // Act
            List<AuditEvent> result = repository.findByAction(AuditAction.CREATE, 5);

            // Assert
            assertThat(result).hasSize(5);
        }
    }

    @Nested
    @DisplayName("findByResource()")
    class FindByResource {

        @Test
        @DisplayName("should find events by resource type and id")
        void shouldFindEventsByResourceTypeAndId() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1", "workflow", "wf-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2", "workflow", "wf-1"));
            repository.save(createEvent(AuditAction.CREATE, "user-1", "workflow", "wf-2"));
            repository.save(createEvent(AuditAction.CREATE, "user-1", "agent", "agent-1"));

            // Act
            List<AuditEvent> result = repository.findByResource("workflow", "wf-1", 10);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).allMatch(e ->
                    "workflow".equals(e.getResourceType()) && "wf-1".equals(e.getResourceId()));
        }
    }

    @Nested
    @DisplayName("findByTimeRange()")
    class FindByTimeRange {

        @Test
        @DisplayName("should find events within time range")
        void shouldFindEventsWithinTimeRange() {
            // Arrange
            Instant now = Instant.now();
            Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
            Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
            Instant threeHoursAgo = now.minus(3, ChronoUnit.HOURS);

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", threeHoursAgo));
            repository.save(createEventWithTimestamp(AuditAction.UPDATE, "user-1", twoHoursAgo));
            repository.save(createEventWithTimestamp(AuditAction.DELETE, "user-1", oneHourAgo));
            repository.save(createEventWithTimestamp(AuditAction.READ, "user-1", now));

            // Act - range includes twoHoursAgo (inclusive) to now (exclusive)
            List<AuditEvent> result = repository.findByTimeRange(twoHoursAgo, now, 10);

            // Assert
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list when no events in range")
        void shouldReturnEmptyListWhenNoEventsInRange() {
            // Arrange
            Instant now = Instant.now();
            Instant yesterday = now.minus(1, ChronoUnit.DAYS);
            Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", now));

            // Act
            List<AuditEvent> result = repository.findByTimeRange(twoDaysAgo, yesterday, 10);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("query()")
    class Query {

        @Test
        @DisplayName("should filter by user id")
        void shouldFilterByUserId() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .userId("user-1");

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo("user-1");
        }

        @Test
        @DisplayName("should filter by action")
        void shouldFilterByAction() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.DELETE, "user-2"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .action(AuditAction.CREATE);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAction()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("should filter by resource type")
        void shouldFilterByResourceType() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1", "workflow", "wf-1"));
            repository.save(createEvent(AuditAction.CREATE, "user-1", "agent", "agent-1"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .resourceType("workflow");

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getResourceType()).isEqualTo("workflow");
        }

        @Test
        @DisplayName("should filter by success status")
        void shouldFilterBySuccessStatus() {
            // Arrange
            repository.save(AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .userId("user-1")
                    .success(true)
                    .build());
            repository.save(AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .userId("user-2")
                    .success(false)
                    .build());

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .success(false);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).isSuccess()).isFalse();
        }

        @Test
        @DisplayName("should filter by time range")
        void shouldFilterByTimeRange() {
            // Arrange
            Instant now = Instant.now();
            Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
            Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", twoHoursAgo));
            repository.save(createEventWithTimestamp(AuditAction.UPDATE, "user-1", oneHourAgo));
            repository.save(createEventWithTimestamp(AuditAction.DELETE, "user-1", now));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .startTime(oneHourAgo)
                    .endTime(now);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should filter by IP address")
        void shouldFilterByIpAddress() {
            // Arrange
            repository.save(AuditEvent.builder()
                    .action(AuditAction.LOGIN_SUCCESS)
                    .userId("user-1")
                    .ipAddress("192.168.1.1")
                    .build());
            repository.save(AuditEvent.builder()
                    .action(AuditAction.LOGIN_SUCCESS)
                    .userId("user-2")
                    .ipAddress("10.0.0.1")
                    .build());

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .ipAddress("192.168.1.1");

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("should combine multiple filters")
        void shouldCombineMultipleFilters() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1", "workflow", "wf-1"));
            repository.save(createEvent(AuditAction.CREATE, "user-2", "workflow", "wf-2"));
            repository.save(createEvent(AuditAction.DELETE, "user-1", "workflow", "wf-1"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .userId("user-1")
                    .action(AuditAction.CREATE);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo("user-1");
            assertThat(result.get(0).getAction()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("should respect limit and offset")
        void shouldRespectLimitAndOffset() {
            // Arrange
            Instant base = Instant.parse("2025-01-01T00:00:00Z");
            for (int i = 0; i < 10; i++) {
                repository.save(createEventWithTimestamp(
                        AuditAction.CREATE, "user-1", base.plusSeconds(i)));
            }

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .limit(3)
                    .offset(2)
                    .sortDescending(false);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("should sort by timestamp descending by default")
        void shouldSortByTimestampDescendingByDefault() {
            // Arrange
            Instant first = Instant.parse("2025-01-01T00:00:00Z");
            Instant second = Instant.parse("2025-01-02T00:00:00Z");
            Instant third = Instant.parse("2025-01-03T00:00:00Z");

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", second));
            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", first));
            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", third));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getTimestamp()).isEqualTo(third);
            assertThat(result.get(1).getTimestamp()).isEqualTo(second);
            assertThat(result.get(2).getTimestamp()).isEqualTo(first);
        }

        @Test
        @DisplayName("should sort by timestamp ascending when configured")
        void shouldSortByTimestampAscending() {
            // Arrange
            Instant first = Instant.parse("2025-01-01T00:00:00Z");
            Instant second = Instant.parse("2025-01-02T00:00:00Z");
            Instant third = Instant.parse("2025-01-03T00:00:00Z");

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", second));
            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", first));
            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", third));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .sortDescending(false);

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getTimestamp()).isEqualTo(first);
            assertThat(result.get(1).getTimestamp()).isEqualTo(second);
            assertThat(result.get(2).getTimestamp()).isEqualTo(third);
        }

        @Test
        @DisplayName("should return all events when no filters set")
        void shouldReturnAllEventsWhenNoFiltersSet() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2"));
            repository.save(createEvent(AuditAction.DELETE, "user-3"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            // Act
            List<AuditEvent> result = repository.query(query);

            // Assert
            assertThat(result).hasSize(3);
        }
    }

    @Nested
    @DisplayName("count()")
    class Count {

        @Test
        @DisplayName("should count events matching query")
        void shouldCountEventsMatchingQuery() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.CREATE, "user-2"));
            repository.save(createEvent(AuditAction.DELETE, "user-3"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .action(AuditAction.CREATE);

            // Act
            long count = repository.count(query);

            // Assert
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("should return zero when no events match")
        void shouldReturnZeroWhenNoEventsMatch() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .action(AuditAction.DELETE);

            // Act
            long count = repository.count(query);

            // Assert
            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("should count all events with empty query")
        void shouldCountAllEventsWithEmptyQuery() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2"));

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            // Act
            long count = repository.count(query);

            // Assert
            assertThat(count).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("deleteOlderThan()")
    class DeleteOlderThan {

        @Test
        @DisplayName("should delete events older than specified time")
        void shouldDeleteEventsOlderThanSpecifiedTime() {
            // Arrange
            Instant now = Instant.now();
            Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
            Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", twoHoursAgo));
            repository.save(createEventWithTimestamp(AuditAction.UPDATE, "user-1", oneHourAgo));
            repository.save(createEventWithTimestamp(AuditAction.DELETE, "user-1", now));

            // Act
            long deleted = repository.deleteOlderThan(oneHourAgo);

            // Assert
            assertThat(deleted).isEqualTo(1);
            assertThat(repository.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return zero when no events to delete")
        void shouldReturnZeroWhenNoEventsToDelete() {
            // Arrange
            Instant now = Instant.now();
            repository.save(createEventWithTimestamp(AuditAction.CREATE, "user-1", now));

            // Act
            long deleted = repository.deleteOlderThan(now.minus(1, ChronoUnit.DAYS));

            // Assert
            assertThat(deleted).isEqualTo(0);
            assertThat(repository.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("should also remove from id index")
        void shouldAlsoRemoveFromIdIndex() {
            // Arrange
            Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
            AuditEvent event = createEventWithTimestamp(AuditAction.CREATE, "user-1", old);
            repository.save(event);

            // Act
            repository.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));

            // Assert
            assertThat(repository.findById(event.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Max events limit")
    class MaxEventsLimit {

        @Test
        @DisplayName("should enforce max events limit")
        void shouldEnforceMaxEventsLimit() {
            // Arrange
            InMemoryAuditRepository limitedRepo = new InMemoryAuditRepository(3);

            // Act
            for (int i = 0; i < 5; i++) {
                limitedRepo.save(AuditEvent.builder()
                        .id("event-" + i)
                        .action(AuditAction.CREATE)
                        .userId("user-1")
                        .build());
            }

            // Assert
            assertThat(limitedRepo.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("should remove oldest events when limit exceeded")
        void shouldRemoveOldestEventsWhenLimitExceeded() {
            // Arrange
            InMemoryAuditRepository limitedRepo = new InMemoryAuditRepository(2);

            // Act
            limitedRepo.save(AuditEvent.builder()
                    .id("first").action(AuditAction.CREATE).build());
            limitedRepo.save(AuditEvent.builder()
                    .id("second").action(AuditAction.UPDATE).build());
            limitedRepo.save(AuditEvent.builder()
                    .id("third").action(AuditAction.DELETE).build());

            // Assert
            assertThat(limitedRepo.findById("first")).isEmpty();
            assertThat(limitedRepo.findById("second")).isPresent();
            assertThat(limitedRepo.findById("third")).isPresent();
        }
    }

    @Nested
    @DisplayName("clear()")
    class Clear {

        @Test
        @DisplayName("should remove all events")
        void shouldRemoveAllEvents() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2"));

            // Act
            repository.clear();

            // Assert
            assertThat(repository.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("size()")
    class Size {

        @Test
        @DisplayName("should return zero for empty repository")
        void shouldReturnZeroForEmptyRepository() {
            assertThat(repository.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("should return correct count after saves")
        void shouldReturnCorrectCountAfterSaves() {
            // Arrange
            repository.save(createEvent(AuditAction.CREATE, "user-1"));
            repository.save(createEvent(AuditAction.UPDATE, "user-2"));

            // Assert
            assertThat(repository.size()).isEqualTo(2);
        }
    }
}
