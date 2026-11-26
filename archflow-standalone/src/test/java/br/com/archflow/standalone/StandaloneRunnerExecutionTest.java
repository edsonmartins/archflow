package br.com.archflow.standalone;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.standalone.StandaloneRunner.CliArgs;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O runner standalone executa os passos de verdade (RCHFLW-37).
 *
 * <p>Antes, todo fluxo terminava FAILED no primeiro passo: o runner entregava ao motor o
 * {@code SerializableStep} do transporte, cujo {@code execute} só devolvia "Use StandaloneRunner to
 * execute steps". Os testes antigos só conferiam a leitura do arquivo, por isso nada pegava. Aqui o
 * arquivo exportado roda ponta a ponta — com o adapter de chat real contra um servidor local no lugar do
 * provedor, sem nenhuma chamada externa.</p>
 */
@DisplayName("StandaloneRunner — execução dos passos")
class StandaloneRunnerExecutionTest {

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"resposta do modelo"},
             "finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
            """;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            byte[] resposta = RESPOSTA.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private String local() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** O ambiente de um deployment com OpenRouter num gateway (aqui, o servidor local). */
    private Map<String, String> ambienteComChave() {
        return Map.of(
                "ARCHFLOW_LLM_PROVIDER", "openrouter",
                "ARCHFLOW_LLM_MODEL", "google/gemma-4-31b-it:free",
                "ARCHFLOW_API_KEY", "chave-do-ambiente",
                "ARCHFLOW_LLM_BASE_URL", local());
    }

    private FlowResult rodar(String json, Map<String, String> ambiente, String entrada) throws Exception {
        Path arquivo = dir.resolve("fluxo.json");
        Files.writeString(arquivo, json);
        try (StandaloneRunner runner = new StandaloneRunner(ambiente)) {
            return runner.run(CliArgs.parse(new String[]{arquivo.toString(), "--input", entrada,
                    "--timeout", "60"}));
        }
    }

    /** Entrada → Condição → Saída, como o designer exporta: o ramo verdadeiro vai à Saída "aceito". */
    private static final String ENTRADA_CONDICAO_SAIDA = """
            {"id": "triagem", "metadata": {"name": "Triagem", "version": "1.0.0"},
             "steps": [
               {"id": "entrada", "type": "input", "componentId": "input", "label": "Entrada",
                "connections": [{"sourceId": "entrada", "targetId": "decide"}]},
               {"id": "decide", "type": "condition", "componentId": "condition", "label": "É olá?",
                "configuration": {"conditionExpression": "${input} == 'olá'"},
                "connections": [
                  {"sourceId": "decide", "targetId": "aceito", "branch": "true"},
                  {"sourceId": "decide", "targetId": "recusado", "branch": "false"}]},
               {"id": "aceito", "type": "output", "componentId": "output", "label": "Aceito"},
               {"id": "recusado", "type": "output", "componentId": "output", "label": "Recusado"}
             ]}
            """;

    @Test
    @DisplayName("Entrada → Condição → Saída termina COMPLETED com a saída do ramo escolhido")
    void entradaCondicaoSaida() throws Exception {
        FlowResult result = rodar(ENTRADA_CONDICAO_SAIDA, Map.of(), "olá");

        assertThat(result.getStatus()).as(String.valueOf(result.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        // Uma saída só — a do ramo verdadeiro. Com os dois ramos percorridos, viria o mapa das duas.
        assertThat(result.getOutput()).hasValue("olá");
    }

    /** O portão do QA (RCHFLW-123): {@code ${entrada.valor} > 500}, com só o ramo verdadeiro ligado. */
    private static final String PORTAO_DE_VALOR = """
            {"id": "reembolso", "metadata": {"name": "Reembolso", "version": "1.0.0"},
             "steps": [
               {"id": "entrada", "type": "input", "componentId": "input", "label": "Entrada",
                "connections": [{"sourceId": "entrada", "targetId": "acima"}]},
               {"id": "acima", "type": "condition", "componentId": "condition", "label": "Acima de 500?",
                "configuration": {"conditionExpression": "${entrada.valor} > 500"},
                "connections": [{"sourceId": "acima", "targetId": "gerente", "branch": "true"}]},
               {"id": "gerente", "type": "output", "componentId": "output", "label": "Gerente"}
             ]}
            """;

    @Test
    @DisplayName("ARCHFLOW_FLOW_LENIENT_CONDITIONS: sem a variável a referência sem valor falha; com true, ramo false")
    void lenienteVemDoAmbiente() throws Exception {
        String texto = "quero reembolso de 900 reais";

        FlowResult padrao = rodar(PORTAO_DE_VALOR, Map.of(), texto);
        assertThat(padrao.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(String.valueOf(padrao.getErrors())).contains("a referência ${entrada.valor} não tem valor");

        FlowResult desligada = rodar(PORTAO_DE_VALOR, Map.of("ARCHFLOW_FLOW_LENIENT_CONDITIONS", "false"), texto);
        assertThat(desligada.getStatus()).isEqualTo(ExecutionStatus.FAILED);

        FlowResult leniente = rodar(PORTAO_DE_VALOR, Map.of("ARCHFLOW_FLOW_LENIENT_CONDITIONS", "true"), texto);
        assertThat(leniente.getStatus()).as(String.valueOf(leniente.getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        // O comportamento antigo: false sem aviso de falha, e o fluxo termina na condição (o ramo false não tem
        // aresta) — a saída é a da condição, não a do gerente.
        assertThat(String.valueOf(leniente.getOutput().orElseThrow())).contains("branch=false");
    }

    @Test
    @DisplayName("llm-chat do designer fala com o provedor usando a chave do ambiente, não a do arquivo")
    void llmChatContraServidorLocal() throws Exception {
        FlowResult result = rodar("""
                {"id": "chat", "metadata": {"name": "Chat"},
                 "steps": [
                   {"id": "entrada", "type": "input", "componentId": "input",
                    "connections": [{"sourceId": "entrada", "targetId": "responde"}]},
                   {"id": "responde", "type": "llm-chat", "componentId": "llm-chat",
                    "config": {"systemPrompt": "Responda em uma frase.", "apiKey": "chave-do-arquivo"},
                    "connections": [{"sourceId": "responde", "targetId": "saida"}]},
                   {"id": "saida", "type": "output", "componentId": "output"}
                 ]}
                """, ambienteComChave(), "Qual a capital da França?");

        assertThat(result.getStatus()).as(String.valueOf(result.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).hasValue("resposta do modelo");
        assertThat(corpos).singleElement().satisfies(corpo -> assertThat(corpo.replaceAll("\\s", ""))
                .contains("\"model\":\"google/gemma-4-31b-it:free\"")
                .contains("{\"role\":\"system\",\"content\":\"Respondaemumafrase.\"}")
                .contains("\"content\":\"QualacapitaldaFrança?\""));
        assertThat(autorizacoes).singleElement().isEqualTo("Bearer chave-do-ambiente");
    }

    @Test
    @DisplayName("sem chave no ambiente, a chave escrita no nó não é usada: nada sai para o provedor")
    void chaveDoArquivoNaoVale() throws Exception {
        Map<String, String> semChave = Map.of(
                "ARCHFLOW_LLM_PROVIDER", "openrouter",
                "ARCHFLOW_LLM_MODEL", "google/gemma-4-31b-it:free",
                "ARCHFLOW_LLM_BASE_URL", local());

        FlowResult result = rodar("""
                {"id": "chat", "metadata": {"name": "Chat"},
                 "steps": [
                   {"id": "responde", "type": "llm-chat", "componentId": "llm-chat",
                    "config": {"apiKey": "chave-do-arquivo"}}
                 ]}
                """, semChave, "oi");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).extracting(e -> e.message())
                .anySatisfy(m -> assertThat(m).contains("[LLM-CHAVE]").contains("ARCHFLOW_LLM_KEYS_OPENROUTER"));
        assertThat(corpos).as("a chave do arquivo levaria a requisição ao provedor").isEmpty();
    }

    @Test
    @DisplayName("nó que precisa do servidor recusa o fluxo antes do primeiro passo, nomeando o nó e o motivo")
    void noDoServidorFalhaAntes() {
        String fluxo = """
                {"id": "com-aprovacao", "metadata": {"name": "Com aprovação"},
                 "steps": [
                   {"id": "responde", "type": "llm-chat", "componentId": "llm-chat",
                    "connections": [{"sourceId": "responde", "targetId": "aprova"}]},
                   {"id": "aprova", "type": "APPROVAL", "label": "Aprovar resposta",
                    "connections": [{"sourceId": "aprova", "targetId": "agente"}]},
                   {"id": "agente", "type": "agent", "componentId": "mcp-agent",
                    "configuration": {"systemPrompt": "x"}}
                 ]}
                """;

        assertThatThrownBy(() -> rodar(fluxo, ambienteComChave(), "oi"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó \"Aprovar resposta\" (aprova) precisa do servidor archflow "
                        + "(aprovação humana")
                .hasMessageContaining("o nó agente precisa do servidor archflow (agente MCP");
        assertThat(corpos).as("o llm-chat anterior não pode ter rodado").isEmpty();
    }

    @Test
    @DisplayName("decisão com o provedor 'llm' (só do servidor) recusa o fluxo antes — não termina pelo ramo "
            + "ESCALATE")
    void decisaoComProvedorDoServidorFalhaAntes() {
        String fluxo = """
                {"id": "triagem", "metadata": {"name": "Triagem"},
                 "steps": [
                   {"id": "entrada", "type": "input", "componentId": "input",
                    "connections": [{"sourceId": "entrada", "targetId": "responde"}]},
                   {"id": "responde", "type": "llm-chat", "componentId": "llm-chat",
                    "connections": [{"sourceId": "responde", "targetId": "decide"}]},
                   {"id": "decide", "type": "decision", "componentId": "decision", "label": "Triagem",
                    "configuration": {"provider": "llm",
                      "questions": {"ok": {"type": "noul", "instructions": "Está ok?"}}},
                    "connections": [
                      {"sourceId": "decide", "targetId": "auto", "branch": "AUTO"},
                      {"sourceId": "decide", "targetId": "escala", "branch": "ESCALATE"}]},
                   {"id": "auto", "type": "output", "componentId": "output"},
                   {"id": "escala", "type": "output", "componentId": "output"}
                 ]}
                """;

        assertThatThrownBy(() -> rodar(fluxo, ambienteComChave(), "oi"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó \"Triagem\" (decide) precisa do servidor archflow (decisão: o provedor "
                        + "escolhido é registrado só no servidor — provedor 'llm' não existe fora do servidor");
        assertThat(corpos).as("o llm-chat anterior não pode ter rodado").isEmpty();
    }

    @Test
    @DisplayName("nó de dados sem adapter recusa o fluxo antes — o llm-chat anterior não roda")
    void noDeDadosFalhaAntes() {
        String fluxo = """
                {"id": "busca", "metadata": {"name": "Busca"},
                 "steps": [
                   {"id": "responde", "type": "llm-chat", "componentId": "llm-chat",
                    "connections": [{"sourceId": "responde", "targetId": "busca"}]},
                   {"id": "busca", "type": "vector-search", "componentId": "vector-search",
                    "configuration": {"vectorBackend": "pgvector"}}
                 ]}
                """;

        assertThatThrownBy(() -> rodar(fluxo, ambienteComChave(), "oi"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó busca precisa do servidor archflow (nó de dados")
                .hasMessageContaining("o backend 'pgvector' não tem adapter de vectorstore fora do servidor");
        assertThat(corpos).as("o llm-chat anterior não pode ter rodado").isEmpty();
    }

    @Test
    @DisplayName("tenantId, userId e sessionId da entrada vão para o contexto, como no caminho antigo do motor")
    void identidadeDaEntrada() {
        var comIdentidade = StandaloneFlowMaterializer.executionContext("exec-1",
                Map.of("tenantId", "acme", "userId", "ana", "sessionId", "s-9", "input", "oi"));
        var semIdentidade = StandaloneFlowMaterializer.executionContext("exec-1", Map.of("input", "oi"));

        assertThat(comIdentidade.getTenantId()).isEqualTo("acme");
        assertThat(comIdentidade.getUserId()).isEqualTo("ana");
        assertThat(comIdentidade.getSessionId()).isEqualTo("s-9");
        assertThat(comIdentidade.get("input")).hasValue("oi");
        assertThat(semIdentidade.getTenantId()).isEqualTo(br.com.archflow.model.tenant.DefaultTenant.ID);
        assertThat(semIdentidade.getUserId()).isNull();
        assertThat(semIdentidade.getSessionId()).isEqualTo("exec-1");
    }
}
