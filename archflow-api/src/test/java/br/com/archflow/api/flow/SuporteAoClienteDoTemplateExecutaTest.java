package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.decision.LlmDecisionProvider;
import br.com.archflow.api.knowledge.DocumentChunk;
import br.com.archflow.api.knowledge.HashEmbeddingModel;
import br.com.archflow.api.knowledge.InMemoryKnowledgeRepository;
import br.com.archflow.api.knowledge.InMemoryKnowledgeVectorIndex;
import br.com.archflow.api.knowledge.KnowledgeService;
import br.com.archflow.api.knowledge.KnowledgeVectorIndex;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.impl.TemplateControllerImpl;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
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
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import br.com.archflow.template.WorkflowTemplateRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-203: o template customer-support, instalado pelo {@code POST /api/templates/{id}/install}, executa de ponta a
 * ponta no motor de verdade.
 *
 * <p>Antes toda execução falhava no 2º passo com "component not found: llm"; trocando por llm-chat, terminava COMPLETED
 * depois da busca, com todos os agentes num ramo morto ({@code ${intent}} nunca era gravado), e 'general' não tinha
 * aresta. Aqui o documento é o que o install grava ({@link SpringTemplateController}), lido pelo deserializer real; a
 * busca vai a uma base de conhecimento em memória populada como a ingestão faz; o classificador é o provedor
 * {@code llm} real ({@link LlmDecisionProvider}, com o teto de confiança de produção) sobre um modelo determinístico; e
 * os llm-chat vão a um servidor local no lugar do OpenRouter, que guarda cada pedido para se ver quem respondeu e com
 * que mensagem. A aprovação passa pela fila de verdade ({@link ApprovalQueueService}).</p>
 */
@DisplayName("customer-support instalado executa: cada assunto no seu agente, o frustrado passa pela aprovação")
class SuporteAoClienteDoTemplateExecutaTest {

    private static final String TENANT = "acme";
    private static final String RESPOSTA = "Resposta ao cliente.";
    private static final String TRECHO_DA_FATURA =
            "A segunda via da fatura com valor errado sai no portal, em Financeiro, Faturas.";

    private static final String RESPOSTA_DO_LLM = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
            """.formatted(RESPOSTA);

    private static final ResolvedLLMConfig PLATAFORMA_DECISAO = ResolvedLLMConfig.builder()
            .provider("openrouter").model("modelo-padrao").build();

    private HttpServer servidor;
    private final List<String> pedidosAoLlm = new CopyOnWriteArrayList<>();
    /** O prompt de sistema de cada pergunta feita ao classificador (sentimento e assunto). */
    private final List<String> perguntasAoClassificador = new CopyOnWriteArrayList<>();

    private KnowledgeService conhecimento;
    private String baseId;

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

        // A base como o DocumentIngestionHandler a deixa: trechos embutidos pelo mesmo modelo, versão ativada.
        InMemoryKnowledgeVectorIndex indice = new InMemoryKnowledgeVectorIndex();
        HashEmbeddingModel embeddings = new HashEmbeddingModel(128);
        conhecimento = new KnowledgeService(new InMemoryKnowledgeRepository(), null, null, embeddings, indice);
        baseId = conhecimento.createBase(TENANT, null, "suporte").id();
        List<String> trechos = List.of(TRECHO_DA_FATURA, "O suporte atende de segunda a sexta, das 8h às 18h.");
        var candidata = indice.createCandidate(TENANT, baseId);
        indice.replaceDocument(TENANT, baseId, candidata.id(), "doc-1", IntStream.range(0, trechos.size())
                .mapToObj(i -> new KnowledgeVectorIndex.EmbeddedChunk(
                        new DocumentChunk("chunk-" + i, TENANT, "doc-1", i, trechos.get(i), 0, 0),
                        embeddings.embed(trechos.get(i))))
                .toList());
        assertThat(indice.activate(TENANT, baseId, candidata.id())).isTrue();
    }

    @AfterEach
    void desce() {
        servidor.stop(0);
    }

    private String local() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1";
    }

    /**
     * O classificador determinístico. Ao sentimento ("frustrated"), diz que sim quando o texto traz ABSURDO; ao assunto
     * ("intent"), escolhe pela palavra-chave, com confiança alta (o provedor a limita a 0,89) — ou baixa (0,3) quando o
     * texto traz VAGO. Texto com FALHA recebe uma resposta que não é JSON: a decisão falha.
     */
    private ChatModel classificador() {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                String sistema = request.messages().stream().filter(SystemMessage.class::isInstance)
                        .map(m -> ((SystemMessage) m).text()).reduce("", String::concat);
                String estado = request.messages().stream().filter(UserMessage.class::isInstance)
                        .map(m -> ((UserMessage) m).singleText()).reduce("", String::concat);
                perguntasAoClassificador.add(sistema);
                String resposta;
                if (estado.contains("FALHA")) {
                    resposta = "não sei classificar isto";
                } else if (sistema.contains("- frustrated:")) {
                    double p = estado.contains("ABSURDO") ? 0.97 : estado.contains("chateado") ? 0.4 : 0.03;
                    resposta = "{\"frustrated\":{\"probability\":" + p + "}}";
                } else {
                    String assunto = estado.contains("fatura") ? "billing"
                            : estado.contains("erro") ? "technical"
                            : estado.contains("plano") ? "sales" : "general";
                    double confianca = estado.contains("VAGO") ? 0.3 : 0.97;
                    resposta = "{\"intent\":{\"choice\":\"" + assunto + "\",\"confidence\":" + confianca + "}}";
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(resposta)).build();
            }
        };
    }

    /** O motor de uma execução, com a fila de aprovações sobre o mesmo estado. */
    private record Execucao(FlowResult resultado, DefaultExecutionContext contexto, StateManager estado,
                            ApprovalQueueService fila, String flowId, List<Map<String, Object>> passos) {

        boolean executou(String passo) {
            return contexto.get(passo).isPresent();
        }
    }

    /** Instala o template pelo endpoint, como a galeria faz, e devolve o documento que ficou gravado. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> instalar(DefaultFlowStepFactory fabrica, Map<String, Object> parametros) {
        InMemoryWorkflowRuntimeStore store = new InMemoryWorkflowRuntimeStore();
        SpringTemplateController web = new SpringTemplateController(
                new TemplateControllerImpl(WorkflowTemplateRegistry.getInstance()), store, new NodeSupport(fabrica));
        Map<String, Object> todos = new HashMap<>(parametros);
        todos.put("knowledgeBaseId", baseId);
        var criado = web.installTemplate("customer-support", new InstallTemplateRequest("SAC", todos));
        assertThat(criado.getBody().get("unsupportedNodeTypes")).asList().as("nó sem executor").isEmpty();
        return store.getWorkflow(DefaultTenant.ID, (String) criado.getBody().get("workflowId"));
    }

    private Execucao executar(Object pedido) throws Exception {
        return executar(pedido, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Execucao executar(Object pedido, Map<String, Object> parametros) throws Exception {
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
        fabrica.useKnowledgeService(conhecimento);
        ChatModel modelo = classificador();
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return PLATAFORMA_DECISAO; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
        };
        fabrica.setDecisionSupport(DecisionProviders.withDefaults()
                .register(new LlmDecisionProvider(resolver, PLATAFORMA_DECISAO)), DecisionKeys.NONE);

        Map<String, Object> documento = instalar(fabrica, parametros);
        Flow flow = new DefaultWorkflowDeserializer(fabrica).toFlow(documento);
        repositorio.save(flow);
        DefaultExecutionContext ctx = new DefaultExecutionContext(TENANT, "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        if (pedido instanceof Map<?, ?> json) {
            RunInputSeeding.seedVariables(ctx, (Map<String, Object>) json);
        } else {
            ctx.set("input", pedido);
        }
        FlowResult resultado = motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
        return new Execucao(resultado, ctx, estado, new ApprovalQueueService(estado, motor), flow.getId(),
                (List<Map<String, Object>>) documento.get("steps"));
    }

    /** O prompt de sistema e a mensagem do usuário do único pedido que chegou ao LLM. */
    private List<String> pedidoUnicoAoLlm() throws IOException {
        assertThat(pedidosAoLlm).hasSize(1);
        JsonNode mensagens = new ObjectMapper().readTree(pedidosAoLlm.get(0)).get("messages");
        String sistema = "";
        String usuario = "";
        for (JsonNode m : mensagens) {
            String texto = m.get("content").isTextual() ? m.get("content").asText() : m.get("content").toString();
            if ("system".equals(m.get("role").asText())) {
                sistema = texto;
            } else if ("user".equals(m.get("role").asText())) {
                usuario = texto;
            }
        }
        return List.of(sistema, usuario);
    }

    private static void esperar(BooleanSupplier condicao) throws Exception {
        long prazo = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < prazo) {
            if (condicao.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condição não satisfeita dentro do prazo");
    }

    private static final List<String> AGENTES = List.of("billing-agent", "technical-agent", "sales-agent",
            "general-agent", "escalated-draft");

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "'Minha fatura veio com valor errado', billing-agent, billing support agent",
            "'Aparece um erro ao salvar o pedido', technical-agent, technical support agent",
            "'Quero mudar para o plano anual', sales-agent, sales agent",
            "'Vocês abrem no feriado?', general-agent, customer support agent. You answer general questions"})
    @DisplayName("cada assunto chega ao seu agente, com a mensagem do cliente e os trechos da base")
    void cadaAssuntoChegaAoSeuAgente(String pedido, String agente, String persona) throws Exception {
        Execucao r = executar(pedido);

        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(r.resultado().getOutput()).hasValue(RESPOSTA);
        assertThat(r.contexto().get(agente)).hasValue(RESPOSTA);
        assertThat(AGENTES.stream().filter(r::executou)).as("só um agente responde").containsExactly(agente);
        assertThat(r.executou("handoff")).isFalse();
        assertThat(r.fila().listPending(TENANT)).isEmpty();
        // O ramo veio da resposta do classificador, não da rota (que com o provedor llm nunca sai AUTO).
        assertThat((Map<String, Object>) r.contexto().get("classify").orElseThrow())
                .containsEntry("provider", "llm").containsEntry("route", "REVIEW");

        List<String> llm = pedidoUnicoAoLlm();
        assertThat(llm.get(0)).contains(persona).contains("ISO 639-1 code is \"en\"");
        assertThat(llm.get(1)).startsWith("Customer message:\n" + pedido).contains("Knowledge base excerpts");
    }

    @Test
    @DisplayName("a busca roda logo depois da entrada, sobre a mensagem do cliente, e o trecho chega ao agente")
    void buscaUsaAMensagemDoCliente() throws Exception {
        Execucao r = executar(Map.of("message", "Minha fatura veio com valor errado", "customerId", "c-1",
                "channel", "web"));

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat((List<Map<String, Object>>) r.contexto().get("kb-search").orElseThrow())
                .extracting(h -> h.get("content")).contains(TRECHO_DA_FATURA);
        assertThat(pedidoUnicoAoLlm().get(1)).contains(TRECHO_DA_FATURA);
    }

    /**
     * O revisor aprova o rascunho, não um sim/não às cegas: antes a proposta na fila era o JSON da triagem
     * ({route, confidence, answers...}) e a resposta só era escrita depois, sem o que o revisor viu ou editou
     * (RCHFLW-203).
     */
    @Test
    @DisplayName("cliente frustrado: o LLM rascunha, a fila mostra o rascunho; aprovar faz dele a resposta")
    void frustradoPassaPelaAprovacao() throws Exception {
        Execucao r = executar("É um ABSURDO, a minha fatura veio errada de novo");

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(r.estado().loadState(r.flowId()).getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(r.contexto().get("is-frustrated").map(o -> (Object) ((Map<?, ?>) o).get("branch")))
                .hasValue("true");
        assertThat(r.executou("classify")).as("o frustrado não é triado pelo LLM").isFalse();
        assertThat(r.executou("output")).as("nada sai como resposta antes da aprovação").isFalse();

        List<String> rascunho = pedidoUnicoAoLlm();
        assertThat(rascunho.get(0)).contains("senior customer support agent")
                .contains("the customer may be frustrated").contains("do not mention the review");
        assertThat(rascunho.get(1)).contains("ABSURDO").contains("Knowledge base excerpts");

        List<ApprovalResponse> pendentes = r.fila().listPending(TENANT);
        assertThat(pendentes).singleElement().satisfies(p -> {
            assertThat(p.stepId()).isEqualTo("handoff");
            assertThat(p.title()).isEqualTo("Customer support: escalated inquiry");
            assertThat(p.proposal()).as("a proposta é o rascunho, não o JSON da triagem").isEqualTo(RESPOSTA);
            assertThat(p.description()).contains("the customer may be frustrated")
                    .contains("drafted by the LLM").doesNotContain("support agent answer");
        });
        r.fila().submitDecision(pendentes.get(0).requestId(),
                new ApprovalSubmitRequest(TENANT, "APPROVED", null, "atendente-1", null));

        esperar(() -> r.estado().loadState(r.flowId()).getStatus() == FlowStatus.COMPLETED);
        assertThat(pedidosAoLlm).as("aprovar não escreve outra resposta").hasSize(1);
        assertThat(r.estado().loadState(r.flowId()).getVariables())
                .containsEntry("output", RESPOSTA)
                .doesNotContainKeys("billing-agent", "general-agent");
    }

    @Test
    @DisplayName("o revisor edita o rascunho: a versão dele é a resposta, sem nova chamada ao LLM")
    void edicaoDoRevisorViraAResposta() throws Exception {
        Execucao r = executar("É um ABSURDO, a minha fatura veio errada de novo");
        ApprovalResponse pendente = r.fila().listPending(TENANT).get(0);
        String editado = "Sentimos muito. A segunda via correta está em Financeiro > Faturas.";

        r.fila().submitDecision(pendente.requestId(),
                new ApprovalSubmitRequest(TENANT, "EDITED", editado, "atendente-1", "tom mais curto"));

        esperar(() -> r.estado().loadState(r.flowId()).getStatus() == FlowStatus.COMPLETED);
        assertThat(pedidosAoLlm).hasSize(1);
        assertThat(r.estado().loadState(r.flowId()).getVariables())
                .containsEntry("handoff", editado)
                .containsEntry("output", editado);
    }

    /**
     * O que se viu no servidor com o LLM real: "a fatura veio com uma cobrança que não reconheço" saiu com probabilidade
     * 0,4 de frustração. Com o limiar padrão do decision (review 0,5) a confiança 0,2 dava ESCALATE e o pedido comum ia
     * à aprovação. Quem decide é a resposta; a rota só escala a falha e a moeda ao ar.
     */
    @Test
    @DisplayName("cliente só um pouco chateado (probabilidade 0,4) não vai ao humano: segue para a triagem")
    void poucoChateadoSegueParaATriagem() throws Exception {
        Execucao r = executar("Estou chateado, a minha fatura veio com uma cobrança que não reconheço");

        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat((Map<String, Object>) r.contexto().get("sentiment").orElseThrow())
                .containsEntry("route", "REVIEW");
        assertThat(r.contexto().get("billing-agent")).hasValue(RESPOSTA);
        assertThat(r.executou("handoff")).isFalse();
    }

    @Test
    @DisplayName("recusar a aprovação encerra a execução sem resposta ao cliente")
    void recusarEncerraSemResposta() throws Exception {
        Execucao r = executar("É um ABSURDO o que vocês fazem");
        ApprovalResponse pendente = r.fila().listPending(TENANT).get(0);

        r.fila().submitDecision(pendente.requestId(),
                new ApprovalSubmitRequest(TENANT, "REJECTED", null, "atendente-1", null));

        // Recusar encerra: o estado vai a STOPPED sem retomar a travessia (DefaultFlowEngine.decide). O rascunho
        // existiu (é o que o revisor recusou), mas não vira resposta.
        esperar(() -> r.estado().loadState(r.flowId()).getStatus() == FlowStatus.STOPPED);
        assertThat(pedidosAoLlm).as("só o rascunho").hasSize(1);
        assertThat(r.estado().loadState(r.flowId()).getVariables()).doesNotContainKey("output");
    }

    @Test
    @DisplayName("confidenceThreshold é real: abaixo dele a pergunta vai ao humano; com o limiar baixo, ao agente")
    void limiarDeConfiancaDecide() throws Exception {
        Execucao vaga = executar("VAGO: queria saber uma coisa");

        assertThat(vaga.resultado().getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat((Map<String, Object>) vaga.contexto().get("classify").orElseThrow())
                .containsEntry("route", "ESCALATE").containsEntry("decision", "general");
        assertThat(vaga.executou("by-intent")).isFalse();
        assertThat(vaga.fila().listPending(TENANT)).singleElement().satisfies(p -> {
            assertThat(p.stepId()).isEqualTo("handoff");
            assertThat(p.proposal()).isEqualTo(RESPOSTA);
        });
        assertThat(vaga.executou("general-agent")).isFalse();
        assertThat(pedidosAoLlm).as("só o rascunho").hasSize(1);

        Execucao comLimiarBaixo = executar("VAGO: queria saber uma coisa", Map.of("confidenceThreshold", 0.2));

        assertThat(comLimiarBaixo.resultado().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(comLimiarBaixo.contexto().get("general-agent")).hasValue(RESPOSTA);
    }

    @Test
    @DisplayName("decisão que falha vai ao humano: a falha nunca vira palpite")
    void falhaDoClassificadorVaiAoHumano() throws Exception {
        Execucao r = executar("FALHA: minha fatura", Map.of("enableSentiment", false));

        assertThat(r.resultado().getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat((Map<String, Object>) r.contexto().get("classify").orElseThrow())
                .containsEntry("route", "ESCALATE").containsKey("error");
        // Sem o passo do sentimento, o motivo não cita frustração: nada no grafo a verificou.
        assertThat(r.fila().listPending(TENANT)).singleElement().satisfies(p -> {
            assertThat(p.stepId()).isEqualTo("handoff");
            assertThat(p.proposal()).isEqualTo(RESPOSTA);
            assertThat(p.description()).contains("could not be classified").doesNotContain("frustrated");
        });
        assertThat(pedidoUnicoAoLlm().get(0)).contains("could not be classified").doesNotContain("frustrated");
    }

    @Test
    @DisplayName("enableSentiment=false: o passo do sentimento não existe e o cliente irritado vai direto à triagem")
    void semSentimentoOPassoNaoExiste() throws Exception {
        Execucao r = executar("É um ABSURDO, a minha fatura veio errada de novo", Map.of("enableSentiment", false));

        List<String> ids = new ArrayList<>(r.passos().stream().map(s -> String.valueOf(s.get("id"))).toList());
        assertThat(ids).doesNotContain("sentiment", "is-frustrated").contains("classify", "handoff");
        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(r.contexto().get("billing-agent")).hasValue(RESPOSTA);
        assertThat(perguntasAoClassificador).as("só a triagem foi perguntada")
                .singleElement().asString().contains("- intent:").doesNotContain("frustrated");
    }
}
