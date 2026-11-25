package br.com.archflow.api.flow;

import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Analysis;
import br.com.archflow.model.ai.domain.Response;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import br.com.archflow.plugins.agents.ConversationalAgent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-15: os componentes embutidos do catálogo nunca rodavam de um nó. Três defeitos em fila — o
 * catálogo registrava instâncias sem {@code initialize} ("not initialized"), o nó sem operação usava
 * {@code "execute"}, que nenhum deles declara ("Unsupported operation"), e o mapa da entrada do
 * Executar chegava a plugins que fazem {@code (String) input} ({@code ClassCastException}).
 *
 * <p>Os testes usam o catálogo real ({@link ArchflowBeanConfiguration#componentCatalog}), o
 * {@link DefaultFlowStepFactory} e o {@link ComponentStep} de verdade, e semeiam a entrada como o
 * Executar do designer faz ({@link RunInputSeeding}).</p>
 */
@DisplayName("Componentes embutidos executam de um nó e de uma invocação")
class BuiltInComponentsExecuteTest {

    private static final String MENSAGEM = "minha impressora não liga";

    private ComponentCatalog catalog;
    private DefaultFlowStepFactory factory;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        catalog = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        factory = new DefaultFlowStepFactory(catalog, mock(DynamicWorkflowService.class), null, null, null);
    }

    private static ExecutionContext contexto(Map<String, Object> entrada) {
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        RunInputSeeding.seedVariables(ctx, new HashMap<>(entrada));
        return ctx;
    }

    private StepResult executar(Map<String, Object> node, Map<String, Object> entrada) {
        return factory.create(node).execute(contexto(entrada)).join();
    }

    private static Map<String, Object> no(String componentId) {
        return Map.of("id", "s1", "type", "TOOL", "componentId", componentId);
    }

    private static Map<String, Object> no(String componentId, Map<String, Object> config) {
        return Map.of("id", "s1", "type", "TOOL", "componentId", componentId, "config", config);
    }

    static Stream<String> embutidos() {
        return Stream.of("conversational-agent", "research-agent", "data-analysis-agent",
                "monitoring-agent", "tech-support-assistant", "text-transform-tool");
    }

    static Stream<Arguments> semPadraoDeclarado() {
        return Stream.of(
                Arguments.of("research-agent", null),
                Arguments.of("data-analysis-agent", null),
                Arguments.of("monitoring-agent", null),
                Arguments.of("tech-support-assistant", null),
                Arguments.of("text-transform-tool", "uppercase, lowercase, reverse, wordcount"));
    }

    @Test
    @DisplayName("nó sem operação roda a operação padrão que o componente declara (conversational-agent)")
    void noSemOperacaoRodaOPadraoDeclarado() {
        StepResult result = executar(no("conversational-agent"), Map.of("message", MENSAGEM));

        assertThat(result.getStatus()).as("%s", result.getOutput()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).get().isInstanceOf(Response.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semPadraoDeclarado")
    @DisplayName("nó sem operação num componente sem padrão declarado falha pedindo a operação, sem adivinhar")
    void noSemOperacaoSemPadraoPedeAOperacao(String componentId, String operacoes) {
        StepResult result = executar(no(componentId), Map.of("message", MENSAGEM));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).get().asString()
                .contains("nenhuma operação informada e " + componentId + " não declara uma operação padrão; "
                        + "informe a operação: ");
        if (operacoes != null) {
            assertThat(result.getOutput()).get().asString().contains(operacoes);
        }
    }

    @Test
    @DisplayName("o texto vem do campo message, não do toString do mapa")
    void textTransformUsaOTextoDaMensagem() {
        StepResult result = executar(no("text-transform-tool", Map.of("operation", "uppercase")),
                Map.of("message", MENSAGEM));

        // Antes saía "{MESSAGE=MINHA ...}".
        assertThat(result.getOutput()).contains("MINHA IMPRESSORA NÃO LIGA");
    }

    static Stream<Arguments> operacoesDeclaradas() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        ComponentCatalog catalogo = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        return embutidos().flatMap(id -> catalogo.getComponent(id).orElseThrow().getMetadata()
                .operations().stream().map(op -> Arguments.of(id, op.id())));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("operacoesDeclaradas")
    @DisplayName("toda operação declarada roda a partir de um nó com a entrada do Executar")
    void todaOperacaoDeclaradaRoda(String componentId, String operation) {
        StepResult result = executar(no(componentId, Map.of("operation", operation)),
                Map.of("message", MENSAGEM));

        assertThat(result.getStatus()).as("%s.%s: %s", componentId, operation, result.getOutput())
                .isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    @DisplayName("analyze → respond encadeados: o respond recebe a análise do passo anterior")
    void analiseEncadeadaComResposta() {
        ExecutionContext ctx = contexto(Map.of("message", "I forgot my password and cannot login"));
        FlowStep analyze = factory.create(Map.of("id", "a", "componentId", "tech-support-assistant",
                "config", Map.of("operation", "analyze")));
        FlowStep respond = factory.create(Map.of("id", "r", "componentId", "tech-support-assistant",
                "config", Map.of("operation", "respond")));

        analyze.execute(ctx).join();
        StepResult result = respond.execute(ctx).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        Response response = (Response) result.getOutput().orElseThrow();
        assertThat(response.metadata()).containsEntry("intent", "password_reset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "execute"})
    @DisplayName("operação explícita que o componente não tem falha listando as que ele tem")
    void operacaoInexistenteListaAsValidas(String operation) {
        StepResult result = executar(no("text-transform-tool", Map.of("operation", operation)),
                Map.of("message", MENSAGEM));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).get().asString().contains(
                "operação '" + operation + "' não existe em text-transform-tool; "
                        + "operações: uppercase, lowercase, reverse, wordcount");
    }

    @Test
    @DisplayName("config inválida não quebra ao abrir o fluxo; falha ao executar, dizendo o porquê")
    void configInvalidaFalhaNaExecucao() {
        Map<String, Object> node = no("conversational-agent", Map.of("escalationThreshold", 5));

        assertThatCode(() -> factory.create(node)).doesNotThrowAnyException();
        StepResult result = factory.create(node).execute(contexto(Map.of("message", MENSAGEM))).join();

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).get().asString()
                .contains("configuração inválida para conversational-agent")
                .contains("escalationThreshold");
    }

    @Test
    @DisplayName("cada nó vê a própria config, e o singleton do catálogo não acumula conversa")
    void instanciaIsoladaPorExecucao() {
        Map<String, Object> entrada = Map.of("message", "can you help me");
        Response tolerante = (Response) executar(no("conversational-agent",
                Map.of("escalationThreshold", 0.5)), entrada).getOutput().orElseThrow();
        Response exigente = (Response) executar(no("conversational-agent",
                Map.of("escalationThreshold", 0.9)), entrada).getOutput().orElseThrow();

        // "support" tem confiança 0.75: abaixo de 0.9 escala, acima de 0.5 não.
        assertThat(tolerante.metadata()).doesNotContainKey("escalated");
        assertThat(exigente.metadata()).containsEntry("escalated", true);
        // O histórico da conversa é por execução: o singleton compartilhado entre fluxos e tenants
        // não pode guardar a mensagem de ninguém.
        ConversationalAgent singleton =
                (ConversationalAgent) catalog.getComponent("conversational-agent").orElseThrow();
        assertThat(singleton.getConversationContext()).isEmpty();
    }

    @Test
    @DisplayName("o escopo do fluxo continua valendo para os componentes do catálogo")
    void escopoDoFluxo() {
        ComponentAccessPolicy policy = ComponentAccessPolicy.allowOnly(List.of("text-transform-tool"));
        ExecutionContext ctx = contexto(Map.of("message", MENSAGEM));

        StepResult permitido = factory.create(no("text-transform-tool", Map.of("operation", "uppercase")), policy)
                .execute(ctx).join();
        StepResult negado = factory.create(no("conversational-agent"), policy).execute(ctx).join();

        assertThat(permitido.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(negado.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(negado.getOutput()).get().asString().contains("component not found: conversational-agent");
    }

    @Test
    @DisplayName("invocação direta (payload da fila, sem operação) roda o componente com padrão declarado")
    void invocacaoDireta() throws Exception {
        // O que o AgentControllerImpl enfileira: o payload do Agent lab com o sessionId acrescentado.
        Map<String, Object> payload = Map.of("message", MENSAGEM, "sessionId", "sess-1");
        AIComponent registrado = catalog.getComponent("conversational-agent").orElseThrow();

        Object output = CatalogComponents.invoke(registrado, null, Map.of(), payload, contextoDaInvocacao());

        assertThat(output).isInstanceOf(Response.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("embutidos")
    @DisplayName("invocação direta com a operação no payload roda qualquer embutido")
    void invocacaoDiretaComOperacao(String componentId) throws Exception {
        AIComponent registrado = catalog.getComponent(componentId).orElseThrow();
        String operacao = registrado.getMetadata().operations().get(0).id();
        Map<String, Object> payload = Map.of("message", MENSAGEM, "sessionId", "sess-1");

        Object output = CatalogComponents.invoke(registrado, operacao, Map.of(), payload, contextoDaInvocacao());

        assertThat(output).as(componentId).isNotNull();
        if (output instanceof Result r) {
            assertThat(r.success()).as("%s: %s", componentId, r).isTrue();
        }
        if ("text-transform-tool".equals(componentId)) {
            assertThat(output).isEqualTo("MINHA IMPRESSORA NÃO LIGA");
        }
    }

    @Test
    @DisplayName("invocação sem operação de um embutido sem padrão falha pedindo a operação")
    void invocacaoSemOperacaoSemPadrao() {
        AIComponent registrado = catalog.getComponent("tech-support-assistant").orElseThrow();
        Map<String, Object> payload = Map.of("message", MENSAGEM, "sessionId", "sess-1");

        assertThatThrownBy(() -> CatalogComponents.invoke(registrado, null, Map.of(), payload, contextoDaInvocacao()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nenhuma operação informada e tech-support-assistant");
    }

    private static ExecutionContext contextoDaInvocacao() {
        return new DefaultExecutionContext("acme", "u", "sess-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Test
    @DisplayName("os embutidos entram no catálogo já inicializados (uso direto, sem passar pelo nó)")
    void embutidosInicializadosNoRegistro() {
        // Quem usa o singleton direto (CatalogAgentWorker chama executeTask) não pode receber
        // "not initialized".
        assertThatCode(() -> catalog.getComponent("text-transform-tool").orElseThrow()
                .execute("uppercase", "ok", null)).doesNotThrowAnyException();
        assertThatCode(() -> catalog.getComponent("conversational-agent").orElseThrow()
                .execute("analyzeRequest", "", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("plugins do diretório entram inicializados; um que exige config não derruba a subida")
    void pluginsDoDiretorioInicializados() {
        ComponentCatalog doManager = new br.com.archflow.plugin.api.catalog.DefaultComponentCatalog();
        doManager.register(new br.com.archflow.plugins.tools.TextTransformTool());
        doManager.register(new ExigeConfig());
        ArchflowPluginManager manager = mock(ArchflowPluginManager.class);
        org.mockito.Mockito.when(manager.getCatalog()).thenReturn(doManager);
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> comPlugins = mock(ObjectProvider.class);
        org.mockito.Mockito.when(comPlugins.getIfAvailable()).thenReturn(manager);

        ComponentCatalog app = new ArchflowBeanConfiguration().componentCatalog(comPlugins);

        assertThat(app.getComponent(ExigeConfig.ID)).as("falhar o initialize não tira do catálogo").isPresent();
        assertThatCode(() -> app.getComponent("text-transform-tool").orElseThrow()
                .execute("uppercase", "ok", null)).doesNotThrowAnyException();
        // O nó entrega a própria config ao initialize da instância por execução.
        StepResult result = new DefaultFlowStepFactory(app, mock(DynamicWorkflowService.class), null, null, null)
                .create(no(ExigeConfig.ID, Map.of("token", "t-1", "operation", "run"))).execute(contexto(Map.of("message", "x")))
                .join();
        assertThat(result.getOutput()).contains("t-1:x");
    }

    /** Plugin externo que só inicializa com {@code token} — como um que precisa de credencial. */
    public static class ExigeConfig implements AIComponent, br.com.archflow.plugin.api.spi.ComponentPlugin {
        static final String ID = "exige-config";
        private String token;

        @Override
        public void initialize(Map<String, Object> config) {
            validateConfig(config);
            token = config.get("token").toString();
        }

        @Override
        public void validateConfig(Map<String, Object> config) {
            if (config.get("token") == null) {
                throw new IllegalArgumentException("token é obrigatório");
            }
        }

        @Override
        public br.com.archflow.model.ai.metadata.ComponentMetadata getMetadata() {
            return new br.com.archflow.model.ai.metadata.ComponentMetadata(ID, ID, "teste",
                    br.com.archflow.model.ai.type.ComponentType.TOOL, "1.0.0", java.util.Set.of(),
                    List.of(new br.com.archflow.model.ai.metadata.ComponentMetadata.OperationMetadata(
                            "run", "Run", "",
                            List.of(new br.com.archflow.model.ai.metadata.ComponentMetadata.ParameterMetadata(
                                    "text", "string", "", true)),
                            List.of())),
                    Map.of(), java.util.Set.of());
        }

        @Override
        public Object execute(String operation, Object input, ExecutionContext context) {
            if (token == null) {
                throw new IllegalStateException("not initialized");
            }
            return token + ":" + input;
        }

        @Override
        public void shutdown() {
            token = null;
        }
    }
}
