package br.com.archflow.api.assist;

import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.impl.AssistServiceImpl;
import br.com.archflow.api.catalog.impl.CatalogControllerImpl;
import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.NodeSupport;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A API de assistência conhece o archflow (RCHFLW-107).
 *
 * <p>Na homologação, {@code nl-to-flow} sem catálogo no corpo devolveu um fluxo vazio ("O catálogo está
 * vazio"), e {@code explain-error} para "component not found: transform" falou de "plataforma iPaaS" e
 * sugeriu {@code data-transform}, {@code mapper} e {@code jq}. O teste lê o prompt que chega ao modelo:
 * a resposta do modelo não é do archflow, o que ele recebe é.</p>
 */
@DisplayName("Assistência: prompts com o catálogo do servidor")
class AssistDoArchflowTest {

    private final List<String> prompts = new ArrayList<>();
    private ServerFlowCatalog serverCatalog;
    private AssistServiceImpl assist;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        ComponentCatalog catalog = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        NodeSupport nodeSupport = new NodeSupport(new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class), null, null, null));
        serverCatalog = new ServerFlowCatalog(new CatalogControllerImpl(catalog), nodeSupport);

        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return "{\"workflow\":{\"steps\":[],\"links\":[]},\"lacunas\":[],\"diagnostico\":\"x\"}";
        });
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolveModel(any())).thenReturn(model);
        when(resolver.resolve(any())).thenReturn(ResolvedLLMConfig.builder().build());
        assist = new AssistServiceImpl(resolver, ResolvedLLMConfig.builder().build(), null, serverCatalog);
    }

    @Test
    @DisplayName("nl-to-flow sem catálogo no corpo usa o catálogo executável do servidor")
    void nlToFlowUsaOCatalogoDoServidor() {
        assist.nlToFlow(new NlToFlowRequest("triagem de pedidos de suporte", null, "pt-BR"));

        String prompt = prompts.getFirst();
        assertThat(prompt).doesNotContain("catálogo vazio");
        assertThat(serverCatalog.executableComponents()).isNotEmpty();
        assertThat(serverCatalog.executableComponents())
                .allSatisfy(c -> assertThat(prompt).contains("plugin=" + c.component() + ","));
        assertThat(prompt).contains("plugin=text-transform-tool,").contains("nodeType=tool");
        // Só o que executa: um tipo da paleta sem execução não entra como opção de nó.
        assertThat(serverCatalog.nonExecutableNodeTypes()).contains("transform");
        assertThat(prompt).doesNotContain("plugin=transform,");
    }

    @Test
    @DisplayName("nl-to-flow com catálogo no corpo continua usando o do chamador")
    void nlToFlowRespeitaOCatalogoDoCorpo() {
        assist.nlToFlow(new NlToFlowRequest("x", List.of(new NlToFlowRequest.CatalogPlugin(
                "crm-source", "CRM", "1.0", "source", List.of())), "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("plugin=crm-source")
                .doesNotContain("plugin=text-transform-tool");
    }

    @Test
    @DisplayName("explain-error descreve o archflow, o que é 'component not found' e os tipos sem execução")
    void explainErrorDescreveOArchflow() {
        assist.explainError(new ExplainErrorRequest(null, "component not found: transform", null, null,
                null, null, "transformar", "transform", "pt-BR"));

        String prompt = prompts.getFirst();
        assertThat(prompt)
                .contains("archflow")
                .doesNotContain("iPaaS")
                .contains("\"component not found: X\" significa")
                .contains("NUNCA invente nomes de componente");
        assertThat(serverCatalog.nonExecutableNodeTypes())
                .allSatisfy(t -> assertThat(prompt).contains(t));
        assertThat(prompt).contains("componentId=text-transform-tool");
        // Os nomes que o modelo inventou na homologação não aparecem como sugestão possível.
        assertThat(prompt).doesNotContain("data-transform").doesNotContain("mapper").doesNotContain("jq");
    }

    @Test
    @DisplayName("tipos executáveis e sem execução saem do factory do motor, não de uma lista própria")
    void tiposSaemDoMotor() {
        assertThat(serverCatalog.executableNodeTypes())
                .contains("condition", "switch", "approval", "decision")
                .doesNotContain("transform");
        assertThat(serverCatalog.nonExecutableNodeTypes())
                .contains("transform", "map", "filter")
                .doesNotContain("condition");
    }
}
