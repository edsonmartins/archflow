package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que o provedor respondeu — quando a resposta não serve, e o que se faz com isso.
 *
 * <p>Dois defeitos distintos, ambos medidos no VendaX em 06/08 e ambos com o
 * mesmo sintoma para quem estava do lado de fora: <i>a cotação não chegou</i>.
 *
 * <ol>
 *   <li><b>Chamada malformada.</b> Duas ocorrências de
 *       {@code MALFORMED_FUNCTION_CALL} no OpenRouter. O modelo <b>tentou</b>
 *       chamar a tool e o provedor recusou a serialização, devolvendo
 *       {@code call:montar_cotacao{clienteRef:<ctrl46>…} como texto. É falha
 *       transitória — a mesma entrada funciona na tentativa seguinte — e
 *       derrubava a execução inteira.</li>
 *   <li><b>Resposta vazia sem explicação.</b> O runner dizia
 *       {@code o modelo respondeu: "(nada)"} e nada sobre o porquê. A causa real
 *       estava no CSV do provedor: {@code MAX_TOKENS} com raciocínio consumindo
 *       ~3930 de um teto de 4096. Três dias de hipóteses erradas — contexto,
 *       volume de candidatos, escolha de modelo — por falta de uma linha de log.</li>
 * </ol>
 */
@DisplayName("resposta do provedor: repetição e diagnóstico")
class RespostaDoProvedorTest {

    // ── Fakes ────────────────────────────────────────────────────────

    private static final class ClienteFalso implements McpClient {
        @Override public void connect() { }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void initialized() { }
        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return new McpModel.ServerMetadata("fake");
        }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return McpModel.ServerCapabilities.toolsOnly();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            return CompletableFuture.completedFuture(List.of(new McpModel.Tool(
                    "montar_cotacao", "monta", Map.of("type", "object", "properties", Map.of()))));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("{\"ok\":true}"));
        }
    }

    /** Respostas roteirizadas, com metadados de provedor por turno. */
    private static final class ModeloRoteirizado implements ChatModel {
        private final List<ChatResponse> script;
        int turnos;

        ModeloRoteirizado(List<ChatResponse> script) {
            this.script = script;
        }

        @Override public ChatResponse chat(ChatRequest request) {
            return script.get(Math.min(turnos++, script.size() - 1));
        }
    }

    /**
     * Modelo que estoura nas primeiras {@code falhas} chamadas e depois responde.
     * Diferente do {@link ModeloRoteirizado}, aqui não há resposta nenhuma — que é
     * exatamente a diferença entre a demanda 9 e a 2.
     */
    private static final class ModeloQueEstoura implements ChatModel {
        private final int falhas;
        private final RuntimeException erro;
        private final ChatResponse depois;
        int turnos;

        ModeloQueEstoura(int falhas, RuntimeException erro, ChatResponse depois) {
            this.falhas = falhas;
            this.erro = erro;
            this.depois = depois;
        }

        @Override public ChatResponse chat(ChatRequest request) {
            if (turnos++ < falhas) {
                throw erro;
            }
            return depois;
        }
    }

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash-lite").maxTokens(4096).build();

    private static final class ResolvedorFixo implements LLMConfigResolver {
        private final ChatModel model;

        ResolvedorFixo(ChatModel model) {
            this.model = model;
        }

        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) { return model; }
        @Override public StreamingChatModel resolveStreamingModel(LLMResolutionRequest r) {
            throw new UnsupportedOperationException();
        }
    }

    private static ChatResponse texto(String t) {
        return ChatResponse.builder().aiMessage(AiMessage.from(t)).build();
    }

    private static ChatResponse chamaTool() {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id("c1").name("montar_cotacao").arguments("{}").build())).build();
    }

    private static McpAgentRunner runner(ChatModel model) {
        return new McpAgentRunner(new ResolvedorFixo(model), CONFIG);
    }

    private static McpAgentRunner.Options opcoes() {
        return new McpAgentRunner.Options(ToolAccessPolicy.allowAll());
    }

    // ── Captura de log ───────────────────────────────────────────────

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void capturar() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(McpAgentRunner.class);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void soltar() {
        logger.detachAppender(appender);
    }

    private List<String> avisos() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ── Demanda 2 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("chamada de tool malformada")
    class Malformada {

        /** O caso medido: pseudo-chamada na 1ª volta, chamada válida na 2ª. */
        @Test
        @DisplayName("repete uma vez e o passo conclui")
        void repeteEConclui() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(
                    texto("call:montar_cotacao{clienteRef:<ctrl46>64336<ctrl46>"),
                    chamaTool(),
                    texto("pronto")));

            McpAgentRunner.Result r = runner(modelo)
                    .run("acme", "sys", "monte a cotação", new ClienteFalso(), opcoes());

            assertThat(r.toolCalls())
                    .as("a falha e transitoria: a mesma entrada funciona na tentativa seguinte")
                    .extracting(McpAgentRunner.ToolCall::name)
                    .contains("montar_cotacao");
            assertThat(avisos())
                    .as("uma repeticao silenciosa esconderia um problema do provedor")
                    .anySatisfy(m -> assertThat(m).contains("malformada").contains("repetindo"));
        }

        @Test
        @DisplayName("insistindo na pseudo-chamada, falha como antes — sem laço infinito")
        void naoRepeteParaSempre() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(
                    texto("call:montar_cotacao{a:1"),
                    texto("call:montar_cotacao{a:1")));

            McpAgentRunner.Result r = runner(modelo)
                    .run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(r.toolCalls()).isEmpty();
            assertThat(modelo.turnos)
                    .as("uma repeticao, nao um laco: 1a volta + 1 repeticao")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("resposta normal não paga por isto")
        void caminhoComumIntacto() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(texto("respondi normalmente")));

            McpAgentRunner.Result r = runner(modelo)
                    .run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(r.finalText()).isEqualTo("respondi normalmente");
            assertThat(modelo.turnos).as("nenhuma volta extra no caminho comum").isEqualTo(1);
            assertThat(avisos()).noneSatisfy(m -> assertThat(m).contains("malformada"));
        }

        @Test
        @DisplayName("texto que apenas menciona uma tool não dispara repetição")
        void mencaoNaoEChamada() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(
                    texto("Vou usar a tool call: montar_cotacao quando tiver os dados.")));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(modelo.turnos)
                    .as("a assinatura e `call:nome{`, nao a palavra call em prosa")
                    .isEqualTo(1);
        }
    }

    // ── Demanda 9 ────────────────────────────────────────────────────

    /**
     * O vizinho da demanda 2: lá o provedor respondeu e a serialização saiu
     * corrompida; aqui não houve resposta nenhuma.
     *
     * <p>Medido em 07/08: o {@code gemma-4-31b} perdeu a primeira das oito
     * execuções com {@code TimeoutException} em rota fria e acertou as sete
     * seguintes. As outras duas assinaturas da mesma semana são
     * {@code ConnectException} e
     * {@code IOException: HTTP/1.1 header parser received no bytes}.
     */
    @Nested
    @DisplayName("falha de transporte")
    class Transporte {

        private static RuntimeException embrulhado(Exception causa) {
            return new RuntimeException("falha ao chamar o provedor", causa);
        }

        /** O caso medido: rota fria na 1ª chamada, tudo certo na 2ª. */
        @Test
        @DisplayName("timeout na 1ª e resposta na 2ª: o passo conclui")
        void repeteEConclui() {
            ModeloQueEstoura modelo = new ModeloQueEstoura(
                    1, embrulhado(new java.util.concurrent.TimeoutException("rota fria")),
                    texto("pronto"));

            McpAgentRunner.Result r = runner(modelo)
                    .run("acme", "sys", "monte a cotação", new ClienteFalso(), opcoes());

            assertThat(r.finalText()).isEqualTo("pronto");
            assertThat(modelo.turnos).as("1a chamada + 1 repeticao").isEqualTo(2);
            assertThat(avisos())
                    .as("sem este registro, 1/8 de perda por rota fria vira 'o modelo e estavel'")
                    .anySatisfy(m -> assertThat(m).contains("TRANSPORTE").contains("repetindo"));
        }

        @Test
        @DisplayName("conexão recusada também repete")
        void connectException() {
            ModeloQueEstoura modelo = new ModeloQueEstoura(
                    1, embrulhado(new java.net.ConnectException("recusada")), texto("pronto"));

            assertThat(runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes())
                    .finalText()).isEqualTo("pronto");
        }

        @Test
        @DisplayName("resposta sem cabeçalho também repete")
        void semCabecalho() {
            ModeloQueEstoura modelo = new ModeloQueEstoura(
                    1, embrulhado(new java.io.IOException(
                            "HTTP/1.1 header parser received no bytes")),
                    texto("pronto"));

            assertThat(runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes())
                    .finalText()).isEqualTo("pronto");
        }

        @Test
        @DisplayName("falhando nas duas, propaga — sem laço infinito")
        void naoRepeteParaSempre() {
            ModeloQueEstoura modelo = new ModeloQueEstoura(
                    99, embrulhado(new java.net.ConnectException("recusada")), texto("nunca"));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> runner(modelo)
                            .run("acme", "sys", "x", new ClienteFalso(), opcoes()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(modelo.turnos)
                    .as("uma repeticao, nao um laco: 1a chamada + 1 repeticao")
                    .isEqualTo(2);
        }

        /**
         * O {@code 400} do Bedrock por prompt caching, de 08/08. Repetir teria
         * escondido um defeito de configuração atrás de uma latência dobrada.
         */
        @Test
        @DisplayName("erro de APLICAÇÃO (4xx) não repete")
        void erroDeAplicacaoNaoRepete() {
            ModeloQueEstoura modelo = new ModeloQueEstoura(
                    99, embrulhado(new dev.langchain4j.exception.HttpException(
                            400, "Provider returned error: did not allow prompt caching")),
                    texto("nunca"));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> runner(modelo)
                            .run("acme", "sys", "x", new ClienteFalso(), opcoes()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(modelo.turnos)
                    .as("repetir a mesma requisicao invalida da o mesmo erro, mais tarde")
                    .isEqualTo(1);
            assertThat(avisos()).noneSatisfy(m -> assertThat(m).contains("TRANSPORTE"));
        }

        /**
         * Um {@code 4xx} embrulhado em algo genérico não pode ser confundido com
         * queda de conexão — é por isso que a checagem de {@code HttpException}
         * varre a cadeia de causas <b>antes</b> da de transporte.
         */
        @Test
        @DisplayName("4xx embrulhado fundo na cadeia continua sem repetir")
        void quatroXxEmbrulhado() {
            RuntimeException fundo = new RuntimeException("camada 1",
                    new IllegalStateException("camada 2",
                            new dev.langchain4j.exception.HttpException(422, "nao processavel")));
            ModeloQueEstoura modelo = new ModeloQueEstoura(99, fundo, texto("nunca"));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> runner(modelo)
                            .run("acme", "sys", "x", new ClienteFalso(), opcoes()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(modelo.turnos).isEqualTo(1);
        }

        @Test
        @DisplayName("resposta normal não paga por isto")
        void caminhoComumIntacto() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(texto("respondi normalmente")));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(modelo.turnos).isEqualTo(1);
            assertThat(avisos()).noneSatisfy(m -> assertThat(m).contains("TRANSPORTE"));
        }
    }

    // ── Demanda 3 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("diagnóstico de conclusão sem artefato")
    class Diagnostico {

        private static ChatResponse vazioCom(FinishReason motivo, int completion) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(""))
                    .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                            .finishReason(motivo)
                            .tokenUsage(new TokenUsage(2857, completion))
                            .build())
                    .build();
        }

        /**
         * O caso que custou três dias: {@code MAX_TOKENS} com o completion
         * encostado no teto. Antes, o log dizia apenas {@code "(nada)"}.
         */
        @Test
        @DisplayName("resposta vazia registra finishReason, tokens e o teto efetivo")
        void respostaVaziaExplicada() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(
                    List.of(vazioCom(FinishReason.LENGTH, 4038)));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .as("quatro causas distintas eram indistinguiveis no log")
                    .contains("LENGTH")
                    .contains("4038")
                    .contains("4096")
                    .contains("CORTADO NO TETO"));
        }

        @Test
        @DisplayName("quando o provedor não reporta um número, o log diz que não reportou")
        void ausenciaEDeclarada() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(texto("")));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .as("omitir e pior que dizer que nao veio — some a diferenca entre 0 e ausente")
                    .contains("nao reportado"));
        }

        /**
         * O raciocínio não é reportado por {@link TokenUsage} — só pela subclasse
         * do adapter do provedor, lida por reflexão. Sem ela, o campo tem que
         * aparecer como ausente, e não sumir.
         */
        @Test
        @DisplayName("raciocínio ausente aparece como ausente, e não some da linha")
        void raciocinioAusente() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(
                    List.of(vazioCom(FinishReason.LENGTH, 4038)));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .contains("tokensRaciocinio=(nao reportado pelo provedor)"));
        }

        @Test
        @DisplayName("conclusão normal com texto não gera aviso")
        void conclusaoNormalESilenciosa() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(texto("aqui está a resposta")));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

            assertThat(avisos())
                    .as("avisar no caminho normal treina todo mundo a ignorar o aviso")
                    .isEmpty();
        }

        /** Concluir falando quando o passo exige artefato é anomalia, mesmo com texto. */
        @Test
        @DisplayName("com tool exigida e não chamada, avisa mesmo havendo texto")
        void exigidaENaoChamadaAvisa() {
            ModeloRoteirizado modelo = new ModeloRoteirizado(List.of(
                    texto("resolvi os cinco produtos"),
                    texto("resolvi os cinco produtos")));
            McpAgentRunner.Options exigindo = new McpAgentRunner.Options(
                    ToolAccessPolicy.allowAll(), ToolTrustPolicy.untrustedByDefault(),
                    ToolApprovalPolicy.none(), 10,
                    br.com.archflow.model.config.LLMConfigPatch.empty(),
                    br.com.archflow.model.config.LLMConfigPatch.empty(),
                    java.util.Set.of("montar_cotacao"));

            runner(modelo).run("acme", "sys", "x", new ClienteFalso(), exigindo);

            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .contains("concluiu sem artefato")
                    .contains("montar_cotacao"));
        }
    }

    /** Guarda contra um teste que passaria por não exercitar nada. */
    @Test
    @DisplayName("o roteiro é realmente consumido")
    void roteiroConsumido() {
        List<ChatResponse> script = new ArrayList<>(List.of(texto("ok")));
        ModeloRoteirizado modelo = new ModeloRoteirizado(script);

        runner(modelo).run("acme", "sys", "x", new ClienteFalso(), opcoes());

        assertThat(modelo.turnos).isPositive();
    }
}
