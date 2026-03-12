package br.com.archflow.template.builtin;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.WorkflowTemplate;
import br.com.archflow.template.WorkflowTemplate.ParameterDefinition;
import br.com.archflow.template.WorkflowTemplateDefinition;
import br.com.archflow.template.knowledge.KnowledgeBaseTemplate;
import br.com.archflow.template.processing.DocumentProcessingTemplate;
import br.com.archflow.template.supervisor.AgentSupervisorTemplate;
import br.com.archflow.template.support.CustomerSupportTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for all built-in workflow templates.
 */
@DisplayName("Built-in Workflow Templates")
class BuiltinTemplatesTest {

    @Nested
    @DisplayName("CustomerSupportTemplate")
    class CustomerSupportTemplateTests {

        private final CustomerSupportTemplate template = new CustomerSupportTemplate();

        @Test
        @DisplayName("should have correct ID")
        void shouldHaveCorrectId() {
            assertThat(template.getId()).isEqualTo("customer-support");
        }

        @Test
        @DisplayName("should have correct display name")
        void shouldHaveCorrectDisplayName() {
            assertThat(template.getDisplayName()).isEqualTo("Customer Support with RAG");
        }

        @Test
        @DisplayName("should have correct category")
        void shouldHaveCorrectCategory() {
            assertThat(template.getCategory()).isEqualTo("support");
        }

        @Test
        @DisplayName("should have expected tags")
        void shouldHaveExpectedTags() {
            assertThat(template.getTags()).containsExactlyInAnyOrder("rag", "support", "multi-agent", "routing");
        }

        @Test
        @DisplayName("should have non-empty description")
        void shouldHaveDescription() {
            assertThat(template.getDescription()).isNotBlank();
        }

        @Nested
        @DisplayName("Parameters")
        class Parameters {

            @Test
            @DisplayName("should define knowledgeBaseId as required")
            void shouldHaveRequiredKnowledgeBaseId() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("knowledgeBaseId");
                assertThat(params.get("knowledgeBaseId").required()).isTrue();
                assertThat(params.get("knowledgeBaseId").type()).isEqualTo(String.class);
            }

            @Test
            @DisplayName("should define maxIterations as optional with default 5")
            void shouldHaveOptionalMaxIterations() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("maxIterations");
                assertThat(params.get("maxIterations").required()).isFalse();
                assertThat(params.get("maxIterations").defaultValue()).isEqualTo(5);
            }

            @Test
            @DisplayName("should define responseLanguage as enum parameter")
            void shouldHaveResponseLanguageEnum() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("responseLanguage");
                assertThat(params.get("responseLanguage").options()).contains("en", "pt", "es");
            }

            @Test
            @DisplayName("should contain all expected parameter keys")
            void shouldContainAllExpectedKeys() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params.keySet()).containsExactlyInAnyOrder(
                        "knowledgeBaseId", "maxIterations", "escalationEmail",
                        "enableSentiment", "responseLanguage", "confidenceThreshold"
                );
            }
        }

        @Nested
        @DisplayName("createInstance()")
        class CreateInstance {

            @Test
            @DisplayName("should create workflow with valid parameters")
            void shouldCreateWorkflowWithValidParams() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("knowledgeBaseId", "support-docs");
                params.put("responseLanguage", "en");

                // Act
                Workflow workflow = template.createInstance("my-support", params);

                // Assert
                assertThat(workflow).isNotNull();
                assertThat(workflow.getName()).isEqualTo("my-support");
                assertThat(workflow.getId()).isEqualTo("my-support");
            }

            @Test
            @DisplayName("should throw when required knowledgeBaseId is missing")
            void shouldThrowWhenRequiredParamMissing() {
                // Arrange
                Map<String, Object> params = Map.of("maxIterations", 3);

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("my-support", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("knowledgeBaseId");
            }

            @Test
            @DisplayName("should throw when parameter has wrong type")
            void shouldThrowWhenWrongType() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("knowledgeBaseId", "valid");
                params.put("maxIterations", "not-an-integer"); // wrong type
                params.put("responseLanguage", "en");

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("my-support", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("maxIterations");
            }

            @Test
            @DisplayName("should throw when null parameters and required param exists")
            void shouldThrowWhenNullParams() {
                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("my-support", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("knowledgeBaseId");
            }
        }

        @Nested
        @DisplayName("getDefinition()")
        class GetDefinition {

            @Test
            @DisplayName("should return non-null definition")
            void shouldReturnNonNullDefinition() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition).isNotNull();
                assertThat(definition.getId()).isEqualTo("customer-support");
            }

            @Test
            @DisplayName("should have structure with input entry point")
            void shouldHaveStructureWithEntryPoint() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure()).isNotNull();
                assertThat(definition.getStructure().getEntryPoint()).isEqualTo("input");
            }

            @Test
            @DisplayName("should have nodes in the structure")
            void shouldHaveNodes() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure().getNodes()).isNotEmpty();
                assertThat(definition.getStructure().getNodes()).containsKey("input");
                assertThat(definition.getStructure().getNodes()).containsKey("intent-classifier");
            }

            @Test
            @DisplayName("should have connections in the structure")
            void shouldHaveConnections() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure().getConnections()).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("KnowledgeBaseTemplate")
    class KnowledgeBaseTemplateTests {

        private final KnowledgeBaseTemplate template = new KnowledgeBaseTemplate();

        @Test
        @DisplayName("should have correct ID")
        void shouldHaveCorrectId() {
            assertThat(template.getId()).isEqualTo("knowledge-base");
        }

        @Test
        @DisplayName("should have correct display name")
        void shouldHaveCorrectDisplayName() {
            assertThat(template.getDisplayName()).isEqualTo("Knowledge Base with RAG");
        }

        @Test
        @DisplayName("should have correct category")
        void shouldHaveCorrectCategory() {
            assertThat(template.getCategory()).isEqualTo("knowledge");
        }

        @Test
        @DisplayName("should have expected tags")
        void shouldHaveExpectedTags() {
            assertThat(template.getTags()).containsExactlyInAnyOrder(
                    "rag", "knowledge-base", "search", "qa", "retrieval"
            );
        }

        @Nested
        @DisplayName("Parameters")
        class Parameters {

            @Test
            @DisplayName("should define vectorStoreId as required")
            void shouldHaveRequiredVectorStoreId() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("vectorStoreId");
                assertThat(params.get("vectorStoreId").required()).isTrue();
                assertThat(params.get("vectorStoreId").type()).isEqualTo(String.class);
            }

            @Test
            @DisplayName("should define answerStyle as enum parameter")
            void shouldHaveAnswerStyleEnum() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("answerStyle");
                assertThat(params.get("answerStyle").options()).contains("concise", "detailed", "conversational");
            }

            @Test
            @DisplayName("should contain all expected parameter keys")
            void shouldContainAllExpectedKeys() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params.keySet()).containsExactlyInAnyOrder(
                        "vectorStoreId", "topK", "minScore", "includeCitations",
                        "hybridSearch", "keywordWeight", "rerankResults",
                        "maxResponseLength", "answerStyle"
                );
            }
        }

        @Nested
        @DisplayName("createInstance()")
        class CreateInstance {

            @Test
            @DisplayName("should create workflow with valid parameters")
            void shouldCreateWorkflowWithValidParams() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("vectorStoreId", "my-kb");
                params.put("answerStyle", "concise");

                // Act
                Workflow workflow = template.createInstance("kb-chat", params);

                // Assert
                assertThat(workflow).isNotNull();
                assertThat(workflow.getName()).isEqualTo("kb-chat");
            }

            @Test
            @DisplayName("should throw when required vectorStoreId is missing")
            void shouldThrowWhenRequiredParamMissing() {
                // Arrange
                Map<String, Object> params = Map.of("topK", 10);

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("kb-chat", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("vectorStoreId");
            }

            @Test
            @DisplayName("should throw when null parameters and required param exists")
            void shouldThrowWhenNullParams() {
                assertThatThrownBy(() -> template.createInstance("kb-chat", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("vectorStoreId");
            }
        }

        @Nested
        @DisplayName("getDefinition()")
        class GetDefinition {

            @Test
            @DisplayName("should return non-null definition with correct ID")
            void shouldReturnDefinition() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition).isNotNull();
                assertThat(definition.getId()).isEqualTo("knowledge-base");
            }

            @Test
            @DisplayName("should have structure with nodes and connections")
            void shouldHaveStructure() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure()).isNotNull();
                assertThat(definition.getStructure().getNodes()).isNotEmpty();
                assertThat(definition.getStructure().getConnections()).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("DocumentProcessingTemplate")
    class DocumentProcessingTemplateTests {

        private final DocumentProcessingTemplate template = new DocumentProcessingTemplate();

        @Test
        @DisplayName("should have correct ID")
        void shouldHaveCorrectId() {
            assertThat(template.getId()).isEqualTo("document-processing");
        }

        @Test
        @DisplayName("should have correct display name")
        void shouldHaveCorrectDisplayName() {
            assertThat(template.getDisplayName()).isEqualTo("Document Processing with Extraction");
        }

        @Test
        @DisplayName("should have correct category")
        void shouldHaveCorrectCategory() {
            assertThat(template.getCategory()).isEqualTo("processing");
        }

        @Test
        @DisplayName("should have expected tags")
        void shouldHaveExpectedTags() {
            assertThat(template.getTags()).containsExactlyInAnyOrder(
                    "document", "extraction", "ocr", "summarization", "rag"
            );
        }

        @Nested
        @DisplayName("Parameters")
        class Parameters {

            @Test
            @DisplayName("should have all optional parameters (no required)")
            void shouldHaveAllOptionalParams() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params.values())
                        .filteredOn(ParameterDefinition::required)
                        .extracting(ParameterDefinition::name)
                        .containsOnly("outputFormat"); // enum is required
            }

            @Test
            @DisplayName("should define outputFormat as enum parameter")
            void shouldHaveOutputFormatEnum() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("outputFormat");
                assertThat(params.get("outputFormat").options()).contains("json", "csv", "xml");
            }

            @Test
            @DisplayName("should contain all expected parameter keys")
            void shouldContainAllExpectedKeys() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params.keySet()).containsExactlyInAnyOrder(
                        "supportedFormats", "enableOcr", "extractEntities", "entityTypes",
                        "generateSummary", "summaryMaxLength", "classifyDocument",
                        "documentTypes", "outputFormat"
                );
            }
        }

        @Nested
        @DisplayName("createInstance()")
        class CreateInstance {

            @Test
            @DisplayName("should create workflow with minimal parameters")
            void shouldCreateWorkflowWithMinimalParams() {
                // Arrange - all params are optional (except outputFormat enum which has a default)
                Map<String, Object> params = new HashMap<>();
                params.put("outputFormat", "json");

                // Act
                Workflow workflow = template.createInstance("doc-proc", params);

                // Assert
                assertThat(workflow).isNotNull();
                assertThat(workflow.getName()).isEqualTo("doc-proc");
            }

            @Test
            @DisplayName("should create workflow with custom parameters")
            void shouldCreateWorkflowWithCustomParams() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("enableOcr", false);
                params.put("generateSummary", true);
                params.put("summaryMaxLength", 500);
                params.put("outputFormat", "csv");

                // Act
                Workflow workflow = template.createInstance("custom-doc", params);

                // Assert
                assertThat(workflow).isNotNull();
                assertThat(workflow.getId()).isEqualTo("custom-doc");
            }

            @Test
            @DisplayName("should throw when parameter has wrong type")
            void shouldThrowWhenWrongType() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("enableOcr", "not-a-boolean"); // wrong type
                params.put("outputFormat", "json");

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("doc-proc", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("enableOcr");
            }
        }

        @Nested
        @DisplayName("getDefinition()")
        class GetDefinition {

            @Test
            @DisplayName("should return non-null definition with correct ID")
            void shouldReturnDefinition() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition).isNotNull();
                assertThat(definition.getId()).isEqualTo("document-processing");
            }

            @Test
            @DisplayName("should have structure with parser and OCR nodes")
            void shouldHaveProcessingNodes() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure().getNodes()).containsKey("parser");
                assertThat(definition.getStructure().getNodes()).containsKey("ocr");
                assertThat(definition.getStructure().getNodes()).containsKey("summarizer");
            }
        }
    }

    @Nested
    @DisplayName("AgentSupervisorTemplate")
    class AgentSupervisorTemplateTests {

        private final AgentSupervisorTemplate template = new AgentSupervisorTemplate();

        @Test
        @DisplayName("should have correct ID")
        void shouldHaveCorrectId() {
            assertThat(template.getId()).isEqualTo("agent-supervisor");
        }

        @Test
        @DisplayName("should have correct display name")
        void shouldHaveCorrectDisplayName() {
            assertThat(template.getDisplayName()).isEqualTo("Agent Supervisor with Orchestration");
        }

        @Test
        @DisplayName("should have correct category")
        void shouldHaveCorrectCategory() {
            assertThat(template.getCategory()).isEqualTo("automation");
        }

        @Test
        @DisplayName("should have expected tags")
        void shouldHaveExpectedTags() {
            assertThat(template.getTags()).containsExactlyInAnyOrder(
                    "multi-agent", "supervisor", "orchestration", "coordination"
            );
        }

        @Nested
        @DisplayName("Parameters")
        class Parameters {

            @Test
            @DisplayName("should define agents as required")
            void shouldHaveRequiredAgents() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("agents");
                assertThat(params.get("agents").required()).isTrue();
                assertThat(params.get("agents").type()).isEqualTo(List.class);
            }

            @Test
            @DisplayName("should define aggregationMethod as enum parameter")
            void shouldHaveAggregationMethodEnum() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("aggregationMethod");
                assertThat(params.get("aggregationMethod").options())
                        .contains("concatenate", "synthesis", "voting");
            }

            @Test
            @DisplayName("should define qualityThreshold as optional with default 0.8")
            void shouldHaveOptionalQualityThreshold() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params).containsKey("qualityThreshold");
                assertThat(params.get("qualityThreshold").required()).isFalse();
                assertThat(params.get("qualityThreshold").defaultValue()).isEqualTo(0.8);
            }

            @Test
            @DisplayName("should contain all expected parameter keys")
            void shouldContainAllExpectedKeys() {
                Map<String, ParameterDefinition> params = template.getParameters();

                assertThat(params.keySet()).containsExactlyInAnyOrder(
                        "agents", "supervisorModel", "maxIterations", "qualityThreshold",
                        "enableDecomposition", "maxSubTasks", "parallelExecution",
                        "aggregationMethod", "enableLogging"
                );
            }
        }

        @Nested
        @DisplayName("createInstance()")
        class CreateInstance {

            @Test
            @DisplayName("should create workflow with valid parameters")
            void shouldCreateWorkflowWithValidParams() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("agents", List.of("researcher", "writer", "reviewer"));
                params.put("aggregationMethod", "synthesis");

                // Act
                Workflow workflow = template.createInstance("supervisor-bot", params);

                // Assert
                assertThat(workflow).isNotNull();
                assertThat(workflow.getName()).isEqualTo("supervisor-bot");
                assertThat(workflow.getId()).isEqualTo("supervisor-bot");
            }

            @Test
            @DisplayName("should throw when required agents parameter is missing")
            void shouldThrowWhenRequiredParamMissing() {
                // Arrange
                Map<String, Object> params = Map.of("maxIterations", 3);

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("supervisor-bot", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("agents");
            }

            @Test
            @DisplayName("should throw when null parameters and required param exists")
            void shouldThrowWhenNullParams() {
                assertThatThrownBy(() -> template.createInstance("supervisor-bot", null))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("agents");
            }

            @Test
            @DisplayName("should throw when agents parameter has wrong type")
            void shouldThrowWhenWrongType() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("agents", "not-a-list"); // wrong type
                params.put("aggregationMethod", "synthesis");

                // Act & Assert
                assertThatThrownBy(() -> template.createInstance("supervisor-bot", params))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("agents");
            }

            @Test
            @DisplayName("should include metadata in created workflow")
            void shouldIncludeMetadata() {
                // Arrange
                Map<String, Object> params = new HashMap<>();
                params.put("agents", List.of("agent1"));
                params.put("aggregationMethod", "voting");

                // Act
                Workflow workflow = template.createInstance("meta-test", params);

                // Assert
                assertThat(workflow.getMetadata()).isNotNull();
                assertThat(workflow.getMetadata()).containsEntry("template", "agent-supervisor");
            }
        }

        @Nested
        @DisplayName("getDefinition()")
        class GetDefinition {

            @Test
            @DisplayName("should return non-null definition with correct ID")
            void shouldReturnDefinition() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition).isNotNull();
                assertThat(definition.getId()).isEqualTo("agent-supervisor");
            }

            @Test
            @DisplayName("should have supervisor and dispatcher nodes")
            void shouldHaveSupervisorNodes() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure().getNodes()).containsKey("supervisor");
                assertThat(definition.getStructure().getNodes()).containsKey("dispatcher");
                assertThat(definition.getStructure().getNodes()).containsKey("quality-checker");
            }

            @Test
            @DisplayName("should have connections between nodes")
            void shouldHaveConnections() {
                WorkflowTemplateDefinition definition = template.getDefinition();

                assertThat(definition.getStructure().getConnections()).isNotEmpty();
                assertThat(definition.getStructure().getConnections().size()).isGreaterThanOrEqualTo(5);
            }
        }
    }

    @Nested
    @DisplayName("Cross-template validation")
    class CrossTemplateValidation {

        @Test
        @DisplayName("all templates should have unique IDs")
        void allTemplatesShouldHaveUniqueIds() {
            // Arrange
            WorkflowTemplate[] templates = {
                    new CustomerSupportTemplate(),
                    new KnowledgeBaseTemplate(),
                    new DocumentProcessingTemplate(),
                    new AgentSupervisorTemplate()
            };

            // Act
            List<String> ids = java.util.Arrays.stream(templates)
                    .map(WorkflowTemplate::getId)
                    .toList();

            // Assert
            assertThat(ids).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("all templates should have unique display names")
        void allTemplatesShouldHaveUniqueDisplayNames() {
            // Arrange
            WorkflowTemplate[] templates = {
                    new CustomerSupportTemplate(),
                    new KnowledgeBaseTemplate(),
                    new DocumentProcessingTemplate(),
                    new AgentSupervisorTemplate()
            };

            // Act
            List<String> names = java.util.Arrays.stream(templates)
                    .map(WorkflowTemplate::getDisplayName)
                    .toList();

            // Assert
            assertThat(names).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("all templates should have non-empty parameters")
        void allTemplatesShouldHaveParameters() {
            // Arrange
            WorkflowTemplate[] templates = {
                    new CustomerSupportTemplate(),
                    new KnowledgeBaseTemplate(),
                    new DocumentProcessingTemplate(),
                    new AgentSupervisorTemplate()
            };

            // Assert
            for (WorkflowTemplate t : templates) {
                assertThat(t.getParameters())
                        .as("Parameters for template '%s'", t.getId())
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("all templates should return non-null definitions")
        void allTemplatesShouldHaveDefinitions() {
            // Arrange
            WorkflowTemplate[] templates = {
                    new CustomerSupportTemplate(),
                    new KnowledgeBaseTemplate(),
                    new DocumentProcessingTemplate(),
                    new AgentSupervisorTemplate()
            };

            // Assert
            for (WorkflowTemplate t : templates) {
                assertThat(t.getDefinition())
                        .as("Definition for template '%s'", t.getId())
                        .isNotNull();
            }
        }
    }
}
