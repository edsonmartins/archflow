package br.com.archflow.api.linktor.publisher;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.TraceStoreRecorder;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.linktor.LinktorFalso;
import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl;
import br.com.archflow.api.usage.InMemoryRepositorioDeUso;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Um fluxo executado com {@code conversationId} publica a resposta na conversa do Linktor (RCHFLW-112).
 *
 * <p>Na homologação o fluxo terminava COMPLETED e nada chegava ao Linktor, sem log. Três causas, cada
 * uma suficiente para o silêncio:</p>
 * <ol>
 *   <li><b>Ordem dos beans.</b> O publicador se registrava no {@link FlowLifecycleListeners} ao nascer,
 *   e o {@code flowEngine} — declarado antes, sem depender dele — fotografava o registro ao ser criado.
 *   Por isso o motor aqui sai do bean de verdade: as definições são registradas na ordem em que o
 *   Spring as cria em produção ({@code flowEngine} antes de {@code linktorFlowPublisher}).</li>
 *   <li><b>{@code Optional}.</b> {@code ExecutionContext.get} devolve {@code Optional}, e o publicador
 *   testava o {@code Optional} com {@code instanceof String}: nunca casava.</li>
 *   <li><b>Tenant.</b> O motor roda fora de requisição, onde a configuração lida era a do tenant
 *   padrão — não a que o dono da execução salvou em /admin/linktor.</li>
 * </ol>
 *
 * <p>A entrada passa pela mesma {@link RunInputSeeding} do {@code /execute} e do Run do editor, e o
 * Linktor é um servidor HTTP local: o que se afirma é a requisição que ele recebeu.</p>
 */
@DisplayName("Fluxo com conversationId publica no Linktor")
class PublicacaoDoFluxoNoLinktorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESPOSTA = "Olá, Maria! Seu pedido saiu para entrega.";

    private LinktorFalso linktor;
    private Set<FlowLifecycleListener> registradosAntes;
    private AnnotationConfigApplicationContext spring;
    private InMemoryFlowRepository flowRepository;
    private int execucoes;

    @BeforeEach
    void subir() throws IOException {
        linktor = new LinktorFalso();
        linktor.responde("POST", "/conversations/conv-1/messages", 201,
                "{\"success\":true,\"data\":{\"id\":\"m-1\",\"sender_type\":\"user\"}}");
        registradosAntes = new HashSet<>(FlowLifecycleListeners.snapshot());
    }

    @AfterEach
    void descer() {
        // O registro é do processo: o que o bean registrou não pode vazar para os outros testes.
        FlowLifecycleListeners.snapshot().stream()
                .filter(l -> !registradosAntes.contains(l))
                .forEach(FlowLifecycleListeners::unregister);
        if (spring != null) {
            spring.close();
        }
        RequestContextHolder.resetRequestAttributes();
        linktor.close();
    }

    /** O motor como o Spring o monta: definições de fábrica sobre a configuração real, na ordem real. */
    private FlowEngine motor(LinktorConfigControllerImpl config) {
        spring = new AnnotationConfigApplicationContext();
        spring.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
        var beans = spring.getBeanFactory();
        // Instância, e não definição: assim o processador de @Configuration não carrega a classe inteira,
        // e só os três beans abaixo saem dela.
        beans.registerSingleton("archflowBeanConfiguration", new ArchflowBeanConfiguration());
        flowRepository = new InMemoryFlowRepository();
        beans.registerSingleton("flowRepository", flowRepository);
        beans.registerSingleton("eventStreamRegistry", new EventStreamRegistry());
        beans.registerSingleton("runningFlowsRegistry", new RunningFlowsRegistry());
        beans.registerSingleton("stateManager", new InMemoryStateManager());
        beans.registerSingleton("workflowRuntimeStore", new InMemoryWorkflowRuntimeStore());
        beans.registerSingleton("metricsCollector", new MetricsCollector(AgentConfig.builder().build()));
        beans.registerSingleton("traceStoreRecorder", new TraceStoreRecorder(new InMemoryTraceStore()));
        beans.registerSingleton("registroDeUso",
                new RegistroDeUso(new InMemoryRepositorioDeUso(), TabelaDePrecos.vazia()));
        beans.registerSingleton("linktorConfigController", config);
        // A ordem de ArchflowBeanConfiguration: flowEngine é declarado antes do publicador.
        spring.registerBeanDefinition("flowEngine", fabrica("flowEngine"));
        spring.registerBeanDefinition("linktorHttpClient", fabrica("linktorHttpClient"));
        spring.registerBeanDefinition("linktorFlowPublisher", fabrica("linktorFlowPublisher"));
        spring.refresh();
        return spring.getBean(FlowEngine.class);
    }

    private static RootBeanDefinition fabrica(String metodo) {
        RootBeanDefinition definicao = new RootBeanDefinition();
        definicao.setFactoryBeanName("archflowBeanConfiguration");
        definicao.setFactoryMethodName(metodo);
        // O que o leitor de @Bean faz: os parâmetros do método são resolvidos no contexto.
        definicao.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        return definicao;
    }

    private LinktorConfigDto ligadoNoFalso() {
        return new LinktorConfigDto(true, linktor.baseUrl(), LinktorFalso.CHAVE, "", "", 5);
    }

    private FlowResult executar(FlowEngine engine, String tenant, Map<String, Object> entrada) throws Exception {
        return executar(engine, tenant, entrada, List.of(passoQueResponde()));
    }

    private FlowResult executar(FlowEngine engine, String tenant, Map<String, Object> entrada,
                                List<FlowStep> passos) throws Exception {
        String executionId = "exec-" + (++execucoes);
        Flow flow = new SimpleFlow(executionId,
                new FlowMetadata("Responde no Linktor", "", "1.0.0", null, null, List.of()),
                passos);
        flowRepository.save(flow);
        ExecutionContext ctx = new DefaultExecutionContext(tenant, "runner", executionId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        RunInputSeeding.seedVariables(ctx, entrada);
        // O publicador roda no fim da travessia, antes de o futuro completar.
        return engine.execute(flow, ctx).get(10, TimeUnit.SECONDS);
    }

    private static FlowStep passoQueResponde() {
        return passo("resposta", c -> SimpleStepResult.ok("resposta", RESPOSTA, 1));
    }

    /** Um rascunho deixado no contexto e na saída — o que um passo de LLM no meio do fluxo produz. */
    private static FlowStep passoQueRascunha() {
        return passo("rascunho", c -> {
            c.set(LinktorFlowPublisher.CTX_REPLY_TEXT, RESPOSTA);
            return SimpleStepResult.ok("rascunho", RESPOSTA, 1);
        });
    }

    private static FlowStep passo(String id, java.util.function.Function<ExecutionContext, StepResult> corpo) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(corpo.apply(c));
            }
        };
    }

    private void assertPublicou() throws IOException {
        assertThat(linktor.recebidas()).singleElement().satisfies(r -> {
            assertThat(r.metodo()).isEqualTo("POST");
            assertThat(r.caminho()).isEqualTo("/conversations/conv-1/messages");
            assertThat(JSON.readTree(r.corpo())).isEqualTo(JSON.readTree(
                    JSON.writeValueAsString(Map.of("content", RESPOSTA, "content_type", "text"))));
        });
    }

    @Test
    @DisplayName("o motor do bean publica a saída do fluxo na conversa")
    void motorDoBeanPublica() throws Exception {
        FlowEngine engine = motor(new LinktorConfigControllerImpl(ligadoNoFalso()));

        FlowResult result = executar(engine, null, Map.of("input", "oi", "conversationId", "conv-1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertPublicou();
    }

    @Test
    @DisplayName("usa a configuração do tenant da execução, salva em /admin/linktor")
    void usaConfiguracaoDoTenantDaExecucao() throws Exception {
        // A instalação sobe com o Linktor desligado; só o tenant acme o ligou pela tela.
        LinktorConfigControllerImpl config = new LinktorConfigControllerImpl(
                new LinktorConfigDto(false, "http://localhost:1/api/v1", "", "", "", 5));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, "acme");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        config.update(ligadoNoFalso());
        RequestContextHolder.resetRequestAttributes();
        FlowEngine engine = motor(config);

        executar(engine, "acme", Map.of("input", "oi", "conversationId", "conv-1"));

        assertPublicou();
    }

    @Test
    @DisplayName("sem conversationId, ou com linktor.autoPublish=false, nada é publicado")
    void semConversaNaoPublica() throws Exception {
        FlowEngine engine = motor(new LinktorConfigControllerImpl(ligadoNoFalso()));

        executar(engine, null, Map.of("input", "oi"));
        executar(engine, null, Map.of("input", "oi", "conversationId", "conv-1",
                LinktorFlowPublisher.CTX_AUTO_PUBLISH, false));

        assertThat(linktor.recebidas()).isEmpty();
    }

    @Test
    @DisplayName("fluxo pausado, cancelado ou com falha não publica o rascunho do passo anterior")
    void soOFluxoConcluidoPublica() throws Exception {
        FlowEngine engine = motor(new LinktorConfigControllerImpl(ligadoNoFalso()));
        Map<String, Object> entrada = Map.of("input", "oi", "conversationId", "conv-1");

        // Pausa (o portão de aprovação suspende assim): a saída do passo é o texto, e a retomada
        // publicaria de novo — o cliente receberia a mesma resposta duas vezes.
        FlowResult pausado = executar(engine, null, entrada, List.of(passoQueRascunha(),
                passo("aprovacao", c -> SimpleStepResult.suspended("aprovacao", RESPOSTA, 1))));
        // Cancelamento enquanto o segundo passo roda (o cliente AG-UI saiu, ou POST .../cancel).
        FlowResult cancelado = executar(engine, null, entrada, List.of(passoQueRascunha(),
                passo("segundo", c -> {
                    // O id do fluxo é o da execução em curso (executar já incrementou o contador).
                    engine.cancel("exec-" + execucoes);
                    return SimpleStepResult.ok("segundo", RESPOSTA, 1);
                })));
        FlowResult falhou = executar(engine, null, entrada, List.of(passoQueRascunha(),
                passo("quebra", c -> SimpleStepResult.failed("quebra", "boom", 1))));

        assertThat(pausado.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(cancelado.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(falhou.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(linktor.recebidas()).isEmpty();
    }

    @Test
    @DisplayName("Linktor fora do ar não derruba o fluxo — a falha fica no log")
    void falhaDoLinktorNaoDerrubaOFluxo() throws Exception {
        linktor.responde("POST", "/conversations/conv-1/messages", 500,
                "{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
        FlowEngine engine = motor(new LinktorConfigControllerImpl(ligadoNoFalso()));

        FlowResult result = executar(engine, null, Map.of("input", "oi", "conversationId", "conv-1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(linktor.recebidas()).hasSize(1);
    }
}
