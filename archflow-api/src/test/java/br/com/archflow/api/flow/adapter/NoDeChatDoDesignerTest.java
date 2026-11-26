package br.com.archflow.api.flow.adapter;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O nó {@code llm-chat} exatamente como o designer o grava — {@code componentId: "llm-chat"}, sem
 * {@code operation}, muitas vezes sem {@code provider} — executa (RCHFLW-16, RCHFLW-51).
 *
 * <p>Os testes antigos sempre passavam {@code operation: generate} e um provider: nunca simulavam o
 * designer, e por isso não viam nem o "component not found: llm-chat" nem o "Unsupported operation:
 * execute". Usa o provider de teste do {@link LangChainAdapterBridgeTest.TestAdapterFactory}, que ecoa
 * operação e entrada e guarda as propriedades que recebeu.
 */
@DisplayName("Nó de chat do designer: herança de provider/modelo, operação e entrada")
class NoDeChatDoDesignerTest {

    private static final String PROVIDER = "provider-de-teste";

    private DefaultFlowStepFactory factory;

    @BeforeEach
    void setUp() {
        LangChainAdapterBridgeTest.TestAdapterFactory.created.clear();
        factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null);
    }

    private static ResolvedLLMConfig plataforma(String provider, String model) {
        return ResolvedLLMConfig.builder().provider(provider).model(model).build();
    }

    /** Nó como o designer grava: componentId = tipo, config só com o que o usuário mexeu. */
    private static Map<String, Object> noDoDesigner(String type, Map<String, Object> config) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "s1");
        node.put("type", type);
        node.put("componentId", type);
        node.put("config", config);
        return node;
    }

    private StepResult executar(Map<String, Object> node, Map<String, Object> configuration, Object input) {
        return executar(node, configuration, input, Map.of());
    }

    /** Com variáveis do contexto além do {@code input} — as saídas de passos anteriores que um template lê. */
    private StepResult executar(Map<String, Object> node, Map<String, Object> configuration, Object input,
                                Map<String, Object> variaveis) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "Chat"));
        json.put("steps", List.of(node));
        if (configuration != null) {
            json.put("configuration", configuration);
        }
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(json);
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        if (input != null) {
            ctx.set("input", input);
        }
        variaveis.forEach(ctx::set);
        return flow.getSteps().get(0).execute(ctx).join();
    }

    private static Map<String, Object> recebido() {
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created).hasSize(1);
        return LangChainAdapterBridgeTest.TestAdapterFactory.created.get(0);
    }

    @Test
    @DisplayName("sem provider e sem operação: herda o padrão da plataforma, uma volta (generate)")
    void herdaAPlataforma() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "modelo-da-plataforma"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of()), null, "oi");

        assertThat(result.getStatus())
                .as("antes: 'component not found: llm-chat' — o nó usava o próprio tipo como provider")
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput())
                .as("antes: 'Unsupported operation: execute' — o designer não grava operação")
                .hasValue("eco:generate:oi");
        assertThat(recebido()).containsEntry("model.name", "modelo-da-plataforma");
    }

    @Test
    @DisplayName("llm-streaming também herda")
    void streamingHerda() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-streaming", Map.of()), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).hasValue("eco:generate:oi");
    }

    @Test
    @DisplayName("o llmConfig do fluxo vence a plataforma")
    void fluxoVenceAPlataforma() {
        factory.setPlatformDefaultLLM(plataforma("gemini", "gemini-x"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of()),
                Map.of("llmConfig", Map.of("provider", PROVIDER, "model", "modelo-do-fluxo",
                        "temperature", 0.1)), "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(recebido()).containsEntry("model.name", "modelo-do-fluxo").containsEntry("temperature", 0.1);
    }

    @Test
    @DisplayName("o modelo do fluxo sem provider vale para o provider da plataforma")
    void modeloDoFluxoComProviderDaPlataforma() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "modelo-da-plataforma"));

        executar(noDoDesigner("llm-chat", Map.of()), Map.of("llmConfig", Map.of("model", "modelo-do-fluxo")),
                "oi");

        assertThat(recebido()).containsEntry("model.name", "modelo-do-fluxo");
    }

    @Test
    @DisplayName("herda o provider e escolhe o modelo: o modelo do nó vale")
    void modeloDoNoComProviderHerdado() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "modelo-da-plataforma"));

        executar(noDoDesigner("llm-chat", Map.of("model", "modelo-do-no")), null, "oi");

        assertThat(recebido()).containsEntry("model.name", "modelo-do-no");
    }

    @Test
    @DisplayName("provider do nó vence fluxo e plataforma, e não leva o modelo de outro provider")
    void providerDoNoVence() {
        factory.setPlatformDefaultLLM(plataforma("openrouter", "openai/gpt-6-luna"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of("provider", PROVIDER)), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(recebido())
                .as("'openai/gpt-6-luna' é id do OpenRouter; mandado a outro provider seria 404")
                .doesNotContainKey("model.name");
    }

    @Test
    @DisplayName("provider explícito igual ao da plataforma herda o modelo dela")
    void providerExplicitoIgualAoDaPlataforma() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "modelo-da-plataforma"));

        executar(noDoDesigner("llm-chat", Map.of("provider", PROVIDER)), null, "oi");

        assertThat(recebido()).containsEntry("model.name", "modelo-da-plataforma");
    }

    @Test
    @DisplayName("forma legada {componentId: provider} continua valendo")
    void componentIdLegado() {
        factory.setPlatformDefaultLLM(plataforma("gemini", "g"));
        Map<String, Object> node = noDoDesigner("llm-chat", Map.of());
        node.put("componentId", PROVIDER);

        StepResult result = executar(node, null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    @DisplayName("padrão da plataforma sem adapter de chat: falha dizendo qual é e quais existem")
    void padraoSemAdapter() {
        factory.setPlatformDefaultLLM(plataforma("gemini", "g"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of()), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(result.getOutput()))
                .contains("component not found: llm-chat")
                .contains("archflow.llm.provider").contains("'gemini'").contains(PROVIDER);
    }

    @Test
    @DisplayName("provider escolhido sem adapter de chat (RCHFLW-43): a falha cita o provider")
    void providerEscolhidoSemAdapter() {
        StepResult result = executar(noDoDesigner("llm-chat", Map.of("provider", "ollama")), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(result.getOutput()))
                .contains("component not found").contains("'ollama'").contains("adapter de chat");
    }

    @Test
    @DisplayName("sem padrão nenhum: o caminho antigo, com o motivo")
    void semPadraoNenhum() {
        StepResult result = executar(noDoDesigner("llm-chat", Map.of()), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(result.getOutput()))
                .contains("component not found: llm-chat").contains("não escolheu provider");
    }

    @Test
    @DisplayName("o escopo do fluxo vale para o provider herdado")
    void escopoValeParaOHerdado() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of()),
                Map.of("allowedComponents", List.of("outra-coisa")), "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created).isEmpty();
    }

    @Test
    @DisplayName("entrada-mapa vira o texto da mensagem; sem campo de texto, o JSON")
    void entradaMapaViraTexto() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        assertThat(executar(noDoDesigner("llm-chat", Map.of()), null, Map.of("message", "meu boleto venceu"))
                .getOutput()).hasValue("eco:generate:meu boleto venceu");
        assertThat(executar(noDoDesigner("llm-chat", Map.of()), null, Map.of("pedido", 42))
                .getOutput()).hasValue("eco:generate:{\"pedido\":42}");
    }

    /** O painel do LLM Chat, onde o campo "Mensagem do usuário" é gravado. */
    private static final java.nio.file.Path AGENT_FIELDS = java.nio.file.Path.of("..", "archflow-ui", "src",
            "components", "PropertyPanel", "fields", "AgentFields.tsx");

    @Test
    @DisplayName("RCHFLW-120: a chave que o painel grava é a que a ponte lê, e ela não vai ao adapter")
    void mensagemDoUsuarioDoPainelChegaAPonte() throws Exception {
        assertThat(java.nio.file.Files.readString(AGENT_FIELDS))
                .as("o painel grava a Mensagem do usuário com a chave que LangChainAdapterComponent lê")
                .contains("'" + LangChainAdapterComponent.USER_MESSAGE_KEY + "'");
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));
        Map<String, Object> decisao = Map.of("route", "REVIEW", "answers", Map.of("urgente", Map.of("result", false)));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of("userMessage", "${receber}")), null, decisao,
                Map.of("receber", "Queria saber o horário"));

        assertThat(result.getOutput())
                .as("antes: o LLM recebia o JSON da decisão que a condição repassa")
                .hasValue("eco:generate:Queria saber o horário");
        assertThat(recebido()).doesNotContainKey("userMessage");
    }

    @Test
    @DisplayName("RCHFLW-120: sem userMessage (ou em branco), a mensagem segue sendo a entrada do passo")
    void semMensagemDoUsuarioNadaMuda() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        assertThat(executar(noDoDesigner("llm-chat", Map.of()), null, "oi", Map.of("receber", "x")).getOutput())
                .hasValue("eco:generate:oi");
        assertThat(executar(noDoDesigner("llm-streaming", Map.of("userMessage", "  ")), null, "oi").getOutput())
                .hasValue("eco:generate:oi");
    }

    @Test
    @DisplayName("RCHFLW-120: texto e referência misturados, objeto como JSON; vale também no llm-streaming")
    void templateComTextoEObjeto() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-streaming",
                        Map.of("userMessage", "Cliente: ${receber.texto} | dados: ${dados}")), null, "ignorado",
                Map.of("receber", Map.of("texto", "oi"), "dados", Map.of("pedido", 42)));

        assertThat(result.getOutput()).hasValue("eco:generate:Cliente: oi | dados: {\"pedido\":42}");
    }

    @Test
    @DisplayName("RCHFLW-120: referência sem valor falha nomeando nó, campo e referência — o adapter nem nasce")
    void referenciaSemValorFalha() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of("userMessage", "Cliente: ${receber}")),
                null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput() + " " + result.getErrors())
                .contains("Nó s1").contains("userMessage").contains("${receber}");
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created).isEmpty();
    }

    @Test
    @DisplayName("RCHFLW-120: referência que resolve para texto vazio e ${ sem fechar também falham")
    void vazioEMalformadoFalham() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult vazio = executar(noDoDesigner("llm-chat", Map.of("userMessage", "${input}")), null, "");
        StepResult aberto = executar(noDoDesigner("llm-chat", Map.of("userMessage", "Cliente: ${receber")), null,
                "oi", Map.of("receber", "x"));

        assertThat(vazio.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(vazio.getOutput() + " " + vazio.getErrors()).contains("texto vazio");
        assertThat(aberto.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(aberto.getOutput() + " " + aberto.getErrors()).contains("«${receber»");
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created).isEmpty();
    }

    @Test
    @DisplayName("o prompt de sistema e a persona do painel chegam ao adapter (RCHFLW-52)")
    void promptChegaAoAdapter() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        executar(noDoDesigner("llm-chat", Map.of("systemPrompt", "Seja breve.", "personaId", "suporte")),
                null, "oi");

        assertThat(recebido()).containsEntry("systemPrompt", "Seja breve.");
    }

    @Test
    @DisplayName("operation: chat explícito continua conversando com a memória da execução")
    void chatExplicito() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));
        Map<String, Object> node = noDoDesigner("llm-chat", Map.of());
        node.put("operation", "chat");

        assertThat(executar(node, null, "oi").getOutput()).hasValue("eco:chat:oi");
    }

    @Test
    @DisplayName("temperatura/maxTokens apagados no painel (\"\") herdam o fluxo em vez de quebrar o adapter")
    void numeroApagadoHerda() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of("temperature", "", "maxTokens", " ")),
                Map.of("llmConfig", Map.of("maxTokens", 512)), "oi");

        assertThat(result.getStatus()).as(String.valueOf(result.getOutput())).isEqualTo(StepStatus.COMPLETED);
        assertThat(recebido()).doesNotContainKey("temperature").containsEntry("maxTokens", 512);
    }

    @Test
    @DisplayName("temperatura/maxTokens em texto numérico (YAML/API) viram número")
    void numeroEmTexto() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        executar(noDoDesigner("llm-chat", Map.of("temperature", "0.3", "maxTokens", "256")), null, "oi");

        assertThat(recebido()).containsEntry("temperature", 0.3).containsEntry("maxTokens", 256);
    }

    @Test
    @DisplayName("temperatura que não é número: o fluxo abre, e a execução diz qual campo")
    void numeroInvalido() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));

        StepResult result = executar(noDoDesigner("llm-chat", Map.of("temperature", "quente")), null, "oi");

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput() + " " + result.getErrors()).contains("'temperature'").contains("quente");
        assertThat(LangChainAdapterBridgeTest.TestAdapterFactory.created).isEmpty();
    }

    @Test
    @DisplayName("nada da herança volta para o documento")
    void documentoNaoMuda() {
        factory.setPlatformDefaultLLM(plataforma(PROVIDER, "m"));
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> node = noDoDesigner("llm-chat", config);

        executar(node, null, "oi");

        assertThat(config).as("mudar archflow.llm.provider tem de mudar os fluxos existentes").isEmpty();
    }
}
