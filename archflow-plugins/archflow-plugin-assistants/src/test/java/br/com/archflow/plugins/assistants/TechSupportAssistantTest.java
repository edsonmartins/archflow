package br.com.archflow.plugins.assistants;

import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TechSupportAssistant")
class TechSupportAssistantTest {

    private TechSupportAssistant assistant;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        assistant = new TechSupportAssistant();
        context = mock(ExecutionContext.class);
        assistant.initialize(Map.of());
    }

    @Nested
    @DisplayName("metadata")
    class MetadataTest {

        @Test
        @DisplayName("should return correct metadata")
        void shouldReturnMetadata() {
            ComponentMetadata meta = assistant.getMetadata();

            assertThat(meta.id()).isEqualTo("tech-support-assistant");
            assertThat(meta.type()).isEqualTo(ComponentType.ASSISTANT);
            assertThat(meta.version()).isEqualTo("1.0.0");
            assertThat(meta.capabilities()).contains("tech-support", "troubleshooting");
        }

        @Test
        @DisplayName("should return specialization")
        void shouldReturnSpecialization() {
            assertThat(assistant.getSpecialization()).isEqualTo("Technical Support");
        }
    }

    @Nested
    @DisplayName("analyzeRequest")
    class AnalyzeRequestTest {

        @Test
        @DisplayName("should detect password reset intent")
        void shouldDetectPasswordReset() {
            Analysis analysis = assistant.analyzeRequest("I forgot my password and can't login", context);

            assertThat(analysis.intent()).isEqualTo("password_reset");
            assertThat(analysis.confidence()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("should detect connectivity intent")
        void shouldDetectConnectivity() {
            Analysis analysis = assistant.analyzeRequest("My internet connection is not working, wifi is offline", context);

            assertThat(analysis.intent()).isEqualTo("connectivity");
            assertThat(analysis.confidence()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("should detect installation intent")
        void shouldDetectInstallation() {
            Analysis analysis = assistant.analyzeRequest("How do I install and configure the software?", context);

            assertThat(analysis.intent()).isEqualTo("installation");
            assertThat(analysis.confidence()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("should detect performance intent")
        void shouldDetectPerformance() {
            Analysis analysis = assistant.analyzeRequest("My computer is very slow and keeps freezing", context);

            assertThat(analysis.intent()).isEqualTo("performance");
            assertThat(analysis.confidence()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("should return unknown for unrecognized input")
        void shouldReturnUnknown() {
            Analysis analysis = assistant.analyzeRequest("What is the meaning of life?", context);

            assertThat(analysis.confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should handle null input")
        void shouldHandleNull() {
            Analysis analysis = assistant.analyzeRequest(null, context);

            assertThat(analysis.intent()).isEqualTo("unknown");
            assertThat(analysis.confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should handle blank input")
        void shouldHandleBlank() {
            Analysis analysis = assistant.analyzeRequest("   ", context);

            assertThat(analysis.intent()).isEqualTo("unknown");
            assertThat(analysis.confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should include entities in analysis")
        void shouldIncludeEntities() {
            Analysis analysis = assistant.analyzeRequest("reset my password", context);

            assertThat(analysis.entities()).containsKey("detected_intent");
            assertThat(analysis.entities()).containsKey("input_length");
        }
    }

    @Nested
    @DisplayName("generateResponse")
    class GenerateResponseTest {

        @Test
        @DisplayName("should generate response for known intent")
        void shouldGenerateForKnownIntent() {
            var analysis = new Analysis("password_reset", Map.of(), 0.6, List.of("provide_solution"));

            Response response = assistant.generateResponse(analysis, context);

            assertThat(response.content()).contains("reset").contains("password");
            assertThat(response.metadata()).containsEntry("intent", "password_reset");
        }

        @Test
        @DisplayName("should suggest escalation for low confidence")
        void shouldSuggestEscalation() {
            var analysis = new Analysis("unknown", Map.of(), 0.1, List.of("ask_details"));

            Response response = assistant.generateResponse(analysis, context);

            assertThat(response.actions()).anyMatch(a -> a.type().equals("escalate"));
        }

        @Test
        @DisplayName("should suggest followup when asking details")
        void shouldSuggestFollowup() {
            var analysis = new Analysis("password_reset", Map.of(), 0.4, List.of("ask_details"));

            Response response = assistant.generateResponse(analysis, context);

            assertThat(response.actions()).anyMatch(a -> a.type().equals("followup"));
        }

        @Test
        @DisplayName("should include metadata with source")
        void shouldIncludeSource() {
            var analysis = new Analysis("connectivity", Map.of(), 0.7, List.of());

            Response response = assistant.generateResponse(analysis, context);

            assertThat(response.metadata()).containsEntry("source", "tech-support-assistant");
        }
    }

    @Nested
    @DisplayName("execute via operation")
    class ExecuteTest {

        @Test
        @DisplayName("should execute analyze operation")
        void shouldExecuteAnalyze() throws Exception {
            var result = assistant.execute("analyze", "I can't login", context);

            assertThat(result).isInstanceOf(Analysis.class);
        }

        @Test
        @DisplayName("should reject unsupported operation")
        void shouldRejectUnsupported() {
            assertThatThrownBy(() -> assistant.execute("translate", "text", context))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should throw when not initialized")
        void shouldThrowNotInitialized() {
            var uninit = new TechSupportAssistant();

            assertThatThrownBy(() -> uninit.execute("analyze", "text", context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTest {

        @Test
        @DisplayName("should shutdown cleanly")
        void shouldShutdown() {
            assistant.shutdown();

            assertThatThrownBy(() -> assistant.execute("analyze", "text", context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
