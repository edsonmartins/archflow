package br.com.archflow.api.template.impl;

import br.com.archflow.api.template.TemplateController;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;
import br.com.archflow.model.Workflow;
import br.com.archflow.template.WorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;
import br.com.archflow.template.WorkflowTemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateControllerImpl")
class TemplateControllerImplTest {

    @Mock
    private WorkflowTemplateRegistry registry;

    private TemplateControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new TemplateControllerImpl(registry);
    }

    private WorkflowTemplate createStubTemplate(String id, String displayName, String description, String category) {
        WorkflowTemplate template = mock(WorkflowTemplate.class);
        when(template.getId()).thenReturn(id);
        when(template.getDisplayName()).thenReturn(displayName);
        when(template.getDescription()).thenReturn(description);
        when(template.getCategory()).thenReturn(category);
        when(template.getTags()).thenReturn(Set.of("ai", "automation"));
        when(template.getParameters()).thenReturn(Map.of(
                "model", WorkflowTemplate.ParameterDefinition.required("model", "The AI model to use", String.class),
                "maxRetries", WorkflowTemplate.ParameterDefinition.optional("maxRetries", "Max retry count", Integer.class, 3)
        ));
        when(template.getDefinition()).thenReturn(null);
        return template;
    }

    @Nested
    @DisplayName("listTemplates")
    class ListTemplatesTest {

        @Test
        @DisplayName("should list all templates")
        void shouldListAllTemplates() {
            var template1 = createStubTemplate("t1", "Template 1", "First template", "support");
            var template2 = createStubTemplate("t2", "Template 2", "Second template", "processing");
            when(registry.getAllTemplates()).thenReturn(List.of(template1, template2));

            List<TemplateResponse> result = controller.listTemplates();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("t1");
            assertThat(result.get(0).displayName()).isEqualTo("Template 1");
            assertThat(result.get(1).id()).isEqualTo("t2");
            assertThat(result.get(1).displayName()).isEqualTo("Template 2");
        }
    }

    @Nested
    @DisplayName("getTemplate")
    class GetTemplateTest {

        @Test
        @DisplayName("should get template by id")
        void shouldGetTemplateById() {
            var template = createStubTemplate("t1", "Template 1", "A template", "support");
            when(registry.getTemplate("t1")).thenReturn(Optional.of(template));

            TemplateResponse result = controller.getTemplate("t1");

            assertThat(result.id()).isEqualTo("t1");
            assertThat(result.displayName()).isEqualTo("Template 1");
            assertThat(result.description()).isEqualTo("A template");
            assertThat(result.category()).isEqualTo("support");
            assertThat(result.tags()).containsExactlyInAnyOrder("ai", "automation");
            assertThat(result.parameters()).hasSize(2);
            assertThat(result.version()).isEqualTo("1.0.0");
            assertThat(result.author()).isEqualTo("archflow");
        }

        @Test
        @DisplayName("should throw when template not found")
        void shouldThrowWhenTemplateNotFound() {
            when(registry.getTemplate("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getTemplate("missing"))
                    .isInstanceOf(TemplateController.TemplateNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    @DisplayName("installTemplate")
    class InstallTemplateTest {

        @Test
        @DisplayName("should install template")
        void shouldInstallTemplate() {
            var template = mock(WorkflowTemplate.class);
            var workflow = mock(Workflow.class);
            var params = Map.<String, Object>of("model", "gpt-4");

            when(registry.getTemplate("t1")).thenReturn(Optional.of(template));
            when(template.createInstance("My Workflow", params)).thenReturn(workflow);

            var request = new InstallTemplateRequest("My Workflow", params);
            Object result = controller.installTemplate("t1", request);

            assertThat(result).isEqualTo(workflow);
            verify(template).validateParameters(params);
            verify(template).createInstance("My Workflow", params);
        }

        @Test
        @DisplayName("should validate parameters on install")
        void shouldValidateParametersOnInstall() {
            var template = mock(WorkflowTemplate.class);
            var params = Map.<String, Object>of("invalid", "value");

            when(registry.getTemplate("t1")).thenReturn(Optional.of(template));
            doThrow(new IllegalArgumentException("Required parameter 'model' is missing"))
                    .when(template).validateParameters(params);

            var request = new InstallTemplateRequest("My Workflow", params);

            assertThatThrownBy(() -> controller.installTemplate("t1", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("should throw when template not found on install")
        void shouldThrowWhenTemplateNotFoundOnInstall() {
            when(registry.getTemplate("missing")).thenReturn(Optional.empty());

            var request = new InstallTemplateRequest("My Workflow", Map.of());

            assertThatThrownBy(() -> controller.installTemplate("missing", request))
                    .isInstanceOf(TemplateController.TemplateNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    @DisplayName("listCategories")
    class ListCategoriesTest {

        @Test
        @DisplayName("should list categories")
        void shouldListCategories() {
            when(registry.getCategories()).thenReturn(Set.of("support", "processing", "automation"));

            List<String> result = controller.listCategories();

            assertThat(result).hasSize(3);
            assertThat(result).containsExactlyInAnyOrder("support", "processing", "automation");
        }
    }

    @Nested
    @DisplayName("searchTemplates")
    class SearchTemplatesTest {

        @Test
        @DisplayName("should search templates")
        void shouldSearchTemplates() {
            var template = createStubTemplate("t1", "Customer Support", "Support template", "support");
            when(registry.search("support")).thenReturn(List.of(template));

            List<TemplateResponse> result = controller.searchTemplates("support");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("t1");
            assertThat(result.get(0).displayName()).isEqualTo("Customer Support");
            verify(registry).search("support");
        }

        @Test
        @DisplayName("should return empty list when no matches")
        void shouldReturnEmptyWhenNoMatches() {
            when(registry.search("nonexistent")).thenReturn(List.of());

            List<TemplateResponse> result = controller.searchTemplates("nonexistent");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("previewTemplate")
    class PreviewTemplateTest {

        @Test
        @DisplayName("should preview template")
        void shouldPreviewTemplate() {
            var template = createStubTemplate("t1", "Template 1", "A template", "support");
            when(registry.getTemplate("t1")).thenReturn(Optional.of(template));

            TemplateResponse result = controller.previewTemplate("t1");

            assertThat(result.id()).isEqualTo("t1");
            assertThat(result.displayName()).isEqualTo("Template 1");
        }

        @Test
        @DisplayName("should throw when template not found on preview")
        void shouldThrowWhenTemplateNotFoundOnPreview() {
            when(registry.getTemplate("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.previewTemplate("missing"))
                    .isInstanceOf(TemplateController.TemplateNotFoundException.class);
        }
    }
}
