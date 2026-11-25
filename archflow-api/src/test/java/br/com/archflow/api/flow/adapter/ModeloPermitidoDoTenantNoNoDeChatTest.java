package br.com.archflow.api.flow.adapter;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.langchain4j.provider.TenantModelPolicy;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Nó de chat de um tenant com {@code allowedModels} (RCHFLW-79): o modelo do documento — escrito no
 * painel, no YAML ou herdado do fluxo/plataforma — tem de estar na lista do tenant, ou o nó falha
 * <b>antes</b> de o adapter existir (nenhuma requisição sai). Antes, a restrição do cadastro de
 * tenants não era aplicada em lugar nenhum.
 *
 * <p>Os tenants são explícitos no {@code ExecutionContext}: hoje as execuções REST/AG-UI chegam como
 * {@code SYSTEM} (RCHFLW-13, à parte), e a conferência tem de valer para qualquer tenant que o
 * contexto trouxer.
 */
@DisplayName("Nó de chat: modelo fora dos permitidos do tenant falha antes da chamada")
class ModeloPermitidoDoTenantNoNoDeChatTest {

    private static final String PROVIDER = "provider-de-teste";

    /** "acme" só pode usar "m-permitido"; os demais tenants, qualquer um. */
    private static final TenantModelPolicy POLITICA = tenantId -> "acme".equals(tenantId)
            ? Optional.of(List.of("m-permitido")) : Optional.empty();

    private DefaultFlowStepFactory factory;

    @BeforeEach
    void setUp() {
        LangChainAdapterBridgeTest.TestAdapterFactory.created.clear();
        factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null);
        factory.setModelPolicy(POLITICA);
    }

    private static ExecutionContext contexto(String tenantId) {
        return new DefaultExecutionContext(tenantId, "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private StepResult executar(Map<String, Object> config, Map<String, Object> configuration, String tenant) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "s1");
        node.put("type", "llm-chat");
        node.put("componentId", "llm-chat");
        node.put("config", config);
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "Chat"));
        json.put("steps", List.of(node));
        if (configuration != null) {
            json.put("configuration", configuration);
        }
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(json);
        ExecutionContext ctx = contexto(tenant);
        ctx.set("input", "oi");
        return flow.getSteps().get(0).execute(ctx).join();
    }

    private static String falha(StepResult result) {
        return result.getOutput() + " " + result.getErrors();
    }

    @Test
    @DisplayName("modelo do nó fora da lista do tenant: FAILED com [LLM-MODELO], e o adapter nem nasce")
    void modeloForaDaListaFalha() {
        StepResult result = executar(Map.of("provider", PROVIDER, "model", "m-caro"), null, "acme");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(falha(result)).contains("[LLM-MODELO]").contains("m-caro").contains("acme");
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created)
                .as("nenhuma requisição pode sair com o modelo recusado")
                .isEmpty();
    }

    @Test
    @DisplayName("modelo permitido executa normalmente")
    void modeloPermitidoExecuta() {
        StepResult result = executar(Map.of("provider", PROVIDER, "model", "m-permitido"), null, "acme");

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).hasValue("eco:generate:oi");
    }

    @Test
    @DisplayName("o modelo herdado do fluxo também é conferido (não só o que o painel grava)")
    void modeloHerdadoDoFluxoEConferido() {
        StepResult result = executar(Map.of(),
                Map.of("llmConfig", Map.of("provider", PROVIDER, "model", "m-do-fluxo")), "acme");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(falha(result)).contains("[LLM-MODELO]").contains("m-do-fluxo");
    }

    @Test
    @DisplayName("o modelo herdado da plataforma também é conferido")
    void modeloDaPlataformaEConferido() {
        factory.setPlatformDefaultLLM(ResolvedLLMConfig.builder().provider(PROVIDER).model("m-da-plataforma").build());

        StepResult result = executar(Map.of(), null, "acme");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(falha(result)).contains("m-da-plataforma");
    }

    @Test
    @DisplayName("tenant restrito sem modelo nenhum: falha em vez de ir com o default do adapter")
    void semModeloComRestricaoFalha() {
        StepResult result = executar(Map.of("provider", PROVIDER), null, "acme");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(falha(result)).contains("não diz o modelo");
    }

    @Test
    @DisplayName("outro tenant (sem restrição) e SYSTEM usam qualquer modelo")
    void semRestricaoUsaQualquerModelo() {
        assertThat(executar(Map.of("provider", PROVIDER, "model", "m-caro"), null, "outro").getStatus())
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(executar(Map.of("provider", PROVIDER, "model", "m-caro"), null, "SYSTEM").getStatus())
                .isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    @DisplayName("a conferência é a cada execução: o adapter em cache não escapa de uma restrição nova")
    void restricaoNovaValeParaAdapterEmCache() throws Exception {
        List<String> permitidos = new java.util.concurrent.CopyOnWriteArrayList<>();
        TenantModelPolicy mutavel = tenantId -> Optional.of(List.copyOf(permitidos));
        var componente = new LangChainAdapterComponent(PROVIDER, "chat", Map.of("model", "m1"),
                TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly(), ProviderPreferences.NONE, false, List.of(),
                mutavel);

        assertThat(componente.execute("generate", "oi", contexto("acme"))).isEqualTo("eco:generate:oi");

        permitidos.add("m2");

        assertThatThrownBy(() -> componente.execute("generate", "oi", contexto("acme")))
                .hasMessageContaining("[LLM-MODELO]").hasMessageContaining("m1");
    }

    @Test
    @DisplayName("o modelo de fallback do nó também precisa estar na lista")
    void fallbackForaDaListaFalha() {
        var componente = new LangChainAdapterComponent(PROVIDER, "chat",
                Map.of("model", "m-permitido", "fallback.base.url", "http://localhost:11434/v1",
                        "fallback.model.name", "llama3.1"),
                TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly(), ProviderPreferences.NONE, false, List.of(),
                POLITICA);

        assertThatThrownBy(() -> componente.execute("generate", "oi", contexto("acme")))
                .hasMessageContaining("llama3.1");
    }
}
