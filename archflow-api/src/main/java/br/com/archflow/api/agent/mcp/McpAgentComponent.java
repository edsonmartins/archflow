package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O laço agente↔tools MCP como um passo de fluxo.
 *
 * <p>Antes disto, um fluxo não chamava tool MCP: o {@link McpAgentRunner} — com allowlist,
 * aprovação, confiança, validação de argumento e teto de catálogo — vivia fora do motor, alcançável
 * só por quem chamasse a API de agente diretamente. Um agente que precisasse conferir um fato antes
 * de concluir não podia virar fluxo sem perder essa checagem.</p>
 *
 * <p>É componente do catálogo, e não um tipo de nó servido por adapter, porque o que importa aqui
 * não é o laço — é a política em volta dele. Reimplementá-la na config de um adapter seria reescrever
 * seis classes maduras num lugar onde o erro é silencioso: uma allowlist que não pega deixa o agente
 * chamar tool destrutiva sem ninguém notar. Como componente, ele ainda herda o
 * {@code ComponentAccessPolicy} do motor — o {@code allowing(...)} da DSL — que vale inclusive para
 * sub-agentes de um nó de orquestração.</p>
 *
 * <p>Config do nó:</p>
 * <pre>
 * systemPrompt   (obrigatório) instrução do agente
 * tools          (lista)       allowlist; ausente = todas as do server, sujeitas ao teto do host
 * server         (texto)       referência ao servidor MCP; ausente = padrão do host
 * maxIterations  (inteiro)     voltas do laço; ausente = padrão do runner
 * </pre>
 *
 * <p>As dependências vêm do {@link McpAgentHost} injetado no contexto — ver a razão lá.</p>
 */
public class McpAgentComponent implements AIComponent, ComponentPlugin {

    public static final String COMPONENT_ID = "mcp-agent";
    private static final String VERSION = "1.0.0";

    /** Entrada e saída do passo, para quem encadeia. */
    public static final String SAIDA_TEXTO = "text";
    public static final String SAIDA_TOOLS = "toolCalls";
    public static final String SAIDA_SUSPENSO = "suspended";
    public static final String SAIDA_APROVACAO = "approvalRequestId";

    private Map<String, Object> config = Map.of();
    private boolean initialized;

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config == null ? Map.of() : Map.copyOf(config);
        this.initialized = true;
    }

    /**
     * Sem prompt não há agente — e um passo que rodasse com prompt vazio produziria uma resposta
     * plausível e sem relação com a intenção de quem montou o fluxo.
     */
    @Override
    public void validateConfig(Map<String, Object> config) {
        Object prompt = config == null ? null : config.get("systemPrompt");
        if (!(prompt instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    COMPONENT_ID + ": 'systemPrompt' é obrigatório e não pode ser vazio");
        }
        Object tools = config.get("tools");
        if (tools != null && !(tools instanceof List<?>)) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'tools' deve ser uma lista");
        }
        Object max = config.get("maxIterations");
        if (max != null && inteiro(max) <= 0) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'maxIterations' deve ser positivo");
        }
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "MCP Agent",
                "Executa o laço agente↔tools contra um servidor MCP, com allowlist, aprovação e "
                        + "política de confiança",
                ComponentType.AGENT,
                VERSION,
                Set.of("mcp", "tools", "agent"),
                List.of(new ComponentMetadata.OperationMetadata(
                        "execute", "Executar", "Roda o laço até a resposta final ou a suspensão",
                        List.of(new ComponentMetadata.ParameterMetadata(
                                "input", "string", "Mensagem do usuário", true)),
                        List.of(
                                new ComponentMetadata.ParameterMetadata(
                                        SAIDA_TEXTO, "string", "Texto final do assistente", true),
                                new ComponentMetadata.ParameterMetadata(
                                        SAIDA_TOOLS, "array", "Tools executadas, na ordem", false),
                                new ComponentMetadata.ParameterMetadata(
                                        SAIDA_SUSPENSO, "boolean",
                                        "true quando parou esperando decisão humana", false)))),
                Map.of(),
                Set.of("mcp", "agent", "tools"));
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) {
        if (!initialized) {
            throw new IllegalStateException(COMPONENT_ID + " não inicializado");
        }
        McpAgentHost host = McpAgentHost.from(context).orElseThrow(() -> new IllegalStateException(
                COMPONENT_ID + ": nenhum McpAgentHost no contexto (chave '"
                        + McpAgentHost.CONTEXT_KEY + "'). Quem hospeda a execução precisa injetá-lo "
                        + "— é de lá que vêm o runner e o cliente MCP."));

        String tenantId = context.getTenantId();
        McpClient client = host.clientFor(tenantId, texto(config.get("server")));

        McpAgentRunner.Options options = new McpAgentRunner.Options(
                politicaDeAcesso(host, tenantId),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                iteracoes());

        McpAgentRunner.Result result = host.runner().run(
                tenantId, texto(config.get("systemPrompt")), mensagemDe(input), client, options);

        return saida(result);
    }

    /**
     * A allowlist efetiva: a do nó, interseccionada com o teto do host.
     *
     * <p>Intersecção e não soma: onde o cliente edita o fluxo, uma allowlist que viesse só do nó
     * deixaria ele próprio conceder ao agente tools que a plataforma não lhe deu. Sem lista no nó,
     * vale o teto; sem teto e sem lista, vale o server inteiro — e isso fica declarado por
     * {@code allowAll()}, não por omissão.</p>
     */
    private ToolAccessPolicy politicaDeAcesso(McpAgentHost host, String tenantId) {
        Set<String> doNo = listaDeTexto(config.get("tools"));
        Set<String> teto = host.toolCeiling(tenantId);

        if (doNo.isEmpty()) {
            return teto.isEmpty() ? ToolAccessPolicy.allowAll() : ToolAccessPolicy.allowOnly(teto);
        }
        if (teto.isEmpty()) {
            return ToolAccessPolicy.allowOnly(doNo);
        }
        Set<String> efetiva = new LinkedHashSet<>(doNo);
        efetiva.retainAll(teto);
        return ToolAccessPolicy.allowOnly(efetiva);
    }

    /**
     * O resultado como mapa, com a suspensão <b>explícita</b>.
     *
     * <p>Tratar um laço suspenso como conclusão entregaria ao passo seguinte uma resposta parcial
     * como se fosse final — e a aprovação pendente sumiria sem que ninguém a visse.</p>
     */
    private Map<String, Object> saida(McpAgentRunner.Result result) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpAgentRunner.ToolCall call : result.toolCalls()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", call.name());
            t.put("arguments", call.arguments());
            t.put("result", call.resultText());
            t.put("error", call.isError());
            tools.add(t);
        }

        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put(SAIDA_TEXTO, result.finalText());
        saida.put(SAIDA_TOOLS, tools);
        saida.put(SAIDA_SUSPENSO, result.isSuspended());
        if (result.isSuspended()) {
            saida.put(SAIDA_APROVACAO, result.pendingApproval().requestId());
            saida.put("pendingTool", result.pendingApproval().toolName());
        }
        return saida;
    }

    /** A entrada do passo: texto direto, ou a chave {@code input}/{@code message} de um mapa. */
    private String mensagemDe(Object input) {
        if (input instanceof String s) {
            return s;
        }
        if (input instanceof Map<?, ?> mapa) {
            Object valor = mapa.get("input");
            if (valor == null) {
                valor = mapa.get("message");
            }
            if (valor != null) {
                return String.valueOf(valor);
            }
        }
        return input == null ? "" : String.valueOf(input);
    }

    private int iteracoes() {
        Object max = config.get("maxIterations");
        return max == null ? McpAgentRunner.DEFAULT_MAX_ITERATIONS : inteiro(max);
    }

    private static int inteiro(Object valor) {
        if (valor instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(valor).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    COMPONENT_ID + ": valor inteiro inválido: " + valor);
        }
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private static Set<String> listaDeTexto(Object valor) {
        if (!(valor instanceof List<?> lista)) {
            return Set.of();
        }
        Set<String> nomes = new LinkedHashSet<>();
        for (Object item : lista) {
            if (item != null && !String.valueOf(item).isBlank()) {
                nomes.add(String.valueOf(item).trim());
            }
        }
        return nomes;
    }

    @Override
    public void shutdown() {
        initialized = false;
    }
}
