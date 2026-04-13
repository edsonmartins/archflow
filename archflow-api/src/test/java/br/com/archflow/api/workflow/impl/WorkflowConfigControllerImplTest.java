package br.com.archflow.api.workflow.impl;

import br.com.archflow.agent.governance.GovernanceProfile;
import br.com.archflow.api.workflow.dto.AgentPatternDto;
import br.com.archflow.api.workflow.dto.GovernanceProfileDto;
import br.com.archflow.api.workflow.dto.McpServerDto;
import br.com.archflow.api.workflow.dto.PersonaDto;
import br.com.archflow.api.workflow.dto.ProviderDto;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.langchain4j.provider.LLMProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowConfigControllerImpl")
class WorkflowConfigControllerImplTest {

    @Test
    @DisplayName("listProviders mirrors the LLMProvider enum with all models")
    void listProvidersMirrorsEnum() {
        var controller = new WorkflowConfigControllerImpl();

        List<ProviderDto> providers = controller.listProviders();

        assertThat(providers).hasSize(LLMProvider.values().length);
        // OpenAI should be present with its models
        ProviderDto openai = providers.stream()
                .filter(p -> "openai".equals(p.id()))
                .findFirst().orElseThrow();
        assertThat(openai.displayName()).isEqualTo("OpenAI");
        assertThat(openai.requiresApiKey()).isTrue();
        assertThat(openai.supportsStreaming()).isTrue();
        assertThat(openai.group()).isEqualTo("Cloud");
        assertThat(openai.models()).isNotEmpty();
        assertThat(openai.models()).anyMatch(m -> "gpt-4o".equals(m.id()));
    }

    @Test
    @DisplayName("Ollama provider is grouped as Local")
    void ollamaIsLocal() {
        var controller = new WorkflowConfigControllerImpl();
        ProviderDto ollama = controller.listProviders().stream()
                .filter(p -> "ollama".equals(p.id()))
                .findFirst().orElseThrow();
        assertThat(ollama.group()).isEqualTo("Local");
    }

    @Test
    @DisplayName("model fields carry context window and max temperature")
    void modelFieldsCarryMeta() {
        var controller = new WorkflowConfigControllerImpl();
        ProviderDto openai = controller.listProviders().stream()
                .filter(p -> "openai".equals(p.id()))
                .findFirst().orElseThrow();
        ProviderDto.ModelDto gpt4o = openai.models().stream()
                .filter(m -> "gpt-4o".equals(m.id()))
                .findFirst().orElseThrow();
        assertThat(gpt4o.contextWindow()).isPositive();
        assertThat(gpt4o.maxTemperature()).isPositive();
    }

    @Test
    @DisplayName("listAgentPatterns returns the four built-in strategies")
    void listAgentPatternsHasFour() {
        var controller = new WorkflowConfigControllerImpl();
        List<AgentPatternDto> patterns = controller.listAgentPatterns();
        assertThat(patterns).hasSize(4);
        assertThat(patterns).extracting(AgentPatternDto::id)
                .containsExactly("react", "plan-execute", "rewoo", "chain-of-thought");
        assertThat(patterns).allSatisfy(p -> {
            assertThat(p.label()).isNotBlank();
            assertThat(p.description()).isNotBlank();
        });
    }

    @Test
    @DisplayName("empty constructor returns empty persona/governance/mcp lists")
    void emptyDefaults() {
        var controller = new WorkflowConfigControllerImpl();
        assertThat(controller.listPersonas()).isEmpty();
        assertThat(controller.listGovernanceProfiles()).isEmpty();
        assertThat(controller.listMcpServers()).isEmpty();
    }

    @Test
    @DisplayName("listPersonas maps Persona records to PersonaDto")
    void listPersonasMapsRecords() {
        Persona persona = Persona.of(
                "order_tracking",
                "Order Tracking",
                "prompts/order_tracking",
                List.of("crm_lookup"),
                "order", "tracking");
        var controller = new WorkflowConfigControllerImpl(
                () -> List.of(persona),
                List::of,
                List::of);

        List<PersonaDto> personas = controller.listPersonas();
        assertThat(personas).hasSize(1);
        assertThat(personas.get(0).id()).isEqualTo("order_tracking");
        assertThat(personas.get(0).label()).isEqualTo("Order Tracking");
        assertThat(personas.get(0).promptId()).isEqualTo("prompts/order_tracking");
    }

    @Test
    @DisplayName("listGovernanceProfiles maps records and their tool sets")
    void listGovernanceProfilesMapsRecords() {
        GovernanceProfile profile = GovernanceProfile.builder()
                .id("strict")
                .name("Strict")
                .systemPrompt("Be very careful.")
                .enableTool("search")
                .disableTool("execute_code")
                .escalationThreshold(0.8)
                .maxToolExecutions(5)
                .customInstructions("No PII disclosure.")
                .build();

        var controller = new WorkflowConfigControllerImpl(
                List::of,
                () -> List.of(profile),
                List::of);

        List<GovernanceProfileDto> dtos = controller.listGovernanceProfiles();
        assertThat(dtos).hasSize(1);
        GovernanceProfileDto dto = dtos.get(0);
        assertThat(dto.id()).isEqualTo("strict");
        assertThat(dto.name()).isEqualTo("Strict");
        assertThat(dto.enabledTools()).containsExactlyInAnyOrderElementsOf(Set.of("search"));
        assertThat(dto.disabledTools()).containsExactlyInAnyOrderElementsOf(Set.of("execute_code"));
        assertThat(dto.escalationThreshold()).isEqualTo(0.8);
        assertThat(dto.maxToolExecutions()).isEqualTo(5);
        assertThat(dto.customInstructions()).isEqualTo("No PII disclosure.");
    }

    @Test
    @DisplayName("listMcpServers returns whatever the supplier provides")
    void listMcpServersReturnsSupplier() {
        McpServerDto server = new McpServerDto(
                "filesystem",
                "stdio",
                "mcp-server-filesystem --root /tmp",
                null,
                7);
        var controller = new WorkflowConfigControllerImpl(
                List::of,
                List::of,
                () -> List.of(server));

        assertThat(controller.listMcpServers()).containsExactly(server);
    }

    @Test
    @DisplayName("suppliers returning null are treated as empty lists")
    void nullSuppliersAreEmpty() {
        var controller = new WorkflowConfigControllerImpl(
                () -> null,
                () -> null,
                () -> null);

        assertThat(controller.listPersonas()).isEmpty();
        assertThat(controller.listGovernanceProfiles()).isEmpty();
        assertThat(controller.listMcpServers()).isEmpty();
    }

    @Test
    @DisplayName("constructor rejects null suppliers")
    void rejectsNullSuppliers() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new WorkflowConfigControllerImpl(null, List::of, List::of));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new WorkflowConfigControllerImpl(List::of, null, List::of));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new WorkflowConfigControllerImpl(List::of, List::of, null));
    }
}
