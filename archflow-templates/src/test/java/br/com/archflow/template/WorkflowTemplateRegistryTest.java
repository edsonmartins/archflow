package br.com.archflow.template;

import br.com.archflow.model.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowTemplateRegistry}.
 */
@DisplayName("WorkflowTemplateRegistry")
class WorkflowTemplateRegistryTest {

    private WorkflowTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        WorkflowTemplateRegistry.reset();
        registry = WorkflowTemplateRegistry.getInstance();
    }

    @AfterEach
    void tearDown() {
        WorkflowTemplateRegistry.reset();
    }

    @Nested
    @DisplayName("Singleton behavior")
    class SingletonBehavior {

        @Test
        @DisplayName("should return same instance on multiple calls")
        void shouldReturnSameInstance() {
            // Arrange & Act
            WorkflowTemplateRegistry instance1 = WorkflowTemplateRegistry.getInstance();
            WorkflowTemplateRegistry instance2 = WorkflowTemplateRegistry.getInstance();

            // Assert
            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("should return new instance after reset")
        void shouldReturnNewInstanceAfterReset() {
            // Arrange
            WorkflowTemplateRegistry before = WorkflowTemplateRegistry.getInstance();

            // Act
            WorkflowTemplateRegistry.reset();
            WorkflowTemplateRegistry after = WorkflowTemplateRegistry.getInstance();

            // Assert
            assertThat(after).isNotSameAs(before);
        }
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("should register a template successfully")
        void shouldRegisterTemplate() {
            // Arrange
            WorkflowTemplate template = createTestTemplate("my-template", "My Template");

            // Act
            registry.register(template);

            // Assert
            assertThat(registry.hasTemplate("my-template")).isTrue();
        }

        @Test
        @DisplayName("should allow retrieving registered template by ID")
        void shouldRetrieveRegisteredTemplate() {
            // Arrange
            WorkflowTemplate template = createTestTemplate("my-template", "My Template");

            // Act
            registry.register(template);

            // Assert
            assertThat(registry.getTemplate("my-template")).isPresent().contains(template);
        }

        @Test
        @DisplayName("should overwrite template with same ID")
        void shouldOverwriteWithSameId() {
            // Arrange
            WorkflowTemplate template1 = createTestTemplate("dup", "First");
            WorkflowTemplate template2 = createTestTemplate("dup", "Second");

            // Act
            registry.register(template1);
            registry.register(template2);

            // Assert
            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.getTemplate("dup").get().getDisplayName()).isEqualTo("Second");
        }

        @Test
        @DisplayName("should index template under its category")
        void shouldIndexByCategory() {
            // Arrange
            WorkflowTemplate template = createTestTemplate("t1", "T1", "support");

            // Act
            registry.register(template);

            // Assert
            assertThat(registry.getTemplatesByCategory("support")).containsExactly(template);
        }

        @Test
        @DisplayName("should increment size for each unique registration")
        void shouldIncrementSize() {
            // Act
            registry.register(createTestTemplate("a", "A"));
            registry.register(createTestTemplate("b", "B"));
            registry.register(createTestTemplate("c", "C"));

            // Assert
            assertThat(registry.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("getTemplate()")
    class GetTemplate {

        @Test
        @DisplayName("should return present Optional for existing template")
        void shouldReturnPresentForExisting() {
            // Arrange
            registry.register(createTestTemplate("exists", "Exists"));

            // Act
            Optional<WorkflowTemplate> result = registry.getTemplate("exists");

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo("exists");
        }

        @Test
        @DisplayName("should return empty Optional for non-existing template")
        void shouldReturnEmptyForNonExisting() {
            // Act
            Optional<WorkflowTemplate> result = registry.getTemplate("non-existent");

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllTemplates()")
    class GetAllTemplates {

        @Test
        @DisplayName("should return empty collection when no templates registered")
        void shouldReturnEmptyWhenNone() {
            // Act
            Collection<WorkflowTemplate> templates = registry.getAllTemplates();

            // Assert
            assertThat(templates).isEmpty();
        }

        @Test
        @DisplayName("should return all registered templates")
        void shouldReturnAllRegistered() {
            // Arrange
            registry.register(createTestTemplate("t1", "Template 1"));
            registry.register(createTestTemplate("t2", "Template 2"));
            registry.register(createTestTemplate("t3", "Template 3"));

            // Act
            Collection<WorkflowTemplate> templates = registry.getAllTemplates();

            // Assert
            assertThat(templates).hasSize(3);
        }

        @Test
        @DisplayName("should return unmodifiable collection")
        void shouldReturnUnmodifiableCollection() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1"));

            // Act
            Collection<WorkflowTemplate> templates = registry.getAllTemplates();

            // Assert
            assertThat(templates).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("getTemplatesByCategory()")
    class GetTemplatesByCategory {

        @Test
        @DisplayName("should return templates matching the category")
        void shouldReturnMatchingCategory() {
            // Arrange
            registry.register(createTestTemplate("s1", "Support 1", "support"));
            registry.register(createTestTemplate("s2", "Support 2", "support"));
            registry.register(createTestTemplate("p1", "Processing 1", "processing"));

            // Act
            List<WorkflowTemplate> supportTemplates = registry.getTemplatesByCategory("support");

            // Assert
            assertThat(supportTemplates).hasSize(2);
            assertThat(supportTemplates).extracting(WorkflowTemplate::getCategory)
                    .containsOnly("support");
        }

        @Test
        @DisplayName("should return empty list for unknown category")
        void shouldReturnEmptyForUnknownCategory() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1", "support"));

            // Act
            List<WorkflowTemplate> result = registry.getTemplatesByCategory("nonexistent");

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should not include templates from other categories")
        void shouldNotIncludeOtherCategories() {
            // Arrange
            registry.register(createTestTemplate("s1", "S1", "support"));
            registry.register(createTestTemplate("p1", "P1", "processing"));

            // Act
            List<WorkflowTemplate> result = registry.getTemplatesByCategory("support");

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("s1");
        }
    }

    @Nested
    @DisplayName("searchByTag()")
    class SearchByTag {

        @Test
        @DisplayName("should find templates with matching tag")
        void shouldFindByTag() {
            // Arrange
            WorkflowTemplate template = createTestTemplate("t1", "T1", "cat", Set.of("rag", "support"));
            registry.register(template);

            // Act
            List<WorkflowTemplate> results = registry.searchByTag("rag");

            // Assert
            assertThat(results).containsExactly(template);
        }

        @Test
        @DisplayName("should return empty list when no templates have the tag")
        void shouldReturnEmptyForUnmatchedTag() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1", "cat", Set.of("rag")));

            // Act
            List<WorkflowTemplate> results = registry.searchByTag("nonexistent-tag");

            // Assert
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return multiple templates sharing the same tag")
        void shouldReturnMultipleWithSameTag() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1", "cat", Set.of("rag", "a")));
            registry.register(createTestTemplate("t2", "T2", "cat", Set.of("rag", "b")));
            registry.register(createTestTemplate("t3", "T3", "cat", Set.of("other")));

            // Act
            List<WorkflowTemplate> results = registry.searchByTag("rag");

            // Assert
            assertThat(results).hasSize(2);
            assertThat(results).extracting(WorkflowTemplate::getId)
                    .containsExactlyInAnyOrder("t1", "t2");
        }
    }

    @Nested
    @DisplayName("getCategories()")
    class GetCategories {

        @Test
        @DisplayName("should return empty set when no templates registered")
        void shouldReturnEmptyWhenNone() {
            // Act
            Set<String> categories = registry.getCategories();

            // Assert
            assertThat(categories).isEmpty();
        }

        @Test
        @DisplayName("should return all distinct categories")
        void shouldReturnAllDistinctCategories() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1", "support"));
            registry.register(createTestTemplate("t2", "T2", "processing"));
            registry.register(createTestTemplate("t3", "T3", "automation"));
            registry.register(createTestTemplate("t4", "T4", "support")); // duplicate category

            // Act
            Set<String> categories = registry.getCategories();

            // Assert
            assertThat(categories).containsExactlyInAnyOrder("support", "processing", "automation");
        }
    }

    @Nested
    @DisplayName("search() by keyword")
    class SearchByKeyword {

        @Test
        @DisplayName("should find template by display name keyword")
        void shouldFindByDisplayName() {
            // Arrange
            registry.register(createTestTemplate("cs", "Customer Support Agent"));
            registry.register(createTestTemplate("dp", "Document Processor"));

            // Act
            List<WorkflowTemplate> results = registry.search("Customer");

            // Assert
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getId()).isEqualTo("cs");
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            // Arrange
            registry.register(createTestTemplate("cs", "Customer Support Agent"));

            // Act
            List<WorkflowTemplate> results = registry.search("customer");

            // Assert
            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should find template by description keyword")
        void shouldFindByDescription() {
            // Arrange
            // TestTemplate has description "Test description" by default
            registry.register(createTestTemplate("t1", "Some Name"));

            // Act
            List<WorkflowTemplate> results = registry.search("description");

            // Assert
            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should return empty list when no match")
        void shouldReturnEmptyWhenNoMatch() {
            // Arrange
            registry.register(createTestTemplate("t1", "Alpha"));

            // Act
            List<WorkflowTemplate> results = registry.search("zzzzz");

            // Assert
            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("unregister()")
    class Unregister {

        @Test
        @DisplayName("should remove the template and return it")
        void shouldRemoveAndReturn() {
            // Arrange
            WorkflowTemplate template = createTestTemplate("remove-me", "Remove Me");
            registry.register(template);

            // Act
            WorkflowTemplate removed = registry.unregister("remove-me");

            // Assert
            assertThat(removed).isEqualTo(template);
            assertThat(registry.hasTemplate("remove-me")).isFalse();
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("should return null for non-existing template")
        void shouldReturnNullForNonExisting() {
            // Act
            WorkflowTemplate removed = registry.unregister("does-not-exist");

            // Assert
            assertThat(removed).isNull();
        }

        @Test
        @DisplayName("should remove template from category index")
        void shouldRemoveFromCategoryIndex() {
            // Arrange
            registry.register(createTestTemplate("t1", "T1", "support"));

            // Act
            registry.unregister("t1");

            // Assert
            assertThat(registry.getTemplatesByCategory("support")).isEmpty();
        }
    }

    @Nested
    @DisplayName("hasTemplate()")
    class HasTemplate {

        @Test
        @DisplayName("should return true for registered template")
        void shouldReturnTrueForRegistered() {
            // Arrange
            registry.register(createTestTemplate("exists", "Exists"));

            // Assert
            assertThat(registry.hasTemplate("exists")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-registered template")
        void shouldReturnFalseForNonRegistered() {
            // Assert
            assertThat(registry.hasTemplate("nope")).isFalse();
        }
    }

    // --- Helper methods ---

    private static WorkflowTemplate createTestTemplate(String id, String displayName) {
        return createTestTemplate(id, displayName, "test", Set.of());
    }

    private static WorkflowTemplate createTestTemplate(String id, String displayName, String category) {
        return createTestTemplate(id, displayName, category, Set.of());
    }

    private static WorkflowTemplate createTestTemplate(String id, String displayName, String category, Set<String> tags) {
        return new TestTemplate(id, displayName, category, tags);
    }

    private static class TestTemplate extends AbstractWorkflowTemplate {

        TestTemplate(String id, String displayName, String category, Set<String> tags) {
            super(id, displayName, "Test description", category, tags, Map.of());
        }

        @Override
        public WorkflowTemplateDefinition getDefinition() {
            return null;
        }

        @Override
        protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
            return new Workflow() {
                @Override
                public String getId() {
                    return name;
                }

                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return "Test workflow";
                }

                @Override
                public Map<String, Object> getMetadata() {
                    return Map.of("test", true);
                }
            };
        }
    }
}
