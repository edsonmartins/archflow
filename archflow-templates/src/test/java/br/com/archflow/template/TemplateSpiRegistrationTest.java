package br.com.archflow.template;

import br.com.archflow.template.knowledge.KnowledgeBaseTemplate;
import br.com.archflow.template.processing.DocumentProcessingTemplate;
import br.com.archflow.template.supervisor.AgentSupervisorTemplate;
import br.com.archflow.template.support.CustomerSupportTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que os templates built-in estão registrados via SPI — sem o
 * arquivo META-INF/services a galeria de templates carrega vazia em silêncio.
 */
class TemplateSpiRegistrationTest {

    @AfterEach
    void resetRegistry() {
        WorkflowTemplateRegistry.reset();
    }

    @Test
    void builtInTemplatesAreDiscoverableViaServiceLoader() {
        List<String> discovered = ServiceLoader.load(WorkflowTemplate.class).stream()
                .map(p -> p.type().getName())
                .toList();

        assertThat(discovered).contains(
                CustomerSupportTemplate.class.getName(),
                DocumentProcessingTemplate.class.getName(),
                KnowledgeBaseTemplate.class.getName(),
                AgentSupervisorTemplate.class.getName());
    }

    @Test
    void registrySingletonLoadsBuiltInTemplates() {
        WorkflowTemplateRegistry.reset();
        WorkflowTemplateRegistry registry = WorkflowTemplateRegistry.getInstance();

        assertThat(registry.size()).isGreaterThanOrEqualTo(4);
        assertThat(registry.getAllTemplates()).isNotEmpty();
    }
}
