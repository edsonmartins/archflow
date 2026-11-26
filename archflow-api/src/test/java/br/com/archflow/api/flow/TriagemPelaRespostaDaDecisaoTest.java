package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.decision.LlmDecisionProvider;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-116, 2ª rodada, no motor de verdade: o fluxo que o copiloto monta para "mandar para um humano quando for
 * urgente; quando não for, responder com um LLM" — o documento de {@code triagemPelaRespostaDaDecisao.json}, o mesmo
 * que o teste do copiloto (CopilotAppOperator.conditions.test.ts) compara com o canvas — manda o URGENTE ao humano e o
 * comum ao LLM.
 *
 * <p>No Chrome os dois pedidos escalaram: o decision gerado não tinha provider nem model (o http-decisions padrão
 * recusa sem model, e {@code onError: ESCALATE} fez o resto), e o ramo era a rota — a confiança, não a urgência.
 * Aqui o ramo vem da resposta ({@code answers.urgente.result}) e o provedor é o {@code llm} real
 * ({@link LlmDecisionProvider}, com o teto de confiança de produção) sobre um modelo de chat determinístico. O
 * {@code llm-chat} vai a um servidor local no lugar do OpenRouter, para se ver quem chegou à resposta.</p>
 *
 * <p>RCHFLW-120: o servidor local guarda o corpo de cada pedido, e o teste lê a mensagem do usuário que o LLM
 * recebeu — com a "Mensagem do usuário" ({@code userMessage: ${receber}}), o pedido do cliente; sem ela, o JSON da
 * decisão, como antes.</p>
 */
@DisplayName("triagem pela resposta da decisão: urgente ao humano, comum ao LLM")
class TriagemPelaRespostaDaDecisaoTest {

    /** O documento que o copiloto monta; o editor e este teste leem o mesmo arquivo. */
    private static final Path FLUXO = Path.of("..", "archflow-ui", "src", "components", "copilot", "__tests__",
            "triagemPelaRespostaDaDecisao.json");

    private static final String URGENTE = "URGENTE: o sistema caiu e nenhum cliente consegue pagar";
    private static final String COMUM = "Queria saber o horário de atendimento";

    private static final String RESPOSTA_DO_LLM = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Atendemos das 8h às 18h."},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
            """;

    private static final ResolvedLLMConfig PLATAFORMA_DECISAO = ResolvedLLMConfig.builder()
            .provider("openrouter").model("modelo-padrao").build();

    private HttpServer servidor;
    private final List<String> pedidosAoLlm = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/chat/completions", troca -> {
            pedidosAoLlm.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] corpo = RESPOSTA_DO_LLM.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void desce() {
        servidor.stop(0);
    }

    private String local() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1";
    }

    /**
     * O classificador: o mesmo JSON que um LLM devolveria ao {@link LlmDecisionProvider}, decidido pelo texto do pedido
     * — determinístico. Ele declara confiança alta de propósito: o provedor a limita a 0,89, e é isso que o teste
     * precisa mostrar (a rota nunca é AUTO; o ramo, pela resposta, separa mesmo assim).
     */
    private static ChatModel classificador(Function<String, String> resposta) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                String estado = request.messages().stream().filter(UserMessage.class::isInstance)
                        .map(m -> ((UserMessage) m).singleText()).reduce("", String::concat);
                return ChatResponse.builder().aiMessage(AiMessage.from(resposta.apply(estado))).build();
            }
        };
    }

    private static String porUrgencia(String estado) {
        return estado.contains("URGENTE")
                ? "{\"assunto\":{\"choice\":\"suporte_tecnico\",\"confidence\":0.97},"
                        + "\"urgente\":{\"probability\":0.98}}"
                : "{\"assunto\":{\"choice\":\"outro\",\"confidence\":0.6},\"urgente\":{\"probability\":0.04}}";
    }

    /** Uma execução isolada: motor, estado e fábrica próprios, como uma execução do servidor. */
    private record Execucao(FlowResult resultado, DefaultExecutionContext contexto, StateManager estado,
                            String flowId) {

        @SuppressWarnings("unchecked")
        Map<String, Object> saida(String passo) {
            return (Map<String, Object>) contexto.get(passo)
                    .orElseThrow(() -> new AssertionError("sem saída: " + passo));
        }

        @SuppressWarnings("unchecked")
        Object resposta(String pergunta, String campo) {
            return ((Map<String, Map<String, Object>>) saida("triagem").get("answers")).get(pergunta).get(campo);
        }
    }

    private Execucao executar(String pedido, ChatModel classificador) throws Exception {
        return executar(pedido, classificador, documento -> { });
    }

    /** A config do nó "Responder com LLM" do documento, para o teste mexer na "Mensagem do usuário". */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> configDoResponder(Map<String, Object> documento) {
        return ((List<Map<String, Object>>) documento.get("steps")).stream()
                .filter(st -> "responder".equals(st.get("id"))).findFirst()
                .map(st -> (Map<String, Object>) st.get("configuration")).orElseThrow();
    }

    /** A mensagem do usuário do único pedido que chegou ao LLM (a última do papel user). */
    private String mensagemDoUsuarioNoLlm() throws IOException {
        assertThat(pedidosAoLlm).hasSize(1);
        com.fasterxml.jackson.databind.JsonNode mensagens = new ObjectMapper().readTree(pedidosAoLlm.get(0))
                .get("messages");
        String ultima = null;
        for (com.fasterxml.jackson.databind.JsonNode m : mensagens) {
            if ("user".equals(m.get("role").asText())) {
                ultima = m.get("content").isTextual() ? m.get("content").asText() : m.get("content").toString();
            }
        }
        return ultima;
    }

    /** O pedido é o texto do Executar ou, num mapa, o JSON de um chamador de API (semeado como no servidor). */
    @SuppressWarnings("unchecked")
    private Execucao executar(Object pedido, ChatModel classificador,
                              java.util.function.Consumer<Map<String, Object>> ajuste) throws Exception {
        Map<String, Object> documento = new ObjectMapper().readValue(Files.readString(FLUXO), Map.class);
        ajuste.accept(documento);
        StateManager estado = new InMemoryStateManager();
        InMemoryFlowRepository repositorio = new InMemoryFlowRepository();
        FlowEngine motor = FlowEngineFactory.create(repositorio, null, null, estado);
        ObjectProvider<FlowEngine> provedorDoMotor = mock(ObjectProvider.class);
        when(provedorDoMotor.getObject()).thenReturn(motor);

        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, estado, provedorDoMotor, null,
                new PropertyTenantKeyResolver(Map.of(), Map.of(), "openrouter", "chave-da-plataforma"));
        fabrica.setPlatformDefaultLLM(ResolvedLLMConfig.builder()
                .provider("openrouter").model("google/gemma-4-31b-it:free")
                .additionalConfig(Map.of("baseUrl", local())).build());
        fabrica.setBaseUrlPolicy(BaseUrlPolicy.of(List.of(local())));
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return PLATAFORMA_DECISAO; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return classificador; }
        };
        fabrica.setDecisionSupport(DecisionProviders.withDefaults()
                .register(new LlmDecisionProvider(resolver, PLATAFORMA_DECISAO)), DecisionKeys.NONE);

        Flow flow = new DefaultWorkflowDeserializer(fabrica).toFlow(documento);
        repositorio.save(flow);
        DefaultExecutionContext ctx = new DefaultExecutionContext("SYSTEM", "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        if (pedido instanceof Map<?, ?> json) {
            RunInputSeeding.seedVariables(ctx, (Map<String, Object>) json);
        } else {
            ctx.set("input", pedido);
        }
        FlowResult resultado = motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
        return new Execucao(resultado, ctx, estado, flow.getId());
    }

    @Test
    @DisplayName("o pedido URGENTE para no atendimento humano, e o LLM não é chamado")
    void urgenteVaiAoHumano() throws Exception {
        Execucao r = executar(URGENTE, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia));

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(r.estado().loadState(r.flowId()).getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(r.saida("triagem")).containsEntry("provider", "llm").doesNotContainKey("error");
        assertThat(r.resposta("urgente", "result")).isEqualTo(true);
        assertThat(r.resposta("assunto", "choice")).isEqualTo("suporte_tecnico");
        assertThat(r.saida("e_urgente")).containsEntry("branch", "true");
        assertThat(r.contexto().get("responder")).isEmpty();
        assertThat(pedidosAoLlm).isEmpty();
    }

    @Test
    @DisplayName("o pedido comum chega à resposta do LLM, sem passar pelo humano")
    void comumVaiAoLlm() throws Exception {
        Execucao r = executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia));

        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(r.estado().loadState(r.flowId()).getStatus()).isNotEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(r.resposta("urgente", "result")).isEqualTo(false);
        assertThat(r.saida("e_urgente")).containsEntry("branch", "false");
        assertThat(r.contexto().get("responder")).hasValue("Atendemos das 8h às 18h.");
        // Uma chamada, a do nó "Responder com LLM" (com o prompt de sistema dele). RCHFLW-120: a mensagem do usuário
        // é a do cliente — o nó a lê da Entrada (userMessage ${receber}); a entrada linear do passo seria a saída do
        // decision, que a condição repassa intacta.
        assertThat(pedidosAoLlm).singleElement().asString().contains("Responda ao pedido do cliente em uma frase.");
        assertThat(mensagemDoUsuarioNoLlm()).isEqualTo(COMUM);
    }

    /**
     * RCHFLW-120, o que se viu no Chrome: sem a "Mensagem do usuário", o LLM recebe o que sempre recebeu — a entrada
     * linear do passo, o JSON da decisão — e respondia "notei que você enviou um registro técnico/JSON". O campo
     * vazio não muda nenhum fluxo existente.
     */
    @Test
    @DisplayName("sem userMessage, o LLM recebe o que recebe hoje: o JSON da decisão, não o pedido")
    void semMensagemDoUsuarioRecebeOJsonDaDecisao() throws Exception {
        Execucao r = executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia),
                documento -> configDoResponder(documento).remove("userMessage"));

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(mensagemDoUsuarioNoLlm()).isNotEqualTo(COMUM).contains("\"answers\"").contains("\"route\"");
    }

    @Test
    @DisplayName("userMessage mistura texto e referências; ${input} é a saída do passo anterior, como nas condições")
    void templateMisturaTextoEReferencias() throws Exception {
        executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia),
                documento -> configDoResponder(documento)
                        .put("userMessage", "Cliente: ${receber} (assunto ${triagem.answers.assunto.choice})"));

        assertThat(mensagemDoUsuarioNoLlm()).isEqualTo("Cliente: " + COMUM + " (assunto outro)");

        pedidosAoLlm.clear();
        executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia),
                documento -> configDoResponder(documento).put("userMessage", "${input}"));
        assertThat(mensagemDoUsuarioNoLlm()).contains("\"answers\"").doesNotContain("${");
    }

    /**
     * Um chamador de API manda {@code {"message": "…"}}: o mapa inteiro é a saída da Entrada, então
     * {@code ${receber}} manda o JSON (valor que não é texto vira JSON, como documentado) e é
     * {@code ${receber.message}} que manda o pedido — a forma que o copiloto, o nl-to-flow e o painel indicam.
     */
    @Test
    @DisplayName("entrada em objeto JSON: ${receber} manda o objeto em JSON; ${receber.message}, o pedido")
    void entradaEmObjetoJson() throws Exception {
        Map<String, Object> chamadaDeApi = Map.of("message", COMUM);
        executar(chamadaDeApi, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia), documento -> { });
        assertThat(mensagemDoUsuarioNoLlm()).isEqualTo("{\"message\":\"" + COMUM + "\"}");

        pedidosAoLlm.clear();
        executar(chamadaDeApi, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia),
                documento -> configDoResponder(documento).put("userMessage", "${receber.message}"));
        assertThat(mensagemDoUsuarioNoLlm()).isEqualTo(COMUM);
    }

    @Test
    @DisplayName("referência sem valor falha o passo nomeando nó, campo e referência, e nada vai ao modelo")
    void referenciaSemValorFalhaSemChamarOLlm() throws Exception {
        Execucao r = executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia),
                documento -> configDoResponder(documento).put("userMessage", "${receber.texto}"));

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(String.valueOf(r.resultado().getErrors()))
                .contains("responder").contains("userMessage").contains("${receber.texto}");
        assertThat(pedidosAoLlm).isEmpty();
        assertThat(r.contexto().get("responder")).isEmpty();
    }

    /**
     * Por que o ramo não pode ser a rota: com o provedor {@code llm} a confiança é limitada a 0,89 e o AUTO padrão é
     * 0,9. Os dois pedidos saem com a MESMA rota (REVIEW) — o desenho do Chrome (AUTO → LLM, ESCALATE → humano) não
     * levaria nenhum dos dois a lugar nenhum. Quem separa é a resposta.
     */
    @Test
    @DisplayName("a rota é a confiança: os dois pedidos saem REVIEW, e só a resposta os separa")
    void rotaNaoSeparaOsPedidos() throws Exception {
        Execucao urgente = executar(URGENTE, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia));
        Execucao comum = executar(COMUM, classificador(TriagemPelaRespostaDaDecisaoTest::porUrgencia));

        assertThat(urgente.saida("triagem")).containsEntry("route", "REVIEW").containsEntry("calibrated", false);
        assertThat(comum.saida("triagem")).containsEntry("route", "REVIEW");
        assertThat(urgente.resposta("urgente", "result")).isNotEqualTo(comum.resposta("urgente", "result"));
    }

    @Test
    @DisplayName("decisão que falha vai ao humano: a falha nunca vira o caminho comum")
    void falhaVaiAoHumano() throws Exception {
        Execucao r = executar(COMUM, classificador(estado -> "não sei classificar isto"));

        assertThat(r.saida("triagem")).containsEntry("route", "ESCALATE").containsKey("error");
        assertThat(r.saida("e_urgente")).containsEntry("branch", "true");
        assertThat(r.estado().loadState(r.flowId()).getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(pedidosAoLlm).isEmpty();
    }
}
