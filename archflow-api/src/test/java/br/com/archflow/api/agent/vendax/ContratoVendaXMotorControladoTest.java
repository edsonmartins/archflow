package br.com.archflow.api.agent.vendax;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

/** Motor, factory, deserializer, runner e dispatcher reais. Componente final determinístico; sem LLM/MCP/HTTP. */
class ContratoVendaXMotorControladoTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBJETO = "{\"valor\":1}";

    private VendaxResult executar(String texto) throws Exception {
        var catalogo = new DefaultComponentCatalog();
        catalogo.register(new AIComponent() {
            public void initialize(Map<String,Object> config) {}
            public ComponentMetadata getMetadata() {
                return new ComponentMetadata("saida-controlada", "Saída", "Fixture sem provedor", ComponentType.TOOL,
                        "1.0.0", Set.of(), List.of(), Map.of(), Set.of());
            }
            public Object execute(String op, Object in, ExecutionContext ctx) {
                return Map.of("text", texto, "toolCalls", List.of(Map.of("name", "terminal_controlada",
                        "arguments", Map.of("valor", 850), "isError", false)));
            }
            public void shutdown() {}
        });
        var repo = new InMemoryFlowRepository();
        var engine = FlowEngineFactory.create(repo);
        var beans = new StaticListableBeanFactory(); beans.addBean("engine", engine);
        var deserializer = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(catalogo, null, null, null, null));
        var runner = new AgentFlowRunner(deserializer, beans.getBeanProvider(br.com.archflow.engine.api.FlowEngine.class), repo, null);
        var resultados = new ArrayList<VendaxResult>();
        var sender = new VendaxResultSender("http://nao-chamar.invalid", "segredo-sintetico") {
            @Override public void send(VendaxResult r, String trace) { resultados.add(r); }
        };
        var documento = Map.<String,Object>of("id", "controle", "metadata", Map.of("name", "Controle"),
                "configuration", Map.of("allowedComponents", List.of("saida-controlada")),
                "steps", List.of(Map.of("id", "final", "type", "TOOL", "componentId", "saida-controlada")));
        var invoke = new VendaxInvoke("1.0", "tenant-teste", "conversa-teste", "AGENTE_CONTROLADO", "m1", "entrada",
                "LIGHT", "contrato", "trace-teste", null, null, null, "controle:1", null,
                new DefinicaoDeAgente("FLUXO", documento, null, "controle@1", List.of(), Map.of(), Map.of(), "controle@1"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new VendaxAgentDispatcher(null, null, null, sender, executor, null, runner).runAndReport(invoke);
        }
        assertThat(resultados).hasSize(1);
        VendaxResult r = resultados.get(0);
        String arquivo = System.getProperty("ven557.resultados");
        if (arquivo != null) {
            Files.writeString(Path.of(arquivo), JSON.writeValueAsString(Map.of("entrada", texto, "callback", r)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"texto {\"valor\":1}", "{\"valor\":1} texto", "{\"valor\":1} {}",
            "{\"valor\":1,}", "{\"valor\":1,\"valor\":2}", "{\"valor\":{}} lixo",
            "```json\n{\"valor\":1}", "[]", "{\"valor\":1"})
    void invalidoNaoViraJsonParcial(String saida) throws Exception {
        var r = executar(saida);
        assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(r.richObject()).isNull();
        assertThat(r.toolCalls()).singleElement().satisfies(t -> assertThat(t.arguments()).containsEntry("valor", 850));
    }

    @Test void cercaCompletaENativoPreservamSaidaETrilha() throws Exception {
        for (String saida : List.of(OBJETO, "```json\n" + OBJETO + "\n```", "```\n" + OBJETO + "\n```")) {
            var r = executar(saida);
            assertThat(r.status()).isEqualTo(VendaxResult.OK);
            assertThat(r.richObject()).isEqualTo(OBJETO);
            assertThat(r.toolCalls()).hasSize(1);
            assertThat(r.idempotencyKey()).isEqualTo("controle:1");
            assertThat(r.uso()).as("nenhum modelo foi chamado").isNull();
        }
    }

    @Test void saidaNaoEstruturadaFicaDiagnosticoSemVirarMensagem() throws Exception {
        var r = executar("SEM_SUGESTAO");
        assertThat(r.status()).isEqualTo(VendaxResult.ERROR);
        assertThat(r.richObject()).isNull();
        assertThat(JSON.valueToTree(r).path("outputText").asText()).isEqualTo("SEM_SUGESTAO");
        assertThat(r.error()).doesNotContain("SEM_SUGESTAO");
    }
}
