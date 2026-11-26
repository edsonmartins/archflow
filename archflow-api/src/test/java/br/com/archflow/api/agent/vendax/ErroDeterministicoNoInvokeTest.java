package br.com.archflow.api.agent.vendax;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.SaidaDeTexto;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os erros do invoke em fluxo dizem a causa, e só os transitórios se dizem recuperáveis (RCHFLW-190, item 1).
 *
 * <p>Motor, fábrica, deserializer, runner e dispatcher reais. Antes: todo erro saía com
 * {@code recuperavel: true} — o Core repetiria um fluxo que vai ser recusado sempre — e o
 * {@code timeout_ms} vencido chegava como {@code Falha ao executar o fluxo: TimeoutException}.</p>
 */
@DisplayName("erros do invoke em fluxo: causa legível e recuperável só quando pode passar")
class ErroDeterministicoNoInvokeTest {

    /** Um nó que devolve texto, depois de {@code demoraMs}. */
    private static AIComponent eco(String id, long demoraMs) {
        return new AIComponent() {
            public void initialize(Map<String, Object> config) { }
            public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "fixture", ComponentType.TOOL, "1.0.0", Set.of(), List.of(),
                        Map.of(), Set.of());
            }
            public Object execute(String op, Object in, ExecutionContext ctx) throws Exception {
                Thread.sleep(demoraMs);
                return Map.of("text", "{\"ok\":true}");
            }
            public void shutdown() { }
        };
    }

    private static Map<String, Object> no(String id, String... alvos) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", id.startsWith("lento") ? "lento" : "eco");
        node.put("componentId", id.startsWith("lento") ? "lento" : "eco");
        node.put("configuration", Map.of());
        node.put("connections", java.util.Arrays.stream(alvos).map(a -> Map.of("targetId", a)).toList());
        return node;
    }

    private static VendaxAgentDispatcher.ResultadoEmFluxo executar(List<Map<String, Object>> passos,
                                                                  Map<String, Object> politica) {
        var catalogo = new DefaultComponentCatalog();
        catalogo.register(eco("eco", 0));
        catalogo.register(eco("lento", 2_000));
        var repo = new InMemoryFlowRepository();
        var engine = FlowEngineFactory.create(repo);
        var beans = new StaticListableBeanFactory();
        beans.addBean("engine", engine);
        var fabrica = new DefaultFlowStepFactory(catalogo, null, null, null, null);
        var runner = new AgentFlowRunner(new DefaultWorkflowDeserializer(fabrica),
                beans.getBeanProvider(br.com.archflow.engine.api.FlowEngine.class), repo, null);
        Map<String, Object> documento = Map.of("id", "qa2", "metadata", Map.of("name", "QA2"), "steps", passos);
        var invoke = new VendaxInvoke("1.0", "tenant-teste", "conversa-teste", "QA2", "m1", "oi", "LIGHT",
                "teste", "trace-teste", null, null, null, "qa2:1", null,
                new DefinicaoDeAgente("FLUXO", documento, null, "manual@1", List.of(), Map.of(), politica,
                        "qa2@1"));
        var sender = new VendaxResultSender("http://nao-chamar.invalid", "segredo-sintetico") {
            @Override public void send(VendaxResult r, String trace) { }
            @Override public void relatarUso(VendaxUsoRelato relato) { }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return new VendaxAgentDispatcher(null, null, null, sender, executor, null, runner)
                    .executarEmFluxo(invoke, SaidaDeTexto.soCancelamento(new CompletableFuture<>()),
                            new ContadorDeUso());
        }
    }

    private static void recusadoSemRepetir(VendaxAgentDispatcher.ResultadoEmFluxo feito) {
        assertThat(feito.resultado().status()).isEqualTo(VendaxResult.ERROR);
        assertThat(feito.resultado().error())
                .startsWith("Falha ao executar o fluxo: ")
                .as("o motivo do motor, não o invólucro com o uuid")
                .doesNotContain("Error executing flow");
        assertThat(feito.recuperavel()).as("antes: true — o mesmo documento é recusado de novo").isFalse();
    }

    @Test
    @DisplayName("aresta para um passo que não existe: motivo legível e recuperavel=false")
    void arestaParaPassoInexistente() {
        var feito = executar(List.of(no("eco", "fantasma")), Map.of());
        recusadoSemRepetir(feito);
        assertThat(feito.resultado().error()).contains("fantasma");
    }

    @Test
    @DisplayName("ciclo a→b→a: motivo legível e recuperavel=false")
    void ciclo() {
        Map<String, Object> a = no("eco", "eco-b");
        Map<String, Object> b = no("eco-b", "eco");
        b.put("type", "eco");
        b.put("componentId", "eco");
        recusadoSemRepetir(executar(List.of(a, b), Map.of()));
    }

    @Test
    @DisplayName("fluxo sem passos: motivo legível e recuperavel=false")
    void semPassos() {
        recusadoSemRepetir(executar(List.of(), Map.of()));
    }

    @Test
    @DisplayName("timeout_ms vencido: o texto diz o prazo, e segue recuperável (pode passar na próxima)")
    void timeoutDaPolitica() {
        var feito = executar(List.of(no("lento")), Map.of("timeout_ms", 300));

        assertThat(feito.resultado().error())
                .as("antes: Falha ao executar o fluxo: TimeoutException")
                .isEqualTo("Falha ao executar o fluxo: não terminou em 300 ms (politica.timeout_ms)");
        assertThat(feito.recuperavel()).isTrue();
    }
}
