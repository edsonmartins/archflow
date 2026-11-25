package br.com.archflow.api.flow;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import br.com.archflow.plugin.api.spi.ComponentPlugin;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-15, segunda rodada: o que a revisão apontou na instância por execução dos componentes do
 * catálogo — ciclo de vida de plugin incompleto ({@code onLoad}/{@code onUnload}), operação inexistente
 * descoberta só depois de rodar o componente, chaves do designer chegando ao {@code validateConfig} e
 * {@code Result.failure} terminando como sucesso.
 */
@DisplayName("Ciclo de vida, operação e saída dos componentes do catálogo")
class CatalogComponentsLifecycleTest {

    private ComponentCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new DefaultComponentCatalog();
        Ciclo.EVENTOS.clear();
        Ciclo.INSTANCIAS.set(0);
    }

    private static ExecutionContext contexto(Map<String, Object> entrada) {
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        RunInputSeeding.seedVariables(ctx, new HashMap<>(entrada));
        return ctx;
    }

    private StepResult executar(ToolInterceptorChain interceptors, Map<String, Object> config) {
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class),
                null, null, null, interceptors);
        return factory.create(Map.of("id", "s1", "type", "TOOL", "componentId", Ciclo.ID, "config", comOperacao(config)))
                .execute(contexto(Map.of("message", "oi"))).join();
    }

    @Test
    @DisplayName("a instância por execução recebe onLoad antes do initialize e onUnload depois do shutdown")
    void cicloDeVidaCompletoDePlugin() {
        catalog.register(new Ciclo());
        Ciclo.EVENTOS.clear();

        StepResult result = executar(null, Map.of("token", "t-1"));

        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        // O jar externo que prepara recursos no onLoad não pode receber uma cópia sem eles.
        assertThat(result.getOutput()).contains("pronto:t-1:oi");
        assertThat(Ciclo.EVENTOS).containsExactly("onLoad", "initialize", "execute", "shutdown", "onUnload");
    }

    @Test
    @DisplayName("operação que o plugin não declara falha antes de instanciar e de passar pelos interceptors")
    void operacaoNaoDeclaradaFalhaAntesDeRodar() {
        catalog.register(new Ciclo());
        Ciclo.EVENTOS.clear();
        int instanciasAntes = Ciclo.INSTANCIAS.get();
        AtomicInteger interceptado = new AtomicInteger();
        ToolInterceptorChain interceptors = ToolInterceptorChain.builder()
                .addInterceptor(new ToolInterceptor() {
                    @Override
                    public void beforeExecute(ToolContext context) {
                        interceptado.incrementAndGet();
                    }
                })
                .build();

        StepResult result = executar(interceptors, Map.of("operation", "foo", "token", "t-1"));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).get().asString()
                .isEqualTo("operação 'foo' não existe em " + Ciclo.ID + "; operações: run");
        assertThat(Ciclo.INSTANCIAS.get()).as("nenhuma instância criada").isEqualTo(instanciasAntes);
        assertThat(Ciclo.EVENTOS).as("nada do componente rodou").isEmpty();
        assertThat(interceptado.get()).as("métricas/log não registram uma execução que não houve").isZero();
    }

    @Test
    @DisplayName("componente que não é plugin: operação não listada ainda chega a ele, e o erro dele fica intacto")
    void componenteManualNaoTemOErroReescrito() throws Exception {
        AIComponent manual = new AIComponent() {
            @Override
            public void initialize(Map<String, Object> config) {
            }

            @Override
            public ComponentMetadata getMetadata() {
                return metadata("manual", "listada");
            }

            @Override
            public Object execute(String operation, Object input, ExecutionContext context) {
                if ("extra".equals(operation)) {
                    throw new IllegalArgumentException("campo 'x' obrigatório");
                }
                return operation + ":" + input;
            }

            @Override
            public void shutdown() {
            }
        };

        assertThat(CatalogComponents.invoke(manual, "oculta", Map.of(), "a", (ExecutionContext) null))
                .isEqualTo("oculta:a");
        try {
            CatalogComponents.invoke(manual, "extra", Map.of(), "a", (ExecutionContext) null);
            throw new AssertionError("deveria falhar");
        } catch (IllegalArgumentException e) {
            // "Entrada inválida" do componente não vira "operação não existe".
            assertThat(e.getMessage()).isEqualTo("campo 'x' obrigatório");
        }
    }

    @Test
    @DisplayName("chaves do motor/designer não chegam ao initialize do plugin")
    void chavesReservadasNaoChegamAoPlugin() {
        catalog.register(new Ciclo());

        StepResult result = executar(null, Map.of("token", "t-1", "operation", "run", "label", "Meu nó",
                "provider", "openai", "model", "gpt-4o", "temperature", "0.7", "agentTypeId", Ciclo.ID));

        // O Ciclo recusa qualquer chave que não seja a dele, como um validateConfig estrito.
        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        assertThat(CatalogComponents.pluginConfig(Map.of("token", "t", "label", "x", "escalationThreshold", 0.5)))
                .containsOnlyKeys("token", "escalationThreshold");
    }

    @Test
    @DisplayName("Result.failure de um agente embutido falha o passo com a mensagem dele")
    void resultadoSemSucessoFalhaOPasso() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        ComponentCatalog real = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(real, mock(DynamicWorkflowService.class),
                null, null, null);

        StepResult result = factory.create(Map.of("id", "s1", "type", "TOOL", "componentId", "research-agent",
                "config", Map.of("operation", "executeTask")))
                .execute(contexto(Map.of("type", "foo", "message", "x"))).join();

        // Antes: COMPLETED com um Result(success=false) dentro — o usuário via sucesso.
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).get().asString()
                .startsWith("research-agent falhou: ")
                .contains("Unsupported task type: foo");
    }

    private static ComponentMetadata metadata(String id, String operation) {
        return new ComponentMetadata(id, id, "teste", ComponentType.TOOL, "1.0.0", Set.of(),
                List.of(new ComponentMetadata.OperationMetadata(operation, operation, "",
                        List.of(new ComponentMetadata.ParameterMetadata("text", "string", "", true)),
                        List.of())),
                Map.of(), Set.of());
    }

    /**
     * Plugin externo que prepara um recurso no {@code onLoad}, exige {@code token} e — como um
     * {@code validateConfig} estrito — recusa chaves que não declara. Registra o ciclo de vida.
     */
    public static class Ciclo implements AIComponent, ComponentPlugin {
        static final String ID = "ciclo";
        static final List<String> EVENTOS = new CopyOnWriteArrayList<>();
        static final AtomicInteger INSTANCIAS = new AtomicInteger();

        private String recurso;
        private String token;

        public Ciclo() {
            INSTANCIAS.incrementAndGet();
        }

        @Override
        public void onLoad(ExecutionContext context) {
            EVENTOS.add("onLoad");
            recurso = "pronto";
        }

        @Override
        public void onUnload() {
            EVENTOS.add("onUnload");
            recurso = null;
        }

        @Override
        public void initialize(Map<String, Object> config) {
            EVENTOS.add("initialize");
            validateConfig(config);
            token = String.valueOf(config.get("token"));
        }

        @Override
        public void validateConfig(Map<String, Object> config) {
            for (String key : config.keySet()) {
                if (!"token".equals(key)) {
                    throw new IllegalArgumentException("chave desconhecida: " + key);
                }
            }
        }

        @Override
        public ComponentMetadata getMetadata() {
            return metadata(ID, "run");
        }

        @Override
        public Object execute(String operation, Object input, ExecutionContext context) {
            EVENTOS.add("execute");
            if (recurso == null) {
                throw new IllegalStateException("recurso não preparado (onLoad não rodou)");
            }
            return recurso + ":" + token + ":" + input;
        }

        @Override
        public void shutdown() {
            EVENTOS.add("shutdown");
        }
    }

    /** O nó informa a operação: o plugin de teste não declara uma padrão (nada é adivinhado). */
    private static Map<String, Object> comOperacao(Map<String, Object> config) {
        Map<String, Object> comOp = new java.util.HashMap<>(config);
        comOp.putIfAbsent("operation", "run");
        return comOp;
    }
}
