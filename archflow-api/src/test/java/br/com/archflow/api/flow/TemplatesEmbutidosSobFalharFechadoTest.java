package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.decision.LlmDecisionProvider;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.template.TemplateDocument;
import br.com.archflow.template.WorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Conformidade dos templates embutidos com o motor que falha fechado (RCHFLW-123/135).
 *
 * <p>Cada template que o servidor registra (SPI, via {@link TemplateDocument}) e cada receita da galeria do
 * designer ({@code archflow-ui/public/templates}, convertida como o clone do designer converte: arestas do topo
 * para {@code steps[].connections}) roda no motor de verdade com a entrada que um usuário mandaria — o JSON
 * documentado e o texto livre do Executar — duas vezes: no padrão e com {@code archflow.flow.lenient-conditions}.
 * O LLM e os demais componentes do catálogo são um eco determinístico; nenhum provedor é chamado.</p>
 *
 * <p>O que se fixa: nenhum template muda de destino ou de status por causa da regra nova (o leniente é o
 * comportamento anterior), nenhum é recusado por nó solto ({@code NODE_DISCONNECTED}). E os dois exemplos que o
 * copiloto/nl-to-flow ensinam com condition/switch depois de um decision chegam ao humano quando a decisão falha.</p>
 */
@DisplayName("templates embutidos: mesmo destino no padrão (falha fechado) e no leniente")
class TemplatesEmbutidosSobFalharFechadoTest {

    private static final ResolvedLLMConfig PLATAFORMA = ResolvedLLMConfig.builder()
            .provider("openrouter").model("modelo-padrao").build();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** O "LLM" e os componentes do catálogo: devolvem sempre o mesmo objeto, sem rede. */
    public static final class Eco implements AIComponent {
        @Override public void initialize(Map<String, Object> config) { }
        @Override public void shutdown() { }

        @Override
        public ComponentMetadata getMetadata() {
            return new ComponentMetadata("eco", "eco", "teste", ComponentType.TOOL, "1.0.0", Set.of(),
                    List.of(new ComponentMetadata.OperationMetadata("execute", "execute", "", List.of(), List.of())),
                    Map.of(), Set.of());
        }

        @Override
        public Object execute(String operation, Object input, ExecutionContext context) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", "resposta do eco");
            out.put("intent", "billing");
            out.put("confidence", 0.9);
            out.put("sentiment", 0.8);
            out.put("quality", 0.9);
            return out;
        }
    }

    /** O desfecho de uma execução: status, erros e os passos que gravaram saída. */
    record Desfecho(String status, String erros, Set<String> passos) {
        boolean recusadoAntes() { return "REJECTED".equals(status); }
    }

    private static Desfecho executar(Map<String, Object> documento, Object entrada, boolean leniente,
                                     String respostaDoClassificador) throws Exception {
        StateManager estado = new InMemoryStateManager();
        InMemoryFlowRepository repositorio = new InMemoryFlowRepository();
        FlowEngine motor = FlowEngineFactory.create(repositorio, null, null, estado);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provedor = mock(ObjectProvider.class);
        when(provedor.getObject()).thenReturn(motor);
        ComponentCatalog catalogo = mock(ComponentCatalog.class);
        when(catalogo.getComponent(anyString())).thenReturn(Optional.of(new Eco()));
        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(catalogo,
                mock(DynamicWorkflowService.class), null, estado, provedor);
        fabrica.setLenientConditionsProperty(leniente);
        ChatModel classificador = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(respostaDoClassificador)).build();
            }
        };
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return PLATAFORMA; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return classificador; }
        };
        fabrica.setDecisionSupport(DecisionProviders.withDefaults()
                .register(new LlmDecisionProvider(resolver, PLATAFORMA)), DecisionKeys.NONE);
        Flow flow = new DefaultWorkflowDeserializer(fabrica).toFlow(documento);
        repositorio.save(flow);
        DefaultExecutionContext ctx = new DefaultExecutionContext("SYSTEM", "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        if (entrada instanceof Map<?, ?> mapa) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variaveis = (Map<String, Object>) mapa;
            RunInputSeeding.seedVariables(ctx, variaveis);
        } else {
            RunInputSeeding.seedVariables(ctx, Map.of("input", entrada));
        }
        Set<String> ids = new TreeSet<>();
        for (Object s : (List<?>) documento.get("steps")) {
            ids.add(String.valueOf(((Map<?, ?>) s).get("id")));
        }
        try {
            FlowResult r = motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
            Set<String> passos = new TreeSet<>();
            ids.forEach(id -> {
                if (ctx.get(id).isPresent()) {
                    passos.add(id);
                }
            });
            return new Desfecho(r.getStatus().name(),
                    String.join(" | ", r.getErrors().stream().map(ExecutionError::message).toList()), passos);
        } catch (ExecutionException e) {
            Throwable raiz = e;
            while (raiz.getCause() != null) {
                raiz = raiz.getCause();
            }
            return new Desfecho("REJECTED", raiz.getClass().getSimpleName() + ": " + raiz.getMessage(), Set.of());
        }
    }

    // ---------------------------------------------------------------- templates SPI do servidor

    private static final Map<String, Map<String, Object>> PARAMETROS_OBRIGATORIOS = Map.of(
            "customer-support", Map.of("knowledgeBaseId", "kb-suporte"),
            "knowledge-base", Map.of("vectorStoreId", "vs-docs"),
            "agent-supervisor", Map.of("agents", List.of("researcher", "writer")));

    private static final Map<String, List<Object>> ENTRADAS_SPI = Map.of(
            "customer-support", List.of(
                    Map.of("message", "Minha fatura veio errada", "customerId", "c-1", "channel", "web"),
                    "Minha fatura veio errada, podem corrigir?"),
            "knowledge-base", List.of(Map.of("query", "Como redefinir a senha?", "sessionId", "s-1"),
                    "Como redefinir a senha?"),
            "document-processing", List.of(Map.of("document", "Contrato de prestação de serviços"),
                    Map.of("document", "Contrato", "hasImages", true),
                    "Contrato de prestação de serviços"),
            "agent-supervisor", List.of(Map.of("task", "Escreva um relatório sobre vendas"),
                    "Escreva um relatório sobre vendas"));

    @Test
    @DisplayName("todo template SPI: o padrão chega ao mesmo destino do leniente e nenhum nó fica solto")
    void templatesSpi() throws Exception {
        List<String> relatorio = new ArrayList<>();
        int vistos = 0;
        for (WorkflowTemplate t : WorkflowTemplateRegistry.getInstance().getAllTemplates()) {
            vistos++;
            List<Map<String, Object>> steps = TemplateDocument.steps(t.getDefinition(),
                    PARAMETROS_OBRIGATORIOS.getOrDefault(t.getId(), Map.of()));
            Map<String, Object> doc = Map.of("id", t.getId(), "metadata", Map.of("name", t.getId()), "steps", steps);
            List<Map<String, Object>> documentos = new ArrayList<>(List.of(doc));
            // Sem KnowledgeService neste teste a busca com base falha e as arestas depois dela nunca são avaliadas:
            // a variante sem a base cai no catálogo (o eco) e anda o resto do grafo.
            if (steps.stream().anyMatch(s -> ((Map<?, ?>) s.get("configuration")).containsKey("knowledgeBaseId"))) {
                List<Map<String, Object>> semBase = new ArrayList<>();
                for (Map<String, Object> s : steps) {
                    Map<String, Object> copia = new LinkedHashMap<>(s);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) s.get("configuration"));
                    cfg.remove("knowledgeBaseId");
                    copia.put("configuration", cfg);
                    semBase.add(copia);
                }
                documentos.add(Map.of("id", t.getId() + "-sem-base", "metadata", Map.of("name", t.getId()),
                        "steps", semBase));
            }
            for (Map<String, Object> d : documentos) {
                for (Object entrada : ENTRADAS_SPI.getOrDefault(t.getId(), List.of("texto livre"))) {
                    Desfecho padrao = executar(d, entrada, false, "{}");
                    Desfecho leniente = executar(d, entrada, true, "{}");
                    relatorio.add(d.get("id") + " <- " + entrada + "\n   padrão:   " + padrao
                            + "\n   leniente: " + leniente);
                    assertThat(padrao.status()).as(d.get("id") + " / " + entrada).isEqualTo(leniente.status());
                    assertThat(padrao.passos()).as(d.get("id") + " / " + entrada).isEqualTo(leniente.passos());
                    assertThat(padrao.erros()).as(String.valueOf(d.get("id"))).doesNotContain("sem nenhuma aresta")
                            .doesNotContain("não tem valor nesta execução");
                }
            }
        }
        System.out.println("=== templates SPI\n" + String.join("\n", relatorio));
        assertThat(vistos).as("templates registrados").isGreaterThanOrEqualTo(4);
    }

    // ---------------------------------------------------------------- galeria do designer

    /** O clone do designer ({@code templateApi.clone} → {@code canvasToDocumentSteps}): config e arestas por passo. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> comoOCloneGrava(Map<String, Object> receita) {
        List<Map<String, Object>> conexoes = (List<Map<String, Object>>) receita.getOrDefault("connections",
                List.of());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) receita.get("steps")) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", s.get("id"));
            step.put("type", s.get("type"));
            step.put("componentId", s.get("componentId"));
            step.put("label", s.get("label"));
            step.put("configuration", new HashMap<>((Map<String, Object>) s.getOrDefault("config", Map.of())));
            List<Map<String, Object>> saem = new ArrayList<>();
            for (Map<String, Object> c : conexoes) {
                if (s.get("id").equals(c.get("sourceId"))) {
                    Map<String, Object> aresta = new LinkedHashMap<>();
                    aresta.put("sourceId", c.get("sourceId"));
                    aresta.put("targetId", c.get("targetId"));
                    aresta.put("isErrorPath", c.getOrDefault("isErrorPath", false));
                    if (c.get("condition") != null) aresta.put("condition", c.get("condition"));
                    if (c.get("branch") != null) aresta.put("branch", c.get("branch"));
                    saem.add(aresta);
                }
            }
            step.put("connections", saem);
            steps.add(step);
        }
        return Map.of("id", String.valueOf(receita.get("id")), "metadata", Map.of("name", receita.get("name")),
                "steps", steps);
    }

    @Test
    @DisplayName("toda receita da galeria: o padrão e o leniente dão o mesmo desfecho, sem nó solto")
    @SuppressWarnings("unchecked")
    void galeriaDoDesigner() throws Exception {
        File pasta = new File("../archflow-ui/public/templates");
        Map<String, Object> indice = JSON.readValue(new File(pasta, "index.json"), Map.class);
        List<String> relatorio = new ArrayList<>();
        for (Map<String, Object> entrada : (List<Map<String, Object>>) indice.get("templates")) {
            Map<String, Object> receita = JSON.readValue(new File(pasta, (String) entrada.get("file")), Map.class);
            Map<String, Object> doc = comoOCloneGrava(receita);
            for (Object input : List.of("Olá, quero saber do meu pedido 123",
                    Map.of("message", "Olá, quero saber do meu pedido 123"))) {
                Desfecho padrao = executar(doc, input, false, "{}");
                Desfecho leniente = executar(doc, input, true, "{}");
                relatorio.add(entrada.get("id") + " <- " + input + "\n   padrão:   " + padrao
                        + "\n   leniente: " + leniente);
                assertThat(padrao.status()).as(entrada.get("id") + " / " + input).isEqualTo(leniente.status());
                assertThat(padrao.passos()).as(entrada.get("id") + " / " + input).isEqualTo(leniente.passos());
                assertThat(padrao.erros()).doesNotContain("sem nenhuma aresta")
                        .doesNotContain("não tem valor nesta execução");
            }
        }
        System.out.println("=== galeria do designer\n" + String.join("\n", relatorio));
    }

    // ---------------------------------------------------------------- exemplos do copiloto / nl-to-flow

    private static Map<String, Object> no(String id, String type, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", id);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    /** O exemplo "urgente vai ao humano; senão, um LLM responde" do nl-to-flow (e, com assunto, do copiloto). */
    private static Map<String, Object> triagem(boolean comAssunto) {
        Map<String, Object> urgente = Map.of("type", "noul", "instructions", "O pedido é urgente?",
                "criteria", Map.of("true", "algo parado ou fora do ar", "false", "pode esperar"));
        Map<String, Object> perguntas = comAssunto
                ? Map.of("assunto", Map.of("type", "choice", "instructions", "Qual o assunto do pedido?",
                        "criteria", Map.of("financeiro", "cobrança", "suporte_tecnico", "defeito",
                                "outro", "qualquer outro")), "urgente", urgente)
                : Map.of("urgente", urgente);
        Map<String, Object> config = new HashMap<>(Map.of("provider", "llm", "questions", perguntas,
                "gate", List.of("urgente")));
        if (comAssunto) {
            config.put("primary", "assunto");
        }
        return Map.of("id", "triagem-exemplo", "metadata", Map.of("name", "triagem"), "steps", List.of(
                no("receber", "input", Map.of(), List.of(Map.of("targetId", "triagem"))),
                no("triagem", "decision", config, List.of(Map.of("targetId", "e_urgente"))),
                no("e_urgente", "condition", Map.of("conditionExpression",
                        "${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'"),
                        List.of(Map.of("targetId", "humano", "branch", "true"),
                                Map.of("targetId", "responder", "branch", "false"))),
                no("humano", "approval", Map.of("approvalTitle", "Pedido urgente"), List.of()),
                no("responder", "llm-chat", Map.of("userMessage", "${receber}"), List.of())));
    }

    @Test
    @DisplayName("exemplo de triagem do copiloto/nl-to-flow: urgente e decisão que falhou vão ao humano")
    void exemploDeTriagem() throws Exception {
        String texto = "O sistema está fora do ar e a loja parou!";
        List<String> relatorio = new ArrayList<>();
        for (boolean comAssunto : List.of(false, true)) {
            Map<String, Object> doc = triagem(comAssunto);
            String assunto = comAssunto ? "\"assunto\":{\"choice\":\"suporte_tecnico\",\"confidence\":0.9}," : "";
            Map<String, String> respostas = new LinkedHashMap<>();
            respostas.put("urgente", "{" + assunto + "\"urgente\":{\"probability\":0.95}}");
            respostas.put("comum", "{" + assunto + "\"urgente\":{\"probability\":0.05}}");
            respostas.put("falhou", "isto não é JSON");
            respostas.put("omitiu urgente", "{" + assunto + "}");
            for (var r : respostas.entrySet()) {
                Desfecho padrao = executar(doc, texto, false, r.getValue());
                Desfecho leniente = executar(doc, texto, true, r.getValue());
                relatorio.add("triagem(assunto=" + comAssunto + ") " + r.getKey() + "\n   padrão:   " + padrao
                        + "\n   leniente: " + leniente);
                assertThat(padrao).as(r.getKey()).isEqualTo(leniente);
                if ("comum".equals(r.getKey())) {
                    assertThat(padrao.passos()).contains("responder").doesNotContain("humano");
                } else {
                    assertThat(padrao.status()).as(r.getKey() + ": " + padrao.erros()).isEqualTo("PAUSED");
                    assertThat(padrao.passos()).doesNotContain("responder");
                }
            }
        }
        System.out.println("=== exemplo de triagem\n" + String.join("\n", relatorio));
    }

    /** O exemplo "por categoria" do copiloto: switch em ${D.decision}, um caso por opção e o default ao humano. */
    @Test
    @DisplayName("exemplo por categoria do copiloto: caso casado segue, decisão que falhou vai ao default/humano")
    void exemploPorCategoria() throws Exception {
        Map<String, Object> pergunta = Map.of("type", "choice", "instructions", "Qual o assunto?",
                "criteria", Map.of("financeiro", "cobrança", "suporte_tecnico", "defeito", "outro", "outro"));
        Map<String, Object> doc = Map.of("id", "categoria", "metadata", Map.of("name", "categoria"), "steps", List.of(
                no("receber", "input", Map.of(), List.of(Map.of("targetId", "d"))),
                no("d", "decision", Map.of("provider", "llm", "questions", Map.of("assunto", pergunta),
                        "primary", "assunto"), List.of(Map.of("targetId", "s"))),
                no("s", "switch", Map.of("switchExpression", "${d.decision}",
                        "switchCases", "financeiro\nsuporte_tecnico\noutro"), List.of(
                        Map.of("targetId", "fin", "branch", "financeiro"),
                        Map.of("targetId", "sup", "branch", "suporte_tecnico"),
                        Map.of("targetId", "out", "branch", "outro"),
                        Map.of("targetId", "humano", "branch", "default"))),
                no("fin", "output", Map.of(), List.of()),
                no("sup", "output", Map.of(), List.of()),
                no("out", "output", Map.of(), List.of()),
                no("humano", "approval", Map.of("approvalTitle", "Revisar"), List.of())));
        List<String> relatorio = new ArrayList<>();
        for (String resposta : List.of("{\"assunto\":{\"choice\":\"financeiro\",\"confidence\":0.9}}",
                "não é JSON")) {
            Desfecho padrao = executar(doc, "Minha fatura veio em dobro", false, resposta);
            Desfecho leniente = executar(doc, "Minha fatura veio em dobro", true, resposta);
            relatorio.add("categoria <- " + resposta + "\n   padrão:   " + padrao + "\n   leniente: " + leniente);
            assertThat(padrao).isEqualTo(leniente);
        }
        System.out.println("=== exemplo por categoria\n" + String.join("\n", relatorio));
    }
}
