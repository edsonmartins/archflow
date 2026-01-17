package br.com.archflow.template;

import br.com.archflow.model.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowTemplateRegistry}.
 */
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

    @Test
    void testSingletonInstance() {
        WorkflowTemplateRegistry instance1 = WorkflowTemplateRegistry.getInstance();
        WorkflowTemplateRegistry instance2 = WorkflowTemplateRegistry.getInstance();

        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void testRegisterAndGetTemplate() {
        WorkflowTemplate template = new TestTemplate("test", "Test Template");

        registry.register(template);

        assertThat(registry.hasTemplate("test")).isTrue();
        assertThat(registry.getTemplate("test")).isPresent().contains(template);
    }

    @Test
    void testGetAllTemplates() {
        registry.register(new TestTemplate("test1", "Template 1"));
        registry.register(new TestTemplate("test2", "Template 2"));

        Collection<WorkflowTemplate> templates = registry.getAllTemplates();

        assertThat(templates).hasSize(2);
    }

    @Test
    void testGetTemplatesByCategory() {
        registry.register(new TestTemplate("t1", "Template 1", "support"));
        registry.register(new TestTemplate("t2", "Template 2", "support"));
        registry.register(new TestTemplate("t3", "Template 3", "processing"));

        List<WorkflowTemplate> supportTemplates = registry.getTemplatesByCategory("support");

        assertThat(supportTemplates).hasSize(2);
    }

    @Test
    void testSearchByTag() {
        WorkflowTemplate template = new TestTemplate("test", "Test", "category", Set.of("rag", "kb"));
        registry.register(template);

        List<WorkflowTemplate> results = registry.searchByTag("rag");

        assertThat(results).containsExactly(template);
    }

    @Test
    void testSearch() {
        registry.register(new TestTemplate("support-agent", "Customer Support Agent"));
        registry.register(new TestTemplate("doc-processor", "Document Processor"));

        List<WorkflowTemplate> results = registry.search("support");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("support-agent");
    }

    @Test
    void testGetCategories() {
        registry.register(new TestTemplate("t1", "T1", "cat1"));
        registry.register(new TestTemplate("t2", "T2", "cat2"));

        Set<String> categories = registry.getCategories();

        assertThat(categories).containsExactlyInAnyOrder("cat1", "cat2");
    }

    @Test
    void testUnregister() {
        WorkflowTemplate template = new TestTemplate("test", "Test");
        registry.register(template);

        WorkflowTemplate removed = registry.unregister("test");

        assertThat(removed).isEqualTo(template);
        assertThat(registry.hasTemplate("test")).isFalse();
    }

    @Test
    void testSize() {
        registry.register(new TestTemplate("t1", "T1"));
        registry.register(new TestTemplate("t2", "T2"));

        assertThat(registry.size()).isEqualTo(2);
    }

    // Test helper class
    private static class TestTemplate extends AbstractWorkflowTemplate {
        public TestTemplate(String id, String displayName) {
            this(id, displayName, "test");
        }

        public TestTemplate(String id, String displayName, String category) {
            this(id, displayName, category, Set.of());
        }

        public TestTemplate(String id, String displayName, String category, Set<String> tags) {
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
