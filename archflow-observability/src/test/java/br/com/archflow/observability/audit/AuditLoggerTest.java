package br.com.archflow.observability.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuditLogger")
class AuditLoggerTest {

    private InMemoryAuditRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAuditRepository();
    }

    @AfterEach
    void resetSingleton() throws Exception {
        Field f = AuditLogger.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("should throw IllegalStateException when not initialized")
        void shouldThrowWhenNotInitialized() {
            assertThatThrownBy(AuditLogger::get)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AuditLogger not initialized");
        }

        @Test
        @DisplayName("should return instance after initialize()")
        void shouldReturnInstanceAfterInitialize() {
            AuditLogger logger = AuditLogger.initialize(repository);

            assertThat(AuditLogger.get()).isSameAs(logger);
        }
    }

    @Nested
    @DisplayName("isInitialized()")
    class IsInitialized {

        @Test
        @DisplayName("should return false initially")
        void shouldReturnFalseInitially() {
            assertThat(AuditLogger.isInitialized()).isFalse();
        }

        @Test
        @DisplayName("should return true after initialize()")
        void shouldReturnTrueAfterInitialize() {
            AuditLogger.initialize(repository);

            assertThat(AuditLogger.isInitialized()).isTrue();
        }
    }

    @Nested
    @DisplayName("initialize()")
    class Initialize {

        @Test
        @DisplayName("should return the same instance on repeated calls")
        void shouldReturnSameInstanceOnRepeatedCalls() {
            AuditLogger first = AuditLogger.initialize(repository);
            AuditLogger second = AuditLogger.initialize(new InMemoryAuditRepository());

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("should use provided repository")
        void shouldUseProvidedRepository() {
            AuditLogger logger = AuditLogger.initialize(repository);

            assertThat(logger.getRepository()).isSameAs(repository);
        }
    }

    @Nested
    @DisplayName("log(AuditEvent)")
    class LogEvent {

        @Test
        @DisplayName("should store event in repository (sync mode)")
        void shouldStoreEventInRepositorySyncMode() throws InterruptedException {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .userId("user-1")
                    .resourceType("workflow")
                    .resourceId("wf-1")
                    .build();

            logger.log(event);

            assertThat(repository.size()).isEqualTo(1);
            Optional<AuditEvent> found = repository.findById(event.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getAction()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("should store event in repository (async mode)")
        void shouldStoreEventInRepositoryAsyncMode() throws InterruptedException {
            AuditLogger logger = AuditLogger.initialize(repository, true);

            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.UPDATE)
                    .userId("user-2")
                    .build();

            logger.log(event);

            // Give virtual thread time to complete
            Thread.sleep(100);

            assertThat(repository.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("log(AuditAction)")
    class LogAction {

        @Test
        @DisplayName("should return AuditBuilder that stores event on submit")
        void shouldReturnBuilderThatStoresEventOnSubmit() throws InterruptedException {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.log(AuditAction.WORKFLOW_EXECUTE)
                    .userId("user-1")
                    .resourceType("workflow")
                    .resourceId("wf-42")
                    .submit();

            assertThat(repository.size()).isEqualTo(1);
            List<AuditEvent> events = repository.findByAction(AuditAction.WORKFLOW_EXECUTE, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getUserId()).isEqualTo("user-1");
            assertThat(events.get(0).getResourceType()).isEqualTo("workflow");
        }

        @Test
        @DisplayName("should build event without submitting when build() is used")
        void shouldBuildEventWithoutSubmitting() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            AuditEvent event = logger.log(AuditAction.READ)
                    .userId("user-1")
                    .build();

            assertThat(event.getAction()).isEqualTo(AuditAction.READ);
            assertThat(repository.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("logFailure()")
    class LogFailure {

        @Test
        @DisplayName("should create event with success=false and error message")
        void shouldCreateEventWithSuccessFalse() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logFailure(AuditAction.WORKFLOW_FAILED, "Timeout occurred")
                    .userId("user-1")
                    .resourceType("workflow")
                    .resourceId("wf-1")
                    .submit();

            assertThat(repository.size()).isEqualTo(1);
            List<AuditEvent> events = repository.findByAction(AuditAction.WORKFLOW_FAILED, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isSuccess()).isFalse();
            assertThat(events.get(0).getErrorMessage()).isEqualTo("Timeout occurred");
        }

        @Test
        @DisplayName("should return chainable builder with failure state")
        void shouldReturnChainableBuilderWithFailureState() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            AuditEvent event = logger.logFailure(AuditAction.LOGIN_FAILED, "Invalid credentials")
                    .userId("user-bad")
                    .ipAddress("10.0.0.1")
                    .build();

            assertThat(event.isSuccess()).isFalse();
            assertThat(event.getErrorMessage()).isEqualTo("Invalid credentials");
            assertThat(event.getUserId()).isEqualTo("user-bad");
            assertThat(event.getIpAddress()).isEqualTo("10.0.0.1");
        }
    }

    @Nested
    @DisplayName("logLogin()")
    class LogLogin {

        @Test
        @DisplayName("should log successful login with LOGIN_SUCCESS action")
        void shouldLogSuccessfulLogin() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logLogin("user-1", "alice", true, "192.168.0.1");

            List<AuditEvent> events = repository.findByAction(AuditAction.LOGIN_SUCCESS, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getUserId()).isEqualTo("user-1");
            assertThat(events.get(0).getUsername()).isEqualTo("alice");
            assertThat(events.get(0).getIpAddress()).isEqualTo("192.168.0.1");
        }

        @Test
        @DisplayName("should log failed login with LOGIN_FAILED action")
        void shouldLogFailedLogin() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logLogin("user-1", "alice", false, "10.0.0.99");

            List<AuditEvent> events = repository.findByAction(AuditAction.LOGIN_FAILED, 10);
            assertThat(events).hasSize(1);
        }
    }

    @Nested
    @DisplayName("logWorkflowStart() / logWorkflowComplete()")
    class LogWorkflow {

        @Test
        @DisplayName("should log workflow start with WORKFLOW_EXECUTE action")
        void shouldLogWorkflowStart() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logWorkflowStart("user-1", "wf-1", "exec-99");

            List<AuditEvent> events = repository.findByAction(AuditAction.WORKFLOW_EXECUTE, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getResourceId()).isEqualTo("wf-1");
            assertThat(events.get(0).getContext()).containsEntry("executionId", "exec-99");
        }

        @Test
        @DisplayName("should log workflow completion with WORKFLOW_COMPLETED action")
        void shouldLogWorkflowComplete() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logWorkflowComplete("user-1", "wf-1", "exec-100", true, null);

            List<AuditEvent> events = repository.findByAction(AuditAction.WORKFLOW_COMPLETED, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should log workflow failure with WORKFLOW_FAILED action and error message")
        void shouldLogWorkflowFailure() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            logger.logWorkflowComplete("user-1", "wf-1", "exec-101", false, "Agent error");

            List<AuditEvent> events = repository.findByAction(AuditAction.WORKFLOW_FAILED, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isSuccess()).isFalse();
            assertThat(events.get(0).getErrorMessage()).isEqualTo("Agent error");
        }
    }

    @Nested
    @DisplayName("Static convenience methods")
    class StaticConvenienceMethods {

        @Test
        @DisplayName("logEvent() is no-op when not initialized")
        void logEventIsNoOpWhenNotInitialized() {
            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .build();

            // Should not throw
            AuditLogger.logEvent(event);
        }

        @Test
        @DisplayName("logEvent() stores event when initialized")
        void logEventStoresEventWhenInitialized() {
            AuditLogger.initialize(repository, false);

            AuditEvent event = AuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .userId("user-static")
                    .build();

            AuditLogger.logEvent(event);

            assertThat(repository.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("logAction() returns no-op builder when not initialized")
        void logActionReturnsNoOpBuilderWhenNotInitialized() {
            AuditLogger.AuditBuilder builder = AuditLogger.logAction(AuditAction.UPDATE);

            // Should not throw; submit is a no-op
            assertThat(builder).isNotNull();
            builder.submit();
        }

        @Test
        @DisplayName("logAction() returns functional builder when initialized")
        void logActionReturnsFunctionalBuilderWhenInitialized() {
            AuditLogger.initialize(repository, false);

            AuditLogger.logAction(AuditAction.DELETE)
                    .userId("user-del")
                    .resourceType("workflow")
                    .resourceId("wf-del")
                    .submit();

            assertThat(repository.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AuditBuilder")
    class AuditBuilderTests {

        @Test
        @DisplayName("should support all fluent setter methods")
        void shouldSupportAllFluentSetterMethods() {
            AuditLogger logger = AuditLogger.initialize(repository, false);

            AuditEvent event = logger.log(AuditAction.AGENT_EXECUTE)
                    .userId("user-1")
                    .username("alice")
                    .resourceType("agent")
                    .resourceId("agent-1")
                    .success(true)
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent/1.0")
                    .sessionId("session-abc")
                    .traceId("trace-xyz")
                    .addContext("env", "test")
                    .build();

            assertThat(event.getUserId()).isEqualTo("user-1");
            assertThat(event.getUsername()).isEqualTo("alice");
            assertThat(event.getResourceType()).isEqualTo("agent");
            assertThat(event.getResourceId()).isEqualTo("agent-1");
            assertThat(event.isSuccess()).isTrue();
            assertThat(event.getIpAddress()).isEqualTo("127.0.0.1");
            assertThat(event.getUserAgent()).isEqualTo("TestAgent/1.0");
            assertThat(event.getSessionId()).isEqualTo("session-abc");
            assertThat(event.getTraceId()).isEqualTo("trace-xyz");
            assertThat(event.getContext()).containsEntry("env", "test");
        }
    }
}
