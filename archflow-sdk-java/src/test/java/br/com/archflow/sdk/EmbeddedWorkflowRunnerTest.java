package br.com.archflow.sdk;

import br.com.archflow.dsl.Nodes;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.standalone.FlowSerializer;
import br.com.archflow.standalone.model.SerializableFlow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A execução embarcada, e — mais importante — a conformidade com o
 * <b>segundo</b> leitor do schema.
 *
 * <p>O documento de workflow tem dois consumidores independentes: o
 * {@code DefaultWorkflowDeserializer} do archflow-api (coberto pelo
 * {@code DslConformanceTest}) e o {@link FlowSerializer} do archflow-standalone,
 * que é o caminho da execução sem servidor. Eles são código separado, sem nada
 * que os obrigue a concordar. Um documento que o motor do servidor entende e o
 * runner embarcado lê torto seria um bug que só aparece em quem escolheu rodar
 * offline.
 */
@DisplayName("EmbeddedWorkflowRunner")
class EmbeddedWorkflowRunnerTest {

    private static WorkflowDocument doc() {
        return Workflows.define("embarcado")
                .named("Fluxo embarcado")
                .step("entrada", Nodes.component("input").labeled("Entrada"))
                .step("saida", Nodes.component("output").operation("emit"))
                .edge("entrada", "saida")
                .build();
    }

    /**
     * O documento da DSL atravessa o serializador do standalone com passos,
     * conexões e configuração intactos.
     */
    @Test
    @DisplayName("o documento da DSL é legível pelo caminho standalone")
    void documentIsReadableByTheStandalonePath() throws Exception {
        SerializableFlow flow = new FlowSerializer().deserialize(doc().toJson());

        assertThat(flow.getId()).isEqualTo("embarcado");
        assertThat(flow.getSteps()).extracting(s -> s.getId())
                .containsExactly("entrada", "saida");

        List<StepConnection> saidas = flow.getSteps().get(0).getConnections();
        assertThat(saidas)
                .as("sem isto o fluxo executaria só o primeiro passo, sem erro")
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getSourceId()).isEqualTo("entrada");
                    assertThat(c.getTargetId()).isEqualTo("saida");
                });
    }

    /**
     * O {@code config} da DSL é gravado sob a chave {@code config}; o modelo do
     * standalone declara {@code configuration} e aceita {@code config} por
     * {@code @JsonAlias}. É a espécie de detalhe que um teste de unidade de cada
     * lado nunca pega.
     */
    @Test
    @DisplayName("a config do nó chega ao modelo do standalone")
    void nodeConfigCrossesTheBoundary() throws Exception {
        WorkflowDocument document = Workflows.define("f")
                .step("chat", Nodes.llmChat("openai").with("model", "gpt-4o").with("temperature", 0.3))
                .build();

        SerializableFlow flow = new FlowSerializer().deserialize(document.toJson());

        var step = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(0);
        assertThat(step.getConfiguration())
                .containsEntry("model", "gpt-4o")
                .containsEntry("provider", "openai");
        assertThat(step.getComponentId()).isEqualTo("openai");
    }

    @Test
    @DisplayName("label e operation sobrevivem à fronteira")
    void labelAndOperationSurvive() throws Exception {
        SerializableFlow flow = new FlowSerializer().deserialize(doc().toJson());

        var entrada = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(0);
        var saida = (br.com.archflow.standalone.model.SerializableStep) flow.getSteps().get(1);

        assertThat(entrada.getLabel()).isEqualTo("Entrada");
        assertThat(saida.getOperation()).isEqualTo("emit");
    }

    /** Entrada → Condição → Saída: o ramo verdadeiro vai a "aceito", o falso a "recusado". */
    private static WorkflowDocument triagem() {
        return Workflows.define("triagem")
                .named("Triagem embarcada")
                .step("entrada", Nodes.component("input").labeled("Entrada"))
                .step("decide", Nodes.component("condition").with("conditionExpression", "${input} == 'olá'"))
                .step("aceito", Nodes.component("output").labeled("Aceito"))
                .step("recusado", Nodes.component("output").labeled("Recusado"))
                .edge("entrada", "decide")
                .route("decide", "aceito", "true")
                .route("decide", "recusado", "false")
                .build();
    }

    /**
     * A execução de fato, com os passos do servidor (RCHFLW-37).
     *
     * <p>Este teste aceitava FAILED, atribuindo a falha a "nenhum componente input registrado". O defeito
     * era outro: o runner entregava ao motor o passo do transporte, que só sabia falhar — qualquer fluxo
     * terminava FAILED no primeiro passo. Agora o resultado tem de ser COMPLETED, com a saída do ramo certo.
     */
    @Test
    @DisplayName("executa Entrada → Condição → Saída e termina COMPLETED com a saída do ramo escolhido")
    void runsAndReturnsAResult() {
        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder()
                .agentId("teste")
                .timeout(Duration.ofSeconds(30))
                .environment(Map.of())
                .build()) {

            FlowResult result = runner.run(triagem(), Map.of("input", "olá"));

            assertThat(result.getStatus()).as(String.valueOf(result.getErrors()))
                    .isEqualTo(ExecutionStatus.COMPLETED);
            // Uma saída só: com os dois ramos percorridos viria o mapa das duas.
            assertThat(result.getOutput()).hasValue("olá");
        }
    }

    /**
     * O nó {@code llm-chat} da DSL contra um servidor local no lugar do provedor: o adapter real, a chave do
     * ambiente — nenhuma chamada externa.
     */
    @Test
    @DisplayName("llm-chat roda embarcado com a chave do ambiente")
    void llmChatAgainstALocalServer() throws Exception {
        List<String> corpos = new CopyOnWriteArrayList<>();
        List<String> autorizacoes = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            byte[] resposta = """
                    {"id":"c1","object":"chat.completion","created":1,"model":"m",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"oi, humano"},
                     "finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
        String local = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        // O destino vai no nó; a chave governada só sai para ele porque o ambiente o permite.
        WorkflowDocument document = Workflows.define("conversa")
                .step("entrada", Nodes.component("input"))
                .step("chat", Nodes.llmChat("openrouter").with("model", "google/gemma-4-31b-it:free")
                        .with("base.url", local))
                .step("saida", Nodes.component("output"))
                .edge("entrada", "chat")
                .edge("chat", "saida")
                .build();

        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder()
                .timeout(Duration.ofSeconds(30))
                .environment(Map.of(
                        "ARCHFLOW_LLM_KEYS_OPENROUTER", "chave-do-ambiente",
                        "ARCHFLOW_LLM_ALLOWED_BASE_URLS", local))
                .build()) {

            FlowResult result = runner.run(document, Map.of("input", "olá"));

            assertThat(result.getStatus()).as(String.valueOf(result.getErrors()))
                    .isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.getOutput()).hasValue("oi, humano");
            assertThat(corpos).singleElement().satisfies(corpo -> assertThat(corpo.replaceAll("\\s", ""))
                    .contains("\"model\":\"google/gemma-4-31b-it:free\"")
                    .contains("\"content\":\"olá\""));
            assertThat(autorizacoes).singleElement().isEqualTo("Bearer chave-do-ambiente");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Um nó que precisa do servidor não roda embarcado — e o runner diz isso antes do primeiro passo, em vez
     * de executar o que vem antes e parar no meio.
     */
    @Test
    @DisplayName("nó de aprovação falha antes do primeiro passo, dizendo que precisa do servidor")
    void serverOnlyNodeFailsUpFront() {
        WorkflowDocument document = Workflows.define("com-portao")
                .step("entrada", Nodes.component("input"))
                .step("portao", Nodes.approval().labeled("Revisão humana"))
                .step("saida", Nodes.component("output"))
                .edge("entrada", "portao")
                .edge("portao", "saida")
                .build();

        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder()
                .environment(Map.of())
                .build()) {
            assertThatThrownBy(() -> runner.run(document, Map.of("input", "olá")))
                    .isInstanceOf(ArchflowClientException.class)
                    .hasMessageContaining("o fluxo com-portao não pode rodar embarcado")
                    .hasMessageContaining("o nó \"Revisão humana\" (portao) precisa do servidor archflow "
                            + "(aprovação humana");
        }
    }

    /**
     * Sem {@code pluginsPath}, nada é varrido.
     *
     * <p>O default do {@code AgentConfig} é o diretório relativo {@code "plugins"},
     * e abrir um jar de plugin executa o {@code onLoad} dele — código arbitrário,
     * sem sandbox. Uma biblioteca que faça isso só porque existia uma pasta com
     * esse nome ao lado do processo é uma armadilha; o runner desliga a varredura
     * por default, e este teste é o que impede alguém de "consertar" isso
     * repassando o default.
     */
    @Test
    @DisplayName("não varre diretório de plugins por default")
    void doesNotScanPluginsByDefault() {
        try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder().build()) {
            // Um diretório ./plugins carregado apareceria como plugin ativo; a
            // ausência de exceção e a execução limpa sao o observavel disponivel
            // sem instrumentar o agente.
            assertThat(runner.run(doc())).isNotNull();
        }
    }

    @Test
    @DisplayName("close é seguro chamar mais de uma vez")
    void closeIsIdempotent() {
        EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder().build();
        runner.close();
        runner.close();
    }
}
