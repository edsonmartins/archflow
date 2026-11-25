package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.flow.CatalogComponents;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executa a invocação no componente do catálogo cujo id é o {@code agentId} — os agentes e assistentes
 * que o Agent lab lista, e também o {@code mcp-agent}.
 *
 * <p>O ciclo de vida é o mesmo de um nó de fluxo ({@link CatalogComponents#invoke}): instância isolada
 * inicializada com a config da invocação, operação padrão do componente quando nenhuma veio, entrada
 * adaptada ao que a operação declara. Assim um agente que roda num nó roda igual pela fila, e o
 * contrário também. Inclusive na exceção: o {@code mcp-agent} é tratado à parte pelo
 * {@code DefaultFlowStepFactory} (instância por passo, {@code initialize} com a config inteira) e aqui
 * também — a config dele traz {@code provider}/{@code model}/{@code temperature}/{@code maxTokens}, que o
 * caminho genérico tira como chaves do designer; pela fila, o override de modelo sumia em silêncio.</p>
 *
 * <p>O payload da invocação é lido assim:</p>
 * <ul>
 *   <li>{@code operation} (opcional) — a operação; ausente, vale a padrão do componente;</li>
 *   <li>{@code config} (opcional, mapa) — a config do {@code initialize}, o equivalente à config do nó
 *       (o {@code mcp-agent} exige {@code systemPrompt} aqui);</li>
 *   <li>o resto vira variáveis do contexto pela mesma regra do Executar ({@link RunInputSeeding}), e é a
 *       entrada do agente: {@code input} explícito vence; senão o próprio payload, sem as chaves de
 *       controle ({@code sessionId}, {@code metadata}) — o {@code {"message": "..."}} do chat chega como
 *       o texto da mensagem, e não como um mapa com o id da sessão dentro.</li>
 * </ul>
 *
 * <p>Um {@code null} do JSON no primeiro nível do payload (ou da config) é chave ausente. O contexto de
 * execução não aceita valor nulo, e a invocação falhava com um {@code NullPointerException} sem dizer
 * qual campo.</p>
 *
 * <p>O tenant é o que a invocação carrega ({@link InvocationRequest#tenantId()}), sem reinterpretação.</p>
 */
public final class CatalogAgentInvocationExecutor implements AgentInvocationExecutor {

    /** Chaves do payload que dizem como executar, e não o que o agente recebe. */
    static final Set<String> CONTROL_KEYS = Set.of("operation", "config");

    /** Chaves que os produtores acrescentam ao payload (sessão, metadados do evento): contexto, não entrada. */
    static final Set<String> ENVELOPE_KEYS = Set.of("sessionId", "metadata");

    private static final int CHAT_WINDOW = 20;

    private final ComponentCatalog catalog;
    private final McpAgentHost mcpAgentHost;

    /**
     * @param mcpAgentHost dependências do {@code mcp-agent}; {@code null} quando o deployment não tem — o
     *                     {@code mcp-agent} então falha com a mensagem dele, e os demais agentes rodam
     */
    public CatalogAgentInvocationExecutor(ComponentCatalog catalog, McpAgentHost mcpAgentHost) {
        this.catalog = catalog;
        this.mcpAgentHost = mcpAgentHost;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object execute(InvocationRequest request) throws Exception {
        AIComponent component = catalog.getComponent(request.agentId()).orElseThrow(() ->
                new IllegalArgumentException("agente '" + request.agentId() + "' não está no catálogo"));

        Map<String, Object> payload = withoutNulls(request.payload());
        String operation = payload.get("operation") instanceof String s && !s.isBlank() ? s : null;
        Map<String, Object> config = withoutNulls(configOf(payload.get("config")));

        Map<String, Object> variables = new LinkedHashMap<>(payload);
        variables.keySet().removeAll(CONTROL_KEYS);
        Object sessionId = payload.get("sessionId");

        ExecutionContext context = new DefaultExecutionContext(request.tenantId(), null,
                sessionId == null ? null : sessionId.toString(),
                MessageWindowChatMemory.builder().maxMessages(CHAT_WINDOW).build());
        Map<String, Object> entrada = new LinkedHashMap<>(variables);
        entrada.keySet().removeAll(ENVELOPE_KEYS);
        variables.forEach(context::set);
        // A entrada segue a regra do Executar, mas sobre o payload sem o envelope: o sessionId que o
        // controller acrescenta não é parte do que o usuário pediu ao agente.
        RunInputSeeding.seedVariables(context, entrada);
        McpAgentHost.inject(context, mcpAgentHost);
        Object input = context.get(RunInputSeeding.INPUT_KEY).orElse(null);
        if (component instanceof McpAgentComponent) {
            return runMcpAgent(operation, config, input, context);
        }
        return CatalogComponents.invoke(component, operation, config, input, context);
    }

    /**
     * O {@code mcp-agent} como o factory o monta num nó: instância nova, {@code initialize} com a config
     * inteira (o override de LLM do nó mora nela) e {@code shutdown} no fim. Uma config inválida (sem
     * {@code systemPrompt}) falha com a mensagem do próprio componente.
     */
    private static Object runMcpAgent(String operation, Map<String, Object> config, Object input,
                                      ExecutionContext context) {
        McpAgentComponent agent = new McpAgentComponent();
        agent.initialize(config);
        try {
            return agent.execute(CatalogComponents.resolveOperation(agent.getMetadata(), operation), input,
                    context);
        } finally {
            agent.shutdown();
        }
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(map);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(
                "payload.config deve ser um objeto, veio " + raw.getClass().getSimpleName());
    }
}
