package br.com.archflow.observability.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditRepository.AuditQuery")
class AuditQueryTest {

    @Nested
    @DisplayName("builder() defaults")
    class Defaults {

        @Test
        @DisplayName("should have limit=100 by default")
        void shouldHaveDefaultLimit() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.getLimit()).isEqualTo(100);
        }

        @Test
        @DisplayName("should have offset=0 by default")
        void shouldHaveDefaultOffset() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.getOffset()).isEqualTo(0);
        }

        @Test
        @DisplayName("should have sortBy=\"timestamp\" by default")
        void shouldHaveDefaultSortBy() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.getSortBy()).isEqualTo("timestamp");
        }

        @Test
        @DisplayName("should have sortDescending=true by default")
        void shouldHaveSortDescendingTrueByDefault() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.isSortDescending()).isTrue();
        }

        @Test
        @DisplayName("should have success=null by default")
        void shouldHaveSuccessNullByDefault() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.getSuccess()).isNull();
        }

        @Test
        @DisplayName("should have null optional fields by default")
        void shouldHaveNullOptionalFieldsByDefault() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            assertThat(query.getUserId()).isNull();
            assertThat(query.getAction()).isNull();
            assertThat(query.getResourceType()).isNull();
            assertThat(query.getResourceId()).isNull();
            assertThat(query.getStartTime()).isNull();
            assertThat(query.getEndTime()).isNull();
            assertThat(query.getIpAddress()).isNull();
        }
    }

    @Nested
    @DisplayName("Setters are chainable")
    class Chainability {

        @Test
        @DisplayName("userId() setter returns same AuditQuery instance")
        void userIdSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.userId("user-1");

            assertThat(returned).isSameAs(query);
            assertThat(returned.getUserId()).isEqualTo("user-1");
        }

        @Test
        @DisplayName("action() setter returns same AuditQuery instance")
        void actionSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.action(AuditAction.CREATE);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getAction()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("resourceType() setter returns same AuditQuery instance")
        void resourceTypeSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.resourceType("workflow");

            assertThat(returned).isSameAs(query);
            assertThat(returned.getResourceType()).isEqualTo("workflow");
        }

        @Test
        @DisplayName("resourceId() setter returns same AuditQuery instance")
        void resourceIdSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.resourceId("wf-1");

            assertThat(returned).isSameAs(query);
            assertThat(returned.getResourceId()).isEqualTo("wf-1");
        }

        @Test
        @DisplayName("success() setter returns same AuditQuery instance")
        void successSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.success(false);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getSuccess()).isFalse();
        }

        @Test
        @DisplayName("startTime() setter returns same AuditQuery instance")
        void startTimeSetterIsChainable() {
            Instant now = Instant.now();
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.startTime(now);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getStartTime()).isEqualTo(now);
        }

        @Test
        @DisplayName("endTime() setter returns same AuditQuery instance")
        void endTimeSetterIsChainable() {
            Instant now = Instant.now();
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.endTime(now);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getEndTime()).isEqualTo(now);
        }

        @Test
        @DisplayName("ipAddress() setter returns same AuditQuery instance")
        void ipAddressSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.ipAddress("192.168.0.1");

            assertThat(returned).isSameAs(query);
            assertThat(returned.getIpAddress()).isEqualTo("192.168.0.1");
        }

        @Test
        @DisplayName("limit() setter returns same AuditQuery instance")
        void limitSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.limit(50);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getLimit()).isEqualTo(50);
        }

        @Test
        @DisplayName("offset() setter returns same AuditQuery instance")
        void offsetSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.offset(10);

            assertThat(returned).isSameAs(query);
            assertThat(returned.getOffset()).isEqualTo(10);
        }

        @Test
        @DisplayName("sortBy() setter returns same AuditQuery instance")
        void sortBySetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.sortBy("userId");

            assertThat(returned).isSameAs(query);
            assertThat(returned.getSortBy()).isEqualTo("userId");
        }

        @Test
        @DisplayName("sortDescending() setter returns same AuditQuery instance")
        void sortDescendingSetterIsChainable() {
            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder();

            AuditRepository.AuditQuery returned = query.sortDescending(false);

            assertThat(returned).isSameAs(query);
            assertThat(returned.isSortDescending()).isFalse();
        }
    }

    @Nested
    @DisplayName("Compound query with all fields set")
    class CompoundQuery {

        @Test
        @DisplayName("should hold all configured values correctly")
        void shouldHoldAllConfiguredValues() {
            Instant start = Instant.parse("2025-01-01T00:00:00Z");
            Instant end = Instant.parse("2025-12-31T23:59:59Z");

            AuditRepository.AuditQuery query = AuditRepository.AuditQuery.builder()
                    .userId("user-42")
                    .action(AuditAction.WORKFLOW_EXECUTE)
                    .resourceType("workflow")
                    .resourceId("wf-99")
                    .success(true)
                    .startTime(start)
                    .endTime(end)
                    .ipAddress("10.10.10.10")
                    .limit(25)
                    .offset(5)
                    .sortBy("action")
                    .sortDescending(false);

            assertThat(query.getUserId()).isEqualTo("user-42");
            assertThat(query.getAction()).isEqualTo(AuditAction.WORKFLOW_EXECUTE);
            assertThat(query.getResourceType()).isEqualTo("workflow");
            assertThat(query.getResourceId()).isEqualTo("wf-99");
            assertThat(query.getSuccess()).isTrue();
            assertThat(query.getStartTime()).isEqualTo(start);
            assertThat(query.getEndTime()).isEqualTo(end);
            assertThat(query.getIpAddress()).isEqualTo("10.10.10.10");
            assertThat(query.getLimit()).isEqualTo(25);
            assertThat(query.getOffset()).isEqualTo(5);
            assertThat(query.getSortBy()).isEqualTo("action");
            assertThat(query.isSortDescending()).isFalse();
        }
    }
}
